package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.integration.externalblock.ResolvedBlockReadSource;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.platform.FluidAddressWitness;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryPublicationEvidence;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CaptureResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Executed on each loader's test classpath with its real adapter and native recording seam.
 * Reflection keeps loader APIs out of common; it never substitutes witness/validation answers.
 * The common-only task skips these cases; loader runs select pingforit.test.fluidLoader explicitly.
 * Forge requires its actual patched artifact first, just like the native registry tests.
 */
@NotNullByDefault
class InventoryFluidLoaderIntegrationTest {
	private static boolean previousBootstrap;
	private static boolean forgeBootstrapOwned;
	@BeforeAll static void bootstrap() throws ReflectiveOperationException {
		net.minecraft.SharedConstants.tryDetectVersion();
		if (!"forge".equals(System.getProperty("pingforit.test.fluidLoader"))) {
			net.minecraft.server.Bootstrap.bootStrap(); return;
		}
		assertEquals("net.minecraftforge.fluids.FluidType", net.minecraft.world.level.material.Fluid.class
			.getMethod("getFluidType").getReturnType().getName(), "Forge source integration requires patched-first Minecraft");
		// Let actual GameData install its registries without unrelated FML item events.
		var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped"); field.setAccessible(true);
		if (!forgeBootstrapOwned) { previousBootstrap = field.getBoolean(null); forgeBootstrapOwned = true; }
		field.setBoolean(null, true);
		var nativeLoader = net.minecraft.world.level.material.Fluid.class.getClassLoader();
		assertNotNull(Class.forName("net.minecraftforge.registries.ForgeRegistries", true, nativeLoader).getField("FLUIDS").get(null));
		assertSame(Class.forName("net.minecraftforge.registries.NamespacedDefaultedWrapper", false, nativeLoader),
			net.minecraft.core.registries.BuiltInRegistries.FLUID.getClass());
		assertEquals(net.minecraft.resources.ResourceLocation.withDefaultNamespace("water"),
			net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(net.minecraft.world.level.material.Fluids.WATER));
	}
	@AfterAll static void restoreBootstrap() throws ReflectiveOperationException {
		if (!forgeBootstrapOwned) return;
		var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped"); field.setAccessible(true);
		field.setBoolean(null, previousBootstrap); forgeBootstrapOwned = false;
	}

	@Test void originalCommonCutRejectsReacquiredNativeRouteAndFabricAddressOrderWithIdenticalDetachedLayout() {
		String loader = loader();
		for (int change = 0; change < (loader.equals("fabric") ? 2 : 1); change++) try (var f = new Fixture(loader)) {
			var consumer = f.attach(); var prefix = f.runtime.step(consumer, 1).orElseThrow();
			assertEquals(CaptureResult.Availability.READABLE, prefix.result().availability());
			assertEquals(CaptureResult.Completeness.CONTINUE, prefix.result().completeness());
			assertEquals(Optional.of(true), f.runtime.probe(consumer), "closed Access must not erase original proof");
			try (var old = f.runtime.publicationEvidence(consumer, InventoryRuntime.EvidenceScope.SCANNING).orElseThrow()) {
				assertEquals(InventorySharedRead.Check.VALID, f.check(old));
				Object original = f.route(); Object layout = field(Objects.requireNonNull(f.source), "layout");
				if (change == 0) f.replaceRoute(); else Collections.swap((List<?>) field(f.nativeFixture, "views"), 0, 1);
				f.runtime.advance(1, f.settings);
				assertEquals(InventorySharedRead.Check.UNAVAILABLE, f.check(old), "a fresh Access cannot recapture proof for this old cut");
				assertEquals(Optional.of(false), f.runtime.probe(consumer)); assertFalse(consumer.scopeTransition());
				consumer.restart(); f.runtime.advance(2, f.settings);
				var fresh = f.runtime.step(consumer).orElseThrow();
				assertEquals(CaptureResult.Availability.READABLE, fresh.result().availability());
				assertEquals(CaptureResult.Completeness.COMPLETE, fresh.result().completeness());
				assertEquals(2, fresh.slots().size());
				assertEquals(layout, field(Objects.requireNonNull(f.source), "layout"), "identity change is intentionally invisible to the DTO");
				assertEquals(InventorySharedRead.Check.UNAVAILABLE, f.check(old), "new Source ownership cannot authorize old history");
				assertTrue(f.probes > 3, "the test actually crossed operation-local native acquisitions");
				java.lang.ref.Reference.reachabilityFence(original);
			}
		}
	}

	@Test void deterministicallyClearedOriginalNativeProofRetiresAndFreshRuntimeScanRecovers() {
		try (var f = new Fixture(loader())) {
			var consumer = f.attach(); var prefix = f.runtime.step(consumer, 1).orElseThrow();
			assertEquals(CaptureResult.Availability.READABLE, prefix.result().availability());
			assertEquals(CaptureResult.Completeness.CONTINUE, prefix.result().completeness());
			var witness = (FluidAddressWitness) field(Objects.requireNonNull(f.source), "addressWitness");
			try { declared(FluidAddressWitness.class, "clearForTesting", int.class).invoke(witness, 0); }
			catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
			f.runtime.advance(1, f.settings); assertEquals(Optional.of(false), f.runtime.probe(consumer));
			assertFalse(consumer.scopeTransition()); consumer.restart(); f.runtime.advance(2, f.settings);
			var fresh = f.runtime.step(consumer).orElseThrow();
			assertEquals(CaptureResult.Availability.READABLE, fresh.result().availability());
			assertEquals(CaptureResult.Completeness.COMPLETE, fresh.result().completeness());
			assertEquals(2, fresh.slots().size());
		}
	}

	private static String loader() {
		String loader = System.getProperty("pingforit.test.fluidLoader", "");
		assumeTrue(List.of("fabric", "forge", "neoforge").contains(loader), "requires a loader's native API/test fixture classpath");
		return loader;
	}

	@NotNullByDefault
	private static final class Fixture implements InventoryFluidSources.World, AutoCloseable {
		final String loader;
		final InventorySettings settings = InventorySettings.serverDefaults();
		final Object nativeFixture;
		final ServerLevel level;
		final BlockEntity entity;
		final BlockState state;
		final IPlatformFluidService service;
		final InventoryRuntime runtime;
		final Target target;
		final InventorySourceInput input;
		@Nullable Object neoHandler;
		InventorySourceAccess.@Nullable Source source;
		int probes;
		Fixture(String loader) {
			this.loader = loader;
			Class<?> tests = type("nx.pingwheel." + loader + ".platform.PlatformFluidServiceTest");
			if (loader.equals("neoforge")) {
				nativeFixture = construct(type(tests.getName() + "$Handler")); neoHandler = nativeFixture;
				var world = allocate(Level.class); world.server = allocate(Server.class); level = world;
				state = Blocks.BEEHIVE.defaultBlockState(); entity = new BeehiveBlockEntity(BlockPos.ZERO, state);
				service = neoService();
			} else {
				nativeFixture = loader.equals("fabric") ? construct(type(tests.getName() + "$Fixture"), true, Direction.WEST)
					: construct(type(tests.getName() + "$Fixture"), Direction.WEST, true);
				level = (ServerLevel) field(nativeFixture, "level"); entity = (BlockEntity) field(nativeFixture, "entity");
				state = (BlockState) field(nativeFixture, "state"); service = (IPlatformFluidService) field(nativeFixture, "service");
			}
			entity.setLevel(level);
			String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			target = Target.ExternalBlockTarget.committed("minecraft:overworld", "sable", "lease", blockId, "locator", true);
			input = new InventorySourceInput(target, new UUID(1, 2), BlockFace.WEST, InventoryResourceKind.FLUID);
			IPlatformFluidService counted = new IPlatformFluidService() {
				@Override public OperationProfile probeProfile() { return service.probeProfile(); }
				@Override public Optional<Probe> probe(Context context, Operation operation) { probes++; return service.probe(context, operation); }
			};
			runtime = new InventoryRuntime(new InventoryRuntime.SourceResolver() {
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput value, int cap) { throw new AssertionError("unadmitted source"); }
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput value, int cap, InventoryValidationBudget.Allowance allowance) {
					var found = InventoryFluidSources.resolve(Fixture.this, counted, value, cap, allowance); source = found.orElse(null); return found;
				}
			}, settings.pendingMemoryBytes());
		}
		InventoryRuntime.Consumer attach() {
			runtime.advance(0, settings); return runtime.attach(input, new InventoryRuntime.TrackingSubject(TargetKey.from(target))).orElseThrow();
		}
		Object route() { return loader.equals("neoforge") ? Objects.requireNonNull(neoHandler) : field(nativeFixture, loader.equals("fabric") ? "route" : "capability"); }
		void replaceRoute() {
			if (loader.equals("fabric")) set(nativeFixture, "route", invoke(declared(nativeFixture.getClass(), "storage", boolean.class), nativeFixture, true));
			else if (loader.equals("neoforge")) neoHandler = construct(nativeFixture.getClass());
			else {
				Object handler = invoke(declared(nativeFixture.getClass(), "newHandler"), nativeFixture); set(nativeFixture, "handler", handler);
				Class<?> supplier = type("net.minecraftforge.common.util.NonNullSupplier");
				Object factory = Proxy.newProxyInstance(supplier.getClassLoader(), new Class<?>[] {supplier}, (proxy, method, args) -> handler);
				set(nativeFixture, "capability", invoke(declared(type("net.minecraftforge.common.util.LazyOptional"), "of", supplier), null, factory));
			}
		}
		InventorySharedRead.Check check(InventoryPublicationEvidence evidence) {
			try (var grant = runtime.reservePublication(evidence).orElseThrow()) { return grant.validate(evidence); }
		}
		private IPlatformFluidService neoService() {
			Class<?> worldType = type("nx.pingwheel.neoforge.platform.FluidCapabilityAccess$World");
			Class<?> blockType = type("nx.pingwheel.neoforge.platform.FluidCapabilityAccess$Block");
			Object block = construct(blockType, state.getBlock(), entity, "minecraft:beehive", "minecraft:beehive");
			var nativeService = (IPlatformFluidService) construct(type("nx.pingwheel.neoforge.platform.PlatformFluidServiceImpl"));
			Method probe = declared(nativeService.getClass(), "probe", worldType, BlockPos.class, Direction.class, IPlatformFluidService.Operation.class);
			return new IPlatformFluidService() {
				@Override public OperationProfile probeProfile() { return nativeService.probeProfile(); }
				@Override @SuppressWarnings("unchecked") public Optional<Probe> probe(Context context, Operation operation) {
					Object world = Proxy.newProxyInstance(worldType.getClassLoader(), new Class<?>[] {worldType}, (proxy, method, args) -> {
						if (method.isDefault() && method.getName().endsWith("Profile")) return InvocationHandler.invokeDefault(proxy, method, args);
						if (method.getName().equals("createAvailable")) return false;
						if (method.getName().equals("dimension")) return "minecraft:overworld";
						Object[] arguments = Objects.requireNonNull(args);
						var current = (Operation) arguments[arguments.length - 1]; context.require(BlockPos.ZERO, current);
						return switch (method.getName()) {
							case "require" -> { context.require(BlockPos.ZERO, current); yield null; }
							case "block" -> block;
							case "capability" -> neoHandler;
							case "held" -> true;
							default -> throw new AssertionError(method.getName());
						};
					});
					return (Optional<Probe>) invoke(probe, null, world, BlockPos.ZERO, Direction.WEST, operation);
				}
			};
		}
		@Override public boolean onServerThread() { return true; }
		@Override public InventoryFluidSources.Binding bind(InventorySourceInput value, InventoryValidationBudget.Allowance allowance) {
			var block = new Target.BlockTarget("minecraft:overworld", 0, 0, 0, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
			var descriptor = new BlockReadSource(target, "sable", "one", block, new MarkerAnchor(0, 0, 0));
			return new InventoryFluidSources.Binding(level, new ResolvedBlockReadSource(level, descriptor, pos -> pos.equals(BlockPos.ZERO)), BlockPos.ZERO);
		}
		@Override public @Nullable BlockEntity existingEntity(ServerLevel world, BlockPos pos) { return pos.equals(BlockPos.ZERO) ? entity : null; }
		@Override public BlockState blockState(ServerLevel world, BlockPos pos) { return state; }
		@Override public boolean loaded(ServerLevel world, BlockPos pos) { return true; }
		@Override public ItemStack ownerKey(InventorySourceInput value) { return ItemStack.EMPTY; }
		@Override public net.minecraft.core.HolderLookup.Provider registries(ServerLevel world) { return RegistryAccess.EMPTY; }
		@Override public void close() { runtime.close(); assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained()); }
	}

	@NotNullByDefault private static final class Level extends ServerLevel {
		private @Nullable Server server;
		private Level() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		@Override public net.minecraft.server.MinecraftServer getServer() { return Objects.requireNonNull(server); }
	}
	@NotNullByDefault private static final class Server extends DedicatedServer {
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
	}
	private static Class<?> type(String name) {
		try { return Class.forName(name); } catch (ClassNotFoundException failure) { throw new AssertionError(failure); }
	}
	private static Method declared(Class<?> owner, String name, Class<?>... parameters) {
		try { var method = owner.getDeclaredMethod(name, parameters); method.setAccessible(true); return method; }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static Object invoke(Method method, @Nullable Object receiver, Object... arguments) {
		try { return Objects.requireNonNull(method.invoke(receiver, arguments)); }
		catch (InvocationTargetException failure) { if (failure.getCause() instanceof RuntimeException runtime) throw runtime; throw new AssertionError(failure.getCause()); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static Object field(Object value, String name) {
		try { var field = value.getClass().getDeclaredField(name); field.setAccessible(true); return Objects.requireNonNull(field.get(value)); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static void set(Object value, String name, Object current) {
		try { var field = value.getClass().getDeclaredField(name); field.setAccessible(true); field.set(value, current); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static Object construct(Class<?> type, Object... arguments) {
		for (Constructor<?> constructor : type.getDeclaredConstructors()) if (constructor.getParameterCount() == arguments.length) {
			try { constructor.setAccessible(true); return constructor.newInstance(arguments); }
			catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
		throw new AssertionError("missing fixture constructor " + type.getName());
	}
	private static <T> T allocate(Class<T> type) {
		try { Class<?> unsafe = type("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type))); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
