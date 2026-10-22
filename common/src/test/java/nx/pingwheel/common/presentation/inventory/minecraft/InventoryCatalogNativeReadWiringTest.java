package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySelection;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CaptureResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** UNRUN. Actual native source and lazy observation path, with the unchanged default memory cap. */
class InventoryCatalogNativeReadWiringTest {

	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	private static final class Chest extends ChestBlockEntity implements BaseContainerLockAccessor {
		int reads;
		Chest(BlockPos position, BlockState state) { super(position, state); setItem(0, new ItemStack(Items.STONE, 3)); }
		@Override public LockCode pingforit$inventoryLock() { return LockCode.NO_LOCK; }
		@Override public ItemStack getItem(int slot) { reads++; return super.getItem(slot); }
	}
	private static final class World implements InventoryMinecraftSources.SourceWorld {
		final Map<BlockPos, BlockState> states = new HashMap<>();
		final Map<BlockPos, Chest> chests = new HashMap<>();
		void add(BlockPos position, BlockState state) { states.put(position, state); chests.put(position, new Chest(position, state)); }
		@Override public boolean isLoaded(BlockPos pos) { return states.containsKey(pos); }
		@Override public BlockState blockState(BlockPos pos) { return states.get(pos); }
		@Override public BlockEntity blockEntity(BlockPos pos) { return chests.get(pos); }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
		@Override public HolderLookup.Provider registries() { return RegistryAccess.EMPTY; }
		@Override public boolean audited(net.minecraft.world.Container container) { return container instanceof Chest; }
		int reads() { return chests.values().stream().mapToInt(chest -> chest.reads).sum(); }
	}
	private static InventorySourceInput input() {
		return new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:chest"), new UUID(82, 1), BlockFace.NORTH);
	}
	private static InventoryMinecraftSources.Source source(World world, InventorySourceInput input, int cap) {
		var admitted = InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.empty());
		return new InventoryMinecraftSources.Source(input, admitted, () -> true,
			() -> InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.empty()),
			entry -> { throw new AssertionError("native source must not use Entry/exemplar compatibility conversion"); }, world, null, cap,
			ignored -> {}, ignored -> InventoryReprojectionSummary.EMPTY);
	}
	@ParameterizedTest @ValueSource(ints = {27, 54})
	void nativeDefaultQuerySelectAndFreshTrackingCaptureNeedNoCapRaise(int slots) {
		var world = new World();
		if (slots == 27) world.add(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
		else {
			var state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST).setValue(ChestBlock.TYPE, ChestType.LEFT);
			world.add(BlockPos.ZERO, state);
			world.add(BlockPos.ZERO.relative(ChestBlock.getConnectedDirection(state)), state.setValue(ChestBlock.TYPE, ChestType.RIGHT));
		}
		var settings = InventorySettings.serverDefaults(); assertEquals(16, settings.getPendingMemoryMiB());
		var input = input();
		try (var runtime = new InventoryRuntime((request, cap) -> Optional.of(source(world, request, cap)), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings);
			var preview = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
			var observed = new ArrayList<InventoryDomainCodec.Item>(); InventoryRuntime.Observation last = null;
			for (int call = 0; observed.size() < slots && call < slots; call++) {
				last = runtime.step(preview).orElseThrow(); observed.addAll(last.slots());
			}
			assertNotNull(last); assertEquals(slots, observed.size()); assertEquals(slots, world.reads());
			assertEquals(CaptureResult.Completeness.COMPLETE, last.result().completeness());
			assertEquals(CaptureResult.Consistency.VERIFIED, last.result().consistency()); assertFalse(observed.getFirst().aggregateRequired());
			var chosen = observed.getFirst();
			var selection = new InventorySelection(chosen.key(), chosen.key().itemId(), chosen.label(), chosen.displayJson(), false, "danger");
			assertTrue(runtime.selectionPresent(input, selection, 0)); assertEquals(slots + 1, world.reads(), "SELECT is a live witness, never the captured count");
			var tracking = runtime.attach(input, new InventoryRuntime.TrackingSubject(TargetKey.from(input.target()))).orElseThrow();
			InventoryRuntime.Observation tracked = null; int consumed = 0;
			for (int call = 0; consumed < slots && call < slots; call++) { tracked = runtime.step(tracking).orElseThrow(); consumed += tracked.slots().size(); }
			assertNotNull(tracked); assertEquals(slots, consumed); assertEquals(2 * slots + 1, world.reads(), "tracking captures afresh");
			assertEquals(CaptureResult.Completeness.COMPLETE, tracked.result().completeness());
			assertEquals(0, runtime.memory().reserved()); preview.close(); tracking.close();
		}
	}

	@Test void nativeLazySnapshotCannotUseCaptureScratchAfterItCloses() {
		var settings = InventorySettings.serverDefaults(); var world = new World(); world.add(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
		try (var runtime = new InventoryRuntime((request, cap) -> Optional.of(source(world, request, cap)), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(), new InventoryRuntime.PreviewSubject(input().readOwner())).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			assertEquals(27, world.reads()); assertEquals(0, runtime.memory().reserved());
			runtime.memory().setCap(runtime.memory().retained() + InventoryItemBudget.workspaceUpperBound(4096) - 1);
			assertTrue(runtime.step(consumer, 1).isEmpty()); assertEquals(27, world.reads()); assertEquals(0, runtime.memory().reserved());
			runtime.memory().setCap(settings.pendingMemoryBytes());
			var value = runtime.step(consumer, 1).orElseThrow().slots().getFirst();
			assertEquals(3, value.count()); assertFalse(value.aggregateRequired()); assertEquals(27, world.reads());
		}
	}

	@Test void cursorlessNativeEnumerationAndWitnessDebitTheSameCumulativeGrant() {
		var world = new World(); world.add(BlockPos.ZERO, Blocks.CHEST.defaultBlockState()); var input = input();
		int[] visits = {0};
		var access = new IPlatformInventoryService.Access() {
			@Override public Optional<String> alias() { return Optional.of("test:cursorless"); }
			@Override public boolean valid() { return true; }
			@Override public int slots() { return 0; }
			@Override public boolean stableCursor() { return false; }
			@Override public java.util.OptionalLong version() { return java.util.OptionalLong.empty(); }
			@Override public IPlatformInventoryService.Entry read(int slot) { throw new AssertionError("no Entry copy"); }
			@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> consumer) { throw new AssertionError("no Entry copy"); }
			@Override public IPlatformInventoryService.ResidentVisit observeResident(int limit, IPlatformInventoryService.ResidentVisitor visitor) {
				for (int index = 0; index < Math.min(limit, 2); index++) {
					visits[0]++;
					if (!visitor.visit(new IPlatformInventoryService.ResidentEntry(new InventoryResidentItem.Stack(new ItemStack(Items.STONE)), 3)))
						return new IPlatformInventoryService.ResidentVisit(index + 1, false);
				}
				return new IPlatformInventoryService.ResidentVisit(Math.min(limit, 2), limit >= 2);
			}
		};
		var view = new InventoryMinecraftSources.View("test:cursorless", List.of(), List.of(), access);
		try (var source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> view,
			entry -> { throw new AssertionError("no Entry copy"); }, world, null)) {
			var grant = new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(4096), 6 * InventoryCatalogAdmission.workUpperBound(4096));
			var page = source.enumerate(2, grant); assertTrue(page.complete()); assertEquals(2, visits[0]);
			long afterPage = grant.attemptedWork(); assertTrue(afterPage > 0);
			var item = page.slots().getFirst(); var selection = new InventorySelection(item.key(), item.key().itemId(), item.label(), item.displayJson(), false, "danger");
			assertTrue(source.observeSelection(2, selection, grant).present()); assertEquals(3, visits[0]);
			assertTrue(grant.attemptedWork() > afterPage, "native witness did not replace the caller's allowance");
		}
	}

	@Test void admittedNbtCounterRemainsLazyMappedIsolatedAndKeepsAuthoritativeLongAmount() {
		String schema = "test:admitted_counter_" + UUID.randomUUID().toString().replace("-", "");
		var order = new ArrayList<Integer>(); long amount = 9007199254740993L;
		assertTrue(InventorySnapshotSchemas.register(schema, (payload, slot, size, registries) -> {
			order.add(slot); assertEquals(3, size); var data = (CompoundTag) payload;
			long count = data.getLong("amount") + slot; data.putLong("amount", 0);
			return new IPlatformInventoryService.Entry(new ItemStack(Items.STONE), count);
		}));
		var layout = new InventorySnapshotLayout("test:admitted_counter", "north", BlockPos.ZERO, List.of(
			new InventorySnapshotLayout.Member(BlockPos.ZERO, "minecraft:chest", "minecraft:chest", "member", List.of(
				new InventorySnapshotLayout.Segment("Custom", schema, 3, List.of(2, 0))))));
		var payload = new CompoundTag(); payload.putLong("amount", amount);
		try (var snapshot = InventoryNbtSnapshot.create(layout, BlockPos.ZERO, List.of(payload), RegistryAccess.EMPTY,
			InventoryNbtSnapshot.memoryUpperBound(2, 1))) {
			assertTrue(order.isEmpty()); payload.putLong("amount", 0);
			var grant = new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(4096), 3 * InventoryCatalogAdmission.workUpperBound(4096));
			assertEquals(amount + 2, snapshot.read(0, grant).count());
			assertEquals(amount, snapshot.read(1, grant).count());
			assertEquals(amount + 2, snapshot.read(0, grant).count());
			assertEquals(List.of(2, 0, 2), order); assertEquals(amount, ((CompoundTag) snapshot.payload(0)).getLong("amount"));
			assertTrue(grant.attemptedWork() > 0);
		}
	}
}
