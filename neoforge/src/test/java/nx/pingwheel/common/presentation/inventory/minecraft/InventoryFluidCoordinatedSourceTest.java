package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.integration.externalblock.ResolvedBlockReadSource;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.presentation.inventory.InventoryResourceValue;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.neoforge.platform.PlatformFluidServiceImpl;
import nx.pingwheel.neoforge.platform.FluidCapabilityAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real common source plus real NeoForge capability access through the production guarded wrapper.
 * The fixture is deterministic headless metadata, not a live ServerLevel or a Create install.
 */
@NotNullByDefault
class InventoryFluidCoordinatedSourceTest {
	private static final BlockPos ROOT = BlockPos.ZERO;
	private static final Direction FACE = Direction.NORTH;
	private static final CostLedger.Counter WORK = new CostLedger.Counter("fluid-coordinated-source", CostLedger.Unit.WORK);
	private static final InventoryValidationBudget.Cost MEMBER = new InventoryValidationBudget.Cost(1024, 1024, 528);
	private static final InventoryValidationBudget.Cost AUTHORITY = new InventoryValidationBudget.Cost(9216, 9216, 2560);

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void ordinaryNeoForgeSourceUsesOneGetterTwoCommonValidationsAndThePreGetterCurrentFence() {
		try (var fixture = new Fixture(false)) {
			var value = Objects.requireNonNull(fixture.read());
			assertEquals(81_000, value.amount());
			assertEquals(1, fixture.handler.contentReads, "one address is exactly one native getter");
			// Two wrapper/delegate full validations, never the removed consume-internal pair.
			assertEquals(3, fixture.level.capabilityCalls);
			// Every root target gate keeps its current binding; internal member callbacks do not.
			assertEquals(fixture.world.contains + 1, fixture.world.binds);
			assertTrue(fixture.world.contains > 4);
			assertEquals(1, fixture.world.used);
		}
	}

	@Test void finalCountCallbackCannotReplaceHistoricalRootObjectWithSameRegistry() {
		try (var fixture = new Fixture(false)) {
			fixture.reset();
			var original = Objects.requireNonNull(fixture.level.entity);
			fixture.handler.onCount = () -> { if (fixture.handler.tankReads == 2) fixture.replaceRootEntity(); };
			assertThrows(InventorySourceUnavailableException.class, fixture::read);
			assertEquals(0, fixture.handler.contentReads);
			assertEquals(0, fixture.world.used);
			java.lang.ref.Reference.reachabilityFence(original);
		}
	}
	@Test void externalBindingKeepsLegacyStandaloneChecksAndTheSameOutcome() {
		try (var fixture = new Fixture(true)) {
			var value = Objects.requireNonNull(fixture.read());
			assertEquals(81_000, value.amount());
			assertEquals(1, fixture.handler.contentReads);
			// Fresh access: probe+finish acquisitions, then two common validations and the two
			// consume-internal current checks. External/Sable bindings never opt into coordination.
			assertEquals(6, fixture.level.capabilityCalls);
			assertEquals(fixture.world.contains + 1, fixture.world.binds);
			assertTrue(fixture.world.contains > 4);
		}
	}

	@Test void getterMutationFailsTheFinalCommonValidationAndDiscardsTheProvisionalValue() {
		try (var fixture = new Fixture(false)) {
			fixture.handler.onContent = () -> fixture.world.allowed = false;
			assertThrows(InventorySourceUnavailableException.class, fixture::read);
			assertEquals(1, fixture.handler.contentReads, "the getter is attempted once before the final rejection");
			assertEquals(0, fixture.world.used, "no result is accepted after the post-getter failure");
		}
	}

	@Test void capabilityMutationDuringTheFirstValidationPreventsTheGetter() {
		try (var fixture = new Fixture(false)) {
			fixture.level.afterCapability = () -> fixture.handler.count = 2;
			assertThrows(InventorySourceUnavailableException.class, fixture::read);
			assertEquals(0, fixture.handler.contentReads);
			assertEquals(0, fixture.world.used);
		}
	}

	@Test void ordinaryCoordinatedAndDecoratedLegacyHaveThreeVersusFourProviderCurrentPasses() {
		try (var coordinated = new Fixture(false); var legacy = new Fixture(false, true)) {
			var current = Objects.requireNonNull(coordinated.read()); var previous = Objects.requireNonNull(legacy.read());
			assertEquals(previous.key(), current.key()); assertEquals(previous.amount(), current.amount());
			assertEquals(1, coordinated.handler.contentReads); assertEquals(1, legacy.handler.contentReads);
			assertEquals(3, coordinated.level.capabilityCalls); assertEquals(4, legacy.level.capabilityCalls);
			assertTrue(coordinated.world.binds < legacy.world.binds);
			System.out.printf("COORDINATED_COMMON_SOURCE coordinatedCurrent=%d legacyCurrent=%d coordinatedBinds=%d legacyBinds=%d getters=1%n",
				coordinated.level.capabilityCalls, legacy.level.capabilityCalls, coordinated.world.binds, legacy.world.binds);
		}
	}

	@Test void oneShortActualCallerWorkDefersBeforeGetterFreezeAndReleasesAllScratch() throws Exception {
		try (var fixture = new Fixture(false)) {
			var field = fixture.source.getClass().getDeclaredField("ordinaryAccess"); field.setAccessible(true);
			var provider = Objects.requireNonNull(IPlatformFluidService.coordinated((IPlatformFluidService.Access) field.get(fixture.source)));
			var resident = InventoryFluidReadOperation.profile().plus(provider.coordinatedResidentProfile());
			long bind = fixture.world.bindingCost().workUnits() + 128;
			long minimum = bind + resident.cost(MEMBER, AUTHORITY).workUnits() + 128;
			var ledger = new CostLedger(Map.of(WORK, minimum - 1));
			fixture.allowance.fluidGrants((scope, member, authority) ->
				new InventoryFluidAdmission(ledger, WORK, fixture.memory, member, authority, scope));
			assertThrows(InventoryFluidAdmission.Deferred.class, fixture::read);
			assertEquals(1, fixture.world.binds); assertEquals(0, fixture.level.capabilityCalls);
			assertEquals(0, fixture.handler.contentReads); assertEquals(0, fixture.world.registryReads);
			assertEquals(0, fixture.world.used); assertEquals(bind, ledger.used(WORK));
			assertEquals(0, fixture.memory.reserved());
		}
	}

	@Test void lastHeldAndCapacityMutationRemainInsideTheFullCurrentValidation() {
		for (boolean held : new boolean[] {false, true}) try (var fixture = new Fixture(false)) {
			if (held) fixture.level.afterChunkMap = () -> fixture.handler.count = 2;
			else fixture.handler.onCapacity = () -> fixture.handler.count = 2;
			assertThrows(InventorySourceUnavailableException.class, fixture::read);
			assertEquals(0, fixture.handler.contentReads); assertEquals(0, fixture.world.used);
		}
	}

	@Test void getterFailureAndPostGetterCapabilityReplacementNeverPublishAndReleaseScratch() {
		for (boolean fatal : new boolean[] {false, true}) try (var fixture = new Fixture(false)) {
			fixture.handler.onContent = () -> { if (fatal) throw new AssertionError("getter fatal"); throw new IllegalStateException("getter failed"); };
			if (fatal) assertThrows(AssertionError.class, fixture::read);
			else assertThrows(IllegalStateException.class, fixture::read);
			assertEquals(1, fixture.handler.contentReads); assertEquals(0, fixture.world.used); assertEquals(0, fixture.memory.reserved());
		}
		try (var fixture = new Fixture(false)) {
			fixture.handler.onContent = () -> fixture.level.handler = new Handler();
			assertThrows(InventorySourceUnavailableException.class, fixture::read);
			assertEquals(1, fixture.handler.contentReads); assertEquals(0, fixture.world.used);
		}
	}

	@Test void providerTargetCheckRejectsSiblingRemovalBeforeGetterAfterGuardedTailAuthority() {
		try (var fixture = new Fixture(false, false, true)) {
			fixture.handler.onCount = () -> {
				if (fixture.handler.tankReads == 2) fixture.world.tailBindings = 3;
			};
			assertThrows(InventorySourceUnavailableException.class, fixture::read);
			assertTrue(fixture.world.tailMutation, "the wrapper's post-delegate authority callback removed the sibling");
			assertEquals(0, fixture.handler.contentReads); assertEquals(0, fixture.world.used);
			assertEquals(0, fixture.memory.reserved());
		}
	}

	@Test void swallowedGetterReentryPoisonsOuterReadAndIndependentRetryCanSucceed() {
		try (var fixture = new Fixture(false)) {
			fixture.handler.onContent = () -> {
				fixture.handler.onContent = null;
				try { fixture.read(); } catch (InventorySourceUnavailableException ignored) { /* provider swallows recursive denial */ }
			};
			assertThrows(InventorySourceUnavailableException.class, fixture::read);
			assertEquals(1, fixture.handler.contentReads); assertEquals(0, fixture.world.used);
			assertEquals(0, fixture.memory.reserved());
			assertNotNull(fixture.read()); assertEquals(2, fixture.handler.contentReads); assertEquals(1, fixture.world.used);
		}
	}

	@Test void swallowedPreGetterCurrentCallbackReentryCannotReachEitherGetter() {
		try (var fixture = new Fixture(false)) {
			fixture.handler.onCapacity = () -> {};
			fixture.handler.onCount = () -> {
				if (fixture.handler.tankReads != 3) return;
				fixture.handler.onCount = null;
				try { fixture.read(); } catch (InventorySourceUnavailableException ignored) { /* swallowed provider callback */ }
			};
			assertThrows(InventorySourceUnavailableException.class, fixture::read);
			assertEquals(0, fixture.handler.contentReads); assertEquals(0, fixture.world.used); assertEquals(0, fixture.memory.reserved());
		}
	}

	@Test void swallowedCodecReentryPoisonsOuterReadAndIndependentRetryCanSucceed() throws Exception {
		try (var registration = new CodecComponent(); var fixture = new Fixture(false)) {
			fixture.handler.stack.set(registration.type, 1);
			registration.callback = () -> {
				registration.callback = () -> {};
				try { fixture.read(); } catch (InventorySourceUnavailableException ignored) { /* codec swallows recursive denial */ }
			};
			assertThrows(InventorySourceUnavailableException.class, fixture::read);
			assertEquals(1, fixture.handler.contentReads); assertEquals(0, fixture.world.used); assertEquals(0, fixture.memory.reserved());
			assertNotNull(fixture.read()); assertEquals(2, fixture.handler.contentReads); assertEquals(1, fixture.world.used);
		}
	}

	@Test void getterClosingTheSourcePoisonsTheOperationAndReleasesAllOwnership() {
		try (var fixture = new Fixture(false)) {
			fixture.handler.onContent = fixture.source::close;
			assertThrows(InventorySourceUnavailableException.class, fixture::read);
			assertEquals(1, fixture.handler.contentReads); assertEquals(0, fixture.world.used);
			assertEquals(0, fixture.memory.reserved()); assertEquals(0, fixture.memory.retained());
		}
	}

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		private final InventorySettings settings = InventorySettings.serverDefaults();
		private final TestLevel level = allocate(TestLevel.class);
		private final Handler handler = new Handler();
		private final WorldFixture world = new WorldFixture(level, handler);
		private final Target target;
		private final InventorySourceInput input;
		private final CostLedger ledger;
		private final RetainedMemoryLedger memory;
		private final InventoryValidationBudget.Allowance allowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
		private final InventorySourceAccess.Source source;
		private Fixture(boolean external) { this(external, false); }
		private Fixture(boolean external, boolean legacy) { this(external, legacy, false); }
		private Fixture(boolean external, boolean legacy, boolean multi) {
			level.server = allocate(TestServer.class); level.handler = handler; level.loaded = true;
			level.entity = new BeaconBlockEntity(ROOT, level.state()); level.entity.setLevel(level);
			if (multi) { level.sibling = new Sibling(); level.sibling.setLevel(level); }
			level.cache = allocate(TestCache.class); level.cache.level = level;
			target = external
				? Target.ExternalBlockTarget.committed("minecraft:overworld", "sable", "lease", "minecraft:beacon", "locator", true)
				: new Target.BlockTarget("minecraft:overworld", ROOT.getX(), ROOT.getY(), ROOT.getZ(), "minecraft:beacon");
			input = new InventorySourceInput(target, new UUID(1, 2), BlockFace.NORTH, InventoryResourceKind.FLUID);
			ledger = new CostLedger(Map.of(WORK, 1_000_000_000L));
			memory = new RetainedMemoryLedger(64L << 20);
			allowance.fluidGrants((scope, member, authority) ->
				new InventoryFluidAdmission(ledger, WORK, memory, member, authority, scope));
			source = InventoryFluidSources.resolve(world, multi ? new MultiService(level) : legacy ? new LegacyService() : new PlatformFluidServiceImpl(), input,
				(int) settings.effectiveDisplayBytes(), allowance).orElseThrow();
			reset();
		}
		private @Nullable InventoryResourceValue read() {
			return source.readResource(0, new InventoryCatalogAdmission(1_000_000,
				InventoryCatalogAdmission.workUpperBound((int) settings.effectiveDisplayBytes())), allowance);
		}
		private void reset() {
			level.capabilityCalls = 0; handler.contentReads = 0; handler.tankReads = 0; handler.capacityReads = 0;
			world.binds = 0; world.contains = 0; world.used = 0; world.registryReads = 0;
		}
		private void replaceRootEntity() {
			var replacement = new BeaconBlockEntity(ROOT, level.state()); replacement.setLevel(level); level.entity = replacement;
		}
		@Override public void close() {
			source.close();
			assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
		}
	}

	@NotNullByDefault
	private static final class WorldFixture implements InventoryFluidSources.World {
		private final TestLevel level;
		@SuppressWarnings("unused") private final Handler handler;
		private final RegistryAccess.Frozen registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		private int binds, contains, used, registryReads;
		private int tailBindings;
		private boolean tailMutation;
		private boolean allowed = true, loaded = true;
		private WorldFixture(TestLevel level, Handler handler) { this.level = level; this.handler = handler; }
		@Override public boolean onServerThread() { return true; }
		@Override public InventoryFluidSources.@Nullable Binding bind(InventorySourceInput input, InventoryValidationBudget.Allowance allowance) {
			binds++;
			if (tailBindings > 0 && --tailBindings == 0) { Objects.requireNonNull(level.sibling).setRemoved(); tailMutation = true; }
			var block = new Target.BlockTarget("minecraft:overworld", ROOT.getX(), ROOT.getY(), ROOT.getZ(), "minecraft:beacon");
			boolean external = input.target() instanceof Target.ExternalBlockTarget;
			var descriptor = new BlockReadSource(input.target(), external ? "sable" : "minecraft", external ? "scope" : "",
				block, new MarkerAnchor(ROOT.getX(), ROOT.getY(), ROOT.getZ()));
			return new InventoryFluidSources.Binding(level, new ResolvedBlockReadSource(level, descriptor, pos -> pos.equals(ROOT)), ROOT);
		}
		@Override public @Nullable BlockEntity existingEntity(ServerLevel level, BlockPos pos) {
			return pos.equals(ROOT) ? this.level.entity : pos.equals(ROOT.east()) ? this.level.sibling : null;
		}
		@Override public BlockState blockState(ServerLevel level, BlockPos pos) { return this.level.state(); }
		@Override public boolean loaded(ServerLevel level, BlockPos pos) { return loaded; }
		@Override public boolean contains(InventoryFluidSources.Binding binding, BlockPos pos, InventoryValidationBudget.Allowance allowance) {
			contains++; return allowed && (pos.equals(ROOT) || level.sibling != null && pos.equals(ROOT.east()));
		}
		@Override public @Nullable ItemStack ownerKey(InventorySourceInput input) { return allowed ? ItemStack.EMPTY : null; }
		@Override public net.minecraft.core.HolderLookup.Provider registries(ServerLevel level) { registryReads++; return registries; }
		@Override public void used(nx.pingwheel.common.platform.IPlatformFluidService.RouteInfo route) { used++; }
	}

	@NotNullByDefault
	private static final class Handler implements IFluidHandler {
		private int count = 1, tankReads, capacityReads, contentReads;
		private int[] capacities = {8_000};
		private @Nullable Runnable onContent;
		private @Nullable Runnable onCapacity;
		private @Nullable Runnable onCount;
		private final FluidStack stack = new FluidStack(Fluids.WATER, 1_000);
		@Override public int getTanks() { tankReads++; var callback = onCount; if (callback != null) callback.run(); return count; }
		@Override public FluidStack getFluidInTank(int tank) {
			assertEquals(0, tank); contentReads++;
			@Nullable var callback = onContent; if (callback != null) callback.run();
			return stack;
		}
		@Override public int getTankCapacity(int tank) {
			assertEquals(0, tank); capacityReads++; var callback = onCapacity; if (callback != null) { onCapacity = null; callback.run(); } return capacities[0];
		}
		@Override public boolean isFluidValid(int tank, FluidStack stack) { throw new AssertionError("no simulation"); }
		@Override public int fill(FluidStack stack, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(FluidStack stack, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(int amount, FluidAction action) { throw new AssertionError("read only"); }
	}

	@NotNullByDefault
	private static final class TestLevel extends ServerLevel {
		private @Nullable TestServer server;
		private @Nullable TestCache cache;
		private @Nullable BlockEntity entity;
		private @Nullable BlockEntity sibling;
		private Handler handler = new Handler();
		private boolean loaded = true;
		private int capabilityCalls;
		private @Nullable Runnable afterCapability;
		private @Nullable Runnable afterChunkMap;
		private TestLevel() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
		private BlockState state() { return Blocks.BEACON.defaultBlockState(); }
		@Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
		@Override public int getMinBuildHeight() { return -64; }
		@Override public void invalidateCapabilities(BlockPos pos) { /* headless listener holder is absent */ }
		@Override public MinecraftServer getServer() { return Objects.requireNonNull(server); }
		@Override public ServerChunkCache getChunkSource() { return Objects.requireNonNull(cache); }
		@Override public boolean isLoaded(BlockPos position) { return loaded; }
		@Override public BlockState getBlockState(BlockPos position) { return state(); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos position) { throw new AssertionError("no NBT promotion"); }
		@Override public <T, C> @Nullable T getCapability(BlockCapability<T, C> capability, BlockPos position, C face) {
			assertSame(Capabilities.FluidHandler.BLOCK, capability);
			assertEquals(ROOT, position); assertSame(FACE, face); assertTrue(loaded);
			capabilityCalls++;
			@Nullable var callback = afterCapability; if (callback != null) { afterCapability = null; callback.run(); }
			return capability.typeClass().cast(handler);
		}
	}

	@NotNullByDefault
	private static final class TestCache extends ServerChunkCache {
		private @Nullable TestLevel level;
		private TestCache() {
			super(null, null, null, null, null, null, 0, 0, false, null, null, null);
		}
		@Override public @Nullable LevelChunk getChunkNow(int x, int z) {
			var chunk = allocate(TestChunk.class); chunk.level = Objects.requireNonNull(level); return chunk;
		}
		@Override public @Nullable net.minecraft.world.level.chunk.ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
			throw new AssertionError("no loading chunk lookup");
		}
	}

	@NotNullByDefault
	private static final class TestChunk extends LevelChunk {
		private @Nullable TestLevel level;
		private TestChunk() { super(null, null); }
		@Override public BlockState getBlockState(BlockPos position) { return Objects.requireNonNull(level).state(); }
		@Override public @Unmodifiable Map<BlockPos, BlockEntity> getBlockEntities() {
			var current = Objects.requireNonNull(level);
			var callback = current.afterChunkMap; if (callback != null) { current.afterChunkMap = null; callback.run(); }
			@Nullable var entity = current.entity;
			return entity == null ? Map.of() : current.sibling == null ? Map.of(ROOT, entity) : Map.of(ROOT, entity, ROOT.east(), current.sibling);
		}
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos position) { throw new AssertionError("no NBT promotion"); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos position, EntityCreationType mode) {
			throw new AssertionError("even CHECK promotes NBT");
		}
	}

	@NotNullByDefault
	private static final class TestServer extends DedicatedServer {
		private TestServer() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
	}
	@NotNullByDefault
	private static final class Sibling extends BlockEntity {
		private Sibling() { super(net.minecraft.world.level.block.entity.BlockEntityType.BEACON, ROOT.east(), Blocks.BEACON.defaultBlockState()); }
	}
	/** Actual provider with two-member route; native world construction stays headless. */
	@NotNullByDefault
	private record MultiService(TestLevel level) implements IPlatformFluidService {
		@Override public OperationProfile probeProfile() { return FluidCapabilityAccess.PROBE_PROFILE; }
		@Override public Optional<Probe> probe(Context context, Operation operation) {
			operation.allowance().debit(probeProfile().ownedCost());
			var world = new FluidCapabilityAccess.World() {
				@Override public void require(BlockPos pos, Operation op) { context.require(pos, op); }
				@Override public FluidCapabilityAccess.Block block(BlockPos pos, Operation op) {
					require(pos, op); return new FluidCapabilityAccess.Block(Blocks.BEACON, pos.equals(ROOT) ? level.entity : level.sibling, "minecraft:beacon", "minecraft:beacon");
				}
				@Override public @Nullable IFluidHandler capability(BlockPos pos, Direction face, Operation op) { require(pos, op); level.capabilityCalls++; return level.handler; }
				@Override public String dimension(Operation op) { return "minecraft:overworld"; }
				@Override public boolean createAvailable() { return false; }
				@Override public boolean held(FluidCapabilityAccess.Member member, Operation op) {
					require(member.position(), op); var entity = member.position().equals(ROOT) ? level.entity : level.sibling;
					return entity != null && !entity.isRemoved() && entity == member.block().entity();
				}
			};
			var members = List.of(new FluidCapabilityAccess.Member(ROOT, world.block(ROOT, operation)),
				new FluidCapabilityAccess.Member(ROOT.east(), world.block(ROOT.east(), operation)));
			var route = new FluidCapabilityAccess.Route() {
				@Override public FluidCapabilityAccess.World world() { return world; }
				@Override public Direction face() { return FACE; }
				@Override public BlockPos capabilityPosition() { return ROOT; }
				@Override public @Unmodifiable List<FluidCapabilityAccess.Member> members() { return members; }
				@Override public Optional<String> alias() { return Optional.empty(); }
				@Override public String description() { return "headless-two-member"; }
				@Override public boolean matches(Operation op) {
					for (var member : members) {
						if (!member.block().same(world.block(member.position(), op))) return false;
						if (member.block().entity() instanceof BlockEntity entity && entity.isRemoved()) return false;
					}
					return true;
				}
				@Override public OperationProfile checkProfile() { return world.blockProfile().times(2); }
				@Override public TopologyEvidence topology() { return TopologyEvidence.EXPLICIT_STRUCTURE; }
			};
			return FluidCapabilityAccess.probe(route, operation);
		}
	}
	@NotNullByDefault
	private static final class CodecComponent implements AutoCloseable {
		private final Object registry = BuiltInRegistries.DATA_COMPONENT_TYPE;
		private final Map<String, Object> previous = new java.util.HashMap<>();
		private final Map<String, Map<Object, Object>> maps = new java.util.HashMap<>();
		private final List<Object> byId;
		private final int size;
		private Runnable callback = () -> {};
		private final DataComponentType<Integer> type;
		@SuppressWarnings("unchecked") private CodecComponent() throws ReflectiveOperationException {
			for (String name : List.of("byLocation", "byKey", "byValue", "registrationInfos", "toId")) maps.put(name, new java.util.HashMap<>((Map<Object, Object>) get(name)));
			previous.put("frozen", get("frozen")); previous.put("registryLifecycle", get("registryLifecycle"));
			byId = (List<Object>) get("byId"); size = byId.size();
			type = DataComponentType.<Integer>builder().persistent(com.mojang.serialization.Codec.INT.xmap(value -> value, value -> { callback.run(); return value; })).build();
			set("frozen", false);
			try { net.minecraft.core.Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, "pingforit_test:fluid_coordinated_reentry", type); }
			finally { set("frozen", Objects.requireNonNull(previous.get("frozen"))); }
		}
		private Object get(String name) throws ReflectiveOperationException {
			var field = net.minecraft.core.MappedRegistry.class.getDeclaredField(name); field.setAccessible(true); return Objects.requireNonNull(field.get(registry));
		}
		private void set(String name, Object value) throws ReflectiveOperationException {
			var field = net.minecraft.core.MappedRegistry.class.getDeclaredField(name); field.setAccessible(true); field.set(registry, value);
		}
		@SuppressWarnings("unchecked") @Override public void close() throws ReflectiveOperationException {
			for (var entry : maps.entrySet()) { var map = (Map<Object, Object>) get(entry.getKey()); map.clear(); map.putAll(entry.getValue()); }
			while (byId.size() > size) byId.removeLast();
			for (var entry : previous.entrySet()) set(entry.getKey(), entry.getValue());
		}
	}
	@NotNullByDefault
	private static final class LegacyService implements IPlatformFluidService {
		private final PlatformFluidServiceImpl delegate = new PlatformFluidServiceImpl();
		@Override public OperationProfile probeProfile() { return delegate.probeProfile(); }
		@Override public Optional<Probe> probe(Context context, Operation operation) {
			return delegate.probe(context, operation).map(probe -> new Probe() {
				@Override public Shape shape() { return probe.shape(); }
				@Override public OperationProfile finishProfile() { return probe.finishProfile(); }
				@Override public Optional<Access> finish(Admission admission) { return probe.finish(admission).map(LegacyAccess::new); }
				@Override public void close() { probe.close(); }
			});
		}
	}
	@NotNullByDefault
	private record LegacyAccess(IPlatformFluidService.Access delegate) implements IPlatformFluidService.Access {
		@Override public InventorySnapshotLayout layout() { return delegate.layout(); }
		@Override public Optional<String> alias() { return delegate.alias(); }
		@Override public boolean stableCursor() { return delegate.stableCursor(); }
		@Override public int slots() { return delegate.slots(); }
		@Override public IPlatformFluidService.RouteInfo routeInfo() { return delegate.routeInfo(); }
		@Override public IPlatformFluidService.OperationProfile validationProfile() { return delegate.validationProfile(); }
		@Override public IPlatformFluidService.OperationProfile readProfile() { return delegate.readProfile(); }
		@Override public boolean valid(IPlatformFluidService.Operation operation) { return delegate.valid(operation); }
		@Override public boolean consume(int slot, IPlatformFluidService.Visitor visitor, IPlatformFluidService.Operation operation) { return delegate.consume(slot, visitor, operation); }
		@Override public IPlatformFluidService.OperationProfile witnessProfile(IPlatformFluidService.WitnessUse use) { return delegate.witnessProfile(use); }
		@Override public nx.pingwheel.common.platform.FluidAddressWitness captureWitness(IPlatformFluidService.Operation operation) { return delegate.captureWitness(operation); }
		@Override public IPlatformFluidService.WitnessMatch compareWitness(nx.pingwheel.common.platform.FluidAddressWitness expected, IPlatformFluidService.Operation operation) { return delegate.compareWitness(expected, operation); }
		@Override public void close() { delegate.close(); }
	}
	private static <T> T allocate(Class<T> type) {
		try {
			var unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
