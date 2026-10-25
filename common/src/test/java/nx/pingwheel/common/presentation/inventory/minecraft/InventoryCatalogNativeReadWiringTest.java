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
import nx.pingwheel.common.presentation.inventory.InventoryStep;
import nx.pingwheel.common.presentation.inventory.InventoryBackend;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.config.IntLimit;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.presentation.source.CaptureResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual native source, freezer and shared runtime, with the unchanged default memory cap. */
class InventoryCatalogNativeReadWiringTest {
	@Test void indexedReadAndExactWitnessAcquireMetadataBeforeGetterAndFreezeBeforeProviderPostcheck() {
		for (boolean external : new boolean[] {false, true}) for (boolean witness : new boolean[] {false, true})
			for (boolean revoke : new boolean[] {false, true}) {
			var stack = new ItemStack(Items.STONE, 7);
			var name = net.minecraft.network.chat.Component.literal("getter-time");
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name);
			var expected = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY).encode(stack);
			var selection = new InventorySelection(expected.key(), "minecraft:stone", expected.display().label(), expected.display().displayJson(), false, "danger");
			var events = new ArrayList<String>(); boolean[] returned = {false}, valid = {true};
			var state = Blocks.BEEHIVE.defaultBlockState();
			var entity = new net.minecraft.world.level.block.entity.BeehiveBlockEntity(BlockPos.ZERO, state);
			var world = new InventoryMinecraftSources.SourceWorld() {
				@Override public boolean isLoaded(BlockPos pos) { return pos.equals(BlockPos.ZERO); }
				@Override public BlockState blockState(BlockPos pos) { return state; }
				@Override public BlockEntity blockEntity(BlockPos pos) { return entity; }
				@Override public boolean onServerThread() { return true; }
				@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
				@Override public HolderLookup.Provider registries() {
					events.add("registries");
					if (returned[0]) { stack.setCount(0); name.append("-registry"); }
					return RegistryAccess.EMPTY;
				}
			};
			var access = new IPlatformInventoryService.Access() {
				@Override public Optional<String> alias() { return Optional.of("test:resident-order"); }
				@Override public boolean valid() { return valid[0]; }
				@Override public int slots() { return 1; }
				@Override public boolean stableCursor() { return true; }
				@Override public java.util.OptionalLong version() { return java.util.OptionalLong.empty(); }
				@Override public IPlatformInventoryService.Entry read(int slot) { throw new AssertionError("no Entry copy"); }
				@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> consumer) { throw new AssertionError("indexed only"); }
				@Override public IPlatformInventoryService.ResidentEntry readResident(int slot) { throw new AssertionError("must enter immediate boundary"); }
				@Override public boolean consumeResident(int slot, IPlatformInventoryService.ResidentVisitor visitor) {
					events.add("getter"); returned[0] = true;
					boolean more = visitor.visit(new IPlatformInventoryService.ResidentEntry(new InventoryResidentItem.Stack(stack), 9007199254740993L));
					events.add("postcheck"); stack.setCount(0); name.append("-postcheck"); valid[0] = !revoke;
					return more;
				}
			};
			var input = external ? new InventorySourceInput(Target.ExternalBlockTarget.candidate("minecraft:overworld", "sable", "minecraft:beehive", "test", true),
				new UUID(82, 2), BlockFace.NORTH) : new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:beehive"), new UUID(82, 2), BlockFace.NORTH);
			InventorySourceAccess.Source source;
			if (external) {
				var descriptor = new nx.pingwheel.common.integration.externalblock.BlockReadSource((Target.ExternalBlockTarget) input.target(), "sable", "test",
					new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:beehive"), new nx.pingwheel.common.marker.MarkerAnchor(0, 0, 0));
				var binding = new InventoryMinecraftSources.ExternalBinding(world, descriptor, BlockPos.ZERO::equals, (pos, face) -> Optional.of(access));
				source = InventoryMinecraftSources.resolveExternal(input, () -> Optional.of(binding), () -> true,
					entry -> { throw new AssertionError("no compatibility codec"); }).orElseThrow();
			} else {
				var view = new InventoryMinecraftSources.View("test:resident-order", List.of(), List.of(), access);
				source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> view,
					entry -> { throw new AssertionError("no compatibility codec"); }, world, null);
			}
			events.clear();
			try (source) {
				if (revoke && witness && !external) assertFalse(source.matchesSelection(0, selection));
				else if (revoke) assertThrows(InventorySourceUnavailableException.class, () -> {
					if (witness) source.matchesSelection(0, selection); else source.read(0);
				});
				else if (witness) assertTrue(source.matchesSelection(0, selection), "exact witness must match getter-time patch");
				else {
					var item = source.read(0); assertNotNull(item); assertEquals(9007199254740993L, item.count());
					assertEquals(expected.key(), item.key()); assertFalse(item.displayJson().contains("postcheck")); assertFalse(item.displayJson().contains("registry"));
				}
			}
			assertEquals("registries", events.getFirst());
			int getter = events.indexOf("getter"); assertTrue(getter > 0);
			assertEquals(List.of("getter", "postcheck"), events.subList(getter, events.size()));
			assertEquals(0, stack.getCount()); assertEquals("getter-time-postcheck", name.getString(), "provider really reused count and mutable patch value");
		}
	}

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
		try (var runtime = new InventoryRuntime(new InventoryRuntime.SourceResolver() {
			@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput request) { return InventoryMinecraftSources.resolutionValidationCost(request); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput request, int cap) { throw new AssertionError("resolution dropped caller allowance"); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput request, int cap, InventoryValidationBudget.Allowance allowance) {
				allowance.debit(resolutionValidationCost(request)); return Optional.of(source(world, request, cap));
			}
		}, settings.pendingMemoryBytes())) {
			runtime.advance(0, settings);
			var preview = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
			var observed = new ArrayList<InventoryDomainCodec.Item>(); InventoryRuntime.Observation last = null;
			for (int call = 0; observed.size() < slots && call < slots; call++) {
				last = runtime.step(preview).orElseThrow(); observed.addAll(last.slots());
			}
			assertNotNull(last); assertEquals(slots, observed.size()); assertEquals(slots, world.reads());
			assertEquals(CaptureResult.Completeness.COMPLETE, last.result().completeness());
			assertEquals(CaptureResult.Consistency.EVENTUAL, last.result().consistency()); assertFalse(observed.getFirst().aggregateRequired());
			assertEquals(InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, last.inventorySweepEvidence());
			preview.retainCompleted(); assertEquals(Optional.of(true), runtime.probe(preview), "publication evidence stays admitted under the unchanged default memory cap");
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

	@Test void nativePreparationDoesNotReadAndEveryStepAcquiresNewScratch() {
		var settings = InventorySettings.serverDefaults(); var world = new World(); world.add(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
		try (var runtime = new InventoryRuntime((request, cap) -> Optional.of(source(world, request, cap)), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(), new InventoryRuntime.PreviewSubject(input().readOwner())).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			assertEquals(0, world.reads()); assertEquals(0, runtime.memory().reserved());
			runtime.memory().setCap(runtime.memory().retained() + InventoryItemBudget.workspaceUpperBound(4096) - 1);
			assertTrue(runtime.step(consumer, 1).isEmpty()); assertEquals(0, world.reads()); assertEquals(0, runtime.memory().reserved());
			runtime.memory().setCap(settings.pendingMemoryBytes());
			var value = runtime.step(consumer, 1).orElseThrow().slots().getFirst();
			assertEquals(3, value.count()); assertFalse(value.aggregateRequired()); assertEquals(1, world.reads());
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

	@NotNullByDefault
	private static final class LargeNative {
		static final long LARGE_AMOUNT = 9007199254740993L;
		final int slots;
		final net.minecraft.world.SimpleContainer resident;
		final List<Integer> visibleReads = new ArrayList<>(), physicalReads = new ArrayList<>();
		final World world = new World();
		boolean valid = true, throwsOnRead, adapter;
		long firstAmount = LARGE_AMOUNT;
		LargeNative(int slots, boolean diverse) {
			this.slots = slots; resident = new net.minecraft.world.SimpleContainer(slots);
			world.add(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
			for (int index = 0; index < slots; index++) {
				var stack = new ItemStack(Items.STONE);
				if (diverse) stack.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA, new net.minecraft.world.item.component.CustomModelData(index));
				resident.setItem(index, stack);
			}
			if (!diverse && slots > 1) resident.setItem(slots - 2, ItemStack.EMPTY); // visible slot 1 is blank
		}
		InventorySourceAccess.Source source(InventorySourceInput input, int ceiling) {
			var access = new IPlatformInventoryService.Access() {
				@Override public Optional<String> alias() { return Optional.of("test:large-native"); }
				@Override public boolean valid() { return LargeNative.this.valid; }
				@Override public int slots() { return slots; }
				@Override public boolean stableCursor() { return true; }
				@Override public java.util.OptionalLong version() { return java.util.OptionalLong.empty(); }
				@Override public IPlatformInventoryService.Entry read(int index) { throw new AssertionError("compatibility getter"); }
				@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("indexed source"); }
				@Override public boolean supportsDetachedSlotCapture() { throw new AssertionError("native mode must precede snapshot-plan discovery"); }
				@Override public boolean consumeResident(int index, IPlatformInventoryService.ResidentVisitor visitor) {
					visibleReads.add(index); physicalReads.add(slots - index - 1);
					if (throwsOnRead) throw new IllegalStateException("getter failed");
					ItemStack stack = resident.getItem(slots - index - 1);
					return visitor.visit(new IPlatformInventoryService.ResidentEntry(stack.isEmpty() ? null : new InventoryResidentItem.Stack(stack), stack.isEmpty() ? 0 : index == 0 ? firstAmount : stack.getCount()));
				}
			};
			// Fixed native Vault layout profile; native reads still enter the real resident freezer and catalogue.
			var layout = new InventorySnapshotLayout("test:large-native", "north", BlockPos.ZERO, List.of(
				new InventorySnapshotLayout.Member(BlockPos.ZERO, "create:item_vault", "create:item_vault", "member", List.of(
					new InventorySnapshotLayout.Segment("Items", "minecraft:items", slots, java.util.stream.IntStream.range(0, slots).boxed().toList())))));
			var view = new InventoryMinecraftSources.View("test:large-native", List.of(), List.of(), access, layout, false);
			return new InventoryMinecraftSources.Source(input, view, () -> true, () -> view,
				entry -> { throw new AssertionError("compatibility codec"); }, world, null, ceiling, ignored -> {}, ignored -> InventoryReprojectionSummary.EMPTY);
		}
		InventoryRuntime runtime(InventorySettings settings) {
			return new InventoryRuntime(new InventoryRuntime.SourceResolver() {
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int ceiling) { throw new AssertionError("missing allowance"); }
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int ceiling, InventoryValidationBudget.Allowance allowance) {
					var nativeSource = source(input, ceiling);
					if (!adapter) return Optional.of(nativeSource);
					// A replacement generic live adapter has no native sweep evidence, even at the same physical source key.
					return Optional.of(new InventorySourceAccess.Source() {
						@Override public nx.pingwheel.common.presentation.source.SourceKey key() { return nativeSource.key(); }
						@Override public boolean valid() { return nativeSource.valid(); }
						@Override public boolean stableCursor() { return true; }
						@Override public int slots() { return slots; }
						@Override public InventoryDomainCodec.@Nullable Item read(int slot) { return nativeSource.read(slot); }
						@Override public int displayBytes() { return ceiling; }
						@Override public InventoryReprojectionSummary reprojectionSummary(int cap) { return InventoryReprojectionSummary.EMPTY; }
						@Override public void close() { nativeSource.close(); }
					});
				}
			}, settings.pendingMemoryBytes());
		}
	}
	private static InventorySourceAccess.InventoryHandle open(LargeNative fixture) {
		var input = input(); var source = fixture.source(input, 4096); var access = new InventorySourceAccess(input, ignored -> Optional.of(source));
		var scope = new SourceAccess.ReadScope(input.viewKey(), java.util.Set.of("pingforit:inventory.items"));
		var resolved = (SourceAccess.ResolveResult.Available) access.resolve(nx.pingwheel.common.presentation.minecraft.MinecraftBlockReadSources.detached(input.target()), scope, ledger(0, 1));
		return (InventorySourceAccess.InventoryHandle) ((SourceAccess.OpenResult.Started) access.open(resolved.descriptor(), scope, ledger(0, 0))).handle();
	}
	private static CostLedger ledger(int slots, int probes) {
		return new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) slots, InventorySourceAccess.PROBES, (long) probes,
			InventorySourceAccess.PROVIDER_WORK, (slots + probes) * InventorySourceAccess.PROVIDER_CALL_WORK));
	}
	@Test void nativeTypedAndLegacyStepsShareOneCursorEmptyNeedsMetadataAndFailuresPayAttempts() {
		for (boolean empty : new boolean[] {false, true}) {
			var fixture = new LargeNative(empty ? 0 : 4, false);
			try (var handle = open(fixture)) {
				assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(new RetainedMemoryLedger(16 * 1024 * 1024)));
				assertTrue(fixture.visibleReads.isEmpty());
				var catalog = new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(4096), 10 * InventoryCatalogAdmission.workUpperBound(4096));
				var refused = ledger(0, 0); assertInstanceOf(InventoryStep.Outcome.Deferred.class, handle.stepInventory(refused, catalog)); assertTrue(fixture.visibleReads.isEmpty());
				var admitted = ledger(empty ? 0 : 1, 1);
				var first = ((InventoryStep.Outcome.Captured) handle.stepInventory(admitted, catalog)).step();
				assertEquals(empty ? 0 : 1, admitted.used(InventorySourceAccess.PHYSICAL)); assertEquals(1, admitted.used(InventorySourceAccess.PROBES));
				assertEquals(empty ? CaptureResult.Completeness.COMPLETE : CaptureResult.Completeness.CONTINUE, first.metadata().completeness());
				assertEquals(CaptureResult.Consistency.EVENTUAL, first.metadata().consistency()); assertEquals(InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, first.evidence());
				if (!empty) {
					var next = (SourceAccess.StepOutcome.Captured) handle.step(ledger(1, 1), catalog); assertEquals(2, next.result().coverage().scanned()); assertEquals(List.of(0, 1), fixture.visibleReads);
					fixture.throwsOnRead = true; var failing = ledger(2, 1);
					var failure = ((InventoryStep.Outcome.Captured) handle.stepInventory(failing, catalog)).step();
					assertEquals(CaptureResult.Availability.UNAVAILABLE, failure.metadata().availability()); assertTrue(failure.slots().isEmpty()); assertEquals(1, failing.used(InventorySourceAccess.PHYSICAL));
				}
			}
		}
	}
	@Test void nativeHardSlotBoundCannotReadBeyond4096() {
		var fixture = new LargeNative(4096, false); int reads = 0;
		try (var handle = open(fixture)) {
			var memory = new RetainedMemoryLedger(16 * 1024 * 1024); assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
			InventoryStep.@Nullable Outcome last = null;
			for (int page = 0; page < 64; page++) {
				var admission = new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(4096), 64 * InventoryCatalogAdmission.workUpperBound(4096));
				var budget = ledger(64, 1); last = handle.stepInventory(budget, admission); reads += Math.toIntExact(budget.used(InventorySourceAccess.PHYSICAL));
			}
			assertEquals(4096, reads); assertEquals(4096, fixture.visibleReads.size());
			assertEquals(CaptureResult.Completeness.COMPLETE, ((InventoryStep.Outcome.Captured) last).step().metadata().completeness());
			assertInstanceOf(InventoryStep.Outcome.Deferred.class, handle.stepInventory(ledger(64, 1), new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(4096), InventoryCatalogAdmission.workUpperBound(4096))));
			assertEquals(4096, fixture.visibleReads.size()); assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
		}
	}
	@Test void nativeReplacementHandoffKeepsSlowPeerPaidPrefixAndEvidenceUntilAccepted() {
		var settings = InventorySettings.serverDefaults(); var fixture = new LargeNative(4, false); var runtime = fixture.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(input().target()));
			var fast = runtime.attach(input(), subject).orElseThrow(); var slow = runtime.attach(input(), subject).orElseThrow();
			runtime.prepare(fast); runtime.prepare(slow);
			var original = runtime.step(fast).orElseThrow(); fast.retainCompleted(); assertEquals(original, runtime.step(slow).orElseThrow()); slow.retainCompleted();
			fast.restart(); slow.restart(); fixture.resident.setItem(0, new ItemStack(Items.GOLD_INGOT, 9)); runtime.advance(3, settings);
			var replacement = runtime.step(fast).orElseThrow(); assertEquals(9, replacement.slots().getLast().count()); fast.retainCompleted(); fast.restart();
			assertEquals(8, fixture.visibleReads.size()); assertEquals(Optional.of(true), runtime.probe(slow));
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(fast), "slow predecessor must finish its successor before another replacement forks");
			runtime.advance(6, settings); assertEquals(replacement, runtime.step(slow).orElseThrow()); slow.retainCompleted(); slow.restart();
			assertEquals(8, fixture.visibleReads.size(), "slow peer consumes the paid successor journal without a getter replay");
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(fast).orElseThrow().result().completeness()); assertEquals(12, fixture.visibleReads.size());
			assertEquals(0, runtime.memory().reserved()); fast.close(); slow.close();
		}
		assertEquals(0, runtime.memory().retained());
	}
	@Test void nativeTighteningRefreshesUniqueJournalDefinitionAndCapRefusalDoesNotAdvance() {
		var settings = InventorySettings.serverDefaults(); var fixture = new LargeNative(90, false);
		var stack = new ItemStack(Items.STONE); stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("x".repeat(1800)));
		for (int slot = 0; slot < 90; slot++) fixture.resident.setItem(slot, stack);
		var runtime = fixture.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var fast = runtime.attach(input(), new InventoryRuntime.PreviewSubject(input().readOwner())).orElseThrow();
			var slow = runtime.attach(input(), new InventoryRuntime.PreviewSubject(new UUID(83, 2))).orElseThrow(); runtime.prepare(fast); runtime.prepare(slow);
			var first = runtime.step(fast, 32).orElseThrow(); assertEquals(32, fixture.visibleReads.size());
			runtime.memory().setCap(runtime.memory().retained()); assertTrue(runtime.step(fast, 1).isEmpty()); assertEquals(32, fixture.visibleReads.size());
			assertEquals(InventoryRuntime.ReprojectionStatus.DEFERRED, runtime.reprojectDisplays(fast, 1024).status());
			runtime.memory().setCap(settings.pendingMemoryBytes());
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, runtime.reprojectDisplays(fast, 1024).status()); assertEquals(32, fixture.visibleReads.size());
			var replay = runtime.step(slow, 32).orElseThrow(); assertEquals(32, fixture.visibleReads.size());
			assertEquals(first.slots().getFirst().key(), replay.slots().getFirst().key()); assertEquals(first.slots().getFirst().count(), replay.slots().getFirst().count());
			assertTrue(replay.slots().stream().allMatch(item -> item.displayJson() == null || item.displayJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024));
			fixture.valid = false; assertEquals(Optional.of(false), runtime.probe(fast)); assertTrue(fast.invalidated()); assertTrue(slow.invalidated());
			assertEquals(0, runtime.memory().reserved());
		}
		assertEquals(0, runtime.memory().retained());
	}
	@Test void nativeEmptyFaceCompletesUnderExhaustedPhysicalAndLogicalAllowanceWithMetadataOnly() {
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(1)); settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		var full = new LargeNative(1, false); var empty = new LargeNative(0, false); var runtime = new InventoryRuntime((request, cap) -> Optional.of(
			(request.face() == BlockFace.NORTH ? full : empty).source(request, cap)), settings.pendingMemoryBytes());
		try (runtime) {
			runtime.advance(0, settings); var subject = new InventoryRuntime.PreviewSubject(input().readOwner());
			runtime.step(runtime.attach(input(), subject).orElseThrow()).orElseThrow(); assertEquals(1, full.visibleReads.size());
			var otherFace = new InventorySourceInput(input().target(), input().readOwner(), BlockFace.SOUTH);
			var result = runtime.step(runtime.attach(otherFace, subject).orElseThrow()).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, result.result().completeness()); assertEquals(0, result.result().coverage().expected().orElseThrow());
			assertEquals(InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, result.inventorySweepEvidence()); assertTrue(empty.visibleReads.isEmpty()); assertEquals(0, runtime.memory().reserved());
		}
		assertEquals(0, runtime.memory().retained());
	}

	@ParameterizedTest @ValueSource(ints = {540, 900})
	void largeNativeSweepSharesPhysicalPrefixButPaysEachConsumerAndSeesUnreadMutation(int slots) {
		var fixture = new LargeNative(slots, false); var settings = InventorySettings.serverDefaults();
		settings.setPhysicalSlotsPerTick(IntLimit.finite(7)); settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(11));
		var runtime = fixture.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings);
			var fast = runtime.attach(input(), new InventoryRuntime.PreviewSubject(input().readOwner())).orElseThrow();
			var slow = runtime.attach(input(), new InventoryRuntime.PreviewSubject(new UUID(83, 2))).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(fast));
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(slow)); assertTrue(fixture.visibleReads.isEmpty());
			var first = runtime.step(fast).orElseThrow(); assertEquals(7, first.slots().size()); assertEquals(LargeNative.LARGE_AMOUNT, first.slots().getFirst().count()); assertNull(first.slots().get(1));
			assertEquals(CaptureResult.Completeness.CONTINUE, first.result().completeness());
			fixture.resident.setItem(slots - 1, new ItemStack(Items.DIAMOND)); // retained earlier slot must stay stone
			fixture.resident.setItem(slots - 8, new ItemStack(Items.GOLD_INGOT, 9)); // next unread visible slot 7
			var fastItems = new ArrayList<InventoryDomainCodec.@Nullable Item>(first.slots());
			var slowItems = new ArrayList<InventoryDomainCodec.@Nullable Item>();
			long tick = 0; InventoryRuntime.@Nullable Observation last = first;
			for (; tick < 900 && (fastItems.size() < slots || slowItems.size() < slots); tick++) {
				runtime.advance(tick, settings); int before = fixture.visibleReads.size();
				if (fastItems.size() < slots) { var step = runtime.step(fast); if (step.isPresent()) { last = step.get(); fastItems.addAll(last.slots()); } }
				if (tick % 2 == 0 && slowItems.size() < slots) runtime.step(slow).ifPresent(step -> slowItems.addAll(step.slots()));
				assertTrue(fixture.visibleReads.size() - before <= 7, "actual getters exceeded the shared tick grant");
				assertEquals(0, runtime.memory().reserved());
			}
			assertEquals(slots, fastItems.size()); assertEquals(fastItems, slowItems); assertEquals(slots, fixture.visibleReads.size());
			assertEquals("minecraft:stone", fastItems.getFirst().key().itemId()); assertEquals("minecraft:gold_ingot", fastItems.get(7).key().itemId()); assertEquals(9, fastItems.get(7).count());
			assertEquals(java.util.stream.IntStream.range(0, slots).boxed().toList(), fixture.visibleReads);
			assertEquals(java.util.stream.IntStream.range(0, slots).map(index -> slots - index - 1).boxed().toList(), fixture.physicalReads, "visible witnesses use the provider's absolute indexed mapping");
			assertEquals(CaptureResult.Completeness.COMPLETE, last.result().completeness()); assertEquals(CaptureResult.Consistency.EVENTUAL, last.result().consistency());
			assertEquals(InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, last.inventorySweepEvidence());
			assertTrue(runtime.memory().retained() < 2 * 1024 * 1024, "duplicates must refund definition admission");
			fast.retainCompleted(); slow.retainCompleted();
			runtime.advance(++tick, settings);
			var chosen = fastItems.get(7); var selection = new InventorySelection(chosen.key(), chosen.key().itemId(), chosen.label(), chosen.displayJson(), false, "danger");
			assertTrue(runtime.selectionPresent(input(), selection, 7)); assertEquals(slots + 1, fixture.visibleReads.size());
			var tracking = runtime.attach(input(), new InventoryRuntime.TrackingSubject(TargetKey.from(input().target()))).orElseThrow();
			int tracked = 0;
			for (int calls = 0; calls < 300 && tracked < slots; calls++) {
				runtime.advance(++tick, settings); var step = runtime.step(tracking);
				if (step.isPresent()) { if (tracked == 0) assertEquals("minecraft:diamond", step.get().slots().getFirst().key().itemId()); tracked += step.get().slots().size(); }
			}
			assertEquals(slots, tracked); assertEquals(2 * slots + 1, fixture.visibleReads.size(), "tracking must start a distinct fresh physical round");
			fast.close(); slow.close(); tracking.close();
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}

	@Test void nativeCapacityIsExplicitIncompleteAndThrowingGetterRemainsCharged() {
		for (boolean throwing : new boolean[] {false, true}) {
			var settings = InventorySettings.serverDefaults(); var fixture = new LargeNative(300, true); fixture.throwsOnRead = throwing;
			var runtime = fixture.runtime(settings);
			try (runtime) {
				runtime.advance(0, settings); var consumer = runtime.attach(input(), new InventoryRuntime.PreviewSubject(input().readOwner())).orElseThrow();
				InventoryRuntime.@Nullable Observation last = null;
				for (int tick = 0; tick < 40; tick++) {
					runtime.advance(tick, settings); var result = runtime.step(consumer);
					if (result.isPresent()) { last = result.get(); if (last.result().completeness() != CaptureResult.Completeness.CONTINUE) break; }
					assertEquals(0, runtime.memory().reserved());
				}
				assertNotNull(last); assertEquals(CaptureResult.Completeness.INCOMPLETE, last.result().completeness());
				assertEquals(throwing ? CaptureResult.Availability.UNAVAILABLE : CaptureResult.Availability.READABLE, last.result().availability());
				assertEquals(throwing ? 1 : 257, fixture.visibleReads.size()); assertEquals(0, runtime.memory().reserved());
			}
			assertEquals(0, runtime.memory().retained());
		}
	}

	@NotNullByDefault
	private static final class BackendHost implements InventoryBackend.Host {
		final List<InventoryS2CPacket> packets = new ArrayList<>();
		final nx.pingwheel.common.marker.ServerMarkerStore store = new nx.pingwheel.common.marker.ServerMarkerStore(new nx.pingwheel.common.marker.MarkerIdSource());
		boolean blockData, permitted = true;
		long expiry = 300;
		@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return Optional.of(new InventoryBackend.Policy(100, permitted ? 1 : 2, permitted ? java.util.Set.of("entity_block") : java.util.Set.of())); }
		@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) { return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention")); }
		@Override public boolean annotationAllowed(InventorySourceInput input, String type) { return true; }
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			var type = nx.pingwheel.common.domain.TargetTypeCatalog.builtIn().findById(frozen.targetType()).orElseThrow();
			var rejected = admission.prepare(frozen.target(), type.id(), List.of(player));
			if (rejected != null) return new InventoryBackend.Created(null, rejected);
			return new InventoryBackend.Created(store.create(player, frozen.target(), type, type.defaultPingType(), new nx.pingwheel.common.marker.MarkerAnchor(0, 0, 0), 1, expiry, List.of(player)).marker(), null);
		}
		@Override public boolean knows(UUID player, nx.pingwheel.common.domain.MarkerId marker) { return store.allMarkers().stream().anyMatch(value -> value.id().equals(marker)); }
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public Optional<InventoryRuntime.DisplayReprojector> displayReprojector() {
			return Optional.of((display, cap) -> InventoryItemCodec.reproject(display, RegistryAccess.EMPTY, cap));
		}
		@Override public int encodedBytes(InventoryS2CPacket packet) {
			var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
			try { packet.write(buffer); return buffer.readableBytes(); } finally { buffer.release(); }
		}
		@Override public void send(UUID player, InventoryS2CPacket packet) {
			if (blockData && !packet.entries().isEmpty()) throw new IllegalStateException("withheld native data");
			var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
			try {
				packet.write(buffer); var decoded = InventoryS2CPacket.readSafe(buffer);
				assertFalse(decoded.isCorrupt()); assertFalse(buffer.isReadable()); packets.add(decoded);
			} finally { buffer.release(); }
		}
	}

	@NotNullByDefault
	private static final class NativeClient {
		final BackendHost host = new BackendHost();
		final List<nx.pingwheel.common.network.IPacket> requests = new ArrayList<>();
		final ClientInventory client = new ClientInventory(requests::add);
		final InventorySettings settings = InventorySettings.serverDefaults();
		int cursor;
		long epoch;
		void open(InventoryBackend backend) {
			client.presentationReset(100, 1);
			backend.handle(input().readOwner(), InventoryC2SPacket.hello(), 0, settings); drain();
			epoch = host.packets.getFirst().epoch();
			client.open(input().target(), BlockFace.NORTH, "entity_block");
			backend.handle(input().readOwner(), (InventoryC2SPacket) requests.getLast(), 0, settings); drain();
		}
		void drain() { for (; cursor < host.packets.size(); cursor++) client.accept(host.packets.get(cursor)); }
		void tick(InventoryBackend backend, int tick) { backend.tick(tick, settings); drain(); assertEquals(0, backend.runtime().memory().reserved()); }
		void select(InventoryBackend backend, int tick, String id) {
			var preview = client.preview(1);
			var chosen = preview.entries().stream().filter(entry -> entry.itemId().equals(id)).findFirst().orElseThrow();
			backend.handle(input().readOwner(), InventoryC2SPacket.select(epoch, 100, 1, 1, 1, preview.baselineId(), preview.statusRevision(), chosen.key(), "danger"), tick, settings);
			assertEquals(1, host.store.size()); client.markerCreated(MarkerSnapshot.from(host.store.allMarkers().getFirst())); drain();
			backend.handle(input().readOwner(), InventoryC2SPacket.close(epoch, 1).stamp(100, 1), tick, settings); drain();
		}
		void repair(InventoryBackend backend, int tick, boolean tracking) {
			backend.handle(input().readOwner(), tracking ? InventoryC2SPacket.resync(epoch, 0, host.store.allMarkers().getFirst().id()).stamp(100, 1)
				: InventoryC2SPacket.resync(epoch, 1).stamp(100, 1), tick, settings);
		}
		@Unmodifiable List<InventoryS2CPacket> since(int start) { return List.copyOf(host.packets.subList(start, host.packets.size())); }
	}

	@Test void nativeEncodedMixedTickPreviewFreshTrackingZeroAndCompletionPlusPeriodCadence() {
		var f = new NativeClient(); var nativeSource = new LargeNative(8, false);
		f.settings.getPreview().setPeriodTicks(1); f.settings.setPhysicalSlotsPerTick(IntLimit.finite(2));
		var runtime = nativeSource.runtime(f.settings);
		try (var backend = new InventoryBackend(f.host, runtime, 1)) {
			f.open(backend); f.tick(backend, 0);
			assertEquals(InventoryS2CPacket.Status.UPDATING, f.client.preview(1).status()); assertFalse(f.client.preview(1).completeScan());
			assertEquals(LargeNative.LARGE_AMOUNT, f.client.preview(1).entries().getFirst().count());
			nativeSource.firstAmount += 100; // Already-read slot stays at its old amount in this sweep.
			nativeSource.resident.setItem(5, new ItemStack(Items.GOLD_INGOT, 9)); // Unread visible slot 2 observes this later tick.
			for (int tick = 1; tick <= 3; tick++) f.tick(backend, tick);
			var preview = f.client.preview(1); assertTrue(preview.completeScan()); assertEquals(InventoryS2CPacket.Status.READY, preview.status());
			assertEquals(LargeNative.LARGE_AMOUNT + 5, preview.entries().stream().filter(entry -> entry.itemId().equals("minecraft:stone")).findFirst().orElseThrow().count());
			assertEquals(9, preview.entries().stream().filter(entry -> entry.itemId().equals("minecraft:gold_ingot")).findFirst().orElseThrow().count());
			assertTrue(preview.entries().stream().allMatch(entry -> entry.quality() == InventoryS2CPacket.Status.READY));
			f.repair(backend, 4, false); f.tick(backend, 4);
			assertEquals(InventoryS2CPacket.Status.READY, f.client.preview(1).status()); assertEquals(8, nativeSource.visibleReads.size(), "corrective replay uses retained sweep evidence");
			f.select(backend, 5, "minecraft:stone"); assertEquals(9, nativeSource.visibleReads.size(), "SELECT witnesses live once");
			nativeSource.firstAmount += 100;
			var marker = f.host.store.allMarkers().getFirst();
			for (int tick = 6; tick <= 8; tick++) {
				f.tick(backend, tick); assertTrue(f.client.tracking(marker.id()).entries().isEmpty(), "first tracking never publishes preview or partial totals");
			}
			f.tick(backend, 9);
			var tracked = f.client.tracking(marker.id()); assertTrue(tracked.complete()); assertFalse(tracked.grey());
			assertEquals(InventoryS2CPacket.Status.READY, tracked.status()); assertEquals(1, tracked.entries().size());
			assertEquals(LargeNative.LARGE_AMOUNT + 205, tracked.entries().getFirst().count()); assertEquals(InventoryS2CPacket.Status.READY, tracked.entries().getFirst().quality());
			assertEquals(17, nativeSource.visibleReads.size());
			f.repair(backend, 10, true); f.tick(backend, 10); f.tick(backend, 11);
			assertEquals(17, nativeSource.visibleReads.size(), "next physical sweep starts at completion plus existing period, not at a global period boundary");
			assertEquals(InventoryS2CPacket.Status.READY, f.client.tracking(marker.id()).status());
			assertNotEquals(tracked.baselineId(), f.client.tracking(marker.id()).baselineId(), "RESYNC rebaselines retained evidence, not the source");
			for (int slot = 0; slot < 8; slot++) nativeSource.resident.setItem(slot, new ItemStack(Items.GOLD_INGOT, 2));
			for (int tick = 12; tick <= 14; tick++) {
				f.tick(backend, tick); assertEquals(tracked.entries().getFirst().count(), f.client.tracking(marker.id()).entries().getFirst().count(), "last completed count stays while replacement is partial");
			}
			f.tick(backend, 15); var absent = f.client.tracking(marker.id());
			assertTrue(absent.complete()); assertFalse(absent.grey()); assertEquals(0, absent.entries().getFirst().count());
			assertEquals("minecraft:stone", absent.entries().getFirst().itemId()); assertEquals(1, absent.entries().size(), "unselected gold is never copied into tracking");
			assertEquals(InventoryS2CPacket.Status.READY, absent.entries().getFirst().quality());
			int reads = nativeSource.visibleReads.size(); f.tick(backend, 300); f.tick(backend, 330);
			assertEquals(reads, nativeSource.visibleReads.size()); assertEquals(300, marker.expiresAtTick()); assertFalse(backend.tracksInventory(marker.id()));
		}
		assertEquals(0, runtime.memory().retained());
	}

	@Test void nativeEncodedEmptyViewIsReadyAndCompleteWithoutAGetter() {
		var f = new NativeClient(); var nativeSource = new LargeNative(0, false); var runtime = nativeSource.runtime(f.settings);
		try (var backend = new InventoryBackend(f.host, runtime, 1)) {
			f.open(backend); f.tick(backend, 0);
			assertEquals(InventoryS2CPacket.Status.READY, f.client.preview(1).status()); assertTrue(f.client.preview(1).completeScan()); assertTrue(f.client.preview(1).entries().isEmpty());
			f.repair(backend, 1, false); f.tick(backend, 1);
			assertEquals(InventoryS2CPacket.Status.READY, f.client.preview(1).status()); assertTrue(f.client.preview(1).completeScan()); assertTrue(nativeSource.visibleReads.isEmpty());
		}
		assertEquals(0, runtime.memory().retained());
	}

	@Test void nativePartialCorrectiveReplayStaysUpdatingWithoutRestartingThePhysicalCursor() {
		var f = new NativeClient(); var nativeSource = new LargeNative(4, false);
		f.settings.setPhysicalSlotsPerTick(IntLimit.finite(1)); f.settings.getPreview().setPeriodTicks(1);
		try (var backend = new InventoryBackend(f.host, nativeSource.runtime(f.settings), 1)) {
			f.open(backend); f.tick(backend, 0); long baseline = f.client.preview(1).baselineId();
			f.repair(backend, 1, false); f.tick(backend, 1);
			assertEquals(InventoryS2CPacket.Status.UPDATING, f.client.preview(1).status()); assertFalse(f.client.preview(1).completeScan());
			assertEquals(baseline, f.client.preview(1).baselineId()); assertEquals(List.of(0, 1), nativeSource.visibleReads);
			f.tick(backend, 2); f.tick(backend, 3);
			assertEquals(InventoryS2CPacket.Status.READY, f.client.preview(1).status()); assertTrue(f.client.preview(1).completeScan());
			assertEquals(List.of(0, 1, 2, 3), nativeSource.visibleReads);
		}
	}

	@Test void nativeReadyCompletionStillWaitsForEveryEncodedPreviewPartAtTheClient() {
		var f = new NativeClient(); var nativeSource = new LargeNative(3, false);
		nativeSource.resident.setItem(0, new ItemStack(Items.GOLD_INGOT, 9));
		try (var backend = new InventoryBackend(f.host, nativeSource.runtime(f.settings), 1)) {
			f.open(backend); backend.tick(0, f.settings); var held = new ArrayList<InventoryS2CPacket>();
			for (; f.cursor < f.host.packets.size(); f.cursor++) {
				var packet = f.host.packets.get(f.cursor);
				if (packet.entries().stream().anyMatch(entry -> entry.itemId().equals("minecraft:gold_ingot"))) held.add(packet);
				else f.client.accept(packet);
			}
			assertEquals(1, held.size()); assertFalse(f.client.preview(1).completeScan(), "sender completion/control is not receipt of a withheld part");
			held.forEach(f.client::accept); assertTrue(f.client.preview(1).completeScan());
			assertEquals(InventoryS2CPacket.Status.READY, f.client.preview(1).status()); assertEquals(2, f.client.preview(1).entries().size());
		}
	}

	@Test void nativeRetainedEvidenceSurvivesDisplayRebaselineWithoutRescanningOrRestoringStrippedDetail() {
		for (boolean tracking : new boolean[] {false, true}) {
			var f = new NativeClient(); var nativeSource = new LargeNative(2, false);
			var stack = new ItemStack(Items.STONE); stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("x".repeat(1800)));
			for (int slot = 0; slot < 2; slot++) nativeSource.resident.setItem(slot, stack);
			f.settings.getTracking().setPeriodTicks(1000);
			try (var backend = new InventoryBackend(f.host, nativeSource.runtime(f.settings), 1)) {
				f.open(backend); f.tick(backend, 0);
				if (tracking) { f.select(backend, 1, "minecraft:stone"); f.tick(backend, 2); }
				var marker = tracking ? Optional.of(f.host.store.allMarkers().getFirst().id()) : Optional.<nx.pingwheel.common.domain.MarkerId>empty();
				long oldBaseline = marker.isPresent() ? f.client.tracking(marker.get()).baselineId() : f.client.preview(1).baselineId();
				int reads = nativeSource.visibleReads.size();
				f.settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(.25));
				for (int tick = 3; tick <= 8; tick++) f.tick(backend, tick);
				var entries = marker.isPresent() ? f.client.tracking(marker.get()).entries() : f.client.preview(1).entries();
				assertEquals(1, entries.size()); assertEquals(LargeNative.LARGE_AMOUNT + 1, entries.getFirst().count());
				assertEquals(InventoryS2CPacket.Status.READY, entries.getFirst().quality());
				assertTrue(entries.getFirst().displayJson() == null || entries.getFirst().displayJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024);
				assertEquals(InventoryS2CPacket.Status.READY, marker.isPresent() ? f.client.tracking(marker.get()).status() : f.client.preview(1).status());
				assertNotEquals(oldBaseline, marker.isPresent() ? f.client.tracking(marker.get()).baselineId() : f.client.preview(1).baselineId());
				assertEquals(reads, nativeSource.visibleReads.size(), "size rebaseline retains scoped native evidence without another getter");
				f.settings.setDisplayByteMultiplier(java.math.BigDecimal.ONE); f.tick(backend, 9);
				assertEquals(entries, marker.isPresent() ? f.client.tracking(marker.get()).entries() : f.client.preview(1).entries(), "a raised cap cannot resurrect retained display detail");
			}
		}
	}

	@Test void nativeEncodedAggregateKeepsComponentTooLongQualityThroughCompletionSelectionAndResync() {
		var f = new NativeClient(); var nativeSource = new LargeNative(2, false);
		nativeSource.resident.setItem(0, new ItemStack(Items.STONE));
		var rejected = new ItemStack(Items.STONE); var payload = new CompoundTag(); payload.putString("data", "\u0800".repeat(23000));
		rejected.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(payload)); nativeSource.resident.setItem(1, rejected);
		try (var backend = new InventoryBackend(f.host, nativeSource.runtime(f.settings), 1)) {
			f.open(backend); f.tick(backend, 0); var preview = f.client.preview(1);
			assertTrue(preview.completeScan()); assertEquals(InventoryS2CPacket.Status.READY, preview.status()); assertEquals(1, preview.entries().size());
			assertEquals(InventoryS2CPacket.Status.COMPONENT_TOO_LONG, preview.entries().getFirst().quality()); assertTrue(preview.entries().getFirst().fallback());
			assertEquals(LargeNative.LARGE_AMOUNT + 1, preview.entries().getFirst().count());
			f.select(backend, 1, "minecraft:stone"); f.tick(backend, 2); var marker = f.host.store.allMarkers().getFirst().id();
			assertEquals(InventoryS2CPacket.Status.READY, f.client.tracking(marker).status()); assertTrue(f.client.tracking(marker).complete());
			assertEquals(InventoryS2CPacket.Status.COMPONENT_TOO_LONG, f.client.tracking(marker).entries().getFirst().quality());
			f.repair(backend, 3, true); f.tick(backend, 3);
			assertEquals(LargeNative.LARGE_AMOUNT + 1, f.client.tracking(marker).entries().getFirst().count());
			assertEquals(InventoryS2CPacket.Status.COMPONENT_TOO_LONG, f.client.tracking(marker).entries().getFirst().quality());
		}
	}

	@Test void nativeEncodedFailureAndCategoryCapacityCannotManufactureTrackingZero() {
		for (boolean throwing : new boolean[] {false, true}) {
			var f = new NativeClient(); var nativeSource = new LargeNative(300, false); var runtime = nativeSource.runtime(f.settings);
			try (var backend = new InventoryBackend(f.host, runtime, 1)) {
				f.open(backend); int tick = 0;
				for (; tick < 70 && !f.client.preview(1).completeScan(); tick++) f.tick(backend, tick);
				assertTrue(f.client.preview(1).completeScan()); f.select(backend, tick++, "minecraft:stone");
				var marker = f.host.store.allMarkers().getFirst();
				for (int slot = 0; slot < 300; slot++) {
					var stack = new ItemStack(Items.GOLD_INGOT); stack.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA, new net.minecraft.world.item.component.CustomModelData(slot));
					nativeSource.resident.setItem(slot, stack);
				}
				nativeSource.throwsOnRead = throwing;
				for (int end = tick + 65; tick < end; tick++) f.tick(backend, tick);
				var view = f.client.tracking(marker.id()); assertFalse(view.complete()); assertTrue(view.entries().isEmpty());
				assertEquals(throwing ? InventoryS2CPacket.Status.INVALID : InventoryS2CPacket.Status.INCOMPLETE, view.status());
				assertTrue(f.host.packets.stream().filter(packet -> marker.id().equals(packet.markerId())).allMatch(packet -> packet.entries().isEmpty()), "no zero or partial count after failed/incomplete native sweeps");
			}
			assertEquals(0, runtime.memory().retained());
		}
	}

	@Test void nativeQueuedReadyIsWithdrawnOnInvalidityOrPermissionRevocationAndGenericRecoveryStaysUncertain() {
		for (boolean revoke : new boolean[] {false, true}) {
			var f = new NativeClient(); var nativeSource = new LargeNative(4, false); var runtime = nativeSource.runtime(f.settings);
			try (var backend = new InventoryBackend(f.host, runtime, 1)) {
				f.open(backend); f.tick(backend, 0); f.select(backend, 1, "minecraft:stone");
				var marker = f.host.store.allMarkers().getFirst(); f.host.blockData = true; f.tick(backend, 2);
				assertTrue(f.client.tracking(marker.id()).entries().isEmpty()); int start = f.host.packets.size();
				if (revoke) f.host.permitted = false; else nativeSource.valid = false;
				f.host.blockData = false; f.tick(backend, 3);
				assertTrue(f.since(start).stream().allMatch(packet -> packet.entries().isEmpty()), "no queued READY count crosses the new fence");
				if (!revoke) {
					assertEquals(InventoryS2CPacket.Status.INVALID, f.client.tracking(marker.id()).status());
					nativeSource.valid = true; nativeSource.adapter = true;
					for (int slot = 0; slot < 4; slot++) nativeSource.resident.setItem(slot, ItemStack.EMPTY);
					for (int tick = 6; tick <= 9; tick++) f.tick(backend, tick);
					var recovered = f.client.tracking(marker.id()); assertTrue(recovered.complete(), () -> recovered + " packets=" + f.since(start));
					assertEquals(0, recovered.entries().getFirst().count()); assertEquals(InventoryS2CPacket.Status.UNCERTAIN, recovered.status());
					assertEquals(InventoryS2CPacket.Status.UNCERTAIN, recovered.entries().getFirst().quality());
					f.repair(backend, 10, true); f.tick(backend, 10); assertEquals(InventoryS2CPacket.Status.UNCERTAIN, f.client.tracking(marker.id()).status());
				}
			}
			assertEquals(0, runtime.memory().retained());
		}
	}

	@ParameterizedTest @ValueSource(ints = {540, 900})
	void backendLargeNativePreviewFinishesReadyWithinReferenceLifetime(int slots) {
		var f = new NativeClient(); var fixture = new LargeNative(slots, false); var runtime = fixture.runtime(f.settings);
		try (var backend = new InventoryBackend(f.host, runtime, 1)) {
			f.open(backend);
			int tick = 0;
			for (; tick < 80 && !f.client.preview(1).completeScan(); tick++) f.tick(backend, tick);
			assertTrue(tick < 80, "native preview must complete before its existing reference deadline");
			assertEquals(slots, fixture.visibleReads.size());
			var preview = f.client.preview(1); assertEquals(InventoryS2CPacket.Status.READY, preview.status());
			assertEquals(LargeNative.LARGE_AMOUNT + slots - 2, preview.entries().getFirst().count());
			assertEquals(InventoryS2CPacket.Status.READY, preview.entries().getFirst().quality());
			assertEquals(slots, fixture.visibleReads.size(), "publication must not replay source getters");
			f.select(backend, tick++, "minecraft:stone");
			assertEquals(slots + 1, fixture.visibleReads.size(), "SELECT must acquire a fresh live absolute-slot witness");
			fixture.resident.setItem(slots - 1, new ItemStack(Items.GOLD_INGOT));
			var marker = f.host.store.allMarkers().getFirst().id();
			for (int calls = 0; calls < 50 && !f.client.tracking(marker).complete(); calls++) f.tick(backend, tick++);
			assertEquals(2 * slots + 1, fixture.visibleReads.size(), "new tracking reads a separate complete native round");
			var tracked = f.client.tracking(marker); assertTrue(tracked.complete()); assertEquals(InventoryS2CPacket.Status.READY, tracked.status());
			assertEquals(1, tracked.entries().size()); assertEquals(slots - 2, tracked.entries().getFirst().count());
			assertEquals(InventoryS2CPacket.Status.READY, tracked.entries().getFirst().quality());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
}
