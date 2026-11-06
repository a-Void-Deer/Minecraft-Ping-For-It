package nx.pingwheel.common.diagnostics;

import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class NeoClientOptionalRegistrationTest {
	@ParameterizedTest
	@ValueSource(strings = {"entity", "flywheel", "raycast", "resolver"})
	void normalAndDisabledReportsNeverProbeButBusinessRegistrationStillRuns(String route) throws Exception {
		try (var fixture = new Fixture()) {
			var harness = new Harness(true);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
			harness.register(route);
			assertEquals(1, harness.count("registrations"));
			assertEquals(0, harness.count("probes"));
			assertTrue(fixture.events.isEmpty());
			assertTrue(fixture.faults.observations().isEmpty());
			assertTrue(harness.state(route).startsWith("reflection-success"));
			if (!route.equals("resolver")) assertTrue(harness.resolved(route));
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			harness.register(route);
			assertEquals(0, harness.count("probes"), "profile changes must not reset the transition history");

			fixture.enabled = false;
			var disabled = new Harness(true);
			disabled.register(route);
			assertEquals(1, disabled.count("registrations"));
			assertEquals(0, disabled.count("probes"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"entity", "flywheel", "raycast", "resolver"})
	void admittedStateProbeRunsOnceAndItsFailureIsContextNotRegistrationFailure(String route) throws Exception {
		for (boolean hasProbe : List.of(false, true)) try (var fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			var harness = new Harness(hasProbe);
			if (hasProbe) harness.stub.getField("probeFailure").set(null, new LinkageError("probe original"));
			harness.register(route);
			assertEquals(1, harness.count("registrations"));
			assertEquals(hasProbe ? 1 : 0, harness.count("probes"));
			assertTrue(harness.state(route).startsWith("reflection-success"));
			if (!route.equals("resolver")) assertTrue(harness.resolved(route));
			assertFalse(fixture.events.stream().anyMatch(event -> event.level == Level.WARN));
			assertTrue(fixture.events.stream().anyMatch(event -> event.text.contains("[context unavailable]")));
			assertEquals(1, fixture.faults.observations().size());
			assertEquals(DiagnosticFaultReporter.Stage.CONTEXT, fixture.faults.observations().getFirst().key().stage());
			assertEquals(route.equals("resolver") ? "optional-resolver-state" : "optional-adapter-state",
				fixture.faults.observations().getFirst().key().site());
			harness.register(route);
			assertEquals(2, harness.count("registrations"), "a diagnostic failure must not disable business retries");
			assertEquals(hasProbe ? 1 : 0, harness.count("probes"), "same transition remains suppressed");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"entity", "flywheel", "raycast", "resolver"})
	void successfulDetailIsLazyAndARealRegisterFailureStillOwnsItsWarning(String route) throws Exception {
		try (var fixture = new Fixture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			var harness = new Harness(true);
			harness.register(route);
			assertEquals(1, harness.count("probes"));
			assertTrue(fixture.events.stream().anyMatch(event -> event.text.contains("registered-private")));
			assertTrue(fixture.faults.observations().isEmpty());
			var broken = new Harness(true);
			broken.stub.getField("registerFailure").set(null, new IllegalStateException("register original"));
			broken.register(route);
			assertEquals(0, broken.count("probes"));
			assertTrue(broken.state(route).startsWith("reflection-failure"));
			assertTrue(fixture.events.stream().anyMatch(event -> event.level == Level.WARN && event.text.contains("register original")));
		}
	}

	/** Fresh class-loader state, not resetting real transition/once gates to make a test pass. */
	@NotNullByDefault
	private static final class Harness extends ClassLoader {
		private static final String CLIENT = "nx.pingwheel.neoforge.NeoClient";
		private static final String STUB = "nx.pingwheel.neoforge.integration.create.CreateContraptionRaycastAdapter";
		private final Class<?> client;
		private final Class<?> stub;

		private Harness(boolean hasProbe) throws Exception {
			super(NeoClientOptionalRegistrationTest.class.getClassLoader());
			byte @Unmodifiable [] stubBytes = stub(hasProbe);
			stub = defineClass(STUB, stubBytes, 0, stubBytes.length);
			try (@Nullable InputStream input = getParent().getResourceAsStream(CLIENT.replace('.', '/') + ".class")) {
				assertNotNull(input);
				byte @Unmodifiable [] bytes = input.readAllBytes();
				client = defineClass(CLIENT, bytes, 0, bytes.length);
			}
		}

		private void register(String route) throws Exception {
			if (route.equals("raycast")) invoke("registerOptionalContraptionRaycastAdapter", new Class<?> @Unmodifiable [0]);
			else if (route.equals("resolver")) invoke("registerOptionalResolver", new Class<?> @Unmodifiable [] {String.class, String.class}, STUB, "create-vault-presentation");
			else invoke("registerOptionalAdapter", new Class<?> @Unmodifiable [] {String.class, String.class, boolean.class}, STUB,
				route.equals("entity") ? "create-entity" : "create-flywheel", route.equals("entity"));
		}

		private void invoke(String name, Class<?> @Unmodifiable [] types, Object @Unmodifiable ... arguments) throws Exception {
			Method method = client.getDeclaredMethod(name, types); method.setAccessible(true);
			try { method.invoke(null, arguments); }
			catch (InvocationTargetException failure) { throw new AssertionError("business entry propagated a failure", failure.getCause()); }
		}

		private int count(String name) throws Exception { return stub.getField(name).getInt(null); }
		private String state(String route) throws Exception {
			return (String) java.util.Objects.requireNonNull(field(switch (route) {
				case "entity" -> "lastEntityAdapterState";
				case "flywheel" -> "lastFlywheelAdapterState";
				case "raycast" -> "lastContraptionRaycastAdapterState";
				default -> "lastCreateVaultResolverState";
			}));
		}
		private boolean resolved(String route) throws Exception {
			return (boolean) java.util.Objects.requireNonNull(field(route.equals("entity") ? "entityAdapterResolved"
				: route.equals("raycast") ? "contraptionRaycastAdapterResolved" : "flywheelAdapterResolved"));
		}
		private @Nullable Object field(String name) throws Exception {
			var field = client.getDeclaredField(name); field.setAccessible(true); return field.get(null);
		}

		private static byte @Unmodifiable [] stub(boolean hasProbe) {
			String owner = STUB.replace('.', '/');
			var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
			for (String field : List.of("registrations", "probes")) writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "I", null, null).visitEnd();
			for (String field : List.of("registerFailure", "probeFailure")) writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "Ljava/lang/Throwable;", null, null).visitEnd();
			for (String name : hasProbe ? List.of("register", "registrationState") : List.of("register")) {
				boolean probe = name.equals("registrationState");
				var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, probe ? "()Ljava/lang/String;" : "()V", null, null);
				method.visitCode();
				String count = probe ? "probes" : "registrations";
				method.visitFieldInsn(Opcodes.GETSTATIC, owner, count, "I"); method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD);
				method.visitFieldInsn(Opcodes.PUTSTATIC, owner, count, "I");
				method.visitFieldInsn(Opcodes.GETSTATIC, owner, probe ? "probeFailure" : "registerFailure", "Ljava/lang/Throwable;");
				var success = new org.objectweb.asm.Label(); method.visitJumpInsn(Opcodes.IFNULL, success);
				method.visitFieldInsn(Opcodes.GETSTATIC, owner, probe ? "probeFailure" : "registerFailure", "Ljava/lang/Throwable;"); method.visitInsn(Opcodes.ATHROW);
				method.visitLabel(success);
				if (probe) method.visitLdcInsn("registered-private");
				method.visitInsn(probe ? Opcodes.ARETURN : Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
			}
			writer.visitEnd(); return writer.toByteArray();
		}
	}

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		private final LoggingProfile client = Diagnostics.profile(DiagnosticSide.CLIENT);
		private final List<Event> events = new ArrayList<>();
		private final DiagnosticFaultReporter faults = new DiagnosticFaultReporter(new DiagnosticEmergencyWriter(ByteArrayOutputStream::new));
		private final DiagnosticFaultReporter previousFaults = Diagnostics.swapFaultsForTesting(faults);
		private boolean enabled = true;
		private final DiagnosticBackend previous = Diagnostics.swapBackendForTesting(new DiagnosticBackend() {
			@Override public boolean isEnabled(DiagnosticSide side, Level severity) { return enabled; }
			@Override public void log(DiagnosticSide side, Level severity, String message) { events.add(new Event(severity, message)); }
		});
		@Override public void close() {
			Diagnostics.swapBackendForTesting(previous); Diagnostics.swapFaultsForTesting(previousFaults);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, client);
		}
	}

	@NotNullByDefault
	private record Event(Level level, String text) {}
}
