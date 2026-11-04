package nx.pingwheel.common.platform;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Discovery executes with client classes forbidden; hard failures retain their native identity. */
@NotNullByDefault
class FluidRegistryClassSafetyTest {
	private static boolean previousBootstrap;
	private static boolean forgeBootstrapOwned;
	@BeforeAll static void bootstrap() throws ReflectiveOperationException {
		try {
			if (!Fluid.class.getMethod("getFluidType").getReturnType().getName().equals("net.minecraftforge.fluids.FluidType")) return;
		}
		catch (NoSuchMethodException vanilla) {
			net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
			return;
		}
		net.minecraft.SharedConstants.tryDetectVersion();
		// Match the native Forge fixtures: install GameData's real registries before
		// touching Fluids, without invoking unrelated item-component/FML mod events.
		var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped"); field.setAccessible(true);
		if (!forgeBootstrapOwned) { previousBootstrap = field.getBoolean(null); forgeBootstrapOwned = true; }
		field.setBoolean(null, true);
		assertNotNull(Class.forName("net.minecraftforge.registries.ForgeRegistries", true, Fluid.class.getClassLoader()).getField("FLUIDS").get(null));
		assertSame(Class.forName("net.minecraftforge.registries.NamespacedDefaultedWrapper", false, Fluid.class.getClassLoader()),
			net.minecraft.core.registries.BuiltInRegistries.FLUID.getClass());
		assertEquals(net.minecraft.resources.ResourceLocation.withDefaultNamespace("water"),
			net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(Fluids.WATER));
	}
	@AfterAll static void restoreBootstrap() throws ReflectiveOperationException {
		if (!forgeBootstrapOwned) return;
		var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped"); field.setAccessible(true);
		field.setBoolean(null, previousBootstrap); forgeBootstrapOwned = false;
	}
	@TempDir Path directory;
	@Test void missingMalformedAndBrokenProvidersFailClosedWithoutClientLinkage() throws Exception {
		Path missing = directory.resolve("missing"); Files.writeString(missing, "missing.RegistryProvider\n");
		Path malformed = directory.resolve("malformed"); Files.writeString(malformed, "not a provider name\n");
		for (@Nullable URL resource : new URL[] {null, missing.toUri().toURL(), malformed.toUri().toURL()}) {
			var loader = new ServerOnly(resource); var previous = Thread.currentThread().getContextClassLoader();
			Thread.currentThread().setContextClassLoader(loader);
			try {
				var method = loader.loadClass(FluidRegistries.class.getName()).getMethod("registeredKey", Fluid.class, IPlatformFluidRegistryService.Admission.class);
				assertNull(method.invoke(null, Fluids.WATER, (IPlatformFluidRegistryService.Admission) (work, bytes) -> {}));
				assertEquals(0, loader.clientAttempts);
			} finally { Thread.currentThread().setContextClassLoader(previous); }
		}
	}
	@SuppressWarnings("removal")
	@Test void actualServiceConstructorHardFailuresPropagateAndRetryWithoutPoisoning() throws Exception {
		var capture = (Throwable) Class.forName("nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidFreezerTest$NameAdmissionFailure")
			.getConstructor().newInstance();
		for (Throwable failure : new Throwable[] {capture, new OutOfMemoryError("sentinel"), new ThreadDeath()}) {
			Path descriptor = directory.resolve("hard"); Files.writeString(descriptor, FailingProvider.class.getName());
			var loader = new ServerOnly(descriptor.toUri().toURL());
			var provider = loader.loadClass(FailingProvider.class.getName()); provider.getField("failure").set(null, failure);
			provider.getField("reenter").setBoolean(null, true);
			var previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
			try {
				var method = loader.loadClass(FluidRegistries.class.getName()).getMethod("registeredKey", Fluid.class, IPlatformFluidRegistryService.Admission.class);
				for (int attempt = 1; attempt <= 2; attempt++) {
					var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(null, Fluids.WATER,
						(IPlatformFluidRegistryService.Admission) (work, bytes) -> {}));
					assertSame(failure, thrown.getCause()); assertEquals(attempt, provider.getField("calls").getInt(null));
					assertEquals(attempt, provider.getField("reentrantCalls").getInt(null));
				}
				assertEquals(0, loader.clientAttempts);
			} finally { Thread.currentThread().setContextClassLoader(previous); }
		}
	}
	@Test void firstAdmissionClassLoadingFatalReleasesDiscoveryAndSameHelperRetriesOnce() throws Exception {
		var descriptor = directory.resolve("retry-after-linkage"); Files.writeString(descriptor, SimpleProvider.class.getName());
		var loader = new ServerOnly(descriptor.toUri().toURL());
		var sentinel = new OutOfMemoryError("one-shot helper linkage sentinel, no actual exhaustion");
		loader.admissionClassFailure = sentinel;
		var helper = loader.loadClass(FluidRegistries.class.getName());
		var method = helper.getMethod("registeredKey", Fluid.class, IPlatformFluidRegistryService.Admission.class);
		var discovering = helper.getDeclaredField("discovering"); discovering.setAccessible(true);
		var discovered = helper.getDeclaredField("discovered"); discovered.setAccessible(true);
		int constructors = SimpleProvider.calls;
		long[] charges = {0};
		IPlatformFluidRegistryService.Admission admission = (work, bytes) -> charges[0] += bytes;
		var previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
		try {
			assertSame(sentinel, assertThrows(java.lang.reflect.InvocationTargetException.class,
				() -> method.invoke(null, Fluids.WATER, admission)).getCause());
			assertFalse(discovering.getBoolean(null), "failure after taking ownership must release the flag");
			assertFalse(discovered.getBoolean(null), "fatal helper linkage cannot cache unavailable");
			assertEquals(65536, charges[0], "cold allocation is admitted before helper construction");
			assertEquals(0, loader.resourceNextCalls); assertEquals(constructors, SimpleProvider.calls);
			assertNull(method.invoke(null, Fluids.WATER, admission));
			assertEquals(constructors + 1, SimpleProvider.calls); assertEquals(1, loader.resourceNextCalls);
			assertTrue(discovered.getBoolean(null)); assertFalse(discovering.getBoolean(null));
			charges[0] = 0;
			assertNull(method.invoke(null, Fluids.WATER, admission));
			assertEquals(0, charges[0]); assertEquals(constructors + 1, SimpleProvider.calls);
			assertEquals(1, loader.resourceNextCalls, "warm retry performs no extra descriptor read");
			assertEquals(1, loader.admissionClassFailures);
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}
	@Test void ordinaryConstructorFailureIsCachedAndRefusedAdmissionPrecedesDiscovery() throws Exception {
		Path descriptor = directory.resolve("ordinary"); Files.writeString(descriptor, FailingProvider.class.getName());
		var loader = new ServerOnly(descriptor.toUri().toURL());
		var provider = loader.loadClass(FailingProvider.class.getName()); provider.getField("failure").set(null, new IllegalStateException("optional"));
		var previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
		try {
			var method = loader.loadClass(FluidRegistries.class.getName()).getMethod("registeredKey", Fluid.class, IPlatformFluidRegistryService.Admission.class);
			var failure = new IllegalStateException("refused admission");
			assertSame(failure, assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(null, Fluids.WATER,
				(IPlatformFluidRegistryService.Admission) (work, bytes) -> { throw failure; })).getCause());
			assertEquals(0, provider.getField("calls").getInt(null));
			for (int attempt = 0; attempt < 2; attempt++) assertNull(method.invoke(null, Fluids.WATER,
				(IPlatformFluidRegistryService.Admission) (work, bytes) -> {}));
			assertEquals(1, provider.getField("calls").getInt(null));
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}
	@Test void realServiceLoaderReaderAndWholeParseArePrepaidBeforeTheFirstRead() throws Exception {
		var allocation = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		allocation.setThreadAllocatedMemoryEnabled(true);
		byte[] payload = (SimpleProvider.class.getName() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
		long[] observed = new long[7]; // admissions, prepaid, stream-start, reader bytes, pre-discovery, total, first-read prepayment
		var descriptor = URL.of(java.net.URI.create("probe:descriptor"), new java.net.URLStreamHandler() {
			@Override protected java.net.URLConnection openConnection(URL url) {
				return new java.net.URLConnection(url) {
					@Override public void connect() {}
					@Override public java.io.InputStream getInputStream() {
						var stream = new java.io.ByteArrayInputStream(payload) {
							private boolean first = true;
							@Override public synchronized int read(byte[] bytes, int offset, int length) {
								if (first) {
									observed[3] = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - observed[2];
									observed[6] = observed[1];
									first = false;
								}
								return super.read(bytes, offset, length);
							}
						};
						observed[2] = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()); return stream;
					}
				};
			}
		});
		var loader = new ServerOnly(descriptor);
		var method = loader.loadClass(FluidRegistries.class.getName()).getMethod("registeredKey", Fluid.class, IPlatformFluidRegistryService.Admission.class);
		var previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
		try {
			IPlatformFluidRegistryService.Admission admission = (work, bytes) -> { observed[0]++; observed[1] += bytes; };
			observed[4] = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
			assertNull(method.invoke(null, Fluids.WATER, admission));
			long parseAndReflection = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - observed[2];
			observed[5] = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - observed[4];
			assertTrue(observed[6] >= 24576 + 2 * 40, "actual ServiceLoader readers require both array payloads plus headers before the first read");
			assertTrue(observed[3] >= 24576, "measure the real JDK reader, not a synthetic buffer helper");
			assertTrue(observed[1] >= observed[3]);
			// ThreadMXBean includes class-loader/JVM linkage, URL callback and JUnit allocation,
			// so report the whole value separately; it is not a simultaneous-workspace measure.
			System.out.println("REGISTRY_DISCOVERY_ALLOC prepaid=" + observed[1] + " reader=" + observed[3]
				+ " parseAndReflection=" + parseAndReflection + " threadTotal=" + observed[5]);
			assertTrue(parseAndReflection < observed[1],
				"real reader/parse/reflection cumulative allocation fits the complete discovery prepayment");
			long cold = observed[1]; observed[0] = observed[1] = 0;
			assertNull(method.invoke(null, Fluids.WATER, admission));
			assertEquals(0, observed[1], "warm lookup cannot repeat cold allocation charges"); assertTrue(cold > 24576);
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}
	@Test void overlongDescriptorAndTooManyEmptyDescriptorsFailClosedWithBoundedReads() throws Exception {
		var huge = directory.resolve("huge"); Files.writeString(huge, "#" + "x".repeat(100_000));
		var loader = new ServerOnly(huge.toUri().toURL());
		var previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
		try {
			var method = loader.loadClass(FluidRegistries.class.getName()).getMethod("registeredKey", Fluid.class, IPlatformFluidRegistryService.Admission.class);
			long[] charged = {0};
			assertNull(method.invoke(null, Fluids.WATER, (IPlatformFluidRegistryService.Admission) (work, bytes) -> charged[0] += bytes));
			assertTrue(charged[0] < 300_000, "a long line is stopped at the bounded byte stream before JDK StringBuilder growth");
			charged[0] = 0; assertNull(method.invoke(null, Fluids.WATER, (IPlatformFluidRegistryService.Admission) (work, bytes) -> charged[0] += bytes));
			assertEquals(0, charged[0], "ordinary bound refusal is cached");
		} finally { Thread.currentThread().setContextClassLoader(previous); }
		var empty = directory.resolve("empty"); Files.writeString(empty, "");
		var many = new ServerOnly(empty.toUri().toURL()); many.resourceCount = 1000;
		Thread.currentThread().setContextClassLoader(many);
		try {
			var method = many.loadClass(FluidRegistries.class.getName()).getMethod("registeredKey", Fluid.class, IPlatformFluidRegistryService.Admission.class);
			assertNull(method.invoke(null, Fluids.WATER, (IPlatformFluidRegistryService.Admission) (work, bytes) -> {}));
			assertEquals(8, many.resourceNextCalls, "JDK receives only the paid bounded descriptor prefix");
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}
	@Test void perDescriptorAdmissionRefusalDoesNotReadOrPoisonRetry() throws Exception {
		var descriptor = directory.resolve("valid"); Files.writeString(descriptor, SimpleProvider.class.getName());
		var loader = new ServerOnly(descriptor.toUri().toURL());
		var previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
		try {
			var method = loader.loadClass(FluidRegistries.class.getName()).getMethod("registeredKey", Fluid.class, IPlatformFluidRegistryService.Admission.class);
			var sentinel = new IllegalStateException("descriptor grant");
			assertSame(sentinel, assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(null, Fluids.WATER,
				(IPlatformFluidRegistryService.Admission) (work, bytes) -> { if (bytes > 100_000) throw sentinel; })).getCause());
			assertEquals(0, loader.resourceNextCalls, "refused grant precedes descriptor URL acquisition/read");
			assertNull(method.invoke(null, Fluids.WATER, (IPlatformFluidRegistryService.Admission) (work, bytes) -> {}));
			assertEquals(1, loader.resourceNextCalls);
		} finally { Thread.currentThread().setContextClassLoader(previous); }
	}
	@Test void automaticModuleProviderUsesRealLayerServiceLoader(@TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path moduleDirectory) throws Exception {
		// A ModuleLayer's JDK reader retains an open jar on Windows until its loader is collected.
		var jar = moduleDirectory.resolve("registry.fixture.jar");
		try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
			var types = new java.util.ArrayList<Class<?>>();
			types.add(FluidRegistries.class); types.addAll(java.util.List.of(FluidRegistries.class.getDeclaredClasses()));
			types.add(IPlatformFluidRegistryService.class); types.add(IPlatformFluidRegistryService.Admission.class); types.add(SimpleProvider.class);
			for (var type : types) {
				String name = type.getName().replace('.', '/') + ".class"; output.putNextEntry(new java.util.jar.JarEntry(name));
				try (var input = getClass().getClassLoader().getResourceAsStream(name)) { assertNotNull(input); input.transferTo(output); }
				output.closeEntry();
			}
			output.putNextEntry(new java.util.jar.JarEntry("META-INF/services/" + IPlatformFluidRegistryService.class.getName()));
			output.write(SimpleProvider.class.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
		}
		var finder = java.lang.module.ModuleFinder.of(jar);
		var configuration = ModuleLayer.boot().configuration().resolve(finder, java.lang.module.ModuleFinder.of(), java.util.Set.of("registry.fixture"));
		var layer = ModuleLayer.boot().defineModulesWithOneLoader(configuration, getClass().getClassLoader());
		var moduleLoader = layer.findLoader("registry.fixture");
		// Explicit module load avoids the parent-first classpath copy of these same names.
		var module = layer.findModule("registry.fixture").orElseThrow();
		var service = Class.forName(module, FluidRegistries.class.getName()); assertNotNull(service);
		var admissionType = Class.forName(module, IPlatformFluidRegistryService.Admission.class.getName()); assertNotNull(admissionType);
		long[] debits = {0, 0};
		var admission = java.lang.reflect.Proxy.newProxyInstance(moduleLoader, new Class<?>[] {admissionType},
			(proxy, method, args) -> { if (method.getName().equals("admit")) {
				var values = java.util.Objects.requireNonNull(args); debits[0] += (long) values[1]; debits[1] += (long) values[0] + (long) values[1];
				assertTrue(debits[0] < 2L * 1024 * 1024, "module discovery allocation fits the existing identity root");
				assertTrue(debits[1] < 12L * 1024 * 1024, "module discovery work plus allocation initialization fits the existing identity root");
			} return null; });
		assertNull(service.getMethod("registeredKey", Fluid.class, admissionType).invoke(null, Fluids.WATER, admission));
		assertTrue(debits[0] >= 65536);
		assertEquals(1, Class.forName(module, SimpleProvider.class.getName()).getField("calls").getInt(null), "named provider constructor was actually invoked");
		System.out.println("REGISTRY_MODULE_DISCOVERY allocation=" + debits[0] + " workIncludingAllocation=" + debits[1]);
		// Forge52 uses this real module loader. Keep the same JPMS service declaration,
		// rather than a fake module/class-name assertion, when its existing API is present.
		try {
			var forgeLoader = Class.forName("net.minecraftforge.securemodules.SecureModuleClassLoader");
			var nativeLoader = (ClassLoader) forgeLoader.getConstructor(String.class, ClassLoader.class,
				java.lang.module.Configuration.class, java.util.List.class).newInstance("registry-fixture", getClass().getClassLoader(), configuration,
				java.util.List.of(ModuleLayer.boot()));
			var nativeLayer = ModuleLayer.defineModules(configuration, java.util.List.of(ModuleLayer.boot()), name -> nativeLoader).layer();
			var nativeModule = nativeLayer.findModule("registry.fixture").orElseThrow();
			var nativeService = Class.forName(nativeModule, FluidRegistries.class.getName()); assertNotNull(nativeService);
			var nativeAdmission = Class.forName(nativeModule, IPlatformFluidRegistryService.Admission.class.getName()); assertNotNull(nativeAdmission);
			var grant = java.lang.reflect.Proxy.newProxyInstance(nativeLoader, new Class<?>[] {nativeAdmission}, (proxy, method, args) -> null);
			assertNull(nativeService.getMethod("registeredKey", Fluid.class, nativeAdmission).invoke(null, Fluids.WATER, grant));
			assertEquals(1, Class.forName(nativeModule, SimpleProvider.class.getName()).getField("calls").getInt(null));
			System.out.println("REGISTRY_FORGE_MODULE_PROVIDER invoked=1 loader=" + nativeLoader.getClass().getName());
		} catch (ClassNotFoundException notForgeClasspath) { /* JPMS path above remains exercised on Common/Fabric/Neo */ }
	}
	@NotNullByDefault
	public static final class SimpleProvider implements IPlatformFluidRegistryService {
		public static int calls;
		public SimpleProvider() { calls++; }
		@Override public @Nullable net.minecraft.resources.ResourceLocation registeredKey(Fluid fluid, Admission admission) { return null; }
	}
	@Test void installedLoaderConstructorsDoNotTouchRegistriesOrClientClasses() throws Exception {
		for (String loader : new String[] {"fabric", "forge", "neoforge"}) {
			String name = "nx/pingwheel/" + loader + "/platform/PlatformFluidRegistryServiceImpl.class";
			try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
				if (input == null) continue;
				var node = new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(input).accept(node, 0);
				assertTrue(node.methods.stream().noneMatch(m -> m.name.equals("<clinit>")));
				for (var method : node.methods) for (var instruction : method.instructions) {
					if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
						assertFalse(call.owner.contains("/client/") || call.desc.contains("/client/"));
						if (method.name.equals("<init>")) { assertEquals("java/lang/Object", call.owner); assertEquals("<init>", call.name); }
					}
				}
			}
		}
	}
	@NotNullByDefault
	public static final class FailingProvider implements IPlatformFluidRegistryService {
		public static @Nullable Throwable failure;
		public static int calls;
		public static boolean reenter;
		public static int reentrantCalls;
		public FailingProvider() {
			calls++;
			if (reenter) {
				if (FluidRegistries.registeredKey(Fluids.WATER, (work, bytes) -> {}) != null)
					throw new AssertionError("reentry observed an uninstalled provider");
				reentrantCalls++;
			}
			if (failure instanceof RuntimeException runtime) throw runtime;
			throw (Error) java.util.Objects.requireNonNull(failure);
		}
		@Override public @Nullable net.minecraft.resources.ResourceLocation registeredKey(Fluid fluid, Admission admission) {
			throw new AssertionError("constructor must fail");
		}
	}
	@NotNullByDefault
	private static final class ServerOnly extends ClassLoader {
		private final @Nullable URL service;
		private int clientAttempts;
		private int resourceCount = 1;
		private int resourceNextCalls;
		private @Nullable VirtualMachineError admissionClassFailure;
		private int admissionClassFailures;
		private ServerOnly(@Nullable URL service) { super(FluidRegistryClassSafetyTest.class.getClassLoader()); this.service = service; }
		@Override public Enumeration<URL> getResources(String name) throws IOException {
			if (name.equals("META-INF/services/" + IPlatformFluidRegistryService.class.getName())) {
				if (service == null) return Collections.emptyEnumeration();
				return new Enumeration<>() {
					private int index;
					@Override public boolean hasMoreElements() { return index < resourceCount; }
					@Override public URL nextElement() { index++; resourceNextCalls++; return service; }
				};
			}
			return super.getResources(name);
		}
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.equals(FluidRegistries.class.getName() + "$DiscoveryAdmission") && admissionClassFailure != null) {
				var failure = admissionClassFailure; admissionClassFailure = null; admissionClassFailures++;
				throw failure; // one class-loading failure, not a permanently poisoned <clinit>
			}
			if (name.startsWith("net.minecraft.client.") || name.contains(".platform.client.") || name.contains(".render.")) {
				clientAttempts++; throw new AssertionError("server registry linked client class " + name);
			}
			if (!name.startsWith(FluidRegistries.class.getName()) && !name.equals(FailingProvider.class.getName())) return super.loadClass(name, resolve);
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
