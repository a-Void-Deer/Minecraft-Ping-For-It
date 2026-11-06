package nx.pingwheel.common.presentation.inventory;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One same-iteration probe candidate, its ownership transfer and its failure fences. */
@NotNullByDefault
class InventoryProbeAttemptTest {

	private static final UUID OWNER = new UUID(91, 1);
	private static final UUID OTHER = new UUID(91, 2);

	private static InventorySettings settings() {
		var settings = InventorySettings.serverDefaults();
		settings.setPendingMemoryMiB(64);
		return settings;
	}

	@NotNullByDefault
	private static class TrackedSource extends InventoryRuntimeTest.Source {
		private final AtomicInteger closes;
		private final AtomicBoolean failClose = new AtomicBoolean();
		private final long retainedBytes;
		private boolean closed;

		private TrackedSource(InventorySourceInput input, AtomicInteger reads, int size, AtomicInteger closes, long retainedBytes) {
			super(input, reads, size);
			this.closes = closes;
			this.retainedBytes = retainedBytes;
		}

		@Override public long sourceRetainedBytes() { return retainedBytes; }

		@Override public void close() {
			closes.incrementAndGet();
			if (closed) throw new IllegalStateException("inventory source closed twice");
			closed = true;
			if (failClose.get()) throw new IllegalStateException("inventory source close refused");
		}
	}

	@NotNullByDefault
	private static final class SnapshotSource extends TrackedSource {
		private final AtomicInteger captures = new AtomicInteger();
		private volatile long snapshotMemory = Long.MAX_VALUE / 4;

		private SnapshotSource(InventorySourceInput input, AtomicInteger reads, AtomicInteger closes) {
			super(input, reads, 1, closes, 0);
		}

		@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() { return Optional.of(plan()); }
		@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan(InventoryValidationBudget.Allowance allowance) { return Optional.of(plan()); }

		private InventorySourceAccess.SnapshotPlan plan() {
			return new InventorySourceAccess.SnapshotPlan() {
				@Override public long memoryUpperBoundBytes() { return snapshotMemory; }
				@Override public int physicalReadUpperBound() { return 0; }
				@Override public long itemWorkUpperBoundUnits() { return 0; }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() { return capture(new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE)); }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture(InventoryValidationBudget.Allowance allowance) {
					captures.incrementAndGet();
					return Optional.of(new InventoryRuntimeTest.Snapshot() {
						@Override public int slots() { return 1; }
						@Override public InventoryDomainCodec.Item read(int slot) { return InventoryRuntimeTest.item(11); }
						@Override public long retainedBytes() { return 128; }
					});
				}
			};
		}
	}

	private static InventoryRuntime runtime(InventorySettings settings, AtomicInteger resolutions,
		java.util.function.Function<InventorySourceInput, TrackedSource> factory) {
		return new InventoryRuntime((input, displayBytes) -> {
			resolutions.incrementAndGet();
			return Optional.of(factory.apply(input));
		}, settings.pendingMemoryBytes());
	}

	@Test
	void firstSamplingConstructsSourceOnceAndReadsOnce() {
		var settings = settings();
		var reads = new AtomicInteger();
		var resolutions = new AtomicInteger();
		var closes = new AtomicInteger();
		var source = new TrackedSource(InventoryRuntimeTest.input(OWNER), reads, 1, closes, 0);
		try (var runtime = runtime(settings, resolutions, input -> source)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			try (var attempt = runtime.beginProbe(consumer)) {
				assertEquals(Optional.of(true), attempt.result());
				assertEquals(1, resolutions.get());
				var observation = runtime.step(consumer, attempt).orElseThrow();
				assertEquals(1, observation.slots().size());
				assertEquals(7, observation.slots().getFirst().count());
			}
			assertEquals(1, resolutions.get(), "prepare used the probe candidate instead of resolving again");
			assertEquals(1, reads.get());
			assertEquals(0, closes.get(), "the round owns the transferred source");
			consumer.close();
			assertEquals(1, closes.get());
			assertEquals(0, runtime.memory().reserved());
		}
	}

	@Test
	void retainedAttemptRemovesTheLegacyProbeThenPrepareSecondConstruction() {
		for (boolean reuse : new boolean[] {false, true}) {
			var settings = settings(); var resolutions = new AtomicInteger(); var reads = new AtomicInteger(); var closes = new AtomicInteger();
			try (var runtime = runtime(settings, resolutions, input -> new TrackedSource(input, reads, 1, closes, 0))) {
				runtime.advance(0, settings);
				var consumer = runtime.attach(InventoryRuntimeTest.input(OWNER), new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
				if (reuse) try (var attempt = runtime.beginProbe(consumer)) {
					assertEquals(Optional.of(true), attempt.result()); runtime.step(consumer, attempt).orElseThrow();
				} else {
					assertEquals(Optional.of(true), runtime.probe(consumer)); runtime.step(consumer).orElseThrow();
				}
				assertEquals(reuse ? 1 : 2, resolutions.get()); assertEquals(1, reads.get()); consumer.close();
				assertEquals(resolutions.get(), closes.get());
			}
		}
	}

	@Test
	void prepareRevalidatesTheCurrentSourceInsteadOfCachingProbeSuccess() {
		var settings = settings();
		var reads = new AtomicInteger();
		var resolutions = new AtomicInteger();
		var closes = new AtomicInteger();
		var source = new TrackedSource(InventoryRuntimeTest.input(OWNER), reads, 1, closes, 0);
		try (var runtime = runtime(settings, resolutions, input -> source)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			try (var attempt = runtime.beginProbe(consumer)) {
				assertEquals(Optional.of(true), attempt.result());
				assertTrue(source.validCalls.get() > 0);
				source.valid = false;
				var observation = runtime.step(consumer, attempt).orElseThrow();
				assertEquals(CaptureResult.Availability.INVALID, observation.result().availability());
			}
			assertEquals(0, reads.get(), "a current validation failure cannot reach content");
			assertEquals(1, resolutions.get());
			assertEquals(1, closes.get());
		}
	}

	@Test
	void unusedCandidateClosesAndReleasesItsEnvelope() {
		var settings = settings();
		var reads = new AtomicInteger();
		var resolutions = new AtomicInteger();
		var closes = new AtomicInteger();
		var source = new TrackedSource(InventoryRuntimeTest.input(OWNER), reads, 1, closes, 0);
		try (var runtime = runtime(settings, resolutions, input -> source)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			try (var attempt = runtime.beginProbe(consumer)) {
				assertEquals(Optional.of(true), attempt.result());
				assertTrue(runtime.memory().reserved() > 0, "the untransferred candidate holds its resolution envelope");
			}
			assertEquals(1, resolutions.get());
			assertEquals(1, closes.get());
			assertEquals(0, runtime.memory().reserved());
			consumer.close();
			assertEquals(0, runtime.memory().retained());
		}
	}

	@Test
	void attemptRejectsAnotherConsumerAndDuplicateTake() {
		var settings = settings();
		var resolutions = new AtomicInteger();
		var closes = new AtomicInteger();
		var reads = new AtomicInteger();
		try (var runtime = runtime(settings, resolutions,
			input -> new TrackedSource(input, reads, 1, closes, 0))) {
			runtime.advance(0, settings);
			var input = InventoryRuntimeTest.input(OWNER);
			var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			var other = runtime.attach(InventoryRuntimeTest.input(OTHER), new InventoryRuntime.PreviewSubject(OTHER)).orElseThrow();
			var attempt = runtime.beginProbe(consumer);
			try {
				assertThrows(IllegalStateException.class, () -> runtime.prepare(other, attempt));
				assertEquals(Optional.of(true), attempt.result());
				assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer, attempt));
				assertThrows(IllegalStateException.class, () -> runtime.prepare(consumer, attempt));
			} finally {
				attempt.close();
				consumer.close();
				other.close();
			}
		}
	}

	@Test
	void attemptCannotCrossTick() {
		var settings = settings();
		var resolutions = new AtomicInteger();
		var closes = new AtomicInteger();
		var reads = new AtomicInteger();
		var source = new TrackedSource(InventoryRuntimeTest.input(OWNER), reads, 1, closes, 0);
		try (var runtime = runtime(settings, resolutions, input -> source)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			try (var attempt = runtime.beginProbe(consumer)) {
				runtime.advance(1, settings);
				assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer, attempt));
			}
			assertEquals(1, closes.get());
			assertEquals(0, runtime.memory().reserved());
			consumer.close();
		}
	}

	@Test
	void attemptCannotCrossDisplayCeilingChange() {
		var settings = settings();
		var resolutions = new AtomicInteger();
		var closes = new AtomicInteger();
		var reads = new AtomicInteger();
		var source = new TrackedSource(InventoryRuntimeTest.input(OWNER), reads, 1, closes, 0);
		try (var runtime = runtime(settings, resolutions, input -> source)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			int before = (int) settings.effectiveDisplayBytes();
			try (var attempt = runtime.beginProbe(consumer)) {
				settings.setDisplayByteMultiplier(BigDecimal.valueOf(2));
				settings.setFrameByteMultiplier(BigDecimal.valueOf(2));
				runtime.advance(0, settings);
				assertNotEquals(before, (int) settings.effectiveDisplayBytes());
				assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer, attempt));
			}
			assertEquals(1, closes.get());
			assertEquals(0, runtime.memory().reserved());
			consumer.close();
		}
	}

	@Test
	void callbackClosingConsumerPreventsInstallation() {
		var settings = settings();
		var reads = new AtomicInteger();
		var resolutions = new AtomicInteger();
		var closes = new AtomicInteger();
		InventoryRuntime.Consumer[] attached = new InventoryRuntime.Consumer[1];
		var source = new TrackedSource(InventoryRuntimeTest.input(OWNER), reads, 1, closes, 0) {
			@Override public boolean valid() {
				attached[0].close();
				return true;
			}
		};
		try (var runtime = runtime(settings, resolutions, input -> source)) {
			runtime.advance(0, settings);
			attached[0] = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			try (var attempt = runtime.beginProbe(attached[0])) {
				assertEquals(Optional.of(true), attempt.result());
				assertEquals(InventorySourceAccess.Preparation.UNAVAILABLE, runtime.prepare(attached[0], attempt));
			}
			assertEquals(1, closes.get());
			assertEquals(0, runtime.memory().reserved());
			assertEquals(0, runtime.memory().retained());
		}
	}

	@Test
	void untransferredCloseFailureRetiresSameBindingOnly() {
		var settings = settings();
		var reads = new AtomicInteger();
		var resolutions = new AtomicInteger();
		var closes = new AtomicInteger();
		var failNextClose = new AtomicBoolean();
		try (var runtime = runtime(settings, resolutions, input -> {
			var source = new TrackedSource(input, reads, 1, closes, 0);
			if (failNextClose.getAndSet(false)) source.failClose.set(true);
			return source;
		})) {
			runtime.advance(0, settings);
			var northInput = InventoryRuntimeTest.input(OWNER);
			var southInput = new InventorySourceInput(InventoryRuntimeTest.TARGET, OWNER, BlockFace.SOUTH);
			var north = runtime.attach(northInput, new InventoryRuntime.TrackingSubject(TargetKey.from(InventoryRuntimeTest.TARGET))).orElseThrow();
			var south = runtime.attach(southInput, new InventoryRuntime.TrackingSubject(TargetKey.from(InventoryRuntimeTest.TARGET))).orElseThrow();
			runtime.step(north).orElseThrow();
			runtime.step(south).orElseThrow();
			assertFalse(north.invalidated());
			assertFalse(south.invalidated());
			var alias = runtime.attach(northInput, new InventoryRuntime.TrackingSubject(TargetKey.from(InventoryRuntimeTest.TARGET))).orElseThrow();
			failNextClose.set(true);
			try (var attempt = runtime.beginProbe(alias)) {
				assertEquals(Optional.of(true), attempt.result());
			}
			assertTrue(north.invalidated(), "the origin binding is retired when its untransferred candidate cannot close");
			assertTrue(alias.invalidated(), "the probing alias is invalidated for the backend sweep");
			assertFalse(south.invalidated(), "a different owner/face binding keeps its observation");
			north.close(); south.close(); alias.close();
			assertEquals(0, runtime.memory().reserved());
		}
	}

	@ParameterizedTest
	@ValueSource(longs = { 0L, 4096L })
	void transferredMetadataEnvelopeIsHeldUntilTheRoundCloses(long retainedBytes) {
		var settings = settings();
		var reads = new AtomicInteger();
		var resolutions = new AtomicInteger();
		var closes = new AtomicInteger();
		var source = new TrackedSource(InventoryRuntimeTest.input(OWNER), reads, 1, closes, retainedBytes);
		try (var runtime = runtime(settings, resolutions, input -> source)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			try (var attempt = runtime.beginProbe(consumer)) {
				assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer, attempt));
			}
			assertEquals(0, closes.get());
			assertTrue(runtime.memory().retained() >= retainedBytes);
			consumer.close();
			assertEquals(1, closes.get());
			assertEquals(0, runtime.memory().retained());
			assertEquals(0, runtime.memory().reserved());
		}
	}

	@Test
	void deferredUnpreparedRoundRetriesWithoutResolvingAgain() {
		var settings = settings();
		var reads = new AtomicInteger();
		var resolutions = new AtomicInteger();
		var closes = new AtomicInteger();
		var source = new SnapshotSource(InventoryRuntimeTest.input(OWNER), reads, closes);
		try (var runtime = runtime(settings, resolutions, input -> source)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			try (var attempt = runtime.beginProbe(consumer)) {
				assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer, attempt));
			}
			assertEquals(1, resolutions.get());
			source.snapshotMemory = 4096;
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			assertEquals(1, resolutions.get(), "a retained unprepared round never resolves its source again");
			assertEquals(1, source.captures.get());
			assertEquals(1, runtime.step(consumer).orElseThrow().slots().size());
			assertEquals(0, reads.get(), "the prepared snapshot is decoded without a live read");
			consumer.close();
			assertEquals(1, closes.get());
			assertEquals(0, runtime.memory().reserved());
		}
	}

	@Test
	void recoveryProbeCarriesTheCandidateAcrossTheSameIterationRestart() {
		var settings = settings();
		var reads = new AtomicInteger();
		var resolutions = new AtomicInteger();
		var closes = new AtomicInteger();
		var source = new TrackedSource(InventoryRuntimeTest.input(OWNER), reads, 1, closes, 0);
		try (var runtime = runtime(settings, resolutions, input -> source)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(source.input, new InventoryRuntime.TrackingSubject(TargetKey.from(InventoryRuntimeTest.TARGET))).orElseThrow();
			consumer.discardObservation();
			try (var attempt = runtime.beginProbeRecovery(consumer)) {
				assertEquals(Optional.of(true), attempt.result());
				assertEquals(1, resolutions.get());
				consumer.restart();
				var observation = runtime.step(consumer, attempt).orElseThrow();
				assertEquals(CaptureResult.Completeness.COMPLETE, observation.result().completeness());
			}
			assertEquals(1, resolutions.get(), "recovery prepare adopted the same probe source");
			consumer.close();
			assertEquals(1, closes.get());
		}
	}

	@Test
	void probeSourceUsesTheSameBindingKeyAndIsNotSharedAcrossFaces() {
		var settings = settings();
		var reads = new AtomicInteger();
		var resolutions = new AtomicInteger();
		var closes = new AtomicInteger();
		try (var runtime = runtime(settings, resolutions,
			input -> new TrackedSource(input, reads, 1, closes, 0))) {
			runtime.advance(0, settings);
			var north = runtime.attach(InventoryRuntimeTest.input(OWNER), new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			var southInput = new InventorySourceInput(InventoryRuntimeTest.TARGET, OWNER, BlockFace.SOUTH);
			var south = runtime.attach(southInput, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			try (var first = runtime.beginProbe(north)) {
				assertEquals(Optional.of(true), first.result());
				assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(north, first));
			}
			try (var second = runtime.beginProbe(south)) {
				assertEquals(Optional.of(true), second.result());
				assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(south, second));
			}
			assertEquals(2, resolutions.get(), "different frozen faces never share one probe candidate");
			north.close(); south.close();
			assertEquals(0, runtime.memory().reserved());
			assertEquals(0, runtime.memory().retained());
		}
	}

	@Test
	void transferredSourceKeyRemainsTheResolvedProviderKey() throws Exception {
		var settings = settings();
		var reads = new AtomicInteger();
		var resolutions = new AtomicInteger();
		var closes = new AtomicInteger();
		var source = new TrackedSource(InventoryRuntimeTest.input(OWNER), reads, 1, closes, 0);
		SourceKey expected = source.key();
		try (var runtime = runtime(settings, resolutions, input -> source)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			try (var attempt = runtime.beginProbe(consumer)) {
				assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer, attempt));
			}
			Object round = InventoryBackendTest.field(consumer, "round");
			assertEquals(expected, InventoryBackendTest.field(round, "key"));
			consumer.close();
		}
	}

	@Test
	void rejectedOrDeferredProbeCannotFallThroughToAnotherResolution() {
		for (boolean deferred : new boolean[] { false, true }) {
			var settings = settings(); var resolutions = new AtomicInteger(); var closes = new AtomicInteger();
			try (var runtime = runtime(settings, resolutions, input -> new TrackedSource(input, new AtomicInteger(), 1, closes, 0) {
				@Override public boolean valid() {
					if (deferred) throw InventoryValidationBudget.itemResolutionDeferred();
					return false;
				}
			})) {
				runtime.advance(0, settings);
				var consumer = runtime.attach(InventoryRuntimeTest.input(OWNER), new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
				try (var attempt = runtime.beginProbe(consumer)) {
					assertEquals(deferred ? Optional.empty() : Optional.of(false), attempt.result());
					assertEquals(deferred ? InventorySourceAccess.Preparation.DEFERRED : InventorySourceAccess.Preparation.UNAVAILABLE,
						runtime.prepare(consumer, attempt));
				}
				assertEquals(1, resolutions.get()); assertEquals(1, closes.get());
				assertEquals(0, runtime.memory().reserved()); consumer.close();
			}
		}
	}

	@Test
	void callbackReentryKeepsThePeerRoundAndClosesOnlyTheUnusedCandidate() throws Exception {
		var settings = settings(); var resolutions = new AtomicInteger(); var closes = new AtomicInteger(); var reads = new AtomicInteger();
		var reentry = new java.util.concurrent.atomic.AtomicReference<@Nullable Runnable>();
		try (var runtime = runtime(settings, resolutions, input -> new TrackedSource(input, reads, 2, closes, 0) {
			@Override public boolean valid() {
				var callback = reentry.getAndSet(null); if (callback != null) callback.run(); return true;
			}
		})) {
			runtime.advance(0, settings);
			var input = InventoryRuntimeTest.input(OWNER);
			var peer = runtime.attach(input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			reentry.set(() -> assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(peer)));
			try (var attempt = runtime.beginProbe(consumer)) {
				assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer, attempt));
			}
			assertEquals(2, resolutions.get()); assertEquals(1, closes.get());
			org.junit.jupiter.api.Assertions.assertSame(InventoryBackendTest.field(peer, "round"), InventoryBackendTest.field(consumer, "round"));
			assertEquals(0, reads.get()); peer.close(); consumer.close();
			assertEquals(2, closes.get()); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
		}
	}

	@Test
	void failedAliasInputProbeRetiresMatchingEvidenceButNotAnotherOwner() {
		var settings = settings(); var resolutions = new AtomicInteger(); var closes = new AtomicInteger(); var fail = new AtomicBoolean();
		try (var runtime = runtime(settings, resolutions, input -> {
			var source = new TrackedSource(input, new AtomicInteger(), 1, closes, 0); source.valid = !fail.get(); return source;
		})) {
			runtime.advance(0, settings); var input = InventoryRuntimeTest.input(OWNER);
			var origin = runtime.attach(input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			var other = runtime.attach(InventoryRuntimeTest.input(OTHER), new InventoryRuntime.PreviewSubject(OTHER)).orElseThrow();
			runtime.step(origin).orElseThrow(); runtime.step(other).orElseThrow();
			var alias = runtime.attach(input, new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow(); fail.set(true);
			try (var attempt = runtime.beginProbe(alias)) { assertEquals(Optional.of(false), attempt.result()); }
			assertTrue(origin.invalidated()); assertFalse(other.invalidated());
			origin.close(); alias.close(); other.close(); runtime.close(); assertEquals(0, runtime.memory().retained());
		}
	}

	@Test
	void fatalProbeAndCleanupKeepTheOriginalFailureAndReleaseTheEnvelope() {
		var settings = settings(); var fatal = new AssertionError("probe fatal"); var cleanup = new AssertionError("cleanup fatal");
		try (var runtime = new InventoryRuntime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, new AtomicInteger(), 1) {
			@Override public boolean valid() { throw fatal; }
			@Override public void close() { throw cleanup; }
		}), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(InventoryRuntimeTest.input(OWNER), new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			org.junit.jupiter.api.Assertions.assertSame(fatal, assertThrows(AssertionError.class, () -> runtime.beginProbe(consumer)));
			org.junit.jupiter.api.Assertions.assertArrayEquals(new Throwable[] { cleanup }, fatal.getSuppressed());
			assertEquals(0, runtime.memory().reserved()); consumer.close(); assertEquals(0, runtime.memory().retained());
		}
	}

	@Test
	void callbackAdvancingTheTickCannotRelabelAnOldProbeCandidateAsCurrent() {
		var settings = settings(); var resolutions = new AtomicInteger(); var closes = new AtomicInteger();
		var callback = new java.util.concurrent.atomic.AtomicReference<@Nullable Runnable>();
		try (var runtime = runtime(settings, resolutions, input -> new TrackedSource(input, new AtomicInteger(), 1, closes, 0) {
			@Override public boolean valid() { var action = callback.getAndSet(null); if (action != null) action.run(); return true; }
		})) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(InventoryRuntimeTest.input(OWNER), new InventoryRuntime.PreviewSubject(OWNER)).orElseThrow();
			callback.set(() -> runtime.advance(1, settings));
			try (var attempt = runtime.beginProbe(consumer)) {
				assertEquals(Optional.empty(), attempt.result());
				assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer, attempt));
			}
			assertEquals(1, resolutions.get()); assertEquals(1, closes.get()); assertEquals(0, runtime.memory().reserved()); consumer.close();
		}
	}
}
