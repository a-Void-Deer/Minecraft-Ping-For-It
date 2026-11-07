package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.presentation.inventory.InventorySharedRead.Check;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Chest certificates freeze producer aliases and retain current authority and admission. */
@NotNullByDefault
class CertifiedChestFrozenMappingTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@NotNullByDefault
	private static final class CountingSlots extends AbstractList<InventoryMinecraftSources.Slot> {
		private final @org.jetbrains.annotations.Unmodifiable List<InventoryMinecraftSources.Slot> delegate;
		int gets;
		CountingSlots(List<InventoryMinecraftSources.Slot> delegate) { this.delegate = List.copyOf(delegate); }
		@Override public InventoryMinecraftSources.Slot get(int index) { gets++; return delegate.get(index); }
		@Override public int size() { return delegate.size(); }
	}

	@Test void constructionSnapshots54AddressesAndCurrentNeverConsultsTheProducerList() {
		var world = InventoryCertifiedReadTest.pair(Blocks.CHEST);
		var input = world.input(BlockPos.ZERO, BlockFace.NORTH, 1);
		var base = InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.empty());
		var slots = new CountingSlots(base.slots());
		var view = new InventoryMinecraftSources.View(base.identity(), base.entities(), slots, null, base.snapshotLayout(), true);
		try (var source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> view, entry -> null, world, null)) {
			var port = java.util.Objects.requireNonNull(source.sharedRead());
			assertTrue(slots.gets > 0, "view construction snapshots the supplied address list");
			slots.gets = 0;
			assertEquals(Check.VALID, port.validate(port.proof(), InventoryCertifiedReadTest.validation(port)));
			assertEquals(0, slots.gets, "current must not consult producer-owned address aliases");
			assertEquals(54, port.proof().graph().slots());
		}
	}

	@Test void singleChestCertificateIs27Pairs() {
		var world = new InventoryCertifiedReadTest.World();
		world.put(BlockPos.ZERO, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.SINGLE));
		var input = world.input(BlockPos.ZERO, BlockFace.NORTH, 1);
		try (var source = world.source(input)) {
			var port = java.util.Objects.requireNonNull(source.sharedRead());
			assertEquals(27, port.proof().graph().slots());
			assertEquals(Check.VALID, port.validate(port.proof(), InventoryCertifiedReadTest.validation(port)));
		}
	}

	@Test void malformedOrReversedAddressMappingIsRefusedAtConstruction() {
		var world = InventoryCertifiedReadTest.pair(Blocks.CHEST);
		var input = world.input(BlockPos.ZERO, BlockFace.NORTH, 1);
		var base = InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.empty());
		var reversed = new ArrayList<>(base.slots());
		Collections.reverse(reversed);
		var view = new InventoryMinecraftSources.View(base.identity(), base.entities(), List.copyOf(reversed), null, base.snapshotLayout(), true);
		try (var source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> view, entry -> null, world, null)) {
			assertNull(source.sharedRead(), "a malformed certificate must not become a paid live fallback");
		}
	}

	@Test void liveChestStateStillRejectsAfterTheFrozenMappingPasses() {
		var world = InventoryCertifiedReadTest.pair(Blocks.CHEST);
		var input = world.input(BlockPos.ZERO, BlockFace.NORTH, 1);
		try (var source = world.source(input)) {
			var port = java.util.Objects.requireNonNull(source.sharedRead());
			assertEquals(Check.VALID, port.validate(port.proof(), InventoryCertifiedReadTest.validation(port)));
			world.entities.put(BlockPos.ZERO, new net.minecraft.world.level.block.entity.ChestBlockEntity(
				BlockPos.ZERO, world.states.get(BlockPos.ZERO)));
			assertEquals(Check.STRUCTURE_INVALID, port.validate(port.proof(), InventoryCertifiedReadTest.validation(port)));
		}
	}

	@Test void mutableAddressAliasesCannotRewriteTheCertifiedReadRoute() {
		var world = InventoryCertifiedReadTest.pair(Blocks.CHEST);
		var input = world.input(BlockPos.ZERO, BlockFace.NORTH, 1);
		var base = InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.empty());
		var aliases = new ArrayList<>(base.slots());
		var entities = new ArrayList<>(base.entities());
		var view = new InventoryMinecraftSources.View(base.identity(), entities, aliases, null, base.snapshotLayout(), true);
		try (var source = new InventoryMinecraftSources.Source(input, view, () -> true, () -> view, entry -> null, world, null)) {
			var port = java.util.Objects.requireNonNull(source.sharedRead());
			Collections.reverse(aliases); entities.clear();
			assertEquals(base.slots(), view.slots());
			assertEquals(base.entities(), view.entities());
			assertThrows(UnsupportedOperationException.class, () -> view.slots().clear());
			assertEquals(Check.VALID, port.validate(port.proof(), InventoryCertifiedReadTest.validation(port)));
		}
	}

	@Test void reversedMemberLocalMappingCannotBeCertifiedByMatchingTotals() {
		var world = InventoryCertifiedReadTest.pair(Blocks.CHEST);
		var input = world.input(BlockPos.ZERO, BlockFace.NORTH, 1);
		var base = InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.empty());
		var layout = java.util.Objects.requireNonNull(base.snapshotLayout());
		var members = new ArrayList<>(layout.members());
		var member = members.getFirst();
		var segment = member.segments().getFirst();
		var mapping = new ArrayList<>(segment.visibleSlots());
		Collections.reverse(mapping);
		members.set(0, new InventorySnapshotLayout.Member(member.position(), member.blockId(), member.blockEntityId(), member.role(),
			List.of(new InventorySnapshotLayout.Segment(segment.fieldPath(), segment.schemaId(), 27, mapping))));
		var malformed = new InventorySnapshotLayout(layout.layoutId(), layout.layoutData(), layout.controller(), members);
		var view = new InventoryMinecraftSources.View(base.identity(), base.entities(), base.slots(), null, malformed, true);
		assertFalse(InventoryCertifiedRead.NativePort.frozenChestMappingValid(view, base.entities()));
	}

	@Test void insufficientValidationGrantDefersBeforeWorldKeyAndReadChecksAndDoesNotWaiveAuthority() {
		var world = InventoryCertifiedReadTest.pair(Blocks.CHEST);
		try (var source = world.source(world.input(BlockPos.ZERO, BlockFace.NORTH, 1))) {
			var port = java.util.Objects.requireNonNull(source.sharedRead());
			var cost = port.validationCost();
			var shortGrant = new InventoryValidationBudget.Allowance(new InventoryValidationBudget.Cost(
				cost.allocationBytes(), cost.workspaceBytes(), cost.workUnits() - 1));
			world.metadata = 0; world.readChecks = 0; world.owner = false;
			assertEquals(Check.DEFERRED, port.validate(port.proof(), shortGrant));
			assertEquals(0, world.metadata); assertEquals(0, world.readChecks);
			var paid = InventoryCertifiedReadTest.validation(port);
			assertEquals(Check.AUTHORITY_DENIED, port.validate(port.proof(), paid));
			assertEquals(cost.workUnits(), paid.attemptedWorkUnits());
			assertEquals(cost.allocationBytes(), paid.attemptedAllocationBytes());
			int before = world.metadata;
			assertEquals(Check.DEFERRED, port.validate(port.proof(), paid));
			assertEquals(before, world.metadata);
		}
	}
}
