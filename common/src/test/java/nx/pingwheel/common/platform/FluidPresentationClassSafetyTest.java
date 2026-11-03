package nx.pingwheel.common.platform;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Execute fresh service discovery with all client/render classes forbidden on the server path. */
@NotNullByDefault
class FluidPresentationClassSafetyTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@TempDir Path directory;
	@Test void absentAndBrokenServiceConfigurationAreSafeWithoutLoadingAnyClientClass() throws Exception {
		Path broken = directory.resolve("broken-service"); Files.writeString(broken, "missing.optional.DescriptionProvider\n");
		Path malformed = directory.resolve("malformed-service"); Files.writeString(malformed, "not a legal provider name\n");
		for (@Nullable URL resource : new URL[] {null, broken.toUri().toURL(), malformed.toUri().toURL()}) {
			var loader = new ServerOnly(resource); var previous = Thread.currentThread().getContextClassLoader();
			Thread.currentThread().setContextClassLoader(loader);
			try {
				var descriptions = loader.loadClass(FluidDescriptions.class.getName());
				assertNull(descriptions.getMethod("describeBase", Fluid.class).invoke(null, Fluids.WATER));
				assertEquals(0, loader.clientAttempts);
			} finally { Thread.currentThread().setContextClassLoader(previous); }
		}
	}
	@Test void actualSpiConstructorCaptureSubclassCannotBecomeAnUnavailableName() throws Exception {
		var type = Class.forName("nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidFreezerTest$NameAdmissionFailure");
		assertSpiHardFailure((Throwable) type.getConstructor().newInstance());
	}
	@Test void actualSpiConstructorSentinelOutOfMemoryErrorCannotBecomeAnUnavailableName() throws Exception {
		assertSpiHardFailure(new OutOfMemoryError("sentinel only, no memory exhaustion"));
	}
	@SuppressWarnings("removal")
	@Test void actualSpiConstructorThreadDeathCannotBecomeAnUnavailableName() throws Exception {
		assertSpiHardFailure(new ThreadDeath());
	}
	private void assertSpiHardFailure(Throwable failure) throws Exception {
		Path descriptor = directory.resolve("constructor-service"); Files.writeString(descriptor, FailingProvider.class.getName() + "\n");
		var loader = new ServerOnly(descriptor.toUri().toURL());
		var provider = loader.loadClass(FailingProvider.class.getName()); provider.getField("failure").set(null, failure);
		provider.getField("reenter").setBoolean(null, true);
		var previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
		try {
			var descriptions = loader.loadClass(FluidDescriptions.class.getName());
			var method = descriptions.getMethod("describeBase", Fluid.class);
			for (int attempt = 1; attempt <= 2; attempt++) {
				var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(null, Fluids.WATER));
				assertSame(failure, thrown.getCause(), "discovery and holder initialization cannot hide the original hard failure");
				assertEquals(attempt, provider.getField("constructorCalls").getInt(null), "hard discovery failure must not poison or install the holder");
				assertEquals(attempt, provider.getField("reentrantCalls").getInt(null), "reentrant discovery is bounded and does not start another constructor");
			}
			assertEquals(0, loader.clientAttempts);
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}
	@Test void actualSpiConstructorOrdinaryAndLinkageFailuresRemainUnavailable() throws Exception {
		for (Throwable failure : new Throwable[] {new IllegalStateException("optional provider"), new NoClassDefFoundError("optional dependency")}) {
			Path descriptor = directory.resolve("ordinary-service"); Files.writeString(descriptor, FailingProvider.class.getName() + "\n");
			var loader = new ServerOnly(descriptor.toUri().toURL()); var provider = loader.loadClass(FailingProvider.class.getName());
			provider.getField("failure").set(null, failure);
			var previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
			try {
				var method = loader.loadClass(FluidDescriptions.class.getName()).getMethod("describeBase", Fluid.class);
				assertNull(method.invoke(null, Fluids.WATER)); assertNull(method.invoke(null, Fluids.WATER));
				assertEquals(1, provider.getField("constructorCalls").getInt(null)); assertEquals(0, loader.clientAttempts);
			} finally { Thread.currentThread().setContextClassLoader(previous); }
		}
	}
	@NotNullByDefault
	public static final class FailingProvider implements IPlatformFluidDescriptionService {
		public static @Nullable Throwable failure;
		public static int constructorCalls;
		public static int reentrantCalls;
		public static boolean reenter;
		public FailingProvider() {
			constructorCalls++;
			if (reenter) {
				if (FluidDescriptions.describeBase(Fluids.WATER) != null) throw new AssertionError("uninstalled service leaked through reentry");
				reentrantCalls++;
			}
			if (failure instanceof RuntimeException runtime) throw runtime;
			throw (Error) java.util.Objects.requireNonNull(failure);
		}
		@Override public @Nullable Description describeBase(Fluid fluid) { throw new AssertionError("constructor must fail first"); }
	}
	@Test void installedLoaderDescriptionClassesHaveNoClientReferencesAndRenderConstructorsOnlyInitializeObject() throws Exception {
		for (String loader : new String[] {"fabric", "forge", "neoforge"}) {
			String base = "nx/pingwheel/" + loader + "/platform/";
			try (var input = getClass().getClassLoader().getResourceAsStream(base + "PlatformFluidDescriptionServiceImpl.class")) {
				if (input == null) continue;
				var node = new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(input).accept(node, 0);
				for (var method : node.methods) for (var instruction : method.instructions) if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
					assertFalse(call.owner.contains("/client/") || call.desc.contains("/client/"), call.owner + "." + call.name);
					assertFalse(call.name.contains("Stack") || call.name.contains("Capability"));
				}
			}
			try (var input = getClass().getClassLoader().getResourceAsStream(base + "client/PlatformFluidRenderServiceImpl.class")) {
				assertNotNull(input); var node = new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(input).accept(node, 0);
				assertTrue(node.fields.isEmpty(), "adapter cannot retain sprites or resource failures");
				var constructor = node.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
				for (var instruction : constructor.instructions) if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
					assertEquals("java/lang/Object", call.owner); assertEquals("<init>", call.name);
				}
				assertTrue(node.methods.stream().noneMatch(m -> m.name.equals("<clinit>")), "loading service does not initialize an atlas or extension");
			}
		}
	}
	@NotNullByDefault
	private static final class ServerOnly extends ClassLoader {
		private final @Nullable URL service;
		private int clientAttempts;
		ServerOnly(@Nullable URL service) { super(FluidPresentationClassSafetyTest.class.getClassLoader()); this.service = service; }
		@Override public Enumeration<URL> getResources(String name) throws IOException {
			if (name.equals("META-INF/services/" + IPlatformFluidDescriptionService.class.getName()))
				return service == null ? Collections.emptyEnumeration() : Collections.enumeration(java.util.List.of(service));
			return super.getResources(name);
		}
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("net.minecraft.client.") || name.contains(".platform.client.") || name.contains(".render.")) {
				clientAttempts++; throw new AssertionError("server name path linked client class " + name);
			}
			if (!(name.startsWith(FluidDescriptions.class.getName()) || name.startsWith(IPlatformFluidDescriptionService.class.getName())
				|| name.equals(FailingProvider.class.getName()))) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				@Nullable Class<?> found = findLoadedClass(name);
				if (found == null) try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
					if (input == null) throw new ClassNotFoundException(name);
					byte[] bytes = input.readAllBytes(); found = defineClass(name, bytes, 0, bytes.length);
				} catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
				if (resolve) resolveClass(found); return found;
			}
		}
	}
}
