package nx.pingwheel.common.diagnostics;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class NeoClientAeronauticsLifecycleTest {
	private static final String CLIENT = "nx.pingwheel.neoforge.NeoClient";
	private static final String AERONAUTICS_ADAPTER =
		"nx.pingwheel.neoforge.integration.create.AeronauticsPropellerRaycastAdapter";

	@Test
	void absentModNeverLoadsTheOptionalOwnerOrCallsLifecycleMethods() throws Exception {
		try (var mods = new Mods()) {
			var harness = new Harness();
			harness.invoke("loadAeronauticsPropellerRaycastAdapter");

			assertEquals(0, harness.optionalClassLoads(), "the optional owner must not be resolved without its mod");
			assertEquals(0, harness.count("registrations"));
			assertFalse(harness.resolved());

			harness.invoke("closeAeronauticsPropellerRaycastAdapter");

			assertEquals(0, harness.optionalClassLoads(), "teardown must not resolve an absent optional owner");
			assertEquals(0, harness.count("closes"));
		}
	}

	@Test
	void unrelatedOptionalModsDoNotResolveTheAeroOwner() throws Exception {
		try (var mods = new Mods("create", "flywheel", "simulated", "sable")) {
			var harness = new Harness();
			harness.invoke("loadAeronauticsPropellerRaycastAdapter");

			assertEquals(0, harness.optionalClassLoads());
			assertEquals(0, harness.count("registrations"));
			assertFalse(harness.resolved());
		}
	}

	@Test
	void presentModRegistersOnInitialLoadAndRejoinThenClosesOnDisconnect() throws Exception {
		try (var mods = new Mods("aeronautics")) {
			var harness = new Harness();
			harness.invoke("loadAeronauticsPropellerRaycastAdapter");

			assertEquals(1, harness.count("registrations"));
			assertTrue(harness.active());
			assertTrue(harness.resolved());
			assertTrue(harness.state().startsWith("reflection-success"));

			harness.invoke("loadAeronauticsPropellerRaycastAdapter");

			assertEquals(2, harness.count("registrations"), "a rejoin reaches the idempotent optional registration");
			assertTrue(harness.active(), "the optional owner keeps one active registration across a rejoin");

			harness.invoke("closeOptionalAdapters");

			assertEquals(1, harness.count("closes"), "disconnect teardown includes the Aero owner");
			assertFalse(harness.active());
			assertFalse(harness.resolved());

			harness.invoke("loadAeronauticsPropellerRaycastAdapter");

			assertEquals(3, harness.count("registrations"), "a later rejoin registers again after disconnect");
			assertTrue(harness.active());
			assertTrue(harness.resolved());
		}
	}

	@Test
	void registrationFailureIsReportedAndDoesNotLatchTheRouteOrSkipTeardown() throws Exception {
		try (var mods = new Mods("aeronautics"); var logs = new Logs()) {
			var harness = new Harness();
			harness.failRegistration(new LinkageError("aero original"));

			assertDoesNotThrow(() -> harness.invoke("loadAeronauticsPropellerRaycastAdapter"));

			assertEquals(1, harness.count("registrations"));
			assertFalse(harness.active());
			assertTrue(harness.resolved());
			assertTrue(harness.state().startsWith("reflection-failure"));
			assertTrue(logs.events.stream().anyMatch(event -> event.level == Level.WARN
				&& event.text.contains("adapter=aeronautics-propeller-raycast")
				&& event.text.contains("aero original")));

			harness.invoke("closeAeronauticsPropellerRaycastAdapter");

			assertEquals(1, harness.count("closes"), "teardown runs for a partially resolved optional owner");
			assertFalse(harness.resolved());

			harness.clearRegistrationFailure();
			harness.invoke("loadAeronauticsPropellerRaycastAdapter");

			assertEquals(2, harness.count("registrations"), "a failed optional registration does not disable retries");
			assertTrue(harness.active());
			assertTrue(harness.resolved());
		}
	}

	@NotNullByDefault
	private static final class Harness extends ClassLoader {
		private final Class<?> client;
		private final Class<?> stub;
		private int optionalClassLoads;

		private Harness() throws Exception {
			super(NeoClientAeronauticsLifecycleTest.class.getClassLoader());
			byte @Unmodifiable [] stubBytes = stub();
			stub = defineClass(AERONAUTICS_ADAPTER, stubBytes, 0, stubBytes.length);
			try (@Nullable InputStream input = getParent().getResourceAsStream(CLIENT.replace('.', '/') + ".class")) {
				assertNotNull(input);
				byte @Unmodifiable [] bytes = input.readAllBytes();
				client = defineClass(CLIENT, bytes, 0, bytes.length);
			}
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.equals(AERONAUTICS_ADAPTER)) {
				optionalClassLoads++;
			}
			return super.loadClass(name, resolve);
		}

		private void invoke(String name) throws Exception {
			Method method = client.getDeclaredMethod(name);
			method.setAccessible(true);
			try {
				method.invoke(null);
			} catch (InvocationTargetException failure) {
				throw new AssertionError("NeoClient lifecycle entry propagated " + name, failure.getCause());
			}
		}

		private int count(String name) throws Exception {
			return stub.getField(name).getInt(null);
		}

		private boolean active() throws Exception {
			return stub.getField("active").getBoolean(null);
		}

		private void failRegistration(Throwable failure) throws Exception {
			stub.getField("registerFailure").set(null, failure);
		}

		private void clearRegistrationFailure() throws Exception {
			stub.getField("registerFailure").set(null, null);
		}

		private boolean resolved() throws Exception {
			return (boolean) java.util.Objects.requireNonNull(field("aeronauticsPropellerRaycastAdapterResolved"));
		}

		private String state() throws Exception {
			return String.valueOf(field("lastAeronauticsPropellerRaycastAdapterState"));
		}

		private int optionalClassLoads() {
			return optionalClassLoads;
		}

		private @Nullable Object field(String name) throws Exception {
			Field field = client.getDeclaredField(name);
			field.setAccessible(true);
			return field.get(null);
		}

		private static byte @Unmodifiable [] stub() {
			String owner = AERONAUTICS_ADAPTER.replace('.', '/');
			var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null);
			for (String field : List.of("registrations", "closes")) {
				writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "I", null, null).visitEnd();
			}
			writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "active", "Z", null, null).visitEnd();
			writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registerFailure", "Ljava/lang/Throwable;",
				null, null).visitEnd();

			var register = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register", "()V", null, null);
			register.visitCode();
			increment(register, owner, "registrations");
			var registered = new Label();
			register.visitFieldInsn(Opcodes.GETSTATIC, owner, "registerFailure", "Ljava/lang/Throwable;");
			register.visitJumpInsn(Opcodes.IFNULL, registered);
			register.visitFieldInsn(Opcodes.GETSTATIC, owner, "registerFailure", "Ljava/lang/Throwable;");
			register.visitInsn(Opcodes.ATHROW);
			register.visitLabel(registered);
			register.visitInsn(Opcodes.ICONST_1);
			register.visitFieldInsn(Opcodes.PUTSTATIC, owner, "active", "Z");
			register.visitInsn(Opcodes.RETURN);
			register.visitMaxs(0, 0);
			register.visitEnd();

			var close = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "close", "()V", null, null);
			close.visitCode();
			increment(close, owner, "closes");
			close.visitInsn(Opcodes.ICONST_0);
			close.visitFieldInsn(Opcodes.PUTSTATIC, owner, "active", "Z");
			close.visitInsn(Opcodes.RETURN);
			close.visitMaxs(0, 0);
			close.visitEnd();

			var state = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"registrationState", "()Ljava/lang/String;", null, null);
			state.visitCode();
			var inactive = new Label();
			state.visitFieldInsn(Opcodes.GETSTATIC, owner, "active", "Z");
			state.visitJumpInsn(Opcodes.IFEQ, inactive);
			state.visitLdcInsn("registered");
			state.visitInsn(Opcodes.ARETURN);
			state.visitLabel(inactive);
			state.visitLdcInsn("not-registered");
			state.visitInsn(Opcodes.ARETURN);
			state.visitMaxs(0, 0);
			state.visitEnd();

			writer.visitEnd();
			return writer.toByteArray();
		}

		private static void increment(org.objectweb.asm.MethodVisitor method, String owner, String field) {
			method.visitFieldInsn(Opcodes.GETSTATIC, owner, field, "I");
			method.visitInsn(Opcodes.ICONST_1);
			method.visitInsn(Opcodes.IADD);
			method.visitFieldInsn(Opcodes.PUTSTATIC, owner, field, "I");
		}
	}

	@NotNullByDefault
	private static final class Mods implements AutoCloseable {
		private final Field instance;
		private final @Nullable Object previous;

		private Mods(String @Unmodifiable ... loadedModIds) throws Exception {
			Class<?> modList = Class.forName("net.neoforged.fml.ModList");
			instance = modList.getDeclaredField("INSTANCE");
			instance.setAccessible(true);
			previous = instance.get(null);
			Object list = allocate(modList);
			Field indexed = modList.getDeclaredField("indexedMods");
			indexed.setAccessible(true);
			Map<String, @Nullable Object> loaded = new HashMap<>();
			for (String modId : loadedModIds) {
				loaded.put(modId, null);
			}
			indexed.set(list, loaded);
			instance.set(null, list);
		}

		@Override
		public void close() throws Exception {
			instance.set(null, previous);
		}

		private static Object allocate(Class<?> type) {
			try {
				Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
				Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
				theUnsafe.setAccessible(true);
				return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(theUnsafe.get(null), type));
			} catch (ReflectiveOperationException failure) {
				throw new AssertionError(failure);
			}
		}
	}

	@NotNullByDefault
	private static final class Logs implements AutoCloseable {
		private final List<Event> events = new ArrayList<>();
		private final LoggingProfile previousProfile = Diagnostics.profile(DiagnosticSide.CLIENT);
		private final DiagnosticBackend previous = Diagnostics.swapBackendForTesting(new DiagnosticBackend() {
			@Override
			public boolean isEnabled(DiagnosticSide side, Level severity) {
				return true;
			}

			@Override
			public void log(DiagnosticSide side, Level severity, String message) {
				events.add(new Event(severity, message));
			}
		});

		private Logs() {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
		}

		@Override
		public void close() {
			Diagnostics.swapBackendForTesting(previous);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, previousProfile);
		}
	}

	@NotNullByDefault
	private record Event(Level level, String text) {}
}
