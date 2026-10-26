package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.neoforge.integration.create.presentation.VaultPerformanceFixture.Contents;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Additional contracts over the unchanged representative benchmark's real common view and Segmented fixture. */
@NotNullByDefault
class VaultCurrentSourceValidationTest {
	private static final InventorySourceInput INPUT = new InventorySourceInput(
		new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "create:item_vault"), new UUID(84, 1), BlockFace.NORTH);
	private static final CostLedger.Counter WORK = new CostLedger.Counter("inventory/item_work", CostLedger.Unit.WORK);
	@BeforeAll static void bootstrap() throws ReflectiveOperationException { CreateVaultTrackingPerformanceTest.bootstrap(); }
	@AfterAll static void restore() { CreateVaultTrackingPerformanceTest.restoreRegistries(); }

	@Test void preparedSweepAndIdleEvidenceReuseTheMappingButReadEachCurrentResident() {
		try (var run = new CreateVaultTrackingPerformanceTest.Run(Contents.REPEATED)) {
			run.prepare(); run.world.resetCounters();
			long total = 0;
			for (int page = 0; page < 29; page++) {
				int first = page * 32, end = Math.min(900, first + 32);
				for (int slot = first; slot < end; slot++) {
					int member = slot / 20;
					var position = new BlockPos(member / 9, member / 3 % 3, member % 3);
					// Change unread counts after prepare and after the previous page.
					run.world.vault.members.get(position).storage.setStackInSlot(slot % 20, new ItemStack(Items.DIRT, 1_000_000 + slot));
					total += 1_000_000L + slot;
				}
				run.step();
				assertEquals(CaptureResult.Availability.READABLE, run.last.result().availability());
				assertEquals(end - first, run.last.slots().size());
				for (int index = 0; index < end - first; index++) {
					var item = run.last.slots().get(index); assertNotNull(item);
					assertEquals("minecraft:dirt", item.key().itemId()); assertEquals(1_000_000L + first + index, item.count());
				}
			}
			assertEquals(total, run.total); assertEquals(900, run.world.vault.getters);
			assertEquals(0, run.world.views); assertEquals(0, run.world.vault.factories);
			assertEquals(2 * 29 + 6 * 900, run.world.vault.controllerChecks,
				"two frozen/current source passes per window and per-slot pre/post source plus resident passes");
			assertEquals(29 * (4 + 4 * 45) + 900 * (13 + 14 * 45), run.world.vault.loaded,
				"one source boundary per successful window; its second reserved boundary covers a failed freeze");
			run.consumer.retainCompleted(); run.world.resetCounters();
			assertTrue(run.probe()); assertEquals(0, run.world.views); assertEquals(0, run.world.vault.factories);
			assertEquals(2, run.world.vault.controllerChecks); assertEquals(0, run.world.vault.getters);
		}
	}

	@Test void sameTypeRootAndMemberReplacementRegistryAndRemovalInvalidateBeforeTailAndFreshSourceRecovers() {
		for (String change : List.of("root", "member", "registry", "removed", "unloaded")) {
			try (var run = new CreateVaultTrackingPerformanceTest.Run(Contents.REPEATED)) {
				run.prepare(); run.step(); long reads = run.world.vault.getters;
				BlockPos position = change.equals("root") ? BlockPos.ZERO : new BlockPos(4, 2, 2);
				BlockEntity previous = run.world.entities.get(position);
				if (change.equals("root") || change.equals("member")) run.world.entities.put(position,
					new BeehiveBlockEntity(position, previous.getBlockState()));
				else if (change.equals("registry")) previous.setBlockState(Blocks.STONE.defaultBlockState());
				else if (change.equals("removed")) previous.setRemoved();
				else run.world.entities.remove(position);
				run.runtime.advance(++run.tick, run.policy);
				var invalid = run.runtime.step(run.consumer, 32).orElseThrow();
				assertEquals(CaptureResult.Availability.INVALID, invalid.result().availability(), change);
				assertTrue(invalid.slots().isEmpty()); assertEquals(reads, run.world.vault.getters);
				previous.clearRemoved(); previous.setBlockState(Blocks.BEEHIVE.defaultBlockState());
				if (!change.equals("root") && !change.equals("member")) run.world.entities.put(position, previous);
				run.consumer.restart(); run.runtime.advance(++run.tick, run.policy);
				assertEquals(InventorySourceAccess.Preparation.READY, run.runtime.prepare(run.consumer));
				var recovered = run.runtime.step(run.consumer, 32).orElseThrow();
				assertEquals(CaptureResult.Availability.READABLE, recovered.result().availability(), change);
				assertEquals(32, recovered.slots().size());
			}
		}
	}

	@NotNullByDefault
	private static final class Port implements InventoryMinecraftSources.SourceWorld {
		final CreateVaultTrackingPerformanceTest.World delegate = new CreateVaultTrackingPerformanceTest.World(Contents.REPEATED);
		Runnable metadata = () -> {};
		boolean ownerPresent = true;
		@Override public boolean isLoaded(BlockPos position) { return delegate.isLoaded(position); }
		@Override public BlockState blockState(BlockPos position) { metadata.run(); return delegate.blockState(position); }
		@Override public @Nullable BlockEntity blockEntity(BlockPos position) { return delegate.entities.get(position); }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() {
			if (!ownerPresent) throw new InventorySourceUnavailableException("owner unavailable");
			return ItemStack.EMPTY;
		}
		@Override public HolderLookup.Provider registries() { return delegate.registries(); }
		InventoryMinecraftSources.Source source() {
			var view = InventoryMinecraftSources.view(this, INPUT, delegate.vault::find);
			return new InventoryMinecraftSources.Source(INPUT, view, this::onServerThread,
				() -> { throw new AssertionError("in-place validation rebuilt the common view"); }, entry -> null, this, null);
		}
	}

	@Test void currentLocalReplacementAndGetterTimeFreezeSurviveSubsequentWorldMetadataButNotRevocation() {
		for (boolean revoke : List.of(false, true)) {
			var port = new Port();
			try (var source = port.source()) {
				var storage = new ItemStackHandler(20);
				var stack = new ItemStack(Items.DIAMOND, 17);
				var name = Component.literal("getter-time"); stack.set(DataComponents.CUSTOM_NAME, name);
				storage.setStackInSlot(0, stack); port.delegate.vault.members.get(BlockPos.ZERO).storage = storage;
				port.metadata = () -> {
					if (port.delegate.vault.getters > 0) {
						stack.setCount(0); name.append("-metadata");
						if (revoke) port.ownerPresent = false;
					}
				};
				if (revoke) assertThrows(InventorySourceUnavailableException.class, () -> source.read(0));
				else {
					var item = source.read(0); assertNotNull(item); assertEquals(17, item.count());
					assertEquals("minecraft:diamond", item.key().itemId()); assertFalse(item.displayJson().contains("metadata"));
				}
				assertEquals(1, port.delegate.vault.getters); assertEquals(0, stack.getCount());
				assertTrue(name.getString().contains("metadata"), "positive control: later metadata really mutated the resident");
			}
		}
	}
	@Test void metadataRevocationCannotAuthorizeTheFirstGetter() {
		var port = new Port();
		try (var source = port.source()) {
			port.metadata = () -> port.ownerPresent = false;
			assertThrows(InventorySourceUnavailableException.class, () -> source.read(0));
			assertEquals(0, port.delegate.vault.getters);
		}
	}

	@Test void zeroAndOneShortValidationAreCallbackFreeAndExactNativeThresholdSettlesEveryAttempt() throws ReflectiveOperationException {
		var field = InventoryRuntime.class.getDeclaredField("physical"); field.setAccessible(true);
		try (var run = new CreateVaultTrackingPerformanceTest.Run(Contents.EMPTY)) {
			run.prepare(); var source = java.util.Objects.requireNonNull(run.world.lastSource);
			var validation = source.evidenceValidationCost();
			for (int dimension = 0; dimension < 3; dimension++) {
				var shortCost = new InventoryValidationBudget.Cost(validation.allocationBytes() - (dimension == 0 ? 1 : 0),
					validation.workspaceBytes() - (dimension == 1 ? 1 : 0), validation.workUnits() - (dimension == 2 ? 1 : 0));
				run.world.resetCounters();
				assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> source.valid(new InventoryValidationBudget.Allowance(shortCost)));
				assertEquals(0, run.world.loaded + run.world.states + run.world.keys + run.world.vault.loaded);
			}
			long one = source.evidenceValidationCost().workUnits() * 2 + source.readValidationCost(1).workUnits();
			for (long shortBy : new long[] {one, 1, 0}) {
				run.policy.setPhysicalSlotsPerTick(IntLimit.finite(1)); run.runtime.advance(++run.tick, run.policy);
				CostLedger ledger = (CostLedger) field.get(run.runtime);
				long leave = one - shortBy + InventoryItemBudget.workUpperBound(source.displayBytes());
				long spend = ledger.remaining(WORK) - leave;
				try (var paid = ledger.tryReserve(java.util.Map.of(WORK, spend)).orElseThrow()) {
					paid.commit(java.util.Map.of(WORK, spend));
				}
				run.world.resetCounters();
				var page = run.runtime.step(run.consumer, 1);
				if (shortBy > 0) {
					assertTrue(page.isEmpty()); assertEquals(0, run.world.vault.getters);
					assertEquals(0, run.world.vault.loaded + run.world.loaded); assertFalse(run.consumer.invalidated());
				} else {
					assertEquals(1, page.orElseThrow().result().coverage().scanned()); assertEquals(1, run.world.vault.getters);
					assertEquals(1, run.world.vault.localAudits, "native read classifies its one current local, never the all-member capture audit");
				}
				assertEquals(0, run.runtime.memory().reserved());
			}
		}
	}

	@Test void failedCurrentEvidencePaysItsAttemptAndSharedConsumersDoNotRepeatPhysicalReads() {
		try (var run = new CreateVaultTrackingPerformanceTest.Run(Contents.REPEATED)) {
			var peer = run.runtime.attach(INPUT, new InventoryRuntime.PreviewSubject(new UUID(95, 3))).orElseThrow();
			run.prepare(); var source = java.util.Objects.requireNonNull(run.world.lastSource);
			var cost = source.evidenceValidationCost();
			assertSame(cost, source.evidenceValidationCost()); assertSame(source.readValidationCost(32), source.readValidationCost(32));
			var allowance = new InventoryValidationBudget.Allowance(cost);
			run.world.vault.available = false;
			assertFalse(source.valid(allowance)); assertEquals(cost.workUnits(), allowance.attemptedWorkUnits());
			assertEquals(cost.allocationBytes(), allowance.attemptedAllocationBytes());
			run.world.vault.available = true;
			try (peer) {
				run.step(); long getters = run.world.vault.getters, views = run.world.views;
				var replay = run.runtime.step(peer, 32).orElseThrow();
				assertEquals(32, replay.slots().size()); assertEquals(getters, run.world.vault.getters);
				assertEquals(views, run.world.views, "compatible prefix joins the same retained physical source");
				run.consumer.close();
				run.runtime.advance(++run.tick, run.policy);
				assertEquals(32, run.runtime.step(peer, 32).orElseThrow().slots().size());
				assertEquals(getters + 32, run.world.vault.getters);
			}
			run.runtime.close(); assertEquals(0, run.runtime.memory().retained()); assertEquals(0, run.runtime.memory().reserved());
		}
	}
}
