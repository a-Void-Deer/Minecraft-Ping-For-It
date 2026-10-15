package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import nx.pingwheel.common.config.ByteMultiplier;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.inventory.InventoryBackend;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout.Member;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout.Segment;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryMinecraftSourcesTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	static final class World {
		String mapping = "axis-X/2x2x3"; final AtomicInteger wrappers = new AtomicInteger(), reads = new AtomicInteger();
		InventoryMinecraftSources.View view() {
			String expected = mapping; wrappers.incrementAndGet();
			return new InventoryMinecraftSources.View("same-controller-alias", List.of(), List.of(), new IPlatformInventoryService.Access() {
				@Override public Optional<String> alias() { return Optional.of("same-controller-alias"); }
				@Override public boolean valid() { return mapping.equals(expected); }
				@Override public boolean stableCursor() { return true; }
				@Override public int slots() { return 12; }
				@Override public OptionalLong version() { return OptionalLong.empty(); }
				@Override public IPlatformInventoryService.Entry read(int slot) { reads.incrementAndGet(); return new IPlatformInventoryService.Entry(new ItemStack(Items.STONE), mapping.contains("axis-X") ? 7 : 9); }
				@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("indexed provider"); }
			});
		}
		InventorySourceAccess.Source source(InventorySourceInput input) {
			return new InventoryMinecraftSources.Source(input, view(), () -> true, this::view, entry ->
				new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), entry.amount(), "Stone", null, false));
		}
	}
	static CostLedger grant(int slots) {
		return new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) slots, InventorySourceAccess.PROBES, 8L, InventorySourceAccess.PROVIDER_WORK, 32768L));
	}
	static SourceAccess.Handle open(World world, InventorySourceInput input) {
		var access = new InventorySourceAccess(input, i -> Optional.of(world.source(i))); var scope = new SourceAccess.ReadScope(input.viewKey(), java.util.Set.of("pingforit:inventory.items"));
		var target = input.ordinaryTarget().orElseThrow(); var resolved = (SourceAccess.ResolveResult.Available) access.resolve(new PresentationAdapter.DetachedTarget(target.dimensionId(), "block", target.blockRegistryId(), target.x(), target.y(), target.z(), ""), scope, grant(0));
		return ((SourceAccess.OpenResult.Started) access.open(resolved.descriptor(), scope, grant(0))).handle();
	}
	@Test void productionSourceWrapperRejectsSameAliasAndCountTopologyChangeBeforeContinuingCursorAndFreshObservationRecovers() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "create:item_vault"), new UUID(1, 1), BlockFace.NORTH); var world = new World();
		try (var handle = open(world, input)) {
			var first = ((SourceAccess.StepOutcome.Captured) handle.step(grant(1))).result();
			assertEquals(CaptureResult.Completeness.CONTINUE, first.completeness()); assertEquals(1, world.reads.get()); assertTrue(world.wrappers.get() > 1, "same mapping can reacquire different wrapper objects");
			world.mapping = "axis-Z/2x2x3";
			var changed = ((SourceAccess.StepOutcome.Captured) handle.step(grant(1))).result();
			assertEquals(CaptureResult.Availability.INVALID, changed.availability()); assertEquals(CaptureResult.Completeness.INCOMPLETE, changed.completeness()); assertTrue(changed.payload().isEmpty()); assertEquals(1, world.reads.get(), "no L1-prefix/L2-tail mixed scan");
		}
		try (var fresh = open(world, input)) {
			var recovered = ((SourceAccess.StepOutcome.Captured) fresh.step(grant(12))).result(); assertEquals(CaptureResult.Completeness.COMPLETE, recovered.completeness());
			var entries = ((CaptureResult.OpaqueKeyedFragment) recovered.payload().orElseThrow()).entries(); assertEquals(12, entries.size());
			assertTrue(entries.values().stream().allMatch(v -> InventoryDomainCodec.decode(v).count() == 9));
		}
	}
	@Test void snapshotPlanIsLazyAndDoesNotReplaceTheLiveSelectionWitness() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"), new UUID(2, 2), BlockFace.NORTH);
		AtomicInteger captures = new AtomicInteger();
		var layout = new InventorySnapshotLayout("same-controller-alias", "face=north", BlockPos.ZERO,
			List.of(new Member(BlockPos.ZERO, "minecraft:chest", "minecraft:chest", "target",
				List.of(new Segment("Items", InventorySnapshotSchemas.ITEMS, 12, java.util.stream.IntStream.range(0, 12).boxed().toList())))));
		var access = new IPlatformInventoryService.Access() {
			@Override public Optional<String> alias() { return Optional.of("same-controller-alias"); }
			@Override public boolean valid() { return true; }
			@Override public boolean stableCursor() { return true; }
			@Override public int slots() { return 12; }
			@Override public OptionalLong version() { return OptionalLong.empty(); }
			@Override public IPlatformInventoryService.Entry read(int slot) { throw new AssertionError("SELECT witness should not be used by this capture-only fixture"); }
			@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("indexed provider"); }
		};
		var snapshot = new InventorySourceAccess.InventorySnapshot() {
			@Override public int slots() { return 12; }
			@Override public InventoryDomainCodec.Item read(int index) { return null; }
			@Override public long retainedBytes() { return 1; }
			@Override public InventorySourceAccess.SnapshotEvidence evidence() { return InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED; }
		};
		var view = new InventoryMinecraftSources.View("same-controller-alias", List.of(), List.of(), access, layout, false);
		var source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> view,
			entry -> new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), entry.amount(), "Stone", null, false),
			() -> { captures.incrementAndGet(); return Optional.of(snapshot); });
		var plan = source.snapshotPlan().orElseThrow();
		assertEquals(0, captures.get(), "plan discovery must not capture or save NBT");
		assertSame(snapshot, plan.capture().orElseThrow());
		assertEquals(1, captures.get());
		assertEquals(12, snapshot.slots());
	}

	@Test void aggregateAliasWithoutExplicitMemberLayoutKeepsLiveRoute() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "create:item_vault"), new UUID(3, 3), BlockFace.NORTH);
		World world = new World();
		var aggregate = new IPlatformInventoryService.Access() {
			@Override public Optional<String> alias() { return Optional.of("same-controller-alias"); }
			@Override public boolean valid() { return true; }
			@Override public boolean stableCursor() { return true; }
			@Override public int slots() { return 12; }
			@Override public OptionalLong version() { return OptionalLong.empty(); }
			@Override public IPlatformInventoryService.Entry read(int slot) { return IPlatformInventoryService.Entry.empty(); }
			@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { return true; }
		};
		var view = new InventoryMinecraftSources.View("aggregate-alias", List.of(), List.of(), aggregate);
		var source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> view,
			entry -> new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), entry.amount(), "Stone", null, false));
		assertTrue(source.snapshotPlan().isEmpty(), "aggregate alias cannot be snapshotted as the hit controller alone");
	}

	static final class Chest extends ChestBlockEntity implements BaseContainerLockAccessor {
		int reads, saves;
		Chest(BlockPos pos, BlockState state) { super(pos, state); }
		@Override public LockCode pingforit$inventoryLock() { return LockCode.NO_LOCK; }
		@Override public ItemStack getItem(int slot) { reads++; return super.getItem(slot); }
		@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) { saves++; super.saveAdditional(tag, registries); }
	}
	static final class ChestWorld implements InventoryMinecraftSources.SourceWorld {
		final BlockPos first = new BlockPos(1, 2, 3), hit;
		final Map<BlockPos, BlockState> states = new java.util.HashMap<>();
		final Map<BlockPos, Chest> chests = new java.util.HashMap<>();
		final InventorySourceInput input;
		final AtomicInteger resolutions = new AtomicInteger();
		ChestWorld() {
			BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST).setValue(ChestBlock.TYPE, ChestType.LEFT);
			hit = first.relative(ChestBlock.getConnectedDirection(left));
			states.put(first, left); states.put(hit, left.setValue(ChestBlock.TYPE, ChestType.RIGHT));
			var items = List.of(Items.STONE, Items.DIRT, Items.DIAMOND, Items.COAL, Items.IRON_INGOT, Items.GOLD_INGOT,
				Items.REDSTONE, Items.LAPIS_LAZULI, Items.EMERALD, Items.COBBLESTONE, Items.SAND, Items.GRAVEL);
			states.forEach((pos, state) -> {
				Chest chest = new Chest(pos, state);
				for (int i = 0; i < items.size(); i++) chest.setItem(i, new ItemStack(items.get(i), 1));
				chest.reads = chest.saves = 0; chests.put(pos, chest);
			});
			input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", hit.getX(), hit.getY(), hit.getZ(), "minecraft:chest"), new UUID(4, 4), BlockFace.NORTH);
		}
		@Override public boolean isLoaded(BlockPos pos) { return states.containsKey(pos); }
		@Override public BlockState blockState(BlockPos pos) { return states.get(pos); }
		@Override public BlockEntity blockEntity(BlockPos pos) { return chests.get(pos); }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
		@Override public HolderLookup.Provider registries() { return RegistryAccess.EMPTY; }
		@Override public boolean audited(net.minecraft.world.Container container) { return container instanceof Chest; } // recording fixture only
		InventorySourceAccess.Source resolve(InventorySourceInput readInput) {
			resolutions.incrementAndGet();
			var view = InventoryMinecraftSources.view(this, readInput, (pos, face) -> Optional.empty());
			return new InventoryMinecraftSources.Source(readInput, view, () -> true,
				() -> InventoryMinecraftSources.view(this, readInput, (pos, face) -> Optional.empty()), entry -> {
					if (entry.isEmpty()) return null;
					String id = BuiltInRegistries.ITEM.getKey(entry.exemplar().getItem()).toString();
					return new InventoryDomainCodec.Item(new InventoryScanner.Key(id, "plain"), entry.amount(), id, null, false);
				}, this, null);
		}
		int saves() { return chests.values().stream().mapToInt(c -> c.saves).sum(); }
		int reads() { return chests.values().stream().mapToInt(c -> c.reads).sum(); }
	}
	static final class PreviewHost implements InventoryBackend.Host {
		final ChestWorld world;
		final List<InventoryS2CPacket> sent = new java.util.ArrayList<>();
		PreviewHost(ChestWorld world) { this.world = world; }
		@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return Optional.of(new InventoryBackend.Policy(100, 1, java.util.Set.of("entity_block"))); }
		@Override public Optional<InventoryBackend.Opened> open(UUID player, Target target) { return Optional.of(new InventoryBackend.Opened(world.input.target(), "entity_block", "attention")); }
		@Override public boolean annotationAllowed(InventorySourceInput input, String type) { return true; }
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) { throw new AssertionError("preview never creates a marker"); }
		@Override public boolean knows(UUID player, MarkerId marker) { return false; }
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public int encodedBytes(InventoryS2CPacket packet) {
			var buf = new FriendlyByteBuf(Unpooled.buffer());
			try { packet.write(buf); return buf.readableBytes(); } finally { buf.release(); }
		}
		@Override public void send(UUID recipient, InventoryS2CPacket packet) {
			var buf = new FriendlyByteBuf(Unpooled.buffer());
			try {
				packet.write(buf); var decoded = InventoryS2CPacket.readSafe(buf);
				assertFalse(decoded.isCorrupt()); assertFalse(buf.isReadable()); sent.add(decoded);
			} finally { buf.release(); }
		}
		long epoch() { return sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.OFFER).findFirst().orElseThrow().epoch(); }
	}
	@Test void completedDoubleChestPreviewWithdrawsCrossPeriodEncodedBatchWhenOriginalHitHalfBecomesLegalSingle() {
		ChestWorld world = new ChestWorld(); var host = new PreviewHost(world); var settings = InventorySettings.serverDefaults();
		settings.getPreview().setClientByteMultiplier(ByteMultiplier.finite(new java.math.BigDecimal("0.25")));
		var runtime = new InventoryRuntime(i -> Optional.of(world.resolve(i)), 16_000_000);
		try (var backend = new InventoryBackend(host, runtime, 1)) {
			backend.handle(world.input.readOwner(), InventoryC2SPacket.hello(), 0, settings);
			backend.handle(world.input.readOwner(), InventoryC2SPacket.open(host.epoch(), 100, 1, 1, world.input.target(), world.input.face()), 0, settings);
			int period = settings.getPreview().getPeriodTicks(), visibleSlots = 2 * 27, variants = 12;
			long now = 0;
			InventoryS2CPacket completed = null;
			// A frozen capture is not a consumed observation. Allow one slot and one
			// part per period, plus the completion control, without assuming page width.
			for (int attempt = 0; attempt < visibleSlots + 2 * variants + 1 && completed == null; attempt++, now += period) {
				backend.tick(now, settings);
				completed = host.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.completeScan()
					&& p.entries().isEmpty() && p.status() == InventoryS2CPacket.Status.READY).findFirst().orElse(null);
			}
			assertNotNull(completed, "the bounded 54-slot observation and its earlier partial cuts must finish");
			var first = host.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			assertEquals(first.baselineId(), completed.baselineId()); assertEquals(first.statusRevision(), completed.statusRevision());
			assertEquals(0, world.saves()); assertEquals(54, world.reads());
			// Earlier partial cuts may have delivered every value before the final
			// empty slots. A request-scoped replay gives a full, identifiable COMPLETE
			// cut under the same baseline/state, without capturing again or raising caps.
			int replayStart = host.sent.size();
			backend.handle(world.input.readOwner(), InventoryC2SPacket.resync(host.epoch(), 1).stamp(100, 1), now, settings);
			backend.tick(now, settings);
			var cut = host.sent.subList(replayStart, host.sent.size()).stream()
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			assertTrue(cut.completeScan()); assertEquals(InventoryS2CPacket.Status.READY, cut.status());
			assertEquals(completed.baselineId(), cut.baselineId()); assertEquals(completed.statusRevision(), cut.statusRevision());
			assertTrue(cut.watermark() > completed.watermark()); assertEquals(variants, cut.partCount()); assertEquals(0, cut.partIndex());
			java.util.function.Predicate<InventoryS2CPacket> belongsToCut = p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()
				&& p.baselineId() == cut.baselineId() && p.statusRevision() == cut.statusRevision()
				&& p.watermark() == cut.watermark() && p.partCount() == cut.partCount();
			assertTrue(host.sent.stream().filter(belongsToCut).count() < cut.partCount(), "the admitted complete cut retains undelivered parts across periods");
			assertTrue(host.sent.stream().filter(belongsToCut).flatMap(p -> p.entries().stream()).allMatch(e -> e.count() == 2));
			assertEquals(0, world.saves()); assertEquals(54, world.reads(), "replay must reuse the one detached capture");
			world.states.remove(world.first); world.states.put(world.hit, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.SINGLE));
			assertTrue(runtime.validate(world.input), "a fresh wrapper admits the still-present original hit half as a legal single chest");
			int start = host.sent.size(), resolutions = world.resolutions.get(); backend.tick(now + period, settings);
			var revoked = host.sent.subList(start, host.sent.size());
			assertTrue(revoked.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.status() == InventoryS2CPacket.Status.INVALID
				&& p.baselineId() == cut.baselineId() && p.statusRevision() > cut.statusRevision()));
			assertTrue(revoked.stream().allMatch(p -> p.entries().isEmpty()), "no pending/admitted old-pair fragment survives the publication gate");
			assertEquals(resolutions, world.resolutions.get(), "terminal publication must retain old source evidence rather than resolve a fresh single");
			backend.tick(now + 2L * period, settings); assertTrue(host.sent.subList(start, host.sent.size()).stream().allMatch(p -> p.entries().isEmpty()));
			assertEquals(0, world.saves()); assertEquals(54, world.reads(), "validity never rereads captured contents");
			assertEquals(world.hit.getX(), world.input.ordinaryTarget().orElseThrow().x()); assertEquals(BlockFace.NORTH, world.input.face());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void normalContentChangesDoNotRebaseOrRecaptureAStableCrossPeriodDoubleChestPreview() {
		ChestWorld world = new ChestWorld(); var host = new PreviewHost(world); var settings = InventorySettings.serverDefaults();
		settings.getPreview().setClientByteMultiplier(ByteMultiplier.finite(new java.math.BigDecimal("0.25")));
		try (var backend = new InventoryBackend(host, new InventoryRuntime(i -> Optional.of(world.resolve(i)), 16_000_000), 1)) {
			backend.handle(world.input.readOwner(), InventoryC2SPacket.hello(), 0, settings);
			backend.handle(world.input.readOwner(), InventoryC2SPacket.open(host.epoch(), 100, 1, 1, world.input.target(), world.input.face()), 0, settings);
			int period = settings.getPreview().getPeriodTicks(), visibleSlots = 2 * 27, variants = 12;
			long now = 0;
			InventoryS2CPacket completed = null;
			for (int attempt = 0; attempt < visibleSlots + 2 * variants + 1 && completed == null; attempt++, now += period) {
				backend.tick(now, settings);
				completed = host.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.completeScan()
					&& p.entries().isEmpty() && p.status() == InventoryS2CPacket.Status.READY).findFirst().orElse(null);
			}
			assertNotNull(completed, "ordinary mutation starts only after the bounded 54-slot observation completes");
			var first = host.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			assertEquals(first.baselineId(), completed.baselineId()); assertEquals(first.statusRevision(), completed.statusRevision());
			assertEquals(0, world.saves()); assertEquals(54, world.reads());
			// Replay the completed retained projection so the pending multipart cut
			// cannot depend on which earlier partial revisions already drained.
			int replayStart = host.sent.size();
			backend.handle(world.input.readOwner(), InventoryC2SPacket.resync(host.epoch(), 1).stamp(100, 1), now, settings);
			backend.tick(now, settings);
			var cut = host.sent.subList(replayStart, host.sent.size()).stream()
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			assertTrue(cut.completeScan()); assertEquals(InventoryS2CPacket.Status.READY, cut.status());
			assertEquals(completed.baselineId(), cut.baselineId()); assertEquals(completed.statusRevision(), cut.statusRevision());
			assertTrue(cut.watermark() > completed.watermark()); assertEquals(variants, cut.partCount()); assertEquals(0, cut.partIndex());
			java.util.function.Predicate<InventoryS2CPacket> belongsToCut = p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()
				&& p.baselineId() == cut.baselineId() && p.statusRevision() == cut.statusRevision()
				&& p.watermark() == cut.watermark() && p.partCount() == cut.partCount();
			assertTrue(host.sent.stream().filter(belongsToCut).count() < cut.partCount(), "live mutation occurs while the complete cut still has admitted parts");
			assertEquals(0, world.saves()); assertEquals(54, world.reads(), "replay does not recapture the contents");
			for (Chest chest : world.chests.values()) { chest.clearContent(); chest.setItem(0, new ItemStack(Items.STONE, 31)); chest.reads = 0; }
			for (int attempt = 0; attempt < cut.partCount() + 2; attempt++) { now += period; backend.tick(now, settings); }
			var parts = host.sent.stream().filter(belongsToCut).toList();
			assertEquals(cut.partCount(), parts.size(), "this immutable complete cut finishes once instead of restarting each period");
			assertEquals(java.util.stream.IntStream.range(0, cut.partCount()).boxed().toList(), parts.stream().map(InventoryS2CPacket::partIndex).sorted().toList());
			assertTrue(parts.stream().allMatch(p -> p.completeScan() && p.status() == InventoryS2CPacket.Status.READY && p.checksum() == cut.checksum()));
			assertTrue(host.sent.subList(replayStart, host.sent.size()).stream().filter(p -> !p.entries().isEmpty()).allMatch(belongsToCut), "ordinary mutation creates no replacement cut or new baseline/state");
			assertTrue(host.sent.subList(replayStart, host.sent.size()).stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.entries().isEmpty()
				&& p.baselineId() == cut.baselineId() && p.statusRevision() == cut.statusRevision() && p.watermark() == cut.watermark()
				&& p.completeScan() && p.status() == InventoryS2CPacket.Status.READY && p.checksum() == cut.checksum()), "the completed cut reaches its matching delivery barrier");
			assertEquals(variants, parts.stream().flatMap(p -> p.entries().stream()).count());
			assertEquals(variants, parts.stream().flatMap(p -> p.entries().stream()).map(InventoryS2CPacket.Entry::key).distinct().count());
			assertEquals(variants, parts.stream().flatMap(p -> p.entries().stream()).map(InventoryS2CPacket.Entry::itemId).distinct().count());
			assertTrue(parts.stream().flatMap(p -> p.entries().stream()).allMatch(e -> e.count() == 2), "the completed detached observation is unchanged by live contents");
			assertTrue(host.sent.stream().noneMatch(p -> p.status() == InventoryS2CPacket.Status.INVALID));
			assertEquals(0, world.saves()); assertEquals(0, world.reads());
		}
	}
	@Test void terminalEvidenceDetectsSameAliasSameCountSnapshotLayoutChangeEvenWhenAccessRemainsSelfValid() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(5, 5);
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "create:item_vault"), owner, BlockFace.NORTH);
		String[] mapping = {"axis-X"}; var captures = new AtomicInteger();
		java.util.function.Supplier<InventoryMinecraftSources.View> live = () -> {
			var access = new IPlatformInventoryService.Access() {
				@Override public Optional<String> alias() { return Optional.of("same-controller-alias"); }
				@Override public boolean valid() { return true; }
				@Override public boolean stableCursor() { return true; }
				@Override public int slots() { return 12; }
				@Override public OptionalLong version() { return OptionalLong.empty(); }
				@Override public IPlatformInventoryService.Entry read(int slot) { throw new AssertionError("evidence validation cannot read items"); }
				@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("evidence validation cannot enumerate"); }
			};
			var layout = new InventorySnapshotLayout("same-controller-alias", mapping[0], BlockPos.ZERO,
				List.of(new Member(BlockPos.ZERO, "create:item_vault", "create:item_vault", "target",
					List.of(new Segment("Items", InventorySnapshotSchemas.ITEMS, 12, java.util.stream.IntStream.range(0, 12).boxed().toList())))));
			return new InventoryMinecraftSources.View("same-controller-alias", List.of(), List.of(), access, layout, false);
		};
		try (var runtime = new InventoryRuntime(i -> Optional.of(new InventoryMinecraftSources.Source(i, live.get(), () -> true, live,
			entry -> { throw new AssertionError("snapshot route only"); }, () -> {
				captures.incrementAndGet(); return Optional.of(new InventorySourceAccess.InventorySnapshot() {
					@Override public int slots() { return 12; }
					@Override public InventoryDomainCodec.Item read(int slot) { return null; }
					@Override public long retainedBytes() { return 1; }
					// The fixture's intended effective ceiling is the ordinary 4 KiB
					// display cap; the absolute default would force a multi-page
					// admission and make this terminal-evidence test incomplete.
					@Override public int displayBytes() { return InventoryItemCodec.MAX_DISPLAY_BYTES; }
					@Override public InventorySourceAccess.SnapshotEvidence evidence() { return InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED; }
				});
			})), 16_000_000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
			mapping[0] = "axis-Z"; assertTrue(runtime.validate(input)); assertEquals(Optional.of(false), runtime.probe(consumer));
			assertTrue(consumer.invalidated()); assertEquals(1, captures.get());
		}
	}
	@Test void liveCatalogueAdoptsTheEffectiveCapAndTightensMonotonically() {
		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, 32768);
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"), new UUID(6, 6), BlockFace.NORTH);
		ItemStack stack = new ItemStack(Items.STONE, 4);
		CompoundTag data = new CompoundTag();
		data.putString("payload", "x".repeat(6000));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		var access = new IPlatformInventoryService.Access() {
			@Override public Optional<String> alias() { return Optional.empty(); }
			@Override public boolean valid() { return true; }
			@Override public boolean stableCursor() { return true; }
			@Override public int slots() { return 1; }
			@Override public OptionalLong version() { return OptionalLong.empty(); }
			@Override public IPlatformInventoryService.Entry read(int slot) { return new IPlatformInventoryService.Entry(stack, 4); }
			@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("indexed provider"); }
		};
		var view = new InventoryMinecraftSources.View("same-controller-alias", List.of(), List.of(), access);
		InventorySourceAccess.Source source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> view,
			entry -> {
				var encoded = catalog.encode(entry.exemplar());
				return new InventoryDomainCodec.Item(encoded.key(), entry.amount(), encoded.display().label(), encoded.display().displayJson(), encoded.display().componentsStripped());
			}, (InventoryMinecraftSources.SourceWorld) null, null, 32768, catalog::tightenDisplay);
		assertEquals(32768, source.displayBytes());
		var before = source.read(0);
		assertTrue(utf8(before.displayJson()) > InventoryItemCodec.MAX_DISPLAY_BYTES, "the effective cap keeps the real component JSON");
		source.tightenDisplay(4096);
		assertEquals(4096, source.displayBytes());
		var tightened = source.read(0);
		assertEquals(before.key(), tightened.key(), "tightening never recomputes identity");
		assertEquals(4L, tightened.count());
		assertTrue(utf8(tightened.displayJson()) <= 4096);
		assertFalse(tightened.aggregateRequired(), "display-only tightening never folds exact identity");
		source.tightenDisplay(32768); // an increase is ignored
		assertEquals(4096, source.displayBytes());
		assertEquals(tightened.displayJson(), source.read(0).displayJson(), "raising the cap never resurrects the omitted component");
	}
	@Test void effectiveDisplayCapBoundaryRejectsBelowTheOneKiBLegalMinimum() {
		assertEquals(1024, InventoryItemCodec.minimumDisplayBytes(), "the legal effective minimum is the 1 KiB display cap");
		assertEquals(1024, InventoryMinecraftSources.requireDisplayBytes(1024));
		assertThrows(IllegalArgumentException.class, () -> InventoryMinecraftSources.requireDisplayBytes(1023));
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"), new UUID(7, 7), BlockFace.NORTH);
		var view = new InventoryMinecraftSources.View("boundary-alias", List.of(), List.of(), null);
		assertThrows(IllegalArgumentException.class, () -> new InventoryMinecraftSources.Source(input, view, () -> true, () -> view,
			entry -> { throw new AssertionError("cap boundary only"); }, (InventoryMinecraftSources.SourceWorld) null, null, 1023, ignored -> {}));
		InventorySourceAccess.Source source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> view,
			entry -> { throw new AssertionError("cap boundary only"); }, (InventoryMinecraftSources.SourceWorld) null, null, 1024, ignored -> {});
		assertEquals(1024, source.displayBytes());
		assertThrows(IllegalArgumentException.class, () -> source.tightenDisplay(1023));
		assertEquals(1024, source.displayBytes(), "a rejected tightening leaves the source ceiling unchanged");
		source.close();
	}
	@Test void snapshotCaptureAdoptsTheSourceCatalogueCeiling() {
		var world = new ChestWorld();
		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, 32768);
		var view = InventoryMinecraftSources.view(world, world.input, (pos, face) -> Optional.empty());
		var source = new InventoryMinecraftSources.Source(world.input, view, () -> true,
			() -> InventoryMinecraftSources.view(world, world.input, (pos, face) -> Optional.empty()),
			entry -> {
				if (entry.isEmpty()) return null;
				var encoded = catalog.encode(entry.exemplar());
				return new InventoryDomainCodec.Item(encoded.key(), entry.amount(), encoded.display().label(), encoded.display().displayJson(), encoded.display().componentsStripped());
			}, world, null, 32768, catalog::tightenDisplay);
		var snapshot = source.snapshotPlan().orElseThrow().capture().orElseThrow();
		try (snapshot) { assertEquals(32768, snapshot.displayBytes()); }
		source.tightenDisplay(4096);
		var tightened = source.snapshotPlan().orElseThrow().capture().orElseThrow();
		try (tightened) { assertEquals(4096, tightened.displayBytes()); }
	}
	static int utf8(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
}
