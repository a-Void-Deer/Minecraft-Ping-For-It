package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** UNRUN staged tests; no numerical A1 certificate is inferred from these fixtures. */
class InventoryObservationSnapshotTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	private static InventorySnapshotLayout layout() {
		var segment = new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, 3, List.of(2, 0));
		return new InventorySnapshotLayout("test:resident", "face=north", BlockPos.ZERO, List.of(
			new InventorySnapshotLayout.Member(BlockPos.ZERO, "minecraft:chest", "minecraft:chest", "controller", List.of(segment)),
			new InventorySnapshotLayout.Member(new BlockPos(1, 0, 0), "minecraft:chest", "minecraft:chest", "member", List.of(segment))));
	}
	private static int display() { return InventoryItemCodec.MAX_DISPLAY_BYTES; }
	private static InventorySnapshotLayout chestLayout() {
		var slots = java.util.stream.IntStream.range(0, 27).boxed().toList();
		return new InventorySnapshotLayout("minecraft:container", "face=north", BlockPos.ZERO, List.of(
			new InventorySnapshotLayout.Member(BlockPos.ZERO, "minecraft:chest", "minecraft:chest", "member",
				List.of(new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, 27, slots)))));
	}
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
	void highOldCeilingUsesOnlyOwnedInputsAndCompletesThroughActualHandleWithoutRecapture(boolean occupied) {
		var layout = chestLayout(); int oldCap = 96 * 1024;
		var settings = InventorySettings.serverDefaults();
		settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(24));
		settings.setFrameByteMultiplier(java.math.BigDecimal.valueOf(4)); settings.validate();
		assertEquals(oldCap, settings.effectiveDisplayBytes()); assertEquals(16, settings.getPendingMemoryMiB());
		var reads = new AtomicInteger(); var captures = new AtomicInteger(); var validations = new AtomicInteger(); var slotParses = new AtomicInteger();
		long amount = 9007199254740993L;
		var stack = new ItemStack(Items.STONE);
		var compatibility = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, oldCap);
		var resident = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, oldCap);
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:chest"), new UUID(19, occupied ? 1 : 0), BlockFace.NORTH);
		InventoryObservationSnapshot[] captured = new InventoryObservationSnapshot[1];
		var source = new InventorySourceAccess.Source() {
			int cap = oldCap;
			@Override public SourceKey key() { return new SourceKey("test", "inventory", "chest", input.viewKey()); }
			@Override public boolean valid() { validations.incrementAndGet(); return true; }
			@Override public boolean stableCursor() { return true; }
			@Override public int slots() { throw new AssertionError("capture visibility is already frozen"); }
			@Override public nx.pingwheel.common.presentation.inventory.InventoryDomainCodec.Item read(int slot) { throw new AssertionError("no live item reads"); }
			@Override public int displayBytes() { return cap; }
			@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) {
				return compatibility.reprojectionSummary(ceiling).plus(resident.reprojectionSummary(ceiling));
			}
			@Override public void tightenDisplay(int ceiling) { compatibility.tightenDisplay(ceiling); resident.tightenDisplay(ceiling); cap = Math.min(cap, ceiling); }
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() { return Optional.of(new InventorySourceAccess.SnapshotPlan() {
				@Override public long memoryUpperBoundBytes() { return InventoryObservationSnapshot.memoryUpperBound(layout, oldCap); }
				@Override public int physicalReadUpperBound() { return 27; }
				@Override public long itemWorkUpperBoundUnits() { return 27L * InventoryItemBudget.workUpperBound(oldCap); }
				@Override public int attemptedPhysicalReads() { return reads.get(); }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
					captures.incrementAndGet();
					captured[0] = InventoryObservationSnapshot.capture(layout, BlockPos.ZERO, RegistryAccess.EMPTY, oldCap, memoryUpperBoundBytes(), index -> {
						reads.incrementAndGet();
						return occupied && index == 0 ? new IPlatformInventoryService.ResidentEntry(new InventoryResidentItem.Stack(stack), amount)
							: IPlatformInventoryService.ResidentEntry.empty();
					}, () -> true);
					return Optional.of(captured[0]);
				}
			}); }
		};
		try (var runtime = new InventoryRuntime((ignored, cap) -> Optional.of(source), settings.pendingMemoryBytes(), (retained, cap) -> {
			slotParses.incrementAndGet(); return InventoryItemCodec.reproject(retained, RegistryAccess.EMPTY, cap);
		})) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
			var all = new java.util.ArrayList<nx.pingwheel.common.presentation.inventory.InventoryDomainCodec.Item>();
			InventoryRuntime.Observation page = null;
			for (int call = 0; all.size() < 27 && call < 27; call++) { page = runtime.step(consumer).orElseThrow(); all.addAll(page.slots()); }
			assertNotNull(page);
			var original = new InventoryRuntime.Observation(page.result(), all);
			assertEquals(27, original.slots().size()); assertEquals(CaptureResult.Completeness.COMPLETE, original.result().completeness());
			var summary = captured[0].reprojectionSummary(1024);
			assertEquals(occupied ? 2 : 0, summary.parserCalls(), "the frozen input and its lazy catalogue are separate actual parser calls");
			if (occupied) {
				String json = original.slots().getFirst().displayJson(); int bytes = utf8(json);
				assertTrue(bytes < 1024); assertEquals(2L * bytes, summary.inputBytes());
				assertEquals(2L * (InventoryItemBudget.reprojectionWorkUpperBound(bytes) + 64L * bytes), summary.parserWorkUnits());
				assertEquals(bytes, summary.maxInputBytes());
				assertEquals(InventoryItemBudget.reprojectionWorkspaceUpperBound(bytes) + summary.replacementBytes() + InventoryReprojectionSummary.METADATA_BYTES,
					summary.workspaceBytes(), "sequential repeated copies sum work, not parser scratch");
			} else { assertEquals(0, summary.inputBytes()); assertEquals(0, summary.parserWorkUnits()); assertEquals(0, summary.replacementBytes()); }
			int before = validations.get();
			settings.setDisplayByteMultiplier(new java.math.BigDecimal("0.25")); runtime.advance(1, settings);
			var result = runtime.reprojectDisplays(consumer, 1024);
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, result.status()); assertTrue(result.foldIds().isEmpty());
			assertEquals(1024, captured[0].displayBytes()); assertEquals(27, reads.get()); assertEquals(1, captures.get());
			assertEquals(before, validations.get(), "tightening never calls a provider validity callback"); assertEquals(0, slotParses.get(), "fitting decoded slots do not parse");
			var continued = runtime.step(consumer).orElseThrow(); assertTrue(continued.slots().isEmpty());
			assertEquals(27, continued.result().coverage().scanned(), "tightening does not rewind the consumer cursor");
			if (occupied) { var retained = captured[0].read(0); assertEquals(original.slots().getFirst().key(), retained.key()); assertEquals(amount, retained.count()); assertFalse(retained.aggregateRequired()); }
			assertEquals(InventoryRuntime.ReprojectionStatus.UNCHANGED, runtime.reprojectDisplays(consumer, oldCap).status());
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void reusingProviderStackIsFrozenBeforeNextGetterAndAmountsStayIndependentLongs() {
		var layout = layout(); var resident = new ItemStack(Items.STONE, 7); var reads = new AtomicInteger();
		try (var snapshot = InventoryObservationSnapshot.capture(layout, new BlockPos(1, 0, 0), RegistryAccess.EMPTY, display(),
			InventoryObservationSnapshot.memoryUpperBound(layout, display()), index -> {
				reads.incrementAndGet(); resident.set(DataComponents.CUSTOM_NAME, Component.literal("slot-" + index));
				return new IPlatformInventoryService.ResidentEntry(new InventoryResidentItem.Stack(resident), 9007199254740993L + index);
			}, () -> true)) {
			resident.set(DataComponents.CUSTOM_NAME, Component.literal("mutated")); resident.setCount(99);
			assertEquals(layout, snapshot.layout()); assertEquals(new BlockPos(1, 0, 0), snapshot.originalTarget());
			assertEquals(4, reads.get()); assertEquals(4, snapshot.slots());
			for (int i = 0; i < 4; i++) {
				var item = snapshot.read(i); assertEquals(9007199254740993L + i, item.count());
				assertFalse(item.aggregateRequired()); assertTrue(item.displayJson().contains("slot-" + i));
				if (i > 0) assertNotEquals(snapshot.read(i - 1).key(), item.key());
			}
			assertEquals(4, reads.get(), "consumption never reacquires a provider value");
		}
	}
	@Test void tighteningReprojectsUnreadFrozenDisplaysSoLaterReadsNeverParseTheOldInput() {
		var layout = layout(); int wideCap = 32768;
		ItemStack stack = new ItemStack(Items.STONE, 4);
		CompoundTag data = new CompoundTag();
		data.putString("payload", "x".repeat(6000));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		var expected = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, wideCap).encode(stack);
		assertTrue(utf8(expected.display().displayJson()) > InventoryItemCodec.MAX_DISPLAY_BYTES, "the OLD parser input actually exceeds the new cap");
		try (var snapshot = InventoryObservationSnapshot.capture(layout, BlockPos.ZERO, RegistryAccess.EMPTY, wideCap,
			InventoryObservationSnapshot.memoryUpperBound(layout, wideCap), index ->
				index == 0 ? new IPlatformInventoryService.ResidentEntry(new InventoryResidentItem.Stack(stack), 4)
					: IPlatformInventoryService.ResidentEntry.empty(), () -> true)) {
			assertEquals(wideCap, snapshot.displayBytes());
			long before = snapshot.retainedBytes();
			snapshot.tightenDisplay(InventoryItemCodec.MAX_DISPLAY_BYTES);
			assertEquals(InventoryItemCodec.MAX_DISPLAY_BYTES, snapshot.displayBytes());
			long after = snapshot.retainedBytes();
			assertTrue(after < before, "the unread frozen display is reprojected inside the tightening call");
			var item = snapshot.read(0);
			assertEquals(expected.key(), item.key());
			assertEquals(4L, item.count());
			assertTrue(utf8(item.displayJson()) <= InventoryItemCodec.MAX_DISPLAY_BYTES);
			assertFalse(item.aggregateRequired());
			assertEquals(2L * (expected.display().displayJson().length() - item.displayJson().length()), before - after,
				"only the replaced UTF-16 display ownership is refunded");
			snapshot.tightenDisplay(wideCap); assertEquals(after, snapshot.retainedBytes());
			snapshot.close(); snapshot.close(); assertEquals(0, snapshot.retainedBytes());
		}
	}
	@Test void displayReplacementSharesTheChargedCanonicalBufferAndKeepsExactIdentityAndDisposition() {
		byte[] canonical = new byte[InventoryItemCodec.MAX_CANONICAL_BYTES]; // much larger than the replacement display
		var previous = new InventoryItemCodec.Display("minecraft:stone", "block.minecraft.stone", "x".repeat(6000), false);
		var tightened = new InventoryItemCodec.Display(previous.itemId(), previous.label(), "{}", false);
		long retained = canonical.length + 512L + 2L * (previous.itemId().length() + previous.label().length() + previous.displayJson().length());
		var original = new InventoryDetachedItem(previous.itemId(), InventoryIdentityMode.EXACT, previous, canonical, retained);
		var replacement = original.withDisplay(tightened);
		assertSame(canonical, replacement.canonicalForCatalog(), "tightening must not create another uncharged canonical owner");
		assertSame(original.canonicalForCatalog(), replacement.canonicalForCatalog());
		assertEquals(original.itemId(), replacement.itemId()); assertEquals(InventoryIdentityMode.EXACT, replacement.identityMode());
		assertEquals(2L * (previous.displayJson().length() - tightened.displayJson().length()), original.retainedBytes() - replacement.retainedBytes());
		assertThrows(IllegalArgumentException.class, () -> replacement.withDisplay(previous), "tightening cannot increase ownership");
	}
	private static int utf8(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
	@Test void validationBudgetTermsAreExplicitSymbolicCountsWithIndependentExpectedValues() {
		var layout = layout(); // two members, one [2,0] segment each: four visible slots
		long members = 2, passes = 4 + InventoryObservationSnapshot.FIXED_VALIDATION_PASSES;
		var validation = InventoryValidationBudget.capture(layout, false, false, 0);
		assertTrue(validation.allocationBytes() > validation.workspaceBytes(),
			"attempted allocation accumulates across validations without retaining all transient reflection objects");
		assertTrue(InventoryObservationSnapshot.validationStructureBytes(layout) > 0);
		// Literal lock matching uses only the root budget, not the whole-read Catalog allowance.
		long certificate = InventoryItemBudget.freezeWorkUpperBound(InventoryItemCodec.minimumDisplayBytes());
		assertEquals(passes * members * certificate, InventoryObservationSnapshot.lockValidationWorkUpperBound(layout, members),
			"nonempty lock matching is admitted at the single-call certificate multiplicity");
		assertEquals(0L, InventoryObservationSnapshot.lockValidationWorkUpperBound(layout, 0),
			"a capture whose members cannot run the lock path admits no lock work");
		assertTrue(InventoryObservationSnapshot.memoryUpperBound(layout, display())
			>= InventoryObservationSnapshot.validationStructureBytes(layout) + InventoryItemBudget.workspaceUpperBound(display()),
			"the snapshot reservation covers the validation structure beside the existing terms");
	}
	@Test void validationDebitPrecedesTheNextLayoutAndNeverRefundsAnAttemptedPrefix() {
		var one = InventoryValidationBudget.vaultLayout(2);
		var cumulative = one.times(3);
		assertEquals(one.workspaceBytes(), cumulative.workspaceBytes(), "sequential layouts reuse peak workspace");
		assertEquals(3 * one.allocationBytes(), cumulative.allocationBytes());
		AtomicInteger layouts = new AtomicInteger(), contents = new AtomicInteger();
		var shortGrant = new InventoryValidationBudget.Allowance(new InventoryValidationBudget.Cost(
			2 * one.allocationBytes(), one.workspaceBytes(), 3 * one.workUnits()));
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> {
			for (int call = 0; call < 3; call++) { shortGrant.debit(one); layouts.incrementAndGet(); }
			contents.incrementAndGet();
		});
		assertEquals(2, layouts.get()); assertEquals(0, contents.get());
		assertEquals(2 * one.allocationBytes(), shortGrant.attemptedAllocationBytes());
		assertEquals(2 * one.workUnits(), shortGrant.attemptedWorkUnits());
		// Positive control of the former omission: merely reserving the same
		// capacity without debiting lets the third allocation/content run.
		for (int call = 0; call < 3; call++) layouts.incrementAndGet();
		contents.incrementAndGet(); assertEquals(5, layouts.get()); assertEquals(1, contents.get());
	}
	@Test void tinyGenericAndNativeVaultProfilesFitDefaultAdmissionWithSymbolicThreePointOneMiBItemWorkspace() {
		// This is a conditional B profile certificate, not an assertion that the
		// concurrently repaired A1 implementation already exports this W.
		long candidateW = 3_250_586L; // ceil(3.1 MiB), an independent input
		long runtimeRoundAndConsumer = 299_008L + 4096L;
		long workCap = 4096L * InventoryItemBudget.workUpperBound(display());
		for (String shape : List.of("generic1", "vault12", "ordinary27", "ordinary54")) {
			boolean vault = shape.equals("vault12"), provider = shape.equals("generic1") || vault;
			int members = shape.equals("ordinary54") ? 2 : 1;
			int slots = shape.equals("generic1") ? 1 : vault ? 12 : 27;
			String block = vault ? "create:item_vault" : provider ? "minecraft:beehive" : "minecraft:chest";
			var topology = new java.util.ArrayList<InventorySnapshotLayout.Member>();
			for (int member = 0; member < members; member++) topology.add(new InventorySnapshotLayout.Member(new BlockPos(member, 0, 0), block, block, "member",
				List.of(new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, slots, java.util.stream.IntStream.range(0, slots).boxed().toList()))));
			var layout = new InventorySnapshotLayout(vault ? "test:vault" : provider ? "test:registered" : members == 2 ? "minecraft:double_chest" : "minecraft:container", "face=north", BlockPos.ZERO, topology);
			long locks = vault ? 0 : members;
			var noLockDiscovery = InventoryValidationBudget.discovery(layout, provider, vault, 0)
				.plus(InventoryValidationBudget.viewValidation(layout, vault, 0).times(2));
			long planWithoutItemW = InventoryObservationSnapshot.memoryUpperBound(layout, display(), provider, vault, 0)
				- InventoryItemBudget.workspaceUpperBound(display());
			// Adding rather than max-ing lock scratch is conservative for every
			// primitive overlap. Sequential member locks never multiply the peak.
			long lockW = locks == 0 ? 0 : candidateW;
			long combined = runtimeRoundAndConsumer + planWithoutItemW + candidateW + lockW
				+ Math.max(candidateW, noLockDiscovery.workspaceBytes() + lockW);
			assertTrue(combined < 16_000_000L, shape + " combined candidate peak=" + combined);
			long discoveryWork = InventoryValidationBudget.discovery(layout, provider, vault, locks)
				.plus(InventoryValidationBudget.viewValidation(layout, vault, locks).times(2)).workUnits();
			long captureWork = InventoryValidationBudget.capture(layout, provider, vault, locks).workUnits()
				+ InventoryObservationSnapshot.visibleSlots(layout) * InventoryItemBudget.workUpperBound(display());
			assertTrue(discoveryWork + captureWork <= workCap, shape + " fits one actual default work tick");
		}
	}
	@Test void genericShapeRefusalPaysOnlyItsAttemptAndANewGrantCanReachLiveWithoutExpandedMemberWork() {
		var expected = layout(); var cost = InventoryValidationBudget.view(expected, false);
		var workCounter = new CostLedger.Counter("test/discovery", CostLedger.Unit.WORK);
		var ledger = new CostLedger(Map.of(workCounter, 2 * cost.workUnits()));
		var memory = new RetainedMemoryLedger(16_000_000L);
		var changed = new InventorySnapshotLayout(expected.layoutId(), "changed", expected.controller(), expected.members());
		for (var returned : List.of(changed, expected)) {
			try (var ticket = ledger.tryReserve(Map.of(workCounter, cost.workUnits())).orElseThrow();
				var scratch = memory.tryReserve(cost.workspaceBytes()).orElseThrow()) {
				var allowance = new InventoryValidationBudget.Allowance(cost);
				try {
					allowance.debit(cost);
					if (returned == changed) assertThrows(IllegalStateException.class, () -> InventoryValidationBudget.requireLayout(expected, returned));
					else assertDoesNotThrow(() -> InventoryValidationBudget.requireLayout(expected, returned));
				} finally { ticket.commit(Map.of(workCounter, allowance.attemptedWorkUnits())); }
			}
			assertEquals(0, memory.reserved()); assertEquals(0, memory.retained());
		}
		assertEquals(2 * cost.workUnits(), ledger.used(workCounter), "neither failed shape evidence nor retry refunds attempted work");
	}
	@Test void emptySlotsCountAndFinalRevocationDiscardsRatherThanPublishingPartialAtomicData() {
		AtomicInteger reads = new AtomicInteger(); var layout = layout();
		try (var snapshot = InventoryObservationSnapshot.capture(layout, BlockPos.ZERO, RegistryAccess.EMPTY, display(),
			InventoryObservationSnapshot.memoryUpperBound(layout, display()), index -> {
				reads.incrementAndGet(); return IPlatformInventoryService.ResidentEntry.empty();
			}, () -> true)) {
			assertEquals(4, reads.get()); for (int i = 0; i < 4; i++) assertNull(snapshot.read(i));
		}
		reads.set(0);
		assertThrows(IllegalStateException.class, () -> InventoryObservationSnapshot.capture(layout, BlockPos.ZERO, RegistryAccess.EMPTY,
			display(), InventoryObservationSnapshot.memoryUpperBound(layout, display()), index -> {
				reads.incrementAndGet(); return IPlatformInventoryService.ResidentEntry.empty();
			}, () -> reads.get() < 4));
		assertEquals(4, reads.get(), "the final all-member gate matters even after all slots were attempted");
	}
	@Test void memoryRefusalDoesZeroContentsThenOneLaterCaptureAndRetainsLazyCatalogReserve() {
		var layout = layout(); AtomicInteger reads = new AtomicInteger(), captures = new AtomicInteger();
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:chest"), new UUID(9, 9), BlockFace.NORTH);
		long upper = InventoryObservationSnapshot.memoryUpperBound(layout, display());
		var source = new InventorySourceAccess.Source() {
			@Override public SourceKey key() { return new SourceKey("test", "inventory", "resident", input.viewKey()); }
			@Override public boolean valid() { return true; }
			@Override public boolean stableCursor() { return true; }
			@Override public int slots() { return 4; }
			@Override public nx.pingwheel.common.presentation.inventory.InventoryDomainCodec.Item read(int slot) { throw new AssertionError("live fallback"); }
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() { return Optional.of(new InventorySourceAccess.SnapshotPlan() {
				@Override public long memoryUpperBoundBytes() { return upper; }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
					captures.incrementAndGet();
					return Optional.of(InventoryObservationSnapshot.capture(layout, BlockPos.ZERO, RegistryAccess.EMPTY, display(), upper,
						index -> { reads.incrementAndGet(); return IPlatformInventoryService.ResidentReads.stack(new ItemStack(Items.STONE)); }, () -> true));
				}
			}); }
		};
		var access = new InventorySourceAccess(input, ignored -> Optional.of(source));
		var scope = new SourceAccess.ReadScope(input.viewKey(), java.util.Set.of("pingforit:inventory.items"));
		var ledger = new CostLedger(Map.of(InventorySourceAccess.PROBES, 8L, InventorySourceAccess.PROVIDER_WORK, 65536L, InventorySourceAccess.PHYSICAL, 64L));
		var target = new PresentationAdapter.DetachedTarget("minecraft:overworld", "block", "minecraft:chest", 0, 0, 0, "");
		var resolved = (SourceAccess.ResolveResult.Available) access.resolve(target, scope, ledger);
		var started = (SourceAccess.OpenResult.Started) access.open(resolved.descriptor(), scope, ledger);
		var memory = new RetainedMemoryLedger(upper - 1);
		try (var handle = (InventorySourceAccess.InventoryHandle) started.handle()) {
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, handle.prepareSnapshot(memory));
			assertEquals(0, reads.get()); assertEquals(0, captures.get()); assertEquals(0, memory.reserved());
			memory.setCap(upper);
			assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
			long settled = memory.retained(); assertTrue(settled > 0); assertTrue(settled <= upper);
			assertEquals(4, reads.get()); assertEquals(1, captures.get());
			handle.step(ledger); assertEquals(settled, memory.retained(), "lazy catalogue growth was reserved at settlement");
			handle.prepareSnapshot(memory); assertEquals(1, captures.get());
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
}
