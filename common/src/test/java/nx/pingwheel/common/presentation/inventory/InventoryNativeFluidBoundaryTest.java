package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Runtime-level native boundaries: paid address observations are not completion or authority. */
@NotNullByDefault
class InventoryNativeFluidBoundaryTest {

	private static final CostLedger.Counter WORK = new CostLedger.Counter("inventory/item_work", CostLedger.Unit.WORK);
	private static final InventoryValidationBudget.Cost LOCK_CHECK = new InventoryValidationBudget.Cost(64, 64, 3);
	private static final InventorySourceInput INPUT = new InventorySourceInput(InventoryRuntimeTest.TARGET,
		new UUID(0x257, 2), BlockFace.NORTH, InventoryResourceKind.FLUID);

	@NotNullByDefault
	private static final class Source implements InventorySourceAccess.Source {
		final int slots;
		final InventoryFluidCodec.Catalog catalog = new InventoryFluidCodec.Catalog();
		final IntFunction<String> ids;
		final boolean aggregate;
		final boolean bypassCatalog;
		InventorySourceInput input = INPUT;
		@Nullable InventoryRuntime runtime;
		boolean starveFinal, finalDenied, finalThrows, repeatIdentity;
		int reads, closes, finalChecks;
		Source(int slots, IntFunction<String> ids, boolean aggregate, boolean bypassCatalog) {
			this.slots = slots; this.ids = ids; this.aggregate = aggregate; this.bypassCatalog = bypassCatalog;
		}
		@Override public SourceKey key() { return new SourceKey("test", "fluid", "boundary", input.viewKey()); }
		@Override public boolean valid() { throw new AssertionError("current checks require the caller's actual grant"); }
		@Override public boolean valid(InventoryValidationBudget.Allowance allowance) {
			if (reads == slots && starveFinal) {
				finalChecks++;
				try (var admission = allowance.fluidGrants().open(allowance, InventoryValidationBudget.NONE, LOCK_CHECK);
					var operation = admission.enter(new IPlatformFluidService.OperationProfile(InventoryValidationBudget.NONE, 0, 1, 0))) {
					operation.allowance().debit(LOCK_CHECK);
				}
				if (finalThrows) throw new IllegalStateException("current identity changed");
				return !finalDenied;
			}
			return true;
		}
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return slots; }
		@Override public InventorySourceAccess.ReadMode readMode() { return InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL; }
		@Override public long nativeResourceRetainedBytes() { return bypassCatalog ? 0 : catalog.modeledRetainedBytes(); }
		@Override public long nativeCatalogGrowthUpperBound() { return InventoryFluidCodec.Catalog.retainedGrowthUpperBound(); }
		@Override public boolean certifiedResourceSharing() { return false; }
		@Override public InventoryReprojectionSummary reprojectionSummary(int cap) { return InventoryReprojectionSummary.EMPTY; }
		@Override public InventoryDomainCodec.@Nullable Item read(int slot) { throw new AssertionError("fluid must not use item getters"); }
		@Override public @Nullable InventoryResourceValue readResource(int slot, @Nullable InventoryCatalogAdmission admission,
			InventoryValidationBudget.Allowance allowance) {
			assertEquals(reads, slot, "a paid cursor must not restart"); reads++;
			String id = ids.apply(slot);
			var display = new InventoryFluidValue.Display(id, "fluid", null);
			try {
				return bypassCatalog ? new InventoryFluidValue(new InventoryScanner.Key(InventoryResourceKind.FLUID, id, "exact"), slot + 1L, display, false)
					: catalog.intern(id, slot + 1L, aggregate ? InventoryFluidCodec.Identity.refused()
						: InventoryFluidCodec.Identity.exact(new byte[] {10, 3, 0, 1, 'v', 0, 0, 0, (byte) (repeatIdentity ? 0 : slot / 2), 0}), display,
						java.util.Objects.requireNonNull(admission));
			} finally {
				if (starveFinal && reads == slots) {
					var owner = java.util.Objects.requireNonNull(runtime);
					// The real staged lock grant costs 128 bookkeeping + 3 callback work.
					// Leave it exactly one short while the existing page reservations remain held.
					assertTrue(owner.claimDetachedItemWork(physical(owner).remaining(WORK) - 130));
				}
			}
		}
		@Override public void close() { closes++; }
	}

	private static CostLedger physical(InventoryRuntime runtime) {
		try { var field = InventoryRuntime.class.getDeclaredField("physical"); field.setAccessible(true); return (CostLedger) field.get(runtime); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static void assertSettled(InventoryRuntime runtime) {
		assertEquals(0, runtime.memory().reserved());
		var ledger = physical(runtime);
		for (var counter : java.util.List.of(WORK, InventorySourceAccess.PHYSICAL, InventorySourceAccess.PROBES, InventorySourceAccess.PROVIDER_WORK))
			assertEquals(ledger.limit(counter) - ledger.used(counter), ledger.remaining(counter), "outstanding ticket: " + counter);
	}
	private static InventoryRuntime runtime(Source source, InventorySettings settings) {
		var runtime = new InventoryRuntime(input -> Optional.of(source), settings.pendingMemoryBytes()); source.runtime = runtime; return runtime;
	}
	private static InventoryRuntime.Observation finish(InventoryRuntime runtime, InventoryRuntime.Consumer consumer, InventorySettings settings,
		List<@Nullable InventoryResourceValue> values) {
		for (int tick = 0; tick < 100; tick++) {
			runtime.advance(tick, settings);
			var next = assertDoesNotThrow(() -> runtime.step(consumer));
			if (next.isEmpty()) continue;
			values.addAll(next.get().slots());
			if (next.get().result().completeness() != CaptureResult.Completeness.CONTINUE) return next.get();
		}
		throw new AssertionError("native source did not reach a terminal observation");
	}
	@Test void twoFluidIdsWith129EncodingsReachTheCompleteDefinitionGuardWithoutEscapingTheTick() {
		var settings = InventorySettings.serverDefaults();
		var source = new Source(257, slot -> (slot & 1) == 0 ? "minecraft:water" : "minecraft:lava", false, false);
		var runtime = runtime(source, settings);
		try (runtime) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(INPUT, new InventoryRuntime.PreviewSubject(INPUT.readOwner())).orElseThrow();
			var values = new ArrayList<@Nullable InventoryResourceValue>(); var terminal = finish(runtime, consumer, settings, values);
			assertEquals(CaptureResult.Availability.READABLE, terminal.result().availability());
			assertEquals(CaptureResult.Completeness.INCOMPLETE, terminal.result().completeness());
			assertEquals(256, values.size()); assertEquals(257, source.reads);
			assertEquals(256, terminal.result().coverage().scanned()); assertEquals(257, terminal.result().coverage().expected().orElseThrow());
			for (int index = 0; index < 256; index++) assertEquals(index + 1L, java.util.Objects.requireNonNull(values.get(index)).amount());
			assertThrows(IllegalStateException.class, consumer::retainCompleted);
			assertSettled(runtime); assertTrue(runtime.memory().retained() > 0);
		}
		assertEquals(1, source.closes); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void finalStagedCheckRetriesThroughRuntimeWithNoRemainingLogicalOrPhysicalAddresses() {
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		var source = new Source(1, slot -> "minecraft:water", false, false); source.starveFinal = true;
		var runtime = runtime(source, settings);
		try (runtime) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(INPUT, new InventoryRuntime.PreviewSubject(INPUT.readOwner())).orElseThrow();
			var prefix = runtime.step(consumer).orElseThrow();
			assertEquals(CaptureResult.Completeness.CONTINUE, prefix.result().completeness());
			assertEquals(1, prefix.slots().size()); assertEquals(1, source.reads); assertEquals(1, source.finalChecks);
			runtime.advance(1, settings);
			var spent = physical(runtime).tryReserve(java.util.Map.of(InventorySourceAccess.PHYSICAL, 1L)).orElseThrow();
			spent.commit(java.util.Map.of(InventorySourceAccess.PHYSICAL, 1L));
			var complete = runtime.step(consumer).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, complete.result().completeness());
			assertEquals(1, complete.result().coverage().scanned()); assertEquals(1, complete.result().coverage().expected().orElseThrow());
			assertTrue(complete.slots().isEmpty()); assertEquals(1, source.reads); assertEquals(2, source.finalChecks);
			assertEquals(0, physical(runtime).remaining(InventorySourceAccess.PHYSICAL), "no physical grant is needed to finish metadata");
			consumer.retainCompleted(); assertSettled(runtime);
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void aggregateOnlyAndExtensionJournalExhaustionKeepTheValidatedPrefixAndOtherWorkServiceable() {
		for (boolean bypass : new boolean[] {false, true}) {
			var settings = InventorySettings.serverDefaults();
			var source = new Source(257, slot -> "test:fluid_" + slot, true, bypass);
			var itemReads = new java.util.concurrent.atomic.AtomicInteger();
			var runtime = new InventoryRuntime(input -> Optional.of(input.resourceKind() == InventoryResourceKind.FLUID ? source
				: new InventoryRuntimeTest.Source(input, itemReads, 1)), settings.pendingMemoryBytes());
			try (runtime) {
				runtime.advance(0, settings);
				var consumer = runtime.attach(INPUT, new InventoryRuntime.TrackingSubject(TargetKey.from(INPUT.target()))).orElseThrow();
				var values = new ArrayList<@Nullable InventoryResourceValue>(); var terminal = finish(runtime, consumer, settings, values);
				assertEquals(CaptureResult.Completeness.INCOMPLETE, terminal.result().completeness());
				assertEquals(256, terminal.result().coverage().scanned()); assertEquals(256, values.size()); assertEquals(257, source.reads);
				assertThrows(IllegalStateException.class, consumer::retainCompleted);
				long paid = physical(runtime).used(InventorySourceAccess.PHYSICAL);
				runtime.step(consumer); assertEquals(paid, physical(runtime).used(InventorySourceAccess.PHYSICAL)); assertEquals(257, source.reads);
				var itemInput = new InventorySourceInput(INPUT.target(), INPUT.readOwner(), INPUT.face());
				var item = runtime.attach(itemInput, new InventoryRuntime.TrackingSubject(TargetKey.from(INPUT.target()))).orElseThrow();
				assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(item).orElseThrow().result().completeness());
				assertEquals(1, itemReads.get()); assertEquals(0, runtime.memory().reserved());
			}
			assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void repeatedCompleteKeyDoesNotSpendAnotherDefinitionAndKeepsEveryAuthoritativeAmount() {
		var settings = InventorySettings.serverDefaults();
		var source = new Source(300, slot -> "minecraft:water", false, false); source.repeatIdentity = true;
		var runtime = runtime(source, settings);
		try (runtime) {
			runtime.advance(0, settings); var consumer = runtime.attach(INPUT, new InventoryRuntime.TrackingSubject(TargetKey.from(INPUT.target()))).orElseThrow();
			var values = new ArrayList<@Nullable InventoryResourceValue>(); var terminal = finish(runtime, consumer, settings, values);
			assertEquals(CaptureResult.Completeness.COMPLETE, terminal.result().completeness());
			assertEquals(300, source.reads); assertEquals(300, values.size());
			assertEquals(45_150, values.stream().mapToLong(value -> java.util.Objects.requireNonNull(value).amount()).sum());
			assertEquals(1, values.stream().map(value -> java.util.Objects.requireNonNull(value).key()).distinct().count());
			consumer.retainCompleted(); assertEquals(0, runtime.memory().reserved());
		}
		assertEquals(0, runtime.memory().retained());
	}
	@Test void definitionRefusalInTheMiddleOfOnePaidPagePublishesThatPagesCommittedPrefix() {
		var settings = InventorySettings.serverDefaults();
		var source = new Source(257, slot -> "test:fluid_" + slot, true, false); var runtime = runtime(source, settings);
		try (runtime) {
			runtime.advance(0, settings); var consumer = runtime.attach(INPUT, new InventoryRuntime.PreviewSubject(INPUT.readOwner())).orElseThrow();
			int retained = 0;
			for (int tick = 0; tick < 60; tick++) {
				runtime.advance(tick, settings); var next = runtime.step(consumer, 7);
				if (next.isEmpty()) continue;
				retained += next.get().slots().size();
				if (next.get().result().completeness() == CaptureResult.Completeness.CONTINUE) continue;
				assertEquals(CaptureResult.Completeness.INCOMPLETE, next.get().result().completeness());
				assertEquals(4, next.get().slots().size(), "252 prior addresses leave four usable definitions in this five-attempt page");
				assertEquals(253, java.util.Objects.requireNonNull(next.get().slots().getFirst()).amount());
				assertEquals(256, java.util.Objects.requireNonNull(next.get().slots().getLast()).amount());
				assertEquals(256, retained); assertEquals(257, source.reads);
				assertEquals(5, physical(runtime).used(InventorySourceAccess.PHYSICAL)); assertSettled(runtime);
				return;
			}
			fail("capacity refusal never became an explicit terminal prefix");
		}
	}
	@Test void repeatedFinalGrantDefersDoNotRereadGrowTheJournalOrRefundAttempts() {
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(2));
		settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(2));
		var source = new Source(2, slot -> "minecraft:water", false, false); source.starveFinal = true;
		var runtime = runtime(source, settings);
		try (runtime) {
			runtime.advance(0, settings); var consumer = runtime.attach(INPUT, new InventoryRuntime.TrackingSubject(TargetKey.from(INPUT.target()))).orElseThrow();
			var prefix = runtime.step(consumer).orElseThrow(); assertEquals(2, prefix.slots().size());
			assertEquals(CaptureResult.Completeness.CONTINUE, prefix.result().completeness());
			long retained = runtime.memory().retained();
			for (int tick = 1; tick <= 3; tick++) {
				runtime.advance(tick, settings);
				assertTrue(runtime.claimDetachedItemWork(physical(runtime).remaining(WORK) - 130));
				assertTrue(runtime.step(consumer).isEmpty());
				assertEquals(2, source.reads); assertEquals(retained, runtime.memory().retained()); assertSettled(runtime);
				assertEquals(0, physical(runtime).used(InventorySourceAccess.PHYSICAL));
			}
			runtime.advance(4, settings); var complete = runtime.step(consumer).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, complete.result().completeness()); assertTrue(complete.slots().isEmpty());
			assertEquals(2, complete.result().coverage().scanned()); assertEquals(2, source.reads);
			assertEquals(3, prefix.slots().stream().mapToLong(value -> java.util.Objects.requireNonNull(value).amount()).sum());
			consumer.retainCompleted();
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void capacityFailureWhoseFinalCheckDefersRetriesOnlyMetadataAndRemainsIncomplete() {
		var settings = InventorySettings.serverDefaults();
		var source = new Source(257, slot -> "test:fluid_" + slot, true, false); source.starveFinal = true;
		var runtime = runtime(source, settings);
		try (runtime) {
			runtime.advance(0, settings); var consumer = runtime.attach(INPUT, new InventoryRuntime.PreviewSubject(INPUT.readOwner())).orElseThrow();
			var values = new ArrayList<@Nullable InventoryResourceValue>(); var terminal = finish(runtime, consumer, settings, values);
			assertEquals(CaptureResult.Availability.READABLE, terminal.result().availability());
			assertEquals(CaptureResult.Completeness.INCOMPLETE, terminal.result().completeness());
			assertEquals(256, values.size()); assertEquals(256, terminal.result().coverage().scanned()); assertEquals(257, source.reads);
			assertEquals(2, source.finalChecks, "the capacity failure's deferred authority check must be resumed");
			assertThrows(IllegalStateException.class, consumer::retainCompleted); assertSettled(runtime);
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void journalCapacityFailureCannotBeReopenedByTheSourcesPendingFinalCheck() {
		var settings = InventorySettings.serverDefaults(); settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(257));
		var source = new Source(257, slot -> "test:fluid_" + slot, false, true); source.starveFinal = true;
		var runtime = runtime(source, settings);
		try (runtime) {
			runtime.advance(0, settings); var consumer = runtime.attach(INPUT, new InventoryRuntime.PreviewSubject(INPUT.readOwner())).orElseThrow();
			var values = new ArrayList<@Nullable InventoryResourceValue>();
			// The extension's last paid source check is pending when journal admission fails.
			var terminal = finish(runtime, consumer, settings, values);
			assertEquals(CaptureResult.Completeness.INCOMPLETE, terminal.result().completeness());
			settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(256));
			for (int retry = 0; retry < 3; retry++) {
				runtime.advance(101 + retry, settings); var next = runtime.step(consumer);
				assertTrue(next.isEmpty() || next.get().result().completeness() == CaptureResult.Completeness.INCOMPLETE);
				assertThrows(IllegalStateException.class, consumer::retainCompleted);
			}
			assertEquals(257, source.reads); assertSettled(runtime);
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void pendingFinalCheckFailsClosedOnCurrentRevocationOrIdentityException() {
		for (boolean throwing : new boolean[] {false, true}) {
			var settings = InventorySettings.serverDefaults();
			var source = new Source(1, slot -> "minecraft:water", false, false); source.starveFinal = true;
			var runtime = runtime(source, settings);
			try (runtime) {
				runtime.advance(0, settings); var consumer = runtime.attach(INPUT, new InventoryRuntime.TrackingSubject(TargetKey.from(INPUT.target()))).orElseThrow();
				assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(consumer).orElseThrow().result().completeness());
				source.finalDenied = !throwing; source.finalThrows = throwing;
				runtime.advance(1, settings); var invalid = runtime.step(consumer).orElseThrow();
				assertNotEquals(CaptureResult.Availability.READABLE, invalid.result().availability());
				assertEquals(CaptureResult.Completeness.INCOMPLETE, invalid.result().completeness());
				assertTrue(invalid.slots().isEmpty()); assertTrue(consumer.invalidated()); assertEquals(1, source.reads);
				assertThrows(IllegalStateException.class, consumer::retainCompleted); assertEquals(0, runtime.memory().reserved());
			}
			assertEquals(0, runtime.memory().retained());
		}
	}
	@Test void zeroAddressSourceStillCompletesOnlyUnderItsCurrentMetadataGrant() {
		var settings = InventorySettings.serverDefaults(); var source = new Source(0, slot -> "minecraft:water", false, false); source.starveFinal = true;
		var runtime = runtime(source, settings);
		try (runtime) {
			runtime.advance(0, settings); var consumer = runtime.attach(INPUT, new InventoryRuntime.TrackingSubject(TargetKey.from(INPUT.target()))).orElseThrow();
			var complete = runtime.step(consumer).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, complete.result().completeness()); assertTrue(complete.slots().isEmpty());
			assertEquals(0, complete.result().coverage().expected().orElseThrow()); assertEquals(0, source.reads);
			assertEquals(0, physical(runtime).used(InventorySourceAccess.PHYSICAL)); consumer.retainCompleted();
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void backendPublishesTheAccumulatedTrackingTotalAfterMetadataOnlyFinalization() {
		var settings = InventorySettings.serverDefaults(); var host = new InventoryBackendTest.Host();
		host.policies.replaceAll((owner, policy) -> new InventoryBackend.Policy(100, 1, java.util.Set.of(), java.util.Set.of("entity_block")));
		var sources = new ArrayList<Source>();
		var runtimeOwner = new java.util.concurrent.atomic.AtomicReference<InventoryRuntime>();
		var trackingScan = new java.util.concurrent.atomic.AtomicBoolean();
		var runtime = new InventoryRuntime(input -> {
			var source = new Source(trackingScan.get() ? 2 : 1, slot -> "minecraft:water", false, false);
			source.input = input; source.starveFinal = trackingScan.get(); source.repeatIdentity = true;
			source.runtime = java.util.Objects.requireNonNull(runtimeOwner.get()); sources.add(source);
			return Optional.of(source);
		}, settings.pendingMemoryBytes());
		runtimeOwner.set(runtime);
		try (var backend = new InventoryBackend(host, runtime, 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1,
				InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings);
			backend.tick(0, settings);
			var preview = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW
				&& packet.resourceKind() == InventoryResourceKind.FLUID && !packet.entries().isEmpty()).reduce((a, b) -> b).orElseThrow();
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.select(InventoryResourceKind.FLUID, host.epoch(InventoryBackendTest.A),
				100, 1, 1, 1, preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "attention"), 1, settings);
			trackingScan.set(true);
			for (int tick = 2; tick < 12; tick++) backend.tick(tick, settings);
			@Unmodifiable List<InventoryS2CPacket> completed = host.packets.stream().filter(packet -> packet.markerId() != null && packet.completeScan() && !packet.entries().isEmpty()).toList();
			assertFalse(completed.isEmpty());
			assertTrue(completed.stream().allMatch(packet -> packet.entries().getFirst().amount() == 3));
			assertTrue(sources.stream().anyMatch(source -> source.starveFinal && source.reads == 2));
			assertTrue(sources.stream().filter(source -> source.starveFinal).allMatch(source -> source.reads == 0 || source.reads == 2),
				"metadata-only binding evidence has no getters; a real tracking sweep has exactly two");
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void backendKeepsTheSelectedSeedHistoricalWhenCompleteDefinitionCapacityEndsTrackingIncomplete() {
		var settings = InventorySettings.serverDefaults(); var host = new InventoryBackendTest.Host(); host.expiry = 200;
		host.policies.replaceAll((owner, policy) -> new InventoryBackend.Policy(100, 1, java.util.Set.of(), java.util.Set.of("entity_block")));
		var sources = new ArrayList<Source>();
		var trackingScan = new java.util.concurrent.atomic.AtomicBoolean();
		var runtime = new InventoryRuntime(input -> {
			var source = new Source(trackingScan.get() ? 257 : 1, slot -> (slot & 1) == 0 ? "minecraft:water" : "minecraft:lava", false, false);
			source.input = input; sources.add(source); return Optional.of(source);
		}, settings.pendingMemoryBytes());
		try (var backend = new InventoryBackend(host, runtime, 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1,
				InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings);
			backend.tick(0, settings);
			var preview = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW
				&& packet.resourceKind() == InventoryResourceKind.FLUID && !packet.entries().isEmpty()).reduce((a, b) -> b).orElseThrow();
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.select(InventoryResourceKind.FLUID, host.epoch(InventoryBackendTest.A),
				100, 1, 1, 1, preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "attention"), 1, settings);
			trackingScan.set(true);
			for (int tick = 2; tick < 45; tick++) backend.tick(tick, settings);
			@Unmodifiable List<InventoryS2CPacket> tracking = host.packets.stream().filter(packet -> packet.markerId() != null).toList();
			assertTrue(tracking.stream().anyMatch(packet -> packet.status() == InventoryS2CPacket.Status.INCOMPLETE));
			assertTrue(tracking.stream().noneMatch(packet -> packet.completeScan() || packet.status() == InventoryS2CPacket.Status.READY));
			assertTrue(tracking.stream().flatMap(packet -> packet.entries().stream()).allMatch(entry -> entry.amount() == 1),
				() -> "a partial total must not overwrite the selected seed or become an absence-derived zero: " + tracking);
			assertTrue(sources.stream().anyMatch(source -> source.reads == 257)); assertSettled(runtime);
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
}
