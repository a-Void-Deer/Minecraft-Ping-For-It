package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.source.CaptureResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryPooledChestRuntimeTest {
	@BeforeAll static void bootstrap() { InventoryCertifiedReadTest.bootstrap(); }
	@Test void oppositeNativeDoubleChestHitsShareWithoutCapabilitiesAndOldEvidenceRejectsFreshWrapper() {
		for (var block : List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST)) {
			var world = InventoryCertifiedReadTest.pair(block);
			for (var entity : world.entities.values()) for (int slot = 0; slot < 27; slot++) ((ChestBlockEntity) entity).setItem(slot, new ItemStack(Items.STONE, slot + 1));
			var settings = InventoryPooledRuntimeTest.settings(false, false);
			var resolver = new InventoryRuntime.SourceResolver() {
				@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return InventoryMinecraftSources.resolutionValidationCost(input); }
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted"); }
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
					allowance.debit(resolutionValidationCost(input)); return Optional.of(world.source(input));
				}
			};
			var runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true);
			try (runtime) {
				runtime.advance(0, settings); var input = world.input(BlockPos.ZERO, BlockFace.NORTH, 1); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(input.target()));
				var a = runtime.attach(input, subject).orElseThrow();
				var b = runtime.attach(world.input(new BlockPos(1, 0, 0), BlockFace.SOUTH, 2), subject).orElseThrow();
				assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(a)); assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(b));
				int now = 0;
				for (var consumer : List.of(a, b)) {
					long total = 0; int slots = 0; boolean complete = false;
					for (int tick = 0; tick < 80 && !complete; tick++) {
						runtime.advance(now++, settings); var observation = runtime.step(consumer, 9);
						if (observation.isEmpty()) continue;
						for (var item : observation.get().slots()) { total += java.util.Objects.requireNonNull(item).count(); slots++; }
						complete = observation.get().result().completeness() == CaptureResult.Completeness.COMPLETE;
					}
					assertTrue(complete); assertEquals(54, slots); assertEquals(756, total); consumer.retainCompleted();
				}
				// One exact route audits both members before and after each physical visit. Metadata validation never audits contents.
				assertEquals(54 * 4, world.audits);
				try (var evidence = runtime.retainEvidence(b, InventoryRuntime.EvidenceScope.COMPLETED).orElseThrow()) {
					world.put(BlockPos.ZERO, world.states.get(BlockPos.ZERO));
					assertEquals(InventorySharedRead.Check.STRUCTURE_INVALID, evidence.validate(new InventoryValidationBudget.Allowance(evidence.validationCost())));
					assertFalse(runtime.probe(b).orElseThrow()); assertTrue(a.invalidated());
				}
			} assertEquals(0, runtime.memory().retained());
		}
	}
}
