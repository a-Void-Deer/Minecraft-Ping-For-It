package nx.pingwheel.common.presentation.inventory;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.presentation.source.CaptureResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryRuntimeTest {
	static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	static InventorySourceInput input(UUID owner) { return new InventorySourceInput(TARGET, owner, BlockFace.NORTH); }
	static InventoryDomainCodec.Item item(long count) { return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), count, "stone", null, false); }
	static class Source implements InventorySourceAccess.Source {
		final InventorySourceInput input; final AtomicInteger reads; final int size;
		boolean stable = true, valid = true;
		Source(InventorySourceInput input, AtomicInteger reads, int size) { this.input = input; this.reads = reads; this.size = size; }
		@Override public SourceKey key() { return new SourceKey("test", "inventory", "same-container", input.viewKey()); }
		@Override public boolean valid() { return valid; }
		@Override public boolean stableCursor() { return stable; }
		@Override public int slots() { return size; }
		@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return slot == 0 ? item(7) : null; }
		@Override public InventorySourceAccess.Page enumerate(int limit) {
			var values = new java.util.ArrayList<InventoryDomainCodec.Item>();
			for (int i = 0; i < Math.min(limit, size); i++) values.add(read(i));
			return new InventorySourceAccess.Page(values, size <= limit);
		}
	}
	@Test void onePhysicalReadAndDistinctClientAndTargetLogicalSubjects() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1);
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(2));
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1)); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 2)), 16000000)) {
			runtime.advance(0, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var duplicate = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(new UUID(2, 2))).orElseThrow();
			var tracking = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			assertEquals(1, runtime.step(a).orElseThrow().slots().size());
			assertEquals(1, runtime.step(duplicate).orElseThrow().slots().size());
			assertEquals(1, runtime.step(b).orElseThrow().slots().size());
			assertEquals(1, runtime.step(tracking).orElseThrow().slots().size());
			assertEquals(1, reads.get(), "four consumers share one admitted physical prefix");
			assertTrue(runtime.step(a).isEmpty(), "client quota survives duplicate requests");
			assertTrue(runtime.step(tracking).isEmpty(), "target quota is independent but finite");
			runtime.advance(5, settings);
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(a).orElseThrow().result().completeness());
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(tracking).orElseThrow().result().completeness());
			assertEquals(2, reads.get(), "observed blanks are physical slots too");
		}
	}
	@Test void memoryDeferHasNoResolverOrReadAndRetainedLifetimeEndsOnLastClose() {
		AtomicInteger probes = new AtomicInteger(), reads = new AtomicInteger(); var settings = InventorySettings.serverDefaults();
		var owner = new UUID(1, 1);
		try (var runtime = new InventoryRuntime(i -> { probes.incrementAndGet(); return Optional.of(new Source(i, reads, 1)); }, 16000000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			runtime.memory().setCap(runtime.memory().retained());
			assertTrue(runtime.step(consumer).isEmpty()); assertEquals(0, probes.get()); assertEquals(0, reads.get());
			runtime.memory().setCap(16000000); assertTrue(runtime.step(consumer).isPresent());
			long live = runtime.memory().retained(); assertTrue(live > 4096);
			consumer.close(); assertEquals(1024, runtime.memory().retained(), "quota subject survives consumer close until the period ends");
			assertEquals(0, runtime.memory().reserved()); runtime.advance(5, settings); assertEquals(0, runtime.memory().retained());
		}
	}
	@Test void cursorlessEnumerationCountsBlankViewsAndCannotInventContinuation() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		settings.setPhysicalSlotsPerTick(IntLimit.finite(2));
		try (var runtime = new InventoryRuntime(i -> { Source source = new Source(i, reads, 3); source.stable = false; return Optional.of(source); }, 16000000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var observation = runtime.step(consumer).orElseThrow();
			assertEquals(2, reads.get()); assertEquals(2, observation.slots().size());
			assertNull(observation.slots().get(1)); assertEquals(CaptureResult.Completeness.INCOMPLETE, observation.result().completeness());
			runtime.advance(5, settings); runtime.step(consumer); assertEquals(2, reads.get());
		}
	}
	@Test void ownerAndFrozenFaceArePhysicalSharingBoundaries() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1)), 16000000)) {
			runtime.advance(0, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var b = runtime.attach(new InventorySourceInput(TARGET, owner, BlockFace.SOUTH), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			runtime.step(a); runtime.step(b); assertEquals(2, reads.get());
			assertThrows(NullPointerException.class, () -> new InventorySourceInput(TARGET, owner, null));
		}
	}
	@Test void repeatedTrackingPingsSharePhysicalAndTargetQuotaAndFreshSelectSkipsOlderPreviewSweep() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 3)), 16000000)) {
			runtime.advance(0, settings);
			var preview = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			settings.setPhysicalSlotsPerTick(IntLimit.finite(1)); runtime.advance(1, settings); runtime.step(preview);
			runtime.advance(2, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			assertEquals(1, runtime.step(a).orElseThrow().slots().size()); assertEquals(2, reads.get(), "new SELECT must not inherit pre-selection preview prefix");
			assertEquals(1, runtime.step(b).orElseThrow().slots().size()); assertEquals(2, reads.get());
			assertTrue(runtime.step(a).isEmpty()); assertTrue(runtime.step(b).isEmpty(), "another Ping is not another TargetKey allowance");
			a.close(); runtime.advance(5, settings); assertEquals(1, runtime.step(b).orElseThrow().slots().size());
		}
	}
	@Test void changedCadenceCannotRefundLogicalUsageAndProviderWorkExhaustionDefersBeforeLookup() {
		AtomicInteger probes = new AtomicInteger(), reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		try (var runtime = new InventoryRuntime(i -> { probes.incrementAndGet(); return Optional.of(new Source(i, reads, 3)); }, 16000000)) {
			runtime.advance(0, settings); var a = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(a);
			settings.getPreview().setPeriodTicks(1); runtime.advance(1, settings); assertTrue(runtime.step(a).isEmpty()); assertEquals(1, reads.get());
			for (int i = 0; i < 128; i++) runtime.preflight(() -> Optional.of(true));
			int before = probes.get(); assertTrue(runtime.probe(input(owner)).isEmpty()); assertEquals(before, probes.get());
		}
	}
	@Test void throwingProviderReadAndCloseReleaseAllRetainedAndReservedMemory() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1) {
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); throw new IllegalStateException("read"); }
			@Override public void close() { throw new IllegalStateException("cleanup"); }
		}), 16000000);
		runtime.advance(0, settings); var a = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
		assertEquals(CaptureResult.Availability.UNAVAILABLE, runtime.step(a).orElseThrow().result().availability());
		assertEquals(1, reads.get()); assertEquals(0, runtime.memory().reserved()); runtime.close();
		assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
	}
	@Test void restartedSweepCannotReattachAnOlderPeersIncompleteRound() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 3)), 16000000)) {
			runtime.advance(0, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(a); runtime.step(b); assertEquals(1, reads.get());
			runtime.advance(3, settings); a.restart(); runtime.step(a); assertEquals(2, reads.get(), "fresh sweep re-reads instead of consuming the old paid prefix");
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void sameTickReopenDoesNotInheritAnOldPreviewWhileExistingTrackingSweepsStillShare() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1)), 16000000)) {
			runtime.advance(0, settings);
			var preview = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(preview);
			var reopen = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(reopen);
			assertEquals(2, reads.get(), "reopen starts fresh even within the same tick");
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(a); runtime.step(b); assertEquals(3, reads.get());
			runtime.advance(3, settings); a.restart(); runtime.step(a); b.restart(); runtime.step(b);
			assertEquals(4, reads.get(), "new Pings do not multiply physical reads on periodic tracking sweeps");
		}
	}
	@Test void invalidStepRetiresAllThirtyTwoPhysicalRoundsWithoutClosingConsumersAndUnrelatedWorkProgresses() {
		var settings = InventorySettings.serverDefaults(); var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger(); boolean[] readable = {true};
		try (var runtime = new InventoryRuntime(input -> {
			resolutions.incrementAndGet(); return Optional.of(new Source(input, reads, 2) {
				@Override public boolean valid() { return readable[0]; }
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16000000)) {
			runtime.advance(0, settings); var consumers = new java.util.ArrayList<InventoryRuntime.Consumer>();
			for (int index = 0; index < 32; index++) {
				var owner = new UUID(1, index); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); consumers.add(consumer);
				assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(consumer, 1).orElseThrow().result().completeness());
			}
			assertEquals(32, reads.get()); assertTrue(runtime.memory().retained() > 9_000_000);
			readable[0] = false; runtime.advance(1, settings);
			for (var consumer : consumers) assertEquals(CaptureResult.Availability.INVALID, runtime.step(consumer, 1).orElseThrow().result().availability());
			assertEquals(32, closes.get(), "each shared provider handle is closed exactly once on invalidation, not consumer close");
			assertEquals(32L * (4096 + 1024), runtime.memory().retained(), "only bounded consumers and paid logical quota subjects remain"); assertEquals(0, runtime.memory().reserved());
			readable[0] = true; int resolvedBefore = resolutions.get(); var other = new UUID(2, 2);
			var unrelated = runtime.attach(input(other), new InventoryRuntime.PreviewSubject(other)).orElseThrow(); assertTrue(runtime.step(unrelated, 1).isPresent()); assertEquals(resolvedBefore + 1, resolutions.get());
			consumers.forEach(InventoryRuntime.Consumer::close); assertEquals(32, closes.get(), "retired rounds are not closed twice");
		}
	}
	@Test void invalidProbeRetiresSharedRoundAndPaidQuotasSurviveUntilFreshTrackingRecovery() {
		var settings = InventorySettings.serverDefaults(); settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1)); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		var owner = new UUID(1, 1); var reads = new AtomicInteger(); var closes = new AtomicInteger(); boolean[] readable = {true};
		try (var runtime = new InventoryRuntime(input -> Optional.of(new Source(input, reads, 2) {
			@Override public boolean valid() { return readable[0]; }
			@Override public void close() { closes.incrementAndGet(); }
		}), 16000000)) {
			runtime.advance(0, settings);
			var preview = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(preview); runtime.step(a); runtime.step(b); assertEquals(1, reads.get()); long before = runtime.memory().retained();
			readable[0] = false; runtime.advance(1, settings); assertEquals(Optional.of(false), runtime.probe(input(owner)));
			assertEquals(2, closes.get(), "one probe and one shared physical handle close"); assertTrue(runtime.memory().retained() < before); assertEquals(3L * 4096 + 2L * 1024, runtime.memory().retained());
			assertNotEquals(CaptureResult.Availability.READABLE, runtime.step(a).orElseThrow().result().availability()); assertNotEquals(CaptureResult.Availability.READABLE, runtime.step(b).orElseThrow().result().availability());
			readable[0] = true; a.restart(); b.restart(); preview.restart(); assertTrue(runtime.step(a).isEmpty()); assertTrue(runtime.step(preview).isEmpty(), "retirement never refunds paid logical usage");
			runtime.advance(3, settings); runtime.step(a); runtime.step(b); assertEquals(2, reads.get(), "recovery starts one fresh shared physical observation");
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void livePhysicalReductionWithinTickPreservesSpentSlotsAndCannotReadOrSelectPastNewCap() {
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(2)); var reads = new AtomicInteger(); var owner = new UUID(1, 1);
		try (var runtime = new InventoryRuntime(input -> Optional.of(new Source(input, reads, 3)), 16000000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(consumer, 1); assertEquals(1, reads.get());
			settings.setPhysicalSlotsPerTick(IntLimit.finite(1)); runtime.advance(0, settings);
			assertTrue(runtime.step(consumer, 1).isEmpty()); assertFalse(runtime.selectionPresent(input(owner), new InventorySelection(item(7).key(), "minecraft:stone", "Stone", null, false, "danger"), 0)); assertEquals(1, reads.get());
			settings.setPhysicalSlotsPerTick(IntLimit.finite(3)); runtime.advance(0, settings); runtime.step(consumer, 1); assertEquals(2, reads.get());
			assertTrue(runtime.step(consumer, 1).isEmpty(), "raising live cap does not reset this tick's original finite ledger");
			runtime.advance(1, settings); runtime.step(consumer, 1); assertEquals(3, reads.get());
		}
	}
}
