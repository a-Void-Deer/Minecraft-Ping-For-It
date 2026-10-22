package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.LockCode;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.mixin.BaseContainerLockAccessor;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** UNRUN: source-owned resident capture, independent of BE serialization. */
class InventoryMinecraftSnapshotCaptureTest {
	private static final UUID OWNER = new UUID(4, 4);
	private static final BlockPos FIRST = BlockPos.ZERO;

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void normalLockLootUnloadAndTopologyLossDoNotProduceWarnings() {
		World world = new World(); Chest chest = world.addChest(FIRST, Blocks.CHEST.defaultBlockState(), Items.STONE, 1);
		var input = input("minecraft:chest", FIRST); var admitted = view(world, input);
		try (var capture = new InventoryMinecraftDiagnosticsTest.Capture(); var source = source(world, input, admitted, () -> view(world, input))) {
			chest.lock = new LockCode("locked"); assertFalse(source.valid()); chest.lock = LockCode.NO_LOCK;
			chest.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse("minecraft:chests/simple_dungeon")));
			assertFalse(source.valid()); chest.setLootTable(null);
			world.loaded.clear(); assertFalse(source.valid()); world.loaded.add(FIRST);
			world.blocks.put(FIRST, Blocks.DIRT.defaultBlockState()); assertFalse(source.valid());
			assertTrue(capture.events.isEmpty()); assertEquals(0, chest.itemReads);
		}
	}

	@Test void throwingFrozenAuthorityCallbackReportsItsCauseBeforeCaptureBecomesUnavailable() {
		World world = new World(); boolean[] broken = {false};
		Chest chest = new Chest(FIRST, Blocks.CHEST.defaultBlockState()) {
			@Override public LockCode pingforit$inventoryLock() {
				if (broken[0]) throw new IllegalStateException("lock accessor failed", new RuntimeException("lock accessor cause"));
				return super.pingforit$inventoryLock();
			}
		};
		chest.setItem(0, new ItemStack(Items.STONE)); world.put(FIRST, Blocks.CHEST.defaultBlockState(), chest);
		var input = input("minecraft:chest", FIRST); var admitted = view(world, input);
		try (var capture = new InventoryMinecraftDiagnosticsTest.Capture(); var source = source(world, input, admitted, () -> view(world, input))) {
			var plan = source.snapshotPlan().orElseThrow(); chest.afterRead = () -> broken[0] = true;
			assertThrows(InventorySourceUnavailableException.class, plan::capture);
			assertEquals(1, chest.itemReads); assertEquals(1, plan.attemptedPhysicalReads());
			assertEquals(1, capture.events.size()); assertTrue(capture.message().contains("lock accessor failed"));
			assertTrue(capture.message().contains("lock accessor cause")); assertTrue(capture.message().contains("inventory-snapshot-authority"));
		}
	}

	@Test void singleChestReadsEveryPhysicalSlotWithoutSavingOrCollectingComponents() {
		World world = new World();
		Chest first = world.addChest(FIRST, Blocks.CHEST.defaultBlockState(), Items.DIAMOND, 6);
		InventorySourceInput input = input("minecraft:chest", FIRST);
		InventoryMinecraftSources.View view = view(world, input);
		InventoryMinecraftSources.Source source = source(world, input, view, () -> view(world, input));

		assertTrue(source.valid(), "view resolution and revalidation do not serialize the chest");
		assertEquals("minecraft:diamond", source.read(0).key().itemId(), "the live selection witness still reads normally");
		assertEquals(0, first.saves.get(), "live selection must not save custom NBT");
		assertEquals(1, first.itemReads, "the live witness is a real Container.getItem read");

		InventorySourceAccess.SnapshotPlan plan = source.snapshotPlan().orElseThrow();
		assertEquals(0, first.saves.get(), "snapshot plan discovery remains lazy");
		InventoryObservationSnapshot snapshot = (InventoryObservationSnapshot) plan.capture().orElseThrow();
		try (snapshot) {
			assertEquals(27, snapshot.slots());
			assertEquals(0, first.saves.get(), "resident capture must not serialize the BE");
			assertEquals(0, first.nonEmptySaves.get());
			assertEquals(0, first.implicitComponentCollections.get(), "custom NBT save does not collect implicit data components");
			assertEquals(28, first.itemReads, "capture visits all 27 physical slots, including empties, after the live witness");
			assertEquals(InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED, snapshot.evidence());
			assertEquals("minecraft:diamond", snapshot.read(0).key().itemId());
			assertEquals(6L, snapshot.read(0).count());
			assertNull(snapshot.read(1));
			assertEquals(28, first.itemReads, "reading the detached snapshot never goes back to the container");
		}
	}

	@Test void frozenAuthorityRechecksEveryFrozenMemberWithoutRebuildingTheViewPerSlot() {
		// A per-slot full view reacquisition used to scale the derivative
		// validation with visible slots. The frozen authority rechecks the
		// resolved member set and owning face witness; only the fixed before/after
		// binding validation may reacquire. Single and double chest must therefore
		// show the same fixed reacquisition count while every slot is still read.
		World single = new World();
		Chest one = single.addChest(FIRST, Blocks.CHEST.defaultBlockState(), Items.STONE, 1);
		InventorySourceInput singleInput = input("minecraft:chest", FIRST);
		AtomicInteger singleRebuilds = new AtomicInteger();
		InventoryMinecraftSources.Source singleSource = source(single, singleInput, view(single, singleInput),
			() -> { singleRebuilds.incrementAndGet(); return view(single, singleInput); });
		try (var snapshot = (InventoryObservationSnapshot) singleSource.snapshotPlan().orElseThrow().capture().orElseThrow()) {
			assertEquals(27, snapshot.slots());
			assertEquals(1L, snapshot.read(0).count());
		}
		assertEquals(27, one.itemReads);

		World doubled = doubleChestWorld();
		BlockPos secondPos = doubled.blocks.keySet().stream().filter(pos -> !pos.equals(FIRST)).findFirst().orElseThrow();
		Chest firstHalf = (Chest) doubled.blockEntities.get(FIRST);
		Chest secondHalf = (Chest) doubled.blockEntities.get(secondPos);
		InventorySourceInput doubleInput = input("minecraft:chest", FIRST);
		AtomicInteger doubleRebuilds = new AtomicInteger();
		InventoryMinecraftSources.Source doubleSource = source(doubled, doubleInput, view(doubled, doubleInput),
			() -> { doubleRebuilds.incrementAndGet(); return view(doubled, doubleInput); });
		try (var snapshot = (InventoryObservationSnapshot) doubleSource.snapshotPlan().orElseThrow().capture().orElseThrow()) {
			assertEquals(54, snapshot.slots());
		}
		assertEquals(27, firstHalf.itemReads); assertEquals(27, secondHalf.itemReads);
		assertEquals(singleRebuilds.get(), doubleRebuilds.get(),
			"frozen authority must not reacquire the view once per visible slot");
		assertTrue(doubleRebuilds.get() <= 8, "only the fixed before/after binding validation may reacquire");
	}

	@Test void doubleChestCapturesBothMembersInCanonicalOrderWithIndependentLocalSlotZero() {
		World world = new World();
		BlockState leftState = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST)
			.setValue(ChestBlock.TYPE, ChestType.LEFT);
		BlockPos secondPos = FIRST.relative(ChestBlock.getConnectedDirection(leftState));
		BlockState rightState = leftState.setValue(ChestBlock.TYPE, ChestType.RIGHT);
		Chest first = world.addChest(FIRST, leftState, Items.STONE, 3);
		Chest second = world.addChest(secondPos, rightState, Items.DIRT, 7);
		InventorySourceInput input = input("minecraft:chest", secondPos);
		InventoryMinecraftSources.View view = view(world, input);
		InventoryMinecraftSources.Source source = source(world, input, view, () -> view(world, input));

		assertEquals(54, view.slots().size());
		assertEquals(List.of(FIRST, secondPos), view.snapshotLayout().members().stream().map(InventorySnapshotLayout.Member::position).toList());
		for (var member : view.snapshotLayout().members())
			assertEquals(java.util.stream.IntStream.range(0, 27).boxed().toList(), member.segments().getFirst().visibleSlots());
		assertEquals(0, first.saves.get());
		assertEquals(0, second.saves.get());

		InventoryObservationSnapshot snapshot = (InventoryObservationSnapshot) source.snapshotPlan().orElseThrow().capture().orElseThrow();
		try (snapshot) {
			assertEquals(54, snapshot.slots());
			assertEquals(secondPos, snapshot.originalTarget(), "capture retains the originally hit half");
			assertEquals("minecraft:stone", snapshot.read(0).key().itemId());
			assertEquals(3L, snapshot.read(0).count());
			assertEquals("minecraft:dirt", snapshot.read(27).key().itemId(), "second member's local Slot 0 begins at visible index 27");
			assertEquals(7L, snapshot.read(27).count());
			assertEquals(0, first.saves.get() + second.saves.get());
			assertEquals(27, first.itemReads); assertEquals(27, second.itemReads);
		}
	}
	@Test void frozenAuthorityStopsBeforeTheNextContentReadWhenAnotherMemberBecomesUnsafe() {
		for (String change : List.of("lock", "loot", "replacement", "block")) {
			World world = doubleChestWorld();
			BlockPos other = world.blocks.keySet().stream().filter(pos -> !pos.equals(FIRST)).findFirst().orElseThrow();
			Chest first = (Chest) world.blockEntities.get(FIRST), second = (Chest) world.blockEntities.get(other);
			var input = input("minecraft:chest", FIRST); var admitted = view(world, input);
			try (var source = source(world, input, admitted, () -> view(world, input))) {
				var plan = source.snapshotPlan().orElseThrow();
				first.afterRead = () -> {
					switch (change) {
						case "lock" -> second.lock = new LockCode("revoked");
						case "loot" -> second.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse("minecraft:chests/simple_dungeon")));
						case "replacement" -> world.blockEntities.put(other, new Chest(other, second.getBlockState()));
						case "block" -> world.blocks.put(other, Blocks.DIRT.defaultBlockState());
					}
				};
				assertThrows(IllegalStateException.class, plan::capture, change);
				assertEquals(1, first.itemReads, change); assertEquals(0, second.itemReads, change);
				assertEquals(1, plan.attemptedPhysicalReads(), "only the attempted getter is charged: " + change);
			}
		}
	}
	@Test void frozenAuthorityChecksTheNextSelectedFacePositionBeforeReadingIt() {
		World world = new World(); var state = Blocks.CHEST.defaultBlockState();
		SidedChest chest = new SidedChest(FIRST, state); chest.mapping = new int[] {3, 1};
		chest.setItem(3, new ItemStack(Items.DIAMOND, 2)); world.put(FIRST, state, chest);
		var input = input("minecraft:chest", FIRST); var admitted = view(world, input);
		try (var source = source(world, input, admitted, () -> view(world, input))) {
			var plan = source.snapshotPlan().orElseThrow();
			chest.afterRead = () -> chest.mapping = new int[] {3, 0};
			assertThrows(IllegalStateException.class, plan::capture);
			assertEquals(1, chest.itemReads); assertEquals(1, plan.attemptedPhysicalReads());
		}
	}
	@Test void frozenAuthorityRejectsAnEarlierPositionMappingChangeBeforeTheNextContentRead() {
		World world = new World(); var state = Blocks.CHEST.defaultBlockState();
		SidedChest chest = new SidedChest(FIRST, state); chest.mapping = new int[] {3, 1};
		chest.setItem(3, new ItemStack(Items.DIAMOND, 2)); chest.setItem(1, new ItemStack(Items.DIRT, 5));
		world.put(FIRST, state, chest);
		var input = input("minecraft:chest", FIRST); var admitted = view(world, input);
		try (var source = source(world, input, admitted, () -> view(world, input))) {
			var plan = source.snapshotPlan().orElseThrow();
			// The earlier visible position changes while the next position still
			// maps to its frozen local slot; only the complete ordered witness
			// catches this before the second content read.
			chest.afterRead = () -> chest.mapping = new int[] {0, 1};
			assertThrows(IllegalStateException.class, plan::capture);
			assertEquals(1, chest.itemReads); assertEquals(1, plan.attemptedPhysicalReads());
		}
	}
	@Test void frozenAuthorityRejectsRevokedChestPairingOrFacingBeforeTheNextContentRead() {
		for (String change : List.of("single", "facing")) {
			World world = doubleChestWorld();
			BlockPos other = world.blocks.keySet().stream().filter(pos -> !pos.equals(FIRST)).findFirst().orElseThrow();
			Chest first = (Chest) world.blockEntities.get(FIRST);
			Chest second = (Chest) world.blockEntities.get(other);
			var input = input("minecraft:chest", FIRST); var admitted = view(world, input);
			BlockState original = world.blocks.get(other);
			BlockState revoked = change.equals("single")
				? original.setValue(ChestBlock.TYPE, ChestType.SINGLE)
				: original.setValue(ChestBlock.FACING, Direction.WEST);
			try (var source = source(world, input, admitted, () -> view(world, input))) {
				var plan = source.snapshotPlan().orElseThrow();
				first.afterRead = () -> world.blocks.put(other, revoked);
				assertThrows(IllegalStateException.class, plan::capture, change);
				assertEquals(1, first.itemReads, change); assertEquals(0, second.itemReads, change);
				assertEquals(1, plan.attemptedPhysicalReads(), change);
			}
		}
	}
	@Test void lockedCaptureBoundsTheObservedLockMultiplicityAndStaysServiceable() {
		World world = new World();
		ItemStack key = new ItemStack(Items.STICK); key.set(DataComponents.CUSTOM_NAME, Component.literal("owner-key"));
		world.heldKey = key;
		Chest chest = world.addChest(FIRST, Blocks.CHEST.defaultBlockState(), Items.STONE, 4);
		chest.lock = new LockCode("owner-key");
		var input = input("minecraft:chest", FIRST); var admitted = view(world, input);
		try (var source = source(world, input, admitted, () -> view(world, input))) {
			chest.lockReads = 0;
			var plan = source.snapshotPlan().orElseThrow();
			try (var snapshot = (InventoryObservationSnapshot) plan.capture().orElseThrow()) {
				assertEquals(4L, snapshot.read(0).count(), "a matching held key keeps the locked capture serviceable");
			}
			// Independent expected count: one lock check per authority pass plus
			// plan discovery, capture entry, both member prechecks, the boundary
			// authority call, the final authority pass and the closing valid call.
			assertEquals(27 + 7, chest.lockReads, "every capture-owned lock check is inside the advertised multiplicity");
			assertTrue(chest.lockReads <= 27 + InventoryObservationSnapshot.FIXED_VALIDATION_PASSES);
			assertTrue(plan.attemptedValidationWorkUnits() > 0, "the capture actually debits its validation subledger");
		}
	}
	@Test void tinyValidationAllowanceRejectsBeforeTheNextViewLayoutOrContentCallback() {
		World world = new World(); Chest chest = world.addChest(FIRST, Blocks.CHEST.defaultBlockState(), Items.STONE, 4);
		var input = input("minecraft:chest", FIRST); var admitted = view(world, input);
		AtomicInteger layouts = new AtomicInteger();
		try (var source = source(world, input, admitted, () -> { layouts.incrementAndGet(); return view(world, input); })) {
			var plan = source.snapshotPlan().orElseThrow(); layouts.set(0);
			var fixed = InventoryValidationBudget.fixed(admitted.snapshotLayout()).plus(InventoryValidationBudget.bookkeeping());
			var allowance = new InventoryValidationBudget.Allowance(fixed);
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> plan.capture(allowance));
			assertEquals(0, layouts.get(), "entry debit precedes reacquiring the view and its layout allocation");
			assertEquals(0, chest.itemReads); assertEquals(0, plan.attemptedPhysicalReads());
			assertEquals(fixed.allocationBytes(), plan.attemptedValidationAllocationBytes());
			// Full finite admission uses the same production path and reaches all
			// slots. The refused attempt did not mutate or pre-read the container.
			try (var snapshot = plan.capture(new InventoryValidationBudget.Allowance(plan.validationCost())).orElseThrow()) {
				assertEquals(27, snapshot.slots()); assertEquals(4, snapshot.read(0).count());
			}
			assertEquals(27, chest.itemReads); assertTrue(layouts.get() > 0);
		}
	}
	@Test void layoutlessValidationRefusesZeroBeforeMetadataOrReacquisitionAndPaysTheNextAttempt() {
		World world = new World(); var state = Blocks.BEEHIVE.defaultBlockState();
		world.put(FIRST, state, new BeehiveBlockEntity(FIRST, state));
		AtomicInteger metadata = new AtomicInteger(), rebuilds = new AtomicInteger();
		var backing = new net.minecraft.world.SimpleContainer(1);
		InventorySnapshotLayout[] descriptor = {null};
		IPlatformInventoryService.Access access = new Access(1, Optional.empty(), null) {
			@Override public Optional<InventorySnapshotLayout> snapshotLayout() { return Optional.ofNullable(descriptor[0]); }
			@Override public int slots() {
				metadata.incrementAndGet();
				assertTrue(InventoryContainerReads.audited(backing, ignored -> true));
				return 1;
			}
		};
		var input = input("minecraft:beehive", FIRST);
		var original = InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.of(access));
		try (var source = source(world, input, original, () -> {
			rebuilds.incrementAndGet();
			return InventoryMinecraftSources.view(world, input,
				new InventoryMinecraftSources.PhysicalRead(input.ordinaryTarget().orElseThrow(), input.face(), world::isLoaded),
				(pos, face) -> Optional.of(access), null, true);
		})) {
			metadata.set(0); rebuilds.set(0);
			var zero = new InventoryValidationBudget.Allowance(new InventoryValidationBudget.Cost(0, 0, 0));
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> source.valid(zero));
			assertEquals(0, metadata.get()); assertEquals(0, rebuilds.get());
			assertEquals(0, zero.attemptedWorkUnits());
			var grant = new InventoryValidationBudget.Allowance(source.discoveryValidationCost());
			assertTrue(source.valid(grant)); assertTrue(source.snapshotPlan(grant).isEmpty(), "still LIVE, not an atomic upgrade");
			assertTrue(metadata.get() > 0); assertEquals(1, rebuilds.get());
			assertTrue(grant.attemptedAllocationBytes() > 0); assertTrue(grant.attemptedWorkUnits() > 0);
			BlockPos newMember = new BlockPos(99, 0, 0); world.put(newMember, state, new BeehiveBlockEntity(newMember, state)); world.observedPosition = newMember;
			descriptor[0] = layout(FIRST, List.of(new InventorySnapshotLayout.Member(FIRST, "minecraft:beehive", "minecraft:beehive", "target", List.of(segment(1, List.of(0)))),
				new InventorySnapshotLayout.Member(newMember, "minecraft:beehive", "minecraft:beehive", "member", List.of())));
			assertFalse(source.valid(new InventoryValidationBudget.Allowance(source.discoveryValidationCost())));
			assertEquals(0, world.observedReads, "newly appearing layout cannot allocate/precheck its extra member scope");
		}
	}
	@Test void changedSingleChestRebuildPaysBothLocksBeforeTopologyRejection() {
		World world = new World();
		ItemStack key = new ItemStack(Items.STICK); key.set(DataComponents.CUSTOM_NAME, Component.literal("owner-key")); world.heldKey = key;
		Chest first = world.addChest(FIRST, Blocks.CHEST.defaultBlockState(), Items.STONE, 1); first.lock = new LockCode("owner-key");
		var input = input("minecraft:chest", FIRST); var original = view(world, input);
		try (var source = source(world, input, original, () -> view(world, input))) {
			BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST).setValue(ChestBlock.TYPE, ChestType.LEFT);
			BlockPos other = FIRST.relative(ChestBlock.getConnectedDirection(left));
			world.blocks.put(FIRST, left);
			Chest second = world.addChest(other, left.setValue(ChestBlock.TYPE, ChestType.RIGHT), Items.DIRT, 2); second.lock = new LockCode("owner-key");
			first.lockReads = 0; second.lockReads = 0;
			var layout = original.snapshotLayout();
			var oneLock = InventoryValidationBudget.view(layout, false).plus(InventoryValidationBudget.members(layout, 1));
			var shortGrant = new InventoryValidationBudget.Allowance(oneLock);
			assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> source.valid(shortGrant));
			assertEquals(0, first.lockReads + second.lockReads, "the second possible lock must be admitted before either callback");
			var full = new InventoryValidationBudget.Allowance(source.discoveryValidationCost());
			assertFalse(source.valid(full));
			assertEquals(1, first.lockReads); assertEquals(1, second.lockReads);
			assertTrue(full.attemptedAllocationBytes() >= 2 * InventoryItemBudget.workspaceUpperBound(InventoryItemCodec.minimumDisplayBytes()));
			assertTrue(full.attemptedWorkUnits() >= 2 * InventoryItemBudget.workUpperBound(InventoryItemCodec.minimumDisplayBytes()));
			assertEquals(0, first.itemReads + second.itemReads);
		}
	}
	@Test void tinyRegisteredProviderReachesLiveOrCaptureAndExpandedLayoutStopsBeforeMemberReads() {
		for (boolean detached : List.of(false, true)) {
			World world = new World(); var state = Blocks.BEEHIVE.defaultBlockState();
			world.put(FIRST, state, new BeehiveBlockEntity(FIRST, state));
			var input = input("minecraft:beehive", FIRST);
			var tiny = layout(FIRST, List.of(new InventorySnapshotLayout.Member(FIRST, "minecraft:beehive", "minecraft:beehive", "target", List.of(segment(1, List.of(0))))));
			InventorySnapshotLayout[] returned = {tiny}; AtomicInteger residentReads = new AtomicInteger(), supports = new AtomicInteger();
			var access = new Access(1, Optional.of("test:provider"), tiny) {
				@Override public Optional<InventorySnapshotLayout> snapshotLayout() { return Optional.of(returned[0]); }
				@Override public boolean supportsDetachedSlotCapture() { supports.incrementAndGet(); return detached; }
				@Override public IPlatformInventoryService.ResidentEntry readResident(int slot) { residentReads.incrementAndGet(); return IPlatformInventoryService.ResidentEntry.empty(); }
			};
			var physical = new InventoryMinecraftSources.PhysicalRead(input.ordinaryTarget().orElseThrow(), input.face(), world::isLoaded);
			var original = InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.of(access));
			try (var source = source(world, input, original, () -> InventoryMinecraftSources.view(world, input, physical,
				(pos, face) -> Optional.of(access), tiny, true))) {
				var cost = source.discoveryValidationCost();
				var work = new nx.pingwheel.common.presentation.source.CostLedger(Map.of(InventorySourceAccess.PROVIDER_WORK,
					4096L * InventoryItemBudget.workUpperBound(InventoryItemCodec.MAX_DISPLAY_BYTES)));
				var memory = new nx.pingwheel.common.presentation.source.RetainedMemoryLedger(nx.pingwheel.common.config.InventorySettings.serverDefaults().pendingMemoryBytes());
				try (var workTicket = work.tryReserve(Map.of(InventorySourceAccess.PROVIDER_WORK, cost.workUnits())).orElseThrow();
					var memoryTicket = memory.tryReserve(cost.workspaceBytes()).orElseThrow()) {
					var grant = new InventoryValidationBudget.Allowance(cost);
					try {
						var plan = source.snapshotPlan(grant);
						assertEquals(detached, plan.isPresent()); assertTrue(supports.get() > 0);
						if (detached) {
							var capture = plan.orElseThrow();
							try (var captureWork = work.tryReserve(Map.of(InventorySourceAccess.PROVIDER_WORK, capture.itemWorkUpperBoundUnits())).orElseThrow();
								var captureMemory = memory.tryReserve(capture.memoryUpperBoundBytes()).orElseThrow();
								var snapshot = capture.capture().orElseThrow()) {
								assertEquals(1, snapshot.slots()); assertNull(snapshot.read(0));
								captureWork.commit(Map.of(InventorySourceAccess.PROVIDER_WORK, capture.itemWorkUpperBoundUnits()));
							}
						}
					} finally { workTicket.commit(Map.of(InventorySourceAccess.PROVIDER_WORK, grant.attemptedWorkUnits())); }
				}
				assertEquals(detached ? 1 : 0, residentReads.get());
				BlockPos denied = new BlockPos(99, 0, 0);
				world.put(denied, state, new BeehiveBlockEntity(denied, state)); world.observedPosition = denied;
				returned[0] = layout(FIRST, List.of(tiny.members().getFirst(),
					new InventorySnapshotLayout.Member(denied, "minecraft:beehive", "minecraft:beehive", "member", List.of(segment(1, List.of(0))))));
				assertFalse(source.valid(new InventoryValidationBudget.Allowance(source.discoveryValidationCost())), "changed DTO rejected before prechecking its foreign member");
				assertEquals(0, world.observedReads, "the changed member is loaded, but its state/BE must not be read");
				assertEquals(detached ? 1 : 0, residentReads.get());
			}
		}
	}

	@Test void everyMemberIsPreflightedBeforeTheFirstContentReadForLocksAndPendingLoot() {
		for (boolean pendingLoot : List.of(false, true)) {
			World world = doubleChestWorld();
			BlockPos secondPos = world.blocks.keySet().stream().filter(pos -> !pos.equals(FIRST)).findFirst().orElseThrow();
			Chest first = (Chest) world.blockEntities.get(FIRST);
			Chest second = (Chest) world.blockEntities.get(secondPos);
			InventorySourceInput input = input("minecraft:chest", FIRST);
			InventoryMinecraftSources.View admitted = view(world, input);
			InventoryMinecraftSources.Source source = source(world, input, admitted, () -> view(world, input));
			if (pendingLoot) {
				second.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse("minecraft:chests/simple_dungeon")));
			} else {
				second.lock = new LockCode("other-key");
			}

			assertThrows(IllegalStateException.class, () -> source.snapshotPlan().orElseThrow().capture(),
				pendingLoot ? "pending loot on the second member rejects the whole preflight" : "a locked second member rejects the whole preflight");
			assertEquals(0, first.saves.get(), "no first-member NBT is captured before all-member safety succeeds");
			assertEquals(0, second.saves.get());
			assertEquals(0, first.itemReads + second.itemReads);
		}
	}

	@Test void topologyLossBeforeAndDuringCaptureNeverPublishesASnapshot() {
		World beforeWorld = doubleChestWorld();
		BlockPos secondPos = beforeWorld.blocks.keySet().stream().filter(pos -> !pos.equals(FIRST)).findFirst().orElseThrow();
		Chest first = (Chest) beforeWorld.blockEntities.get(FIRST);
		InventorySourceInput input = input("minecraft:chest", FIRST);
		InventoryMinecraftSources.View admitted = view(beforeWorld, input);
		InventoryMinecraftSources.Source before = source(beforeWorld, input, admitted, () -> view(beforeWorld, input));
		InventorySourceAccess.SnapshotPlan beforePlan = before.snapshotPlan().orElseThrow();
		beforeWorld.loaded.remove(secondPos);
		assertThrows(IllegalStateException.class, beforePlan::capture, "a changed topology is rejected before any contents");
		assertEquals(0, first.saves.get());

		World duringWorld = doubleChestWorld();
		BlockPos duringSecondPos = duringWorld.blocks.keySet().stream().filter(pos -> !pos.equals(FIRST)).findFirst().orElseThrow();
		Chest duringFirst = (Chest) duringWorld.blockEntities.get(FIRST);
		InventorySourceInput duringInput = input("minecraft:chest", FIRST);
		InventoryMinecraftSources.View duringView = view(duringWorld, duringInput);
		InventoryMinecraftSources.Source during = source(duringWorld, duringInput, duringView, () -> view(duringWorld, duringInput));
		duringFirst.afterRead = () -> duringWorld.loaded.remove(duringSecondPos);
		assertThrows(IllegalStateException.class, () -> during.snapshotPlan().orElseThrow().capture(),
			"authority loss discards the whole capture before the next slot/member");
		assertEquals(0, duringFirst.saves.get()); assertEquals(1, duringFirst.itemReads);
		assertEquals(0, ((Chest) duringWorld.blockEntities.get(duringSecondPos)).itemReads);
	}

	@Test void changingNormalContentsAfterCaptureLeavesTheFrozenSnapshotAndSourceValid() {
		World world = new World();
		Chest chest = world.addChest(FIRST, Blocks.CHEST.defaultBlockState(), Items.STONE, 4);
		InventorySourceInput input = input("minecraft:chest", FIRST);
		InventoryMinecraftSources.View view = view(world, input);
		InventoryMinecraftSources.Source source = source(world, input, view, () -> view(world, input));
		InventoryObservationSnapshot snapshot = (InventoryObservationSnapshot) source.snapshotPlan().orElseThrow().capture().orElseThrow();
		try (snapshot) {
			chest.setItem(0, new ItemStack(Items.DIAMOND, 12));
			assertTrue(source.valid(), "ordinary inventory contents changing does not invalidate fixed topology");
			assertEquals("minecraft:stone", snapshot.read(0).key().itemId());
			assertEquals(4L, snapshot.read(0).count(), "the detached contents stay frozen at the capture boundary");
			assertEquals(0, chest.saves.get(), "capture/revalidation/snapshot reads never save");
		}
	}

	@Test void nonContainerCapabilityWithoutExplicitLayoutStaysLiveAndMalformedDescriptorsFailClosed() {
		World world = new World();
		BlockPos pos = new BlockPos(4, 0, 0);
		BlockState state = Blocks.BEEHIVE.defaultBlockState();
		BlockEntity entity = new BeehiveBlockEntity(pos, state);
		world.put(pos, state, entity);
		InventorySourceInput input = input("minecraft:beehive", pos);
		Access access = new Access(3, Optional.empty(), null);
		InventoryMinecraftSources.View noDescriptor = InventoryMinecraftSources.view(world, input, (ignored, face) -> Optional.of(access));
		assertNull(noDescriptor.snapshotLayout(), "provider slot count alone is not NBT face-mapping evidence");
		InventoryMinecraftSources.Source source = source(world, input, noDescriptor,
			() -> InventoryMinecraftSources.view(world, input, (ignored, face) -> Optional.of(access)));
		assertTrue(source.snapshotPlan().isEmpty(), "an absent provider layout keeps the established live route");
		assertEquals(0, access.reads.get());

		var twoSlots = segment(3, List.of(0, 1));
		var wrongTotal = layout(pos, List.of(new InventorySnapshotLayout.Member(pos, "minecraft:beehive",
			BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString(), "target", List.of(twoSlots))));
		Access mismatched = new Access(3, Optional.of("test:provider"), wrongTotal);
		assertThrows(IllegalStateException.class,
			() -> InventoryMinecraftSources.view(world, input, (ignored, face) -> Optional.of(mismatched)),
			"a two-slot explicit descriptor cannot silently fall back against a three-slot live witness");
		assertEquals(0, mismatched.reads.get());

		var absentTarget = layout(new BlockPos(9, 0, 0), List.of(new InventorySnapshotLayout.Member(new BlockPos(9, 0, 0),
			"minecraft:beehive", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString(), "target",
			List.of(segment(3, List.of(0, 1, 2))))));
		Access wrongMember = new Access(3, Optional.of("test:provider"), absentTarget);
		assertThrows(IllegalStateException.class,
			() -> InventoryMinecraftSources.view(world, input, (ignored, face) -> Optional.of(wrongMember)),
			"a descriptor that omits the actual target member is structurally invalid, not unsupported");
		assertEquals(0, wrongMember.reads.get());

		var unknownSchema = new InventorySnapshotLayout.Segment("Items", "test:unknown_schema", 3, List.of(0, 1, 2));
		var unknownLayout = layout(pos, List.of(new InventorySnapshotLayout.Member(pos, "minecraft:beehive",
			BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString(), "target", List.of(unknownSchema))));
		Access unknown = new Access(3, Optional.of("test:provider"), unknownLayout);
		assertNull(InventoryMinecraftSources.view(world, input, (ignored, face) -> Optional.of(unknown)).snapshotLayout(),
			"an explicitly unsupported schema remains a live fallback, unlike a malformed recognized payload");
	}

	@Test void saveOnlyFieldsCannotAffectResidentExtraction() {
		World ambiguousWorld = new World();
		AmbiguousChest ambiguousChest = new AmbiguousChest(FIRST, Blocks.CHEST.defaultBlockState());
		ambiguousChest.setItem(0, new ItemStack(Items.STONE, 1));
		ambiguousChest.resetCounters();
		ambiguousWorld.put(FIRST, Blocks.CHEST.defaultBlockState(), ambiguousChest);
		InventorySourceInput input = input("minecraft:chest", FIRST);
		InventoryMinecraftSources.View ambiguousView = view(ambiguousWorld, input);
		try (var snapshot = source(ambiguousWorld, input, ambiguousView, () -> ambiguousView).snapshotPlan().orElseThrow().capture().orElseThrow()) {
			assertEquals(1, snapshot.read(0).count());
		}
		assertEquals(0, ambiguousChest.saves.get());

		World malformedWorld = new World();
		MalformedChest malformedChest = new MalformedChest(FIRST, Blocks.CHEST.defaultBlockState());
		malformedWorld.put(FIRST, Blocks.CHEST.defaultBlockState(), malformedChest);
		InventoryMinecraftSources.View malformedView = view(malformedWorld, input);
		try (var snapshot = source(malformedWorld, input, malformedView, () -> malformedView).snapshotPlan().orElseThrow().capture().orElseThrow()) {
			assertNull(snapshot.read(0));
		}
		assertEquals(0, malformedChest.saves.get());
	}

	@Test void frozenFaceWitnessPreservesSidedSlotOrderAndDetectsMappingChanges() {
		World world = new World();
		BlockState state = Blocks.CHEST.defaultBlockState();
		SidedChest chest = new SidedChest(FIRST, state);
		chest.setItem(3, new ItemStack(Items.DIAMOND, 2));
		chest.setItem(1, new ItemStack(Items.DIRT, 5));
		chest.resetCounters();
		world.put(FIRST, state, chest);
		InventorySourceInput input = input("minecraft:chest", FIRST);
		InventoryMinecraftSources.View admitted = view(world, input);
		assertEquals(List.of(3, 1), admitted.snapshotLayout().members().getFirst().segments().getFirst().visibleSlots());
		InventoryMinecraftSources.Source source = source(world, input, admitted, () -> view(world, input));
		InventoryObservationSnapshot snapshot = (InventoryObservationSnapshot) source.snapshotPlan().orElseThrow().capture().orElseThrow();
		try (snapshot) {
			assertEquals("minecraft:diamond", snapshot.read(0).key().itemId());
			assertEquals(2L, snapshot.read(0).count());
			assertEquals("minecraft:dirt", snapshot.read(1).key().itemId());
			assertEquals(5L, snapshot.read(1).count());
			chest.mapping = new int[] {1, 3};
			assertFalse(source.valid(), "the live source no longer witnesses the frozen ordered face mapping");
			assertEquals("minecraft:diamond", snapshot.read(0).key().itemId(), "mapping invalidation does not rewrite the detached capture");
		}
	}

	@Test void noSaveTrapHasPositiveControlAndHiddenSidedSlotsAreNeverRead() {
		World world = new World(); BlockState state = Blocks.CHEST.defaultBlockState();
		Chest trap = new Chest(FIRST, state) {
			@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) { throw new AssertionError("full-save trap"); }
		};
		assertThrows(AssertionError.class, () -> trap.saveCustomOnly(RegistryAccess.EMPTY));
		trap.setItem(0, new ItemStack(Items.STONE, 4)); trap.resetCounters(); world.put(FIRST, state, trap);
		var input = input("minecraft:chest", FIRST); var admitted = view(world, input);
		try (var source = source(world, input, admitted, () -> view(world, input)); var snapshot = source.snapshotPlan().orElseThrow().capture().orElseThrow()) {
			assertEquals(4, snapshot.read(0).count()); assertEquals(27, trap.itemReads);
		}
		SidedChest sided = new SidedChest(FIRST, state);
		sided.setItem(3, new ItemStack(Items.DIAMOND, 2)); sided.setItem(1, new ItemStack(Items.DIRT, 3));
		sided.hiddenTrap = true; sided.resetCounters(); world.put(FIRST, state, sided);
		assertThrows(AssertionError.class, () -> sided.getItem(0)); // hidden-slot positive control
		sided.resetCounters(); var faceView = view(world, input);
		try (var source = source(world, input, faceView, () -> view(world, input)); var snapshot = source.snapshotPlan().orElseThrow().capture().orElseThrow()) {
			assertEquals(2, sided.itemReads); assertEquals(2, snapshot.slots()); assertEquals(0, sided.saves.get());
		}
	}

	@Test void customCounterRegistrationAloneNeverCreatesAnAutomaticCapturePlan() {
		String schema = "test:source_custom_" + UUID.randomUUID().toString().replace("-", "");
		AtomicInteger calls = new AtomicInteger();
		assertTrue(InventorySnapshotSchemas.register(schema, (payload, slot, slots, registries) -> { calls.incrementAndGet(); throw new AssertionError("counter"); }));
		World world = new World(); var state = Blocks.BEEHIVE.defaultBlockState(); world.put(FIRST, state, new BeehiveBlockEntity(FIRST, state));
		var topology = layout(FIRST, List.of(new InventorySnapshotLayout.Member(FIRST, "minecraft:beehive", "minecraft:beehive", "target",
			List.of(new InventorySnapshotLayout.Segment("CustomInventory", schema, 1, List.of(0))))));
		Access access = new Access(1, Optional.of("test:provider"), topology);
		var input = input("minecraft:beehive", FIRST); var admitted = InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.of(access));
		try (var source = source(world, input, admitted, () -> admitted)) { assertTrue(source.snapshotPlan().isEmpty()); }
		assertEquals(0, calls.get()); assertEquals(0, access.reads.get());
	}

	@Test void placedColouredAndUncolouredShulkersRemainOrdinaryContainerSources() {
		for (var block : List.of(Blocks.SHULKER_BOX, Blocks.RED_SHULKER_BOX)) {
			World world = new World(); var state = block.defaultBlockState();
			var shulker = new PlacedShulker(FIRST, state);
			shulker.setItem(0, new ItemStack(Items.DIAMOND, 9)); world.put(FIRST, state, shulker);
			var input = input(BuiltInRegistries.BLOCK.getKey(block).toString(), FIRST); var admitted = view(world, input);
			try (var source = source(world, input, admitted, () -> view(world, input)); var snapshot = source.snapshotPlan().orElseThrow().capture().orElseThrow()) {
				assertEquals(27, snapshot.slots()); assertEquals(9, snapshot.read(0).count());
			}
		}
	}
	@Test void inheritedNativeChestReaderOptsIntoTheProductionCaptureClassifier() {
		class NativeChest extends ChestBlockEntity implements BaseContainerLockAccessor {
			NativeChest() { super(FIRST, Blocks.CHEST.defaultBlockState()); }
			@Override public LockCode pingforit$inventoryLock() { return LockCode.NO_LOCK; }
			@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) { throw new AssertionError("native sample save"); }
		}
		World world = new World(); var chest = new NativeChest(); chest.setItem(0, new ItemStack(Items.STONE, 3));
		world.put(FIRST, Blocks.CHEST.defaultBlockState(), chest);
		var input = input("minecraft:chest", FIRST); var admitted = view(world, input);
		assertTrue(InventoryContainerReads.audited(chest, ignored -> true), "no fixture-specific getItem audit override");
		try (var source = source(world, input, admitted, () -> view(world, input)); var snapshot = source.snapshotPlan().orElseThrow().capture().orElseThrow()) {
			assertEquals(27, snapshot.slots()); assertEquals(3L, snapshot.read(0).count());
		}
	}
	private static final class PlacedShulker extends net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity implements BaseContainerLockAccessor {
		PlacedShulker(BlockPos pos, BlockState state) { super(pos, state); }
		@Override public LockCode pingforit$inventoryLock() { return LockCode.NO_LOCK; }
		@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) { throw new AssertionError("placed shulker save"); }
	}

	private static World doubleChestWorld() {
		World world = new World();
		BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST)
			.setValue(ChestBlock.TYPE, ChestType.LEFT);
		BlockPos rightPos = FIRST.relative(ChestBlock.getConnectedDirection(left));
		world.addChest(FIRST, left, Items.STONE, 3);
		world.addChest(rightPos, left.setValue(ChestBlock.TYPE, ChestType.RIGHT), Items.DIRT, 7);
		return world;
	}

	private static InventorySourceInput input(String blockId, BlockPos pos) {
		return new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ(), blockId), OWNER, BlockFace.NORTH);
	}

	private static InventoryMinecraftSources.View view(World world, InventorySourceInput input) {
		return InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.empty());
	}

	private static InventoryMinecraftSources.Source source(World world, InventorySourceInput input,
		InventoryMinecraftSources.View admitted, java.util.function.Supplier<InventoryMinecraftSources.View> liveView) {
		return new InventoryMinecraftSources.Source(input, admitted, () -> true, liveView,
			InventoryMinecraftSnapshotCaptureTest::domainItem, world, null);
	}

	private static InventoryDomainCodec.Item domainItem(IPlatformInventoryService.Entry entry) {
		if (entry.isEmpty()) return null;
		String id = BuiltInRegistries.ITEM.getKey(entry.exemplar().getItem()).toString();
		return new InventoryDomainCodec.Item(new InventoryScanner.Key(id, "plain"), entry.amount(), id, null, false);
	}

	private static InventorySnapshotLayout layout(BlockPos controller, List<InventorySnapshotLayout.Member> members) {
		return new InventorySnapshotLayout("test:provider", "face=north", controller, members);
	}

	private static InventorySnapshotLayout.Segment segment(int localSlots, List<Integer> visible) {
		return new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, localSlots, visible);
	}

	private static final class World implements InventoryMinecraftSources.SourceWorld {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
		final java.util.Set<BlockPos> loaded = new java.util.HashSet<>();
		@Override public boolean isLoaded(BlockPos pos) { return loaded.contains(pos); }
		BlockPos observedPosition; int observedReads;
		@Override public BlockState blockState(BlockPos pos) { if (pos.equals(observedPosition)) observedReads++; return blocks.get(pos); }
		@Override public BlockEntity blockEntity(BlockPos pos) { if (pos.equals(observedPosition)) observedReads++; return blockEntities.get(pos); }
		@Override public boolean onServerThread() { return true; }
		ItemStack heldKey = ItemStack.EMPTY;
		@Override public ItemStack heldKey() { return heldKey; }
		@Override public HolderLookup.Provider registries() { return RegistryAccess.EMPTY; }
		@Override public boolean audited(net.minecraft.world.Container value) { return value instanceof Chest || InventoryContainerReads.audited(value, ignored -> true); } // inspected recording fixture, not production certification
		void put(BlockPos pos, BlockState state, BlockEntity entity) {
			blocks.put(pos.immutable(), state);
			blockEntities.put(pos.immutable(), entity);
			loaded.add(pos.immutable());
		}
		Chest addChest(BlockPos pos, BlockState state, net.minecraft.world.item.Item item, int count) {
			Chest chest = new Chest(pos, state);
			chest.setItem(0, new ItemStack(item, count));
			chest.resetCounters();
			put(pos, state, chest);
			return chest;
		}
	}

	private static class Chest extends ChestBlockEntity implements BaseContainerLockAccessor {
		LockCode lock = LockCode.NO_LOCK;
		int itemReads, lockReads;
		final AtomicInteger saves = new AtomicInteger();
		final AtomicInteger implicitComponentCollections = new AtomicInteger(), nonEmptySaves = new AtomicInteger();
		Runnable afterSave;
		Runnable afterRead;
		Chest(BlockPos pos, BlockState state) { super(pos, state); }
		@Override public LockCode pingforit$inventoryLock() { lockReads++; return lock; }
		@Override public ItemStack getItem(int slot) { itemReads++; ItemStack value = super.getItem(slot); if (afterRead != null) afterRead.run(); return value; }
		@Override protected void collectImplicitComponents(DataComponentMap.Builder components) {
			implicitComponentCollections.incrementAndGet();
			super.collectImplicitComponents(components);
		}
		@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			saves.incrementAndGet();
			super.saveAdditional(tag, registries);
			if (tag.contains("Items")) nonEmptySaves.incrementAndGet();
			if (afterSave != null) afterSave.run();
		}
		void resetCounters() {
			itemReads = 0;
			lockReads = 0;
			saves.set(0);
			implicitComponentCollections.set(0);
			nonEmptySaves.set(0);
		}
	}

	private static final class MalformedChest extends Chest {
		MalformedChest(BlockPos pos, BlockState state) { super(pos, state); }
		@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			tag.put("Items", StringTag.valueOf("not a list"));
		}
	}

	private static final class AmbiguousChest extends Chest {
		AmbiguousChest(BlockPos pos, BlockState state) { super(pos, state); }
		@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			CompoundTag wrapper = new CompoundTag();
			wrapper.put("Items", new ListTag());
			tag.put("Inventory", wrapper);
		}
	}

	private static final class SidedChest extends Chest implements WorldlyContainer {
		int[] mapping = {3, 1};
		boolean hiddenTrap;
		SidedChest(BlockPos pos, BlockState state) { super(pos, state); }
		@Override public int[] getSlotsForFace(Direction face) { return mapping.clone(); }
		@Override public ItemStack getItem(int slot) { if (hiddenTrap && slot != 3 && slot != 1) throw new AssertionError("hidden slot"); return super.getItem(slot); }
		@Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction face) { return false; }
		@Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction face) { return false; }
	}

	private static class Access implements IPlatformInventoryService.Access {
		final int slots;
		final Optional<String> alias;
		final InventorySnapshotLayout layout;
		final AtomicInteger reads = new AtomicInteger();
		Access(int slots, Optional<String> alias, InventorySnapshotLayout layout) { this.slots = slots; this.alias = alias; this.layout = layout; }
		@Override public Optional<String> alias() { return alias; }
		@Override public Optional<InventorySnapshotLayout> snapshotLayout() { return Optional.ofNullable(layout); }
		@Override public boolean valid() { return true; }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return slots; }
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public IPlatformInventoryService.Entry read(int slot) {
			reads.incrementAndGet();
			return new IPlatformInventoryService.Entry(new ItemStack(Items.STONE), 1);
		}
		@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { return true; }
	}
}
