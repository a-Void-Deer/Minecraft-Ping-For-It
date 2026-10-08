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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
		var target = input.target(); var resolved = (SourceAccess.ResolveResult.Available) access.resolve(new PresentationAdapter.DetachedTarget(target.dimensionId(), "block", target.blockRegistryId(), target.x(), target.y(), target.z(), ""), scope, grant(0));
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
			backend.tick(0, settings);
			var first = host.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			assertTrue(first.completeScan(), "all 54 frozen slots have been consumed before the batch is queued");
			assertTrue(first.partCount() > host.sent.stream().filter(p -> !p.entries().isEmpty()).count(), "the encoded multipart batch cannot fit one period");
			assertEquals(2, world.saves()); assertEquals(0, world.reads());
			world.states.remove(world.first); world.states.put(world.hit, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.SINGLE));
			assertTrue(runtime.validate(world.input), "a fresh wrapper admits the still-present original hit half as a legal single chest");
			int start = host.sent.size(), resolutions = world.resolutions.get(); backend.tick(5, settings);
			var revoked = host.sent.subList(start, host.sent.size());
			assertTrue(revoked.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.status() == InventoryS2CPacket.Status.INVALID && p.statusRevision() > first.statusRevision()));
			assertTrue(revoked.stream().allMatch(p -> p.entries().isEmpty()), "no pending/admitted old-pair fragment survives the publication gate");
			assertEquals(resolutions, world.resolutions.get(), "terminal publication must retain old source evidence rather than resolve a fresh single");
			backend.tick(10, settings); assertTrue(host.sent.subList(start, host.sent.size()).stream().allMatch(p -> p.entries().isEmpty()));
			assertEquals(2, world.saves()); assertEquals(0, world.reads(), "validity does not save NBT or read Container items");
			assertEquals(world.hit.getX(), world.input.target().x()); assertEquals(BlockFace.NORTH, world.input.face());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void normalContentChangesDoNotRebaseOrRecaptureAStableCrossPeriodDoubleChestPreview() {
		ChestWorld world = new ChestWorld(); var host = new PreviewHost(world); var settings = InventorySettings.serverDefaults();
		settings.getPreview().setClientByteMultiplier(ByteMultiplier.finite(new java.math.BigDecimal("0.25")));
		try (var backend = new InventoryBackend(host, new InventoryRuntime(i -> Optional.of(world.resolve(i)), 16_000_000), 1)) {
			backend.handle(world.input.readOwner(), InventoryC2SPacket.hello(), 0, settings);
			backend.handle(world.input.readOwner(), InventoryC2SPacket.open(host.epoch(), 100, 1, 1, world.input.target(), world.input.face()), 0, settings); backend.tick(0, settings);
			var first = host.sent.stream().filter(p -> !p.entries().isEmpty()).findFirst().orElseThrow();
			assertTrue(first.partCount() > 1);
			for (Chest chest : world.chests.values()) { chest.clearContent(); chest.setItem(0, new ItemStack(Items.STONE, 31)); chest.reads = 0; }
			for (int now = 5; now <= 60; now += 5) backend.tick(now, settings);
			var parts = host.sent.stream().filter(p -> !p.entries().isEmpty()).toList();
			assertEquals(first.partCount(), parts.size(), "stable multipart completes instead of restarting each period");
			assertTrue(parts.stream().allMatch(p -> p.baselineId() == first.baselineId() && p.statusRevision() == first.statusRevision()));
			assertTrue(parts.stream().flatMap(p -> p.entries().stream()).allMatch(e -> e.count() == 2), "the completed detached observation is unchanged by live contents");
			assertTrue(host.sent.stream().noneMatch(p -> p.status() == InventoryS2CPacket.Status.INVALID));
			assertEquals(2, world.saves()); assertEquals(0, world.reads());
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
					@Override public InventorySourceAccess.SnapshotEvidence evidence() { return InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED; }
				});
			})), 16_000_000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
			mapping[0] = "axis-Z"; assertTrue(runtime.validate(input)); assertEquals(Optional.of(false), runtime.probe(consumer));
			assertTrue(consumer.invalidated()); assertEquals(1, captures.get());
		}
	}
}
