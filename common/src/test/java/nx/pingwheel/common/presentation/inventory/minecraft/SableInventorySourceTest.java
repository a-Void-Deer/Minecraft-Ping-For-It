package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.LockCode;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.minecraft.MinecraftBlockReadSources;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real external source, view, NBT, evidence and runtime algorithms with synchronous provider/world ports. */
class SableInventorySourceTest {
	private static final UUID OWNER = new UUID(7, 7);
	private static final String DIMENSION = "minecraft:overworld";
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static InventorySourceInput candidate(String dimension, String registry, String locator) {
		return new InventorySourceInput(Target.ExternalBlockTarget.candidate(dimension, "sable", registry, locator, true), OWNER, BlockFace.NORTH);
	}
	private static InventorySourceInput committed(String registry, String stableId, String staleLocator) {
		return new InventorySourceInput(Target.ExternalBlockTarget.committed(DIMENSION, "sable", stableId, registry, staleLocator, true), OWNER, BlockFace.NORTH);
	}
	private static InventoryDomainCodec.Item item(IPlatformInventoryService.Entry entry) {
		if (entry.isEmpty()) return null;
		String id = BuiltInRegistries.ITEM.getKey(entry.exemplar().getItem()).toString();
		return new InventoryDomainCodec.Item(new InventoryScanner.Key(id, "plain"), entry.amount(), id, null, false);
	}
	private static CostLedger grant(int slots) {
		return new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) slots, InventorySourceAccess.PROBES, 8L,
			InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.MAX_PROVIDER_WORK_PER_TICK));
	}
	private static InventorySourceAccess.InventoryHandle open(Fixture fixture, InventorySourceInput input) {
		var access = new InventorySourceAccess(input, fixture::resolve);
		var scope = new SourceAccess.ReadScope(input.viewKey(), Set.of("pingforit:inventory.items"));
		var resolved = (SourceAccess.ResolveResult.Available) access.resolve(MinecraftBlockReadSources.detached(input.target()), scope, grant(0));
		return (InventorySourceAccess.InventoryHandle) ((SourceAccess.OpenResult.Started) access.open(resolved.descriptor(), scope, grant(0))).handle();
	}
	private static CaptureResult step(SourceAccess.Handle handle, int slots) {
		return ((SourceAccess.StepOutcome.Captured) handle.step(grant(slots))).result();
	}

	private static class Chest extends ChestBlockEntity implements BaseContainerLockAccessor {
		LockCode lock = LockCode.NO_LOCK; int saves, reads; Runnable afterSave;
		Chest(BlockPos pos, BlockState state, int count) { super(pos, state); setItem(0, new ItemStack(Items.STONE, count)); }
		@Override public LockCode pingforit$inventoryLock() { return lock; }
		@Override public ItemStack getItem(int slot) { reads++; return super.getItem(slot); }
		@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			saves++; super.saveAdditional(tag, registries); if (afterSave != null) afterSave.run();
		}
	}
	private static final class SidedChest extends Chest implements WorldlyContainer {
		int[] mapping = {0}; Direction lastFace;
		SidedChest(BlockPos pos, int count) { super(pos, Blocks.CHEST.defaultBlockState(), count); }
		@Override public int[] getSlotsForFace(Direction face) { lastFace = face; return mapping.clone(); }
		@Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction face) { return false; }
		@Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction face) { return false; }
	}
	private static final class World {
		final Map<BlockPos, BlockState> states = new HashMap<>();
		final Map<BlockPos, BlockEntity> entities = new HashMap<>();
		final Set<BlockPos> loaded = new HashSet<>();
		final List<BlockPos> stateReads = new java.util.ArrayList<>(), entityReads = new java.util.ArrayList<>();
		ItemStack key = ItemStack.EMPTY;
		void put(BlockPos pos, BlockState state, BlockEntity entity) { states.put(pos, state); entities.put(pos, entity); loaded.add(pos); }
		Chest chest(BlockPos pos, int count) {
			var state = Blocks.CHEST.defaultBlockState(); var chest = new Chest(pos, state, count); put(pos, state, chest); return chest;
		}
		void capability(BlockPos pos) { var state = Blocks.BEEHIVE.defaultBlockState(); put(pos, state, new BeehiveBlockEntity(pos, state)); }
	}
	private static final class Route {
		final World world; BlockPos pos; String scope;
		final Set<BlockPos> allowed = new HashSet<>(); boolean active = true;
		long count = 5; String alias = "same-canonical-controller";
		InventorySnapshotLayout layout;
		Runnable afterLookup = () -> {}, afterSlots = () -> {}, afterStableCursor = () -> {}, afterRead = () -> {};
		Route(World world, BlockPos pos, String scope) { this.world = world; this.pos = pos; this.scope = scope; allowed.addAll(world.loaded); }
	}
	private static final class Fixture {
		final Map<String, Route> candidates = new HashMap<>(), points = new HashMap<>();
		final AtomicInteger resolutions = new AtomicInteger(), capabilityReads = new AtomicInteger(), enumerations = new AtomicInteger();
		int tick; boolean onThread = true;
		void current(int epoch) { assertEquals(tick, epoch, "a previous tick's world, predicate or handler was retained"); }
		InventoryMinecraftSources.SourceWorld worldPort(World world, int epoch) {
			return new InventoryMinecraftSources.SourceWorld() {
				@Override public boolean isLoaded(BlockPos pos) { current(epoch); return world.loaded.contains(pos); }
				@Override public BlockState blockState(BlockPos pos) { current(epoch); world.stateReads.add(pos); return world.states.get(pos); }
				@Override public BlockEntity blockEntity(BlockPos pos) { current(epoch); world.entityReads.add(pos); return world.entities.get(pos); }
				@Override public boolean onServerThread() { current(epoch); return onThread; }
				@Override public ItemStack heldKey() { current(epoch); return world.key; }
				@Override public HolderLookup.Provider registries() { current(epoch); return RegistryAccess.EMPTY; }
			};
		}
		Optional<InventoryMinecraftSources.ExternalBinding> binding(InventorySourceInput input) {
			resolutions.incrementAndGet(); var external = (Target.ExternalBlockTarget) input.target();
			Route route = external.isCandidate() ? candidates.get(external.providerLocator()) : points.get(external.stableTargetId());
			if (route == null || !route.active) return Optional.empty();
			int epoch = tick;
			var block = new Target.BlockTarget(external.dimensionId(), route.pos.getX(), route.pos.getY(), route.pos.getZ(), external.expectedBlockRegistryId());
			var descriptor = new BlockReadSource(external, external.providerId(), route.scope, block, new MarkerAnchor(900, 901, 902));
			return Optional.of(new InventoryMinecraftSources.ExternalBinding(worldPort(route.world, epoch), descriptor,
				pos -> { current(epoch); return route.active && route.allowed.contains(pos); }, (pos, face) -> {
					current(epoch); assertTrue(route.allowed.contains(pos)); assertEquals(Direction.valueOf(input.face().name()), face);
					route.afterLookup.run();
					return Optional.of(new IPlatformInventoryService.Access() {
						@Override public Optional<String> alias() { current(epoch); return Optional.of(route.alias); }
						@Override public Optional<InventorySnapshotLayout> snapshotLayout() { current(epoch); return Optional.ofNullable(route.layout); }
						@Override public boolean valid() { current(epoch); return route.active; }
						@Override public int slots() { current(epoch); route.afterSlots.run(); return 2; }
						@Override public boolean stableCursor() { current(epoch); route.afterStableCursor.run(); return true; }
						@Override public OptionalLong version() { return OptionalLong.empty(); }
						@Override public IPlatformInventoryService.Entry read(int slot) {
							current(epoch); capabilityReads.incrementAndGet(); route.afterRead.run(); return new IPlatformInventoryService.Entry(new ItemStack(Items.STONE), route.count);
						}
						@Override public IPlatformInventoryService.Budgeted observe(int limit) { enumerations.incrementAndGet(); return IPlatformInventoryService.Access.super.observe(limit); }
						@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> consumer) { throw new AssertionError("indexed source"); }
					});
				}));
		}
		Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input) {
			return InventoryMinecraftSources.resolveExternal(input, () -> binding(input), () -> onThread, SableInventorySourceTest::item);
		}
		Route bind(String locator, World world, BlockPos pos, String scope) {
			Route route = new Route(world, pos, scope); candidates.put(locator, route); return route;
		}
	}

	@Test void canonicalAliasesShareOnlyInsideSameProviderSublevelWorldOwnerAndFace() {
		Fixture fixture = new Fixture(); World world = new World(); world.capability(BlockPos.ZERO); world.capability(new BlockPos(1, 0, 0));
		fixture.bind("a", world, BlockPos.ZERO, "sublevel-a"); fixture.bind("b", world, new BlockPos(1, 0, 0), "sublevel-a");
		fixture.bind("c", world, BlockPos.ZERO, "sublevel-b"); fixture.bind("d", world, BlockPos.ZERO, "sublevel-a");
		var a = candidate(DIMENSION, "minecraft:beehive", "a"); var b = candidate(DIMENSION, "minecraft:beehive", "b");
		var c = candidate(DIMENSION, "minecraft:beehive", "c"); var d = candidate("minecraft:the_nether", "minecraft:beehive", "d");
		try (var first = fixture.resolve(a).orElseThrow(); var alias = fixture.resolve(b).orElseThrow();
			var sublevel = fixture.resolve(c).orElseThrow(); var dimension = fixture.resolve(d).orElseThrow();
			var owner = fixture.resolve(new InventorySourceInput(a.target(), new UUID(8, 8), a.face())).orElseThrow();
			var face = fixture.resolve(new InventorySourceInput(a.target(), OWNER, BlockFace.SOUTH)).orElseThrow()) {
			assertEquals(first.key(), alias.key()); assertNotEquals(first.key(), sublevel.key()); assertNotEquals(first.key(), dimension.key());
			assertNotEquals(first.key(), owner.key()); assertNotEquals(first.key(), face.key());
		}
		try (var runtime = new InventoryRuntime(fixture::resolve, 16_000_000)) {
			runtime.advance(0, InventorySettings.serverDefaults());
			var first = runtime.attach(a, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			var alias = runtime.attach(b, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			assertEquals(5, runtime.step(first, 1).orElseThrow().slots().getFirst().count());
			assertEquals(5, runtime.step(alias, 1).orElseThrow().slots().getFirst().count());
			assertEquals(1, fixture.capabilityReads.get(), "positive canonical alias shares one physical prefix");
		}
	}

	@Test void sameRegistryCandidatesWithDifferentLocatorsDoNotShortCircuitResolutionOrInvalidRetirement() {
		Fixture fixture = new Fixture(); World world = new World(); Chest first = world.chest(BlockPos.ZERO, 3);
		Chest second = world.chest(new BlockPos(4, 0, 0), 11);
		fixture.bind("a", world, BlockPos.ZERO, "sublevel"); fixture.bind("b", world, new BlockPos(4, 0, 0), "sublevel");
		var a = candidate(DIMENSION, "minecraft:chest", "a"); var b = candidate(DIMENSION, "minecraft:chest", "b");
		assertEquals(a.target(), b.target(), "domain candidate equality deliberately excludes locators"); assertFalse(a.sameBinding(b));
		try (var runtime = new InventoryRuntime(fixture::resolve, 16_000_000)) {
			runtime.advance(0, InventorySettings.serverDefaults());
			var ca = runtime.attach(a, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			var cb = runtime.attach(b, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			assertEquals(3, runtime.step(ca, 1).orElseThrow().slots().getFirst().count());
			assertEquals(11, runtime.step(cb, 1).orElseThrow().slots().getFirst().count());
			assertEquals(1, first.saves); assertEquals(1, second.saves);
			runtime.retireInvalid(a); assertTrue(ca.invalidated()); assertFalse(cb.invalidated()); assertEquals(Optional.of(true), runtime.probe(cb));
		}
	}
	@Test void canonicalAliasSharingRetainsEachCommittedInputsLeaseEvidenceForCompletedPublication() {
		Fixture fixture = new Fixture(); World world = new World(); world.capability(BlockPos.ZERO); world.capability(new BlockPos(1, 0, 0));
		Route first = new Route(world, BlockPos.ZERO, "sublevel"), second = new Route(world, new BlockPos(1, 0, 0), "sublevel");
		fixture.points.put("first", first); fixture.points.put("second", second);
		var a = committed("minecraft:beehive", "first", "old-first"); var b = committed("minecraft:beehive", "second", "old-second");
		var runtime = new InventoryRuntime(fixture::resolve, 16_000_000);
		try (runtime) {
			var settings = InventorySettings.serverDefaults(); runtime.advance(0, settings);
			var ca = runtime.attach(a, new InventoryRuntime.TrackingSubject(TargetKey.from(a.target()))).orElseThrow();
			var cb = runtime.attach(b, new InventoryRuntime.TrackingSubject(TargetKey.from(b.target()))).orElseThrow();
			var duplicate = runtime.attach(b, new InventoryRuntime.TrackingSubject(TargetKey.from(b.target()))).orElseThrow();
			runtime.step(ca, 1).orElseThrow(); runtime.step(cb, 1).orElseThrow();
			runtime.step(duplicate, 1).orElseThrow();
			runtime.step(ca, 1).orElseThrow(); ca.retainCompleted(); runtime.step(cb, 1).orElseThrow(); cb.retainCompleted();
			runtime.step(duplicate, 1).orElseThrow(); duplicate.retainCompleted();
			assertEquals(2, fixture.capabilityReads.get(), "two leased aliases still use one physical sweep");
			ca.restart(); cb.restart(); duplicate.restart(); fixture.tick = 1; runtime.advance(1, settings); second.active = false;
			assertEquals(Optional.of(true), runtime.probe(ca));
			assertEquals(Optional.of(false), runtime.probe(cb), "the peer's still-active controller cannot authorize a released original point");
			assertEquals(Optional.of(false), runtime.probe(duplicate), "same-input shortcut cannot drop alias lease evidence");
			assertTrue(cb.invalidated()); assertFalse(ca.invalidated()); assertEquals(2, fixture.capabilityReads.get());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void cachedAliasPrefixCannotBeConsumedAfterItsOwnPointMovesEvenWhenSharedControllerStillValid() {
		Fixture fixture = new Fixture(); World world = new World(); world.capability(BlockPos.ZERO); world.capability(new BlockPos(1, 0, 0));
		world.capability(new BlockPos(2, 0, 0));
		Route first = new Route(world, BlockPos.ZERO, "sublevel"), second = new Route(world, new BlockPos(1, 0, 0), "sublevel");
		fixture.points.put("first", first); fixture.points.put("second", second);
		var a = committed("minecraft:beehive", "first", "a"); var b = committed("minecraft:beehive", "second", "b");
		try (var runtime = new InventoryRuntime(fixture::resolve, 16_000_000)) {
			var settings = InventorySettings.serverDefaults(); runtime.advance(0, settings);
			var ca = runtime.attach(a, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			var cb = runtime.attach(b, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			runtime.step(ca, 1).orElseThrow(); runtime.step(cb, 1).orElseThrow(); assertEquals(1, fixture.capabilityReads.get());
			second.pos = new BlockPos(2, 0, 0); fixture.tick = 1; runtime.advance(1, settings);
			var rejected = runtime.step(cb, 1).orElseThrow(); assertNotEquals(CaptureResult.Availability.READABLE, rejected.result().availability());
			assertTrue(rejected.slots().isEmpty()); assertEquals(1, fixture.capabilityReads.get()); assertFalse(ca.invalidated());
		}
	}
	@Test void inputOnlyProbeAndValidateDiscardInvalidAliasWithoutRetiringServiceableOriginOrRefundingQuota() {
		for (boolean validate : List.of(false, true)) for (boolean relocate : List.of(false, true)) {
			Fixture fixture = new Fixture(); World world = new World(); world.capability(BlockPos.ZERO); world.capability(new BlockPos(1, 0, 0));
			Route first = new Route(world, BlockPos.ZERO, "sublevel"), second = new Route(world, new BlockPos(1, 0, 0), "sublevel");
			fixture.points.put("first", first); fixture.points.put("second", second);
			var a = committed("minecraft:beehive", "first", "a"); var b = committed("minecraft:beehive", "second", "b");
			var runtime = new InventoryRuntime(fixture::resolve, 16_000_000);
			try (runtime) {
				var settings = InventorySettings.serverDefaults(); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(2)); runtime.advance(0, settings);
				var ca = runtime.attach(a, new InventoryRuntime.TrackingSubject(TargetKey.from(a.target()))).orElseThrow();
				var cb = runtime.attach(b, new InventoryRuntime.TrackingSubject(TargetKey.from(b.target()))).orElseThrow();
				var duplicate = runtime.attach(b, new InventoryRuntime.TrackingSubject(TargetKey.from(b.target()))).orElseThrow();
				runtime.step(ca, 1).orElseThrow(); runtime.step(cb, 1).orElseThrow(); runtime.step(duplicate, 1).orElseThrow();
				runtime.step(ca, 1).orElseThrow(); ca.retainCompleted(); runtime.step(cb, 1).orElseThrow(); cb.retainCompleted();
				runtime.step(duplicate, 1).orElseThrow(); duplicate.retainCompleted();
				long retained = runtime.memory().retained(); assertEquals(2, fixture.capabilityReads.get());
				if (relocate) second.pos = new BlockPos(9, 0, 0); else second.active = false;
				if (validate) assertFalse(runtime.validate(b)); else assertEquals(Optional.of(false), runtime.probe(b));
				assertTrue(cb.invalidated()); assertTrue(duplicate.invalidated()); assertFalse(ca.invalidated());
				assertEquals(Optional.of(true), runtime.probe(ca), "alias input failure cannot retire the origin's completed evidence");
				assertTrue(runtime.memory().retained() < retained, "only failed alias evidence is released");
				assertTrue(runtime.memory().retained() > 300_000, "origin evidence stays retained");
				assertEquals(0, runtime.memory().reserved()); assertEquals(2, fixture.capabilityReads.get());
				ca.restart(); assertTrue(runtime.step(ca, 1).isEmpty(), "invalidation does not refund the origin's paid target quota");
				fixture.tick = settings.getTracking().getPeriodTicks(); runtime.advance(fixture.tick, settings);
				assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(ca).orElseThrow().result().completeness()); ca.retainCompleted();
				assertEquals(4, fixture.capabilityReads.get(), "the surviving origin starts a fresh sweep next period");
			}
			assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void originInputProbeRetiresSharedEvidenceEvenAfterOriginConsumerHasLeft() {
		Fixture fixture = new Fixture(); World world = new World(); world.capability(BlockPos.ZERO); world.capability(new BlockPos(1, 0, 0));
		Route first = new Route(world, BlockPos.ZERO, "sublevel"), second = new Route(world, new BlockPos(1, 0, 0), "sublevel");
		fixture.points.put("first", first); fixture.points.put("second", second);
		var a = committed("minecraft:beehive", "first", "a"); var b = committed("minecraft:beehive", "second", "b");
		var runtime = new InventoryRuntime(fixture::resolve, 16_000_000);
		try (runtime) {
			runtime.advance(0, InventorySettings.serverDefaults());
			var ca = runtime.attach(a, new InventoryRuntime.TrackingSubject(TargetKey.from(a.target()))).orElseThrow();
			var cb = runtime.attach(b, new InventoryRuntime.TrackingSubject(TargetKey.from(b.target()))).orElseThrow();
			runtime.step(ca, 1).orElseThrow(); runtime.step(cb, 1).orElseThrow(); ca.close();
			first.active = false; assertEquals(Optional.of(false), runtime.probe(a));
			assertTrue(cb.invalidated(), "a valid alias cannot publish a sweep whose originating evidence became invalid");
			assertEquals(4096 + 2 * 1024, runtime.memory().retained(), "no origin or alias evidence remains");
			assertEquals(1, fixture.capabilityReads.get()); assertEquals(0, runtime.memory().reserved());
		}
		assertEquals(0, runtime.memory().retained());
	}
	@Test void invalidAliasInputDuringSharedReplacementReleasesItsReferencesWithoutBlockingOriginHandoff() {
		Fixture fixture = new Fixture(); World world = new World(); world.capability(BlockPos.ZERO); world.capability(new BlockPos(1, 0, 0));
		Route first = new Route(world, BlockPos.ZERO, "sublevel"), second = new Route(world, new BlockPos(1, 0, 0), "sublevel");
		fixture.points.put("first", first); fixture.points.put("second", second);
		var a = committed("minecraft:beehive", "first", "a"); var b = committed("minecraft:beehive", "second", "b");
		var runtime = new InventoryRuntime(fixture::resolve, 16_000_000);
		try (runtime) {
			var settings = InventorySettings.serverDefaults(); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(2)); runtime.advance(0, settings);
			var ca = runtime.attach(a, new InventoryRuntime.TrackingSubject(TargetKey.from(a.target()))).orElseThrow();
			var cb = runtime.attach(b, new InventoryRuntime.TrackingSubject(TargetKey.from(b.target()))).orElseThrow();
			runtime.step(ca).orElseThrow(); ca.retainCompleted(); runtime.step(cb).orElseThrow(); cb.retainCompleted(); ca.restart(); cb.restart();
			fixture.tick = settings.getTracking().getPeriodTicks(); runtime.advance(fixture.tick, settings);
			assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(ca, 1).orElseThrow().result().completeness()); runtime.step(cb, 1).orElseThrow();
			assertEquals(3, fixture.capabilityReads.get()); second.active = false;
			assertEquals(Optional.of(false), runtime.probe(b)); assertTrue(cb.invalidated()); assertFalse(ca.invalidated());
			assertEquals(Optional.of(true), runtime.probe(ca), "old publication and replacement evidence remain independently valid for the origin");
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(ca, 1).orElseThrow().result().completeness()); ca.retainCompleted(); ca.restart();
			assertEquals(4, fixture.capabilityReads.get()); assertEquals(Optional.of(true), runtime.probe(ca)); assertEquals(0, runtime.memory().reserved());
			assertTrue(runtime.step(ca, 1).isEmpty(), "dropping an alias cannot refund the origin's replacement allowance");
			fixture.tick += settings.getTracking().getPeriodTicks(); runtime.advance(fixture.tick, settings);
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(ca).orElseThrow().result().completeness()); ca.retainCompleted();
			assertEquals(6, fixture.capabilityReads.get(), "alias retirement neither pins a stale handoff nor prevents a later origin sweep");
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void externalProviderLayoutCannotIntroduceForeignMembersIntoLiveOrSnapshotRoute() {
		Fixture fixture = new Fixture(); World world = new World(); world.capability(BlockPos.ZERO); world.capability(new BlockPos(1, 0, 0));
		Route route = fixture.bind("a", world, BlockPos.ZERO, "sublevel"); BlockPos foreign = new BlockPos(1, 0, 0);
		route.allowed.remove(foreign);
		route.layout = new InventorySnapshotLayout(route.alias, "test-members", BlockPos.ZERO, List.of(
			new InventorySnapshotLayout.Member(BlockPos.ZERO, "minecraft:beehive", "minecraft:beehive", "controller",
				List.of(new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, 1, List.of(0)))),
			new InventorySnapshotLayout.Member(foreign, "minecraft:beehive", "minecraft:beehive", "member",
				List.of(new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, 1, List.of(0))))));
		assertTrue(fixture.resolve(candidate(DIMENSION, "minecraft:beehive", "a")).isEmpty());
		assertEquals(0, fixture.capabilityReads.get()); assertFalse(world.stateReads.contains(foreign)); assertFalse(world.entityReads.contains(foreign));
		route.layout = new InventorySnapshotLayout(route.alias, "unsupported-members", BlockPos.ZERO, List.of(
			new InventorySnapshotLayout.Member(BlockPos.ZERO, "minecraft:beehive", "minecraft:beehive", "controller",
				List.of(new InventorySnapshotLayout.Segment("Items", "test:unsupported", 1, List.of(0)))),
			new InventorySnapshotLayout.Member(foreign, "minecraft:beehive", "minecraft:beehive", "member",
				List.of(new InventorySnapshotLayout.Segment("Items", "test:unsupported", 1, List.of(0))))));
		assertTrue(fixture.resolve(candidate(DIMENSION, "minecraft:beehive", "a")).isEmpty(), "unsupported NBT cannot widen to a foreign live view");
	}

	@Test void foreignChestPartnerIsDeniedBeforeItsStateBlockEntityOrAnyContentsAreRead() {
		Fixture fixture = new Fixture(); World world = new World();
		BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST).setValue(ChestBlock.TYPE, ChestType.LEFT);
		BlockPos other = BlockPos.ZERO.relative(ChestBlock.getConnectedDirection(left));
		Chest first = new Chest(BlockPos.ZERO, left, 3); Chest second = new Chest(other, left.setValue(ChestBlock.TYPE, ChestType.RIGHT), 7);
		world.put(BlockPos.ZERO, left, first); world.put(other, second.getBlockState(), second);
		Route route = fixture.bind("a", world, BlockPos.ZERO, "sublevel-a"); route.allowed.remove(other);
		assertTrue(fixture.resolve(candidate(DIMENSION, "minecraft:chest", "a")).isEmpty());
		assertFalse(world.stateReads.contains(other)); assertFalse(world.entityReads.contains(other));
		assertEquals(0, first.saves + second.saves + first.reads + second.reads);
	}
	@Test void sameSublevelDoubleChestUsesCanonicalAliasAndPerMemberNbtWithoutReplacingOriginalExternalTarget() {
		Fixture fixture = new Fixture(); World world = new World();
		BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST).setValue(ChestBlock.TYPE, ChestType.LEFT);
		BlockPos other = BlockPos.ZERO.relative(ChestBlock.getConnectedDirection(left));
		Chest first = new Chest(BlockPos.ZERO, left, 3), second = new Chest(other, left.setValue(ChestBlock.TYPE, ChestType.RIGHT), 7);
		world.put(BlockPos.ZERO, left, first); world.put(other, second.getBlockState(), second);
		fixture.bind("left", world, BlockPos.ZERO, "same-sublevel"); fixture.bind("right", world, other, "same-sublevel");
		var original = candidate(DIMENSION, "minecraft:chest", "right");
		try (var root = fixture.resolve(candidate(DIMENSION, "minecraft:chest", "left")).orElseThrow();
			var hit = fixture.resolve(original).orElseThrow(); var snapshot = (InventoryNbtSnapshot) hit.snapshotPlan().orElseThrow().capture().orElseThrow()) {
			assertEquals(root.key(), hit.key()); assertEquals(54, snapshot.slots()); assertEquals(other, snapshot.originalTarget());
			assertEquals(List.of(BlockPos.ZERO, other), snapshot.layout().members().stream().map(InventorySnapshotLayout.Member::position).toList());
			assertEquals(3, snapshot.read(0).count()); assertEquals(7, snapshot.read(27).count());
			assertEquals(1, first.saves); assertEquals(1, second.saves); assertEquals(0, first.reads + second.reads);
			assertEquals("right", ((Target.ExternalBlockTarget) original.target()).providerLocator()); assertTrue(original.ordinaryTarget().isEmpty());
		}
	}
	@Test void releaseDuringContiguousNbtSaveCannotPublishAtomicSnapshotOrReadLaterRevokedMember() {
		Fixture fixture = new Fixture(); World world = new World();
		BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST).setValue(ChestBlock.TYPE, ChestType.LEFT);
		BlockPos other = BlockPos.ZERO.relative(ChestBlock.getConnectedDirection(left));
		Chest first = new Chest(BlockPos.ZERO, left, 3), second = new Chest(other, left.setValue(ChestBlock.TYPE, ChestType.RIGHT), 7);
		world.put(BlockPos.ZERO, left, first); world.put(other, second.getBlockState(), second);
		Route route = new Route(world, BlockPos.ZERO, "sublevel"); fixture.points.put("point", route);
		try (var source = fixture.resolve(committed("minecraft:chest", "point", "stale")).orElseThrow()) {
			first.afterSave = () -> route.active = false;
			assertThrows(IllegalStateException.class, () -> source.snapshotPlan().orElseThrow().capture());
			assertEquals(1, first.saves); assertEquals(0, second.saves, "revoked member is not saved after the earlier provider call");
			assertFalse(source.valid());
		}
	}

	@Test void completedCommittedEvidenceInvalidatesOnPointRelocationOrReleaseAndFreshExistingRecoveryRecaptures() {
		for (boolean release : List.of(false, true)) {
			Fixture fixture = new Fixture(); World world = new World(); Chest old = world.chest(BlockPos.ZERO, 3);
			BlockPos moved = new BlockPos(7, 0, 0); Chest replacement = world.chest(moved, 19);
			Route route = new Route(world, BlockPos.ZERO, "sublevel"); fixture.points.put("leased-point", route);
			var input = committed("minecraft:chest", "leased-point", "stale-locator-must-not-be-used");
			InventoryRuntime runtime = new InventoryRuntime(fixture::resolve, 16_000_000);
			try (runtime) {
				var settings = InventorySettings.serverDefaults(); runtime.advance(0, settings);
				var consumer = runtime.attach(input, new InventoryRuntime.TrackingSubject(TargetKey.from(input.target()))).orElseThrow();
				var completed = runtime.step(consumer).orElseThrow(); assertEquals(CaptureResult.Completeness.COMPLETE, completed.result().completeness());
				assertEquals(3, completed.slots().getFirst().count()); consumer.retainCompleted(); consumer.restart();
				assertEquals(1, old.saves); fixture.tick = 1; runtime.advance(1, settings);
				if (release) route.active = false; else route.pos = moved;
				if (!release) assertTrue(runtime.validate(input), "a fresh legal source does not authorize old completed evidence");
				assertEquals(Optional.of(false), runtime.probe(consumer), "send-time probe uses the retained physical descriptor");
				assertTrue(consumer.invalidated()); assertEquals(0, replacement.saves);
				assertNotEquals(CaptureResult.Availability.READABLE, runtime.step(consumer).orElseThrow().result().availability());
				if (release) {
					assertTrue(fixture.resolve(input).isEmpty(), "source reads cannot revive a released lease");
					route.active = true; // stand-in for a provider-authorized active reference, never allocated by the source
				} else route.pos = moved;
				fixture.tick = 5; runtime.advance(5, settings); consumer.restart();
				var recovered = runtime.step(consumer).orElseThrow(); assertEquals(CaptureResult.Completeness.COMPLETE, recovered.result().completeness());
				assertEquals(release ? 3 : 19, recovered.slots().getFirst().count());
				assertEquals(release ? 2 : 1, old.saves); assertEquals(release ? 0 : 1, replacement.saves);
			}
			assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
		}
	}

	@Test void freshWorldAndMembershipClosuresAreUsedForValidityReadEnumerationAndNbtCaptureAcrossTicks() {
		Fixture fixture = new Fixture(); World world = new World(); world.capability(BlockPos.ZERO);
		Route route = fixture.bind("a", world, BlockPos.ZERO, "sublevel"); var input = candidate(DIMENSION, "minecraft:beehive", "a");
		try (var source = fixture.resolve(input).orElseThrow()) {
			fixture.tick = 1; assertTrue(source.valid()); assertEquals(5, source.read(0).count());
			fixture.tick = 2; route.count = 9; assertEquals(9, source.enumerate(2).slots().getFirst().count());
			assertEquals(3, fixture.capabilityReads.get()); assertTrue(fixture.resolutions.get() >= 4);
			fixture.tick = 3; route.allowed.clear(); assertFalse(source.valid());
			int reads = fixture.capabilityReads.get(); assertThrows(IllegalStateException.class, () -> source.read(0));
			assertThrows(IllegalStateException.class, () -> source.enumerate(2)); assertEquals(reads, fixture.capabilityReads.get());
		}
		World chestWorld = new World(); Chest chest = chestWorld.chest(BlockPos.ZERO, 4);
		fixture.bind("chest", chestWorld, BlockPos.ZERO, "sublevel");
		try (var source = fixture.resolve(candidate(DIMENSION, "minecraft:chest", "chest")).orElseThrow()) {
			var plan = source.snapshotPlan().orElseThrow(); fixture.tick++;
			try (var snapshot = plan.capture().orElseThrow()) {
				assertEquals(4, snapshot.read(0).count()); assertEquals(1, chest.saves); assertEquals(0, chest.reads);
			}
		}
	}

	@Test void partialSnapshotRejectsMemberTopologyChangeWithoutMixedTailAndNewCaptureRecovers() {
		Fixture fixture = new Fixture(); World world = new World(); SidedChest chest = new SidedChest(BlockPos.ZERO, 8);
		chest.mapping = new int[] {0, 1}; world.put(BlockPos.ZERO, chest.getBlockState(), chest);
		fixture.bind("a", world, BlockPos.ZERO, "sublevel"); var input = candidate(DIMENSION, "minecraft:chest", "a");
		var memory = new RetainedMemoryLedger(8_000_000);
		try (var handle = open(fixture, input)) {
			assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
			assertEquals(CaptureResult.Completeness.CONTINUE, step(handle, 1).completeness());
			fixture.tick = 1; chest.mapping = new int[] {1, 0};
			var changed = step(handle, 1); assertEquals(CaptureResult.Availability.INVALID, changed.availability()); assertTrue(changed.payload().isEmpty());
			assertFalse(handle.evidenceValid()); assertEquals(1, chest.saves); assertEquals(0, chest.reads);
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
		try (var fresh = open(fixture, input)) {
			assertEquals(InventorySourceAccess.Preparation.READY, fresh.prepareSnapshot(memory));
			var recovered = step(fresh, 2); assertEquals(CaptureResult.Completeness.COMPLETE, recovered.completeness());
			var page = ((CaptureResult.OpaqueKeyedFragment) recovered.payload().orElseThrow()).entries();
			assertNull(InventoryDomainCodec.decode(page.get("0"))); assertEquals(8, InventoryDomainCodec.decode(page.get("1")).count());
			assertEquals(2, chest.saves);
		}
	}

	@Test void committedLocatorRefreshKeepsBindingButDescriptorScopeChangeAndReleasedCaptureAreInvalid() {
		Fixture fixture = new Fixture(); World world = new World(); world.chest(BlockPos.ZERO, 4);
		Route route = new Route(world, BlockPos.ZERO, "sublevel"); fixture.points.put("point", route);
		var input = committed("minecraft:chest", "point", "old"); var refresh = committed("minecraft:chest", "point", "new");
		assertTrue(input.sameBinding(refresh)); assertTrue(input.matchesDetached(MinecraftBlockReadSources.detached(refresh.target())));
		try (var source = fixture.resolve(input).orElseThrow()) {
			var plan = source.snapshotPlan().orElseThrow(); route.scope = "other-sublevel";
			assertFalse(source.valid()); assertThrows(IllegalStateException.class, plan::capture);
			route.scope = "sublevel"; route.active = false;
			assertThrows(IllegalStateException.class, plan::capture);
		}
	}

	@Test void frozenFaceLockAndLootSafetyRemainTheSameForExternalChestNbt() {
		Fixture fixture = new Fixture(); World world = new World(); SidedChest chest = new SidedChest(BlockPos.ZERO, 4);
		world.put(BlockPos.ZERO, chest.getBlockState(), chest); fixture.bind("a", world, BlockPos.ZERO, "sublevel");
		var input = candidate(DIMENSION, "minecraft:chest", "a");
		try (var source = fixture.resolve(input).orElseThrow()) {
			assertEquals(1, source.slots()); assertEquals(Direction.NORTH, chest.lastFace);
			chest.lock = new LockCode("another-key"); assertFalse(source.valid()); assertThrows(IllegalStateException.class, () -> source.read(0));
			assertEquals(0, chest.reads + chest.saves);
		}
		chest.lock = LockCode.NO_LOCK;
		chest.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse("minecraft:chests/simple_dungeon")));
		assertTrue(fixture.resolve(input).isEmpty()); assertEquals(0, chest.reads + chest.saves);
		chest.setLootTable(null); chest.mapping = new int[0];
		try (var source = fixture.resolve(input).orElseThrow(); var snapshot = source.snapshotPlan().orElseThrow().capture().orElseThrow()) {
			assertEquals(0, snapshot.slots()); assertEquals(InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED, snapshot.evidence());
			assertEquals(Direction.NORTH, chest.lastFace); assertEquals(1, chest.saves);
		}
	}

	@Test void mismatchedDetachedCandidateAndWrongResolverBindingAreRejectedBeforeWorldRead() {
		Fixture fixture = new Fixture(); World world = new World(); world.chest(BlockPos.ZERO, 3);
		fixture.bind("a", world, BlockPos.ZERO, "sublevel"); fixture.bind("b", world, BlockPos.ZERO, "sublevel");
		var a = candidate(DIMENSION, "minecraft:chest", "a"); var b = candidate(DIMENSION, "minecraft:chest", "b");
		var access = new InventorySourceAccess(a, fixture::resolve);
		assertEquals(SourceAccess.ResolveResult.Unresolved.UNSUPPORTED, access.resolve(MinecraftBlockReadSources.detached(b.target()),
			new SourceAccess.ReadScope(a.viewKey(), Set.of("pingforit:inventory.items")), grant(0)));
		assertEquals(0, fixture.resolutions.get());
		assertTrue(InventoryMinecraftSources.resolveExternal(a, () -> fixture.binding(b), () -> true, SableInventorySourceTest::item).isEmpty());
		assertTrue(world.stateReads.isEmpty());
		fixture.onThread = false; int before = fixture.resolutions.get(); assertTrue(fixture.resolve(a).isEmpty()); assertEquals(before, fixture.resolutions.get());
	}
	@Test void providerLookupAndLastMetadataRevocationCannotReachNativeReadOrEnumerationHandoff() {
		for (boolean enumerate : List.of(false, true)) for (boolean lookup : List.of(false, true)) {
			Fixture fixture = new Fixture(); World world = new World(); world.capability(BlockPos.ZERO);
			Route route = fixture.bind("a", world, BlockPos.ZERO, "sublevel"); var input = candidate(DIMENSION, "minecraft:beehive", "a");
			try (var source = fixture.resolve(input).orElseThrow()) {
				Runnable revoke = () -> route.allowed.clear();
				if (lookup) route.afterLookup = revoke; else route.afterStableCursor = revoke;
				if (enumerate) assertThrows(IllegalStateException.class, () -> source.enumerate(2));
				else assertThrows(IllegalStateException.class, () -> source.read(0));
				assertEquals(0, fixture.capabilityReads.get(), "metadata or capability lookup cannot authorize a subsequent revoked content read");
				assertFalse(source.valid());
			}
		}
	}
	@Test void metadataDeniedIndexedStepChargesAttemptWithoutPublishingZeroAndFreshHandleRecovers() {
		Fixture fixture = new Fixture(); World world = new World(); world.capability(BlockPos.ZERO);
		Route route = fixture.bind("a", world, BlockPos.ZERO, "sublevel"); var input = candidate(DIMENSION, "minecraft:beehive", "a");
		try (var handle = open(fixture, input)) {
			AtomicInteger metadataCalls = new AtomicInteger();
			// valid() observes the metadata first; the read's fresh current() then revokes it at handoff.
			route.afterStableCursor = () -> { if (metadataCalls.incrementAndGet() == 2) route.allowed.clear(); };
			CostLedger ledger = grant(1); var result = ((SourceAccess.StepOutcome.Captured) handle.step(ledger)).result();
			assertEquals(CaptureResult.Availability.UNAVAILABLE, result.availability()); assertEquals(CaptureResult.Completeness.INCOMPLETE, result.completeness());
			assertTrue(result.payload().isEmpty()); assertEquals(0, fixture.capabilityReads.get()); assertFalse(handle.evidenceValid());
			assertEquals(0, ledger.remaining(InventorySourceAccess.PHYSICAL));
			assertEquals(7, ledger.remaining(InventorySourceAccess.PROBES), "one validation probe is charged");
			assertEquals(2 * InventorySourceAccess.PROVIDER_CALL_WORK, ledger.used(InventorySourceAccess.PROVIDER_WORK));
		}
		route.afterStableCursor = () -> {}; route.allowed.add(BlockPos.ZERO); fixture.tick++;
		try (var fresh = open(fixture, input)) {
			assertEquals(CaptureResult.Completeness.COMPLETE, step(fresh, 2).completeness()); assertEquals(2, fixture.capabilityReads.get());
		}
	}
	@Test void indexedEnumerationHandoffChecksEachReadAndFreshResolutionCannotReplayRevokedContents() {
		Fixture fixture = new Fixture(); World world = new World(); world.capability(BlockPos.ZERO);
		Route route = fixture.bind("a", world, BlockPos.ZERO, "sublevel"); var input = candidate(DIMENSION, "minecraft:beehive", "a");
		try (var source = fixture.resolve(input).orElseThrow()) {
			route.afterRead = () -> route.allowed.clear();
			assertThrows(IllegalStateException.class, () -> source.enumerate(2)); assertEquals(1, fixture.capabilityReads.get()); assertFalse(source.valid());
			assertThrows(IllegalStateException.class, () -> source.enumerate(2)); assertEquals(1, fixture.capabilityReads.get());
			route.afterRead = () -> {}; route.allowed.add(BlockPos.ZERO); fixture.tick++;
			assertTrue(source.valid()); assertEquals(2, source.enumerate(2).slots().size()); assertEquals(3, fixture.capabilityReads.get());
		}
	}
}
