package nx.pingwheel.common.diagnostics;

import nx.pingwheel.common.client.outline.EntityBlockGeometryContext;
import nx.pingwheel.common.client.outline.EntityBlockGeometryOutcome;
import nx.pingwheel.common.client.outline.EntityBlockGeometrySource;
import nx.pingwheel.common.client.outline.EntityBlockGeometrySourceRegistry;
import nx.pingwheel.common.client.outline.EntityOutlineContext;
import nx.pingwheel.common.client.outline.EntityOutlineSource;
import nx.pingwheel.common.client.outline.EntityOutlineSourceRegistry;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class CreateAdapterLifecycleDiagnosticsTest {
	@ParameterizedTest
	@CsvSource({"false, register", "false, close", "true, register", "true, close"})
	void sameThreadLifecycleReentryFromRealDuplicateWarningIsASinkFaultAndLeavesTheWinnerAndOuterHandleUsable(
		boolean flywheel, String operation
	) throws Exception {
		var adapter = new AdapterHarness(flywheel);
		try (var winner = winner(flywheel); var fixture = new ReentrantFaultFixture(adapter, operation)) {
			var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
			var task = executor.submit(() -> {
				Object originalWinner = registeredSource(adapter, flywheel);
				adapter.invoke("register");
				assertEquals(1, fixture.callbacks.get()); assertEquals("rejected", adapter.state());
				assertEquals(0, adapter.closedSources(), "failed nested close cannot silently close the outer candidate");
				assertSame(originalWinner, registeredSource(adapter, flywheel));
				assertEquals(1, fixture.faults.observations().size());
				var fault = fixture.faults.observations().getFirst();
				assertEquals(DiagnosticSide.CLIENT, fault.key().side());
				assertEquals(DiagnosticFaultReporter.Stage.SINK, fault.key().stage());
				assertEquals(DiagnosticFaultReporter.Kind.RUNTIME, fault.key().kind());
				assertEquals(flywheel ? "entity-block-source-register" : "entity-outline-register", fault.key().site());
				assertEquals(1, fault.occurrences());
				adapter.invoke("close"); assertEquals("not-registered", adapter.state()); assertEquals(1, adapter.closedSources());
				assertSame(originalWinner, registeredSource(adapter, flywheel), "closing a rejected handle cannot remove the winner");
				adapter.invoke("register"); assertEquals("rejected", adapter.state());
				adapter.invoke("close"); assertEquals(2, adapter.closedSources());
				assertDoesNotThrow(winner::close);
				adapter.invoke("register"); assertEquals("registered", adapter.state());
				assertNotSame(originalWinner, registeredSource(adapter, flywheel));
				adapter.invoke("register"); adapter.invoke("close"); adapter.invoke("close");
				assertEquals("not-registered", adapter.state()); assertEquals(3, adapter.closedSources());
				assertEquals(1, fixture.callbacks.get(), "the backend must not recursively retry the lifecycle operation");
			});
			try { task.get(5, TimeUnit.SECONDS); }
			finally {
				// The old self-wait ignores interruption. Never join that daemon or close this executor synchronously.
				task.cancel(true); executor.shutdownNow();
			}
		}
	}

	private static Object registeredSource(AdapterHarness adapter, boolean flywheel) {
		return flywheel ? EntityBlockGeometrySourceRegistry.INSTANCE.snapshot().stream()
			.filter(source -> source.id().equals(adapter.id())).findFirst().orElseThrow()
			: EntityOutlineSourceRegistry.INSTANCE.snapshot().stream()
				.filter(source -> source.id().equals(adapter.id())).findFirst().orElseThrow();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void blockedEmergencyOutputAfterRegistrationOrCloseNeverHoldsTheLifecycleMonitor(boolean flywheel) throws Exception {
		var adapter = new AdapterHarness(flywheel);
		try (var fixture = new BlockingFaultFixture(adapter)) {
			try (var executor = Executors.newFixedThreadPool(2)) {
				var registering = executor.submit(() -> adapter.invoke("register"));
				try {
					assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
					assertEquals("registered", executor.submit(adapter::state).get(5, TimeUnit.SECONDS));
					executor.submit(() -> adapter.invoke("close")).get(5, TimeUnit.SECONDS);
					assertEquals("not-registered", adapter.state()); assertEquals(1, adapter.source.getField("closes").getInt(null));
				} finally { fixture.release.countDown(); }
				registering.get(5, TimeUnit.SECONDS);
			}
		}
		try (var fixture = new BlockingFaultFixture(adapter)) {
			fixture.fail.set(false); adapter.invoke("register"); fixture.fail.set(true);
			try (var executor = Executors.newFixedThreadPool(2)) {
				var closing = executor.submit(() -> adapter.invoke("close"));
				try {
					assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
					assertEquals("not-registered", executor.submit(adapter::state).get(5, TimeUnit.SECONDS));
					executor.submit(() -> adapter.invoke("close")).get(5, TimeUnit.SECONDS);
				} finally { fixture.release.countDown(); }
				closing.get(5, TimeUnit.SECONDS);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void nestedDuplicateRegistryDiagnosticRunsOutsideTheLifecycleMonitorAndCannotLoseTheWinner(boolean flywheel) throws Exception {
		var adapter = new AdapterHarness(flywheel);
		try (var winner = winner(flywheel); var fixture = new BlockingFaultFixture(adapter, true)) {
			try (var executor = Executors.newFixedThreadPool(2)) {
				var registering = executor.submit(() -> adapter.invoke("register"));
				try {
					assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
					assertEquals("not-registered", executor.submit(adapter::state).get(5, TimeUnit.SECONDS));
				} finally { fixture.release.countDown(); }
				registering.get(5, TimeUnit.SECONDS);
			}
			assertEquals("rejected", adapter.state());
			adapter.invoke("register"); assertEquals("rejected", adapter.state());
			adapter.invoke("close"); assertEquals("not-registered", adapter.state());
			assertTrue(flywheel ? EntityBlockGeometrySourceRegistry.INSTANCE.snapshot().stream().anyMatch(source -> source.id().equals(adapter.id()))
				: EntityOutlineSourceRegistry.INSTANCE.snapshot().stream().anyMatch(source -> source.id().equals(adapter.id())));
		}
	}

	private static AutoCloseable winner(boolean flywheel) {
		if (flywheel) return EntityBlockGeometrySourceRegistry.INSTANCE.register(new EntityBlockGeometrySource() {
			@Override public String id() { return "create:flywheel_silhouette_mask"; }
			@Override public EntityBlockGeometryOutcome attempt(EntityBlockGeometryContext context) { return EntityBlockGeometryOutcome.EMPTY; }
		});
		return EntityOutlineSourceRegistry.INSTANCE.register(new EntityOutlineSource() {
			@Override public String id() { return "pingforit:create_entity_outline"; }
			@Override public boolean handles(net.minecraft.world.entity.Entity entity) { return false; }
			@Override public EntityBlockGeometryOutcome attempt(EntityOutlineContext context) { return EntityBlockGeometryOutcome.EMPTY; }
		});
	}

	/** Executes the compiled lifecycle code unchanged; only rendering-only initialization/source bodies are replaced. */
	@NotNullByDefault
	private static final class AdapterHarness extends ClassLoader {
		private final String owner;
		private final String sourceName;
		private final Class<?> adapter;
		private final Class<?> source;
		private final boolean flywheel;
		private AdapterHarness(boolean flywheel) throws Exception {
			super(CreateAdapterLifecycleDiagnosticsTest.class.getClassLoader()); this.flywheel = flywheel;
			owner = "nx.pingwheel.neoforge.integration.create.Create" + (flywheel ? "FlywheelGeometryAdapter" : "EntityOutlineAdapter");
			sourceName = owner + (flywheel ? "$CreateFlywheelGeometrySource" : "$CreateEntityOutlineSource");
			adapter = loadClass(owner); source = loadClass(sourceName);
		}
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.equals(owner) && !name.startsWith(owner + '$') && !name.startsWith("nx.pingwheel.neoforge.integration.create.CreateRegistrationLifecycle"))
				return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				@Nullable Class<?> loaded = findLoadedClass(name);
				if (loaded == null) try {
					byte @Unmodifiable [] bytes = name.equals(sourceName) ? sourceBytes() : classBytes(name);
					loaded = defineClass(name, bytes, 0, bytes.length);
				} catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
				if (resolve) resolveClass(loaded); return loaded;
			}
		}
		private byte @Unmodifiable [] classBytes(String name) throws Exception {
			try (@Nullable InputStream input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
				assertNotNull(input); byte @Unmodifiable [] bytes = input.readAllBytes();
				if (!name.equals(owner)) return bytes;
				var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
				node.methods.removeIf(method -> !List.of("register", "close", "registrationState", "install").contains(method.name)
					&& !method.name.startsWith("lambda$register$") && !method.name.startsWith("lambda$install$"));
				var init = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
				init.visitMethodInsn(Opcodes.INVOKESTATIC, "nx/pingwheel/common/diagnostics/Diagnostics", "client", "()Lnx/pingwheel/common/diagnostics/DiagnosticLogger;", false);
				init.visitFieldInsn(Opcodes.PUTSTATIC, node.name, "LOG", "Lnx/pingwheel/common/diagnostics/DiagnosticLogger;");
				String lifecycle = "nx/pingwheel/neoforge/integration/create/CreateRegistrationLifecycle";
				init.visitTypeInsn(Opcodes.NEW, lifecycle); init.visitInsn(Opcodes.DUP);
				init.visitMethodInsn(Opcodes.INVOKESPECIAL, lifecycle, "<init>", "()V", false);
				init.visitFieldInsn(Opcodes.PUTSTATIC, node.name, "LIFECYCLE", 'L' + lifecycle + ';');
				init.visitInsn(Opcodes.RETURN); init.visitMaxs(2, 0); node.methods.add(init);
				var writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
			}
		}
		private byte @Unmodifiable [] sourceBytes() {
			String name = sourceName.replace('.', '/');
			var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String @Unmodifiable [] {
				"nx/pingwheel/common/client/outline/" + (flywheel ? "EntityBlockGeometrySource" : "EntityOutlineSource")});
			writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "closes", "I", null, null).visitEnd();
			var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); constructor.visitCode();
			constructor.visitVarInsn(Opcodes.ALOAD, 0); constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd();
			var id = writer.visitMethod(Opcodes.ACC_PUBLIC, "id", "()Ljava/lang/String;", null, null); id.visitCode(); id.visitLdcInsn(id());
			id.visitInsn(Opcodes.ARETURN); id.visitMaxs(0, 0); id.visitEnd();
			var close = writer.visitMethod(Opcodes.ACC_PUBLIC, "close", "()V", null, null); close.visitCode();
			close.visitFieldInsn(Opcodes.GETSTATIC, name, "closes", "I"); close.visitInsn(Opcodes.ICONST_1); close.visitInsn(Opcodes.IADD);
			close.visitFieldInsn(Opcodes.PUTSTATIC, name, "closes", "I"); close.visitInsn(Opcodes.RETURN); close.visitMaxs(0, 0); close.visitEnd();
			if (!flywheel) {
				var handles = writer.visitMethod(Opcodes.ACC_PUBLIC, "handles", "(Lnet/minecraft/world/entity/Entity;)Z", null, null);
				handles.visitCode(); handles.visitInsn(Opcodes.ICONST_0); handles.visitInsn(Opcodes.IRETURN); handles.visitMaxs(0, 0); handles.visitEnd();
			}
			String context = "nx/pingwheel/common/client/outline/" + (flywheel ? "EntityBlockGeometryContext" : "EntityOutlineContext");
			String outcome = "nx/pingwheel/common/client/outline/EntityBlockGeometryOutcome";
			var attempt = writer.visitMethod(Opcodes.ACC_PUBLIC, "attempt", "(L" + context + ";)L" + outcome + ';', null, null);
			attempt.visitCode(); attempt.visitFieldInsn(Opcodes.GETSTATIC, outcome, "EMPTY", 'L' + outcome + ';');
			attempt.visitInsn(Opcodes.ARETURN); attempt.visitMaxs(0, 0); attempt.visitEnd();
			writer.visitEnd(); return writer.toByteArray();
		}
		private String id() { return flywheel ? "create:flywheel_silhouette_mask" : "pingforit:create_entity_outline"; }
		private void invoke(String name) {
			try { adapter.getMethod(name).invoke(null); }
			catch (InvocationTargetException failure) {
				@Nullable Throwable cause = failure.getCause();
				if (cause instanceof RuntimeException runtime) throw runtime;
				if (cause instanceof Error error) throw error;
				throw new AssertionError(failure);
			}
			catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
		private int closedSources() {
			try { return source.getField("closes").getInt(null); }
			catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
		private String state() {
			try { return (String) adapter.getMethod("registrationState").invoke(null); }
			catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
		private void assertOutsideMonitors() {
			assertFalse(Thread.holdsLock(adapter));
			try {
				var field = adapter.getDeclaredField("LIFECYCLE"); field.setAccessible(true); assertFalse(Thread.holdsLock(field.get(null)));
			} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
	}

	@NotNullByDefault
	private static final class ReentrantFaultFixture implements AutoCloseable {
		private final LoggingProfile client = Diagnostics.profile(DiagnosticSide.CLIENT);
		private final AtomicInteger callbacks = new AtomicInteger();
		private final DiagnosticFaultReporter faults = new DiagnosticFaultReporter(new DiagnosticEmergencyWriter(ByteArrayOutputStream::new));
		private final DiagnosticFaultReporter previousFaults;
		private final DiagnosticBackend previous;
		private ReentrantFaultFixture(AdapterHarness adapter, String operation) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
			previousFaults = Diagnostics.swapFaultsForTesting(faults);
			previous = Diagnostics.swapBackendForTesting(new DiagnosticBackend() {
				@Override public boolean isEnabled(DiagnosticSide side, Level severity) { return true; }
				@Override public void log(DiagnosticSide side, Level severity, String message) {
					if (!(message.contains("site=entity-outline-register") || message.contains("site=entity-block-source-register"))
						|| !callbacks.compareAndSet(0, 1)) return;
					adapter.assertOutsideMonitors();
					try {
						adapter.invoke(operation);
						fail("reentrant lifecycle operation must not return success");
					} catch (IllegalStateException failure) {
						assertEquals("create-registration-lifecycle-reentry", failure.getMessage());
						throw failure; // The real DiagnosticLogger must consume this as its own SINK fault.
					}
				}
			});
		}
		@Override public void close() {
			Diagnostics.swapBackendForTesting(previous); Diagnostics.swapFaultsForTesting(previousFaults);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, client);
		}
	}

	@NotNullByDefault
	private static final class BlockingFaultFixture implements AutoCloseable {
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicBoolean fail = new AtomicBoolean(true);
		private final AtomicInteger writes = new AtomicInteger();
		private final LoggingProfile client = Diagnostics.profile(DiagnosticSide.CLIENT);
		private final DiagnosticBackend previous;
		private final DiagnosticFaultReporter previousFaults;
		private BlockingFaultFixture(AdapterHarness adapter) {
			this(adapter, false);
		}
		private BlockingFaultFixture(AdapterHarness adapter, boolean registryRejection) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
			previousFaults = Diagnostics.swapFaultsForTesting(new DiagnosticFaultReporter(new DiagnosticEmergencyWriter(() -> new OutputStream() {
				@Override public void write(int value) { throw new AssertionError("whole-record write expected"); }
				@Override public void write(byte @Unmodifiable [] bytes) {
					adapter.assertOutsideMonitors();
					if (writes.incrementAndGet() != 1) return;
					entered.countDown();
					try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
					catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
				}
			})));
			previous = Diagnostics.swapBackendForTesting(new DiagnosticBackend() {
				@Override public boolean isEnabled(DiagnosticSide side, Level severity) { adapter.assertOutsideMonitors(); return true; }
				@Override public void log(DiagnosticSide side, Level severity, String message) {
					adapter.assertOutsideMonitors();
					// Select the exact boundary under test, not bootstrap/first-use diagnostics from other owners.
					boolean selected = registryRejection ? message.contains("site=entity-outline-register") || message.contains("site=entity-block-source-register")
						: severity == Level.INFO && message.contains("source registration");
					if (fail.get() && selected) throw new IllegalStateException("backend original");
				}
			});
		}
		@Override public void close() {
			release.countDown(); Diagnostics.swapBackendForTesting(previous); Diagnostics.swapFaultsForTesting(previousFaults);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, client);
		}
	}
}
