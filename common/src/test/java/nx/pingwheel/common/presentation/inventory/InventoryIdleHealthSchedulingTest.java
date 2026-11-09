package nx.pingwheel.common.presentation.inventory;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Idle source health probes run on the owning Preview or Tracking period. These
 * cases count real source validation callbacks and reads; they exercise the
 * production Backend and Runtime seams without claiming a live timing measure.
 */
@NotNullByDefault
class InventoryIdleHealthSchedulingTest {
	private static final UUID A = InventoryBackendTest.A, B = InventoryBackendTest.B;

	@NotNullByDefault
	private static final class Counters {
		final AtomicInteger validations = new AtomicInteger();
		final AtomicInteger reads = new AtomicInteger();
	}

	/** Host hook used to hold one already-offered data handoff across a tick. */
	@NotNullByDefault
	private static final class HoldingHost extends InventoryBackendTest.Host {
		final Counters counters;
		boolean hold;
		int validationsBeforeFirstHandoff = -1;
		int validationsAtSend;
		HoldingHost(Counters counters) { this.counters = counters; }
		@Override public InventorySyncPublisher.SendDecision beforeSend(SyncPublisher.Context context, InventoryS2CPacket packet,
			@Nullable InventoryPublicationEvidence evidence) {
			if (InventorySyncPublisher.requiresSource(packet) && validationsBeforeFirstHandoff < 0)
				validationsBeforeFirstHandoff = counters.validations.get();
			return hold && InventorySyncPublisher.requiresSource(packet)
				? InventorySyncPublisher.SendDecision.DEFER : InventorySyncPublisher.SendDecision.ALLOW;
		}
		@Override public void send(UUID player, InventoryS2CPacket packet) {
			// send() runs after the publisher's final source validation and
			// immediately before the irreversible transport handoff.
			if (InventorySyncPublisher.requiresSource(packet)) validationsAtSend = counters.validations.get();
			super.send(player, packet);
		}
	}

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		final InventoryBackendTest.Host host;
		final Counters counters;
		final InventorySettings settings = InventorySettings.serverDefaults();
		final InventoryRuntime runtime;
		final InventoryBackend backend;
		boolean valid = true;
		Fixture(int slots, int previewPeriod, int trackingPeriod, int physicalSlots) {
			this(new InventoryBackendTest.Host(), new Counters(), slots, previewPeriod, trackingPeriod, physicalSlots);
		}
		Fixture(InventoryBackendTest.Host host, Counters counters, int slots, int previewPeriod, int trackingPeriod, int physicalSlots) {
			this.host = host; this.counters = counters;
			settings.getPreview().setPeriodTicks(previewPeriod);
			settings.getTracking().setPeriodTicks(trackingPeriod);
			settings.setPendingMemoryMiB(64);
			settings.setPhysicalSlotsPerTick(IntLimit.finite(physicalSlots));
			runtime = new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, counters.reads, slots) {
				@Override public boolean valid() {
					counters.validations.incrementAndGet();
					return Fixture.this.valid;
				}
			}), settings.pendingMemoryBytes());
			backend = new InventoryBackend(host, runtime, 1);
			backend.handle(A, InventoryC2SPacket.hello(), 0, settings);
			backend.handle(B, InventoryC2SPacket.hello(), 0, settings);
		}
		int validations() { return counters.validations.get(); }
		int reads() { return counters.reads.get(); }
		void open(long at) {
			backend.handle(A, InventoryC2SPacket.open(host.epoch(A), 100, 1, 1, InventoryRuntimeTest.TARGET, BlockFace.NORTH), at, settings);
		}
		void tick(long at) { backend.tick(at, settings); }
		MarkerId select(long at) {
			var preview = host.packets.stream()
				.filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && !packet.entries().isEmpty())
				.reduce((first, second) -> second).orElseThrow();
			backend.handle(A, InventoryC2SPacket.select(host.epoch(A), 100, 1, 1, 1,
				preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger"), at, settings);
			return host.store.allMarkers().getLast().id();
		}
		void closePreview(long at) {
			backend.handle(A, InventoryC2SPacket.close(host.epoch(A), 1).stamp(100, 1), at, settings);
		}
		boolean invalidTrackingStatus() {
			return host.packets.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.STATUS
				&& packet.status() == InventoryS2CPacket.Status.INVALID);
		}
		@Override public void close() { backend.close(); }
	}

	@Test void completedPreviewUsesPreviewPeriodAndKeepsADeferredCheckOverdue() {
		try (var f = new Fixture(1, 8, 8, 1)) {
			f.open(0); f.tick(0);
			int checks = f.validations();
			assertTrue(checks > 0, "the initial Preview sweep performs a real source check");
			for (long tick = 1; tick < 8; tick++) f.tick(tick);
			assertEquals(checks, f.validations(), "a completed Preview must not health-probe before its period");
			f.tick(8);
			assertTrue(f.validations() > checks, "the Preview period makes the idle check due");

			checks = f.validations();
			var held = f.runtime.memory().tryReserve(f.runtime.memory().remaining()).orElseThrow();
			try { f.tick(16); }
			finally { held.close(); }
			assertEquals(checks, f.validations(), "a budget-deferred check records no completed time");
			f.tick(17);
			assertTrue(f.validations() > checks, "an overdue deferred check retries on the next tick");
		}
	}

	@Test void activeTrackingSweepKeepsProbingWhileTheWaitUsesTheTrackingPeriod() {
		try (var f = new Fixture(3, 8, 8, 1)) {
			f.open(0); f.tick(0); f.tick(1); f.tick(2);
			f.select(2);
			int checks = f.validations(), reads = f.reads();
			for (long tick = 3; tick <= 5; tick++) {
				f.tick(tick);
				assertTrue(f.validations() > checks, "an unfinished sweep keeps its per-tick source check");
				assertTrue(f.reads() > reads, "an unfinished sweep keeps reading its paid prefix");
				checks = f.validations(); reads = f.reads();
			}
			f.closePreview(5);
			checks = f.validations(); reads = f.reads();
			for (long tick = 6; tick < 13; tick++) f.tick(tick);
			assertEquals(checks, f.validations(), "between sweeps a Tracking lease waits for its own period");
			assertEquals(reads, f.reads());
			f.tick(13);
			assertTrue(f.validations() > checks, "the due Tracking sweep checks before restarting");
			assertTrue(f.reads() > reads, "the due Tracking sweep reads instead of reusing health state");
		}
	}

	@Test void liveTrackingPeriodChangeReevaluatesTheLastCompletedCheck() {
		try (var f = new Fixture(1, 10, 10, 1)) {
			f.open(0); f.tick(0);
			f.select(0); f.tick(1);
			f.closePreview(1);
			int checks = f.validations(), reads = f.reads();
			f.tick(7);
			assertEquals(checks, f.validations(), "the long initial period is not due yet");

			f.settings.getTracking().setPeriodTicks(4);
			f.tick(8);
			assertTrue(f.validations() > checks, "a shorter live period applies to the last completed check");
			assertEquals(reads, f.reads(), "an idle health check must not start the next sweep early");

			checks = f.validations();
			f.settings.getTracking().setPeriodTicks(10);
			f.tick(9); f.tick(10);
			assertEquals(checks, f.validations(), "a longer live period re-evaluates the same completion time");
			f.tick(11);
			assertTrue(f.reads() > reads, "the already-scheduled sweep still starts when it becomes due");
		}
	}

	@Test void invalidRecoveryKeepsItsPerTickAttemptsOutsideTheIdleSchedule() {
		try (var f = new Fixture(1, 10, 8, 1)) {
			f.open(0); f.tick(0);
			f.select(0); f.tick(1);
			f.closePreview(1);
			int checks = f.validations();
			f.valid = false;
			for (long tick = 2; tick < 9; tick++) f.tick(tick);
			assertEquals(checks, f.validations(), "an invalid source is detected at the next due health check");
			assertFalse(f.invalidTrackingStatus(), "the idle wait does not claim a hard wall-clock invalidation");

			f.tick(9);
			assertTrue(f.invalidTrackingStatus(), "the due health check invalidates the source");
			checks = f.validations();
			f.tick(10);
			assertTrue(f.validations() > checks, "INVALID recovery retries on the very next tick");
			checks = f.validations();
			f.tick(11);
			assertTrue(f.validations() > checks, "INVALID recovery keeps trying each tick until service can resume");
			int reads = f.reads();
			f.valid = true;
			f.tick(12);
			assertTrue(f.reads() > reads, "the first readable recovery attempt resumes the scan immediately");
		}
	}

	@Test void newRecipientGetsAnImmediateCheckAndFreshHandoffWithoutAnotherRead() {
		try (var f = new Fixture(1, 10, 8, 1)) {
			f.host.policies.remove(B);
			f.backend.disconnect(B);
			f.open(0); f.tick(0);
			MarkerId marker = f.select(0); f.tick(1);
			f.closePreview(1);
			int checks = f.validations(), reads = f.reads();
			f.tick(2);
			assertEquals(checks, f.validations(), "the existing consumer stays on its own due");

			f.host.policies.put(B, new InventoryBackend.Policy(100, 1, Set.of("entity_block")));
			f.backend.handle(B, InventoryC2SPacket.hello(), 2, f.settings);
			f.tick(2);
			assertTrue(f.validations() > checks, "a late-joining recipient triggers an immediate source check");
			assertEquals(reads, f.reads(), "the late join uses the closed completed cut, not another getter");
			assertTrue(f.host.packets.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.SNAPSHOT
				&& marker.equals(packet.markerId()) && !packet.entries().isEmpty()),
				"the late join receives the marker's completed data");
		}
	}

	@Test void pendingHandoffStillValidatesTheCurrentSourceWithoutAHealthProbe() {
		var counters = new Counters();
		var host = new HoldingHost(counters);
		try (var f = new Fixture(host, counters, 1, 10, 8, 1)) {
			f.open(0); f.tick(0);
			host.hold = true;
			f.select(0); f.tick(1);
			host.hold = false;
			host.validationsBeforeFirstHandoff = -1;
			int checks = counters.validations.get();
			f.tick(2);
			assertTrue(counters.validations.get() > checks, "frame handoff performs its own current source check");
			assertEquals(checks, host.validationsBeforeFirstHandoff,
				"no scheduled idle probe ran before the pending handoff");
			assertTrue(host.validationsAtSend > checks, "the check happens immediately before the actual send");
			assertEquals(counters.validations.get(), host.validationsAtSend,
				"the pending handoff supplied the validation");
			assertTrue(host.packets.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.SNAPSHOT
				&& packet.markerId() != null && !packet.entries().isEmpty()), "the held completed frame reaches transport");
		}
	}
}
