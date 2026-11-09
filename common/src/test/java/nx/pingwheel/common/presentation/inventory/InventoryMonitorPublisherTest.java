package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.monitor.server.ServerInventoryMonitor;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.platform.TransportHandoff;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryMonitorPublisherTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

	@NotNullByDefault
	static final class Transport implements InventorySyncPublisher.Transport {
		final InventoryMonitorBackendTest.Clock clock;
		final List<InventoryS2CPacket> sent = new ArrayList<>();
		TransportHandoff handoff = TransportHandoff.HANDED_OFF;
		boolean fail, defer;
		int measurements, proofs, dataSent, dataAllowance = Integer.MAX_VALUE;
		Runnable onSend = () -> {};
		Transport(InventoryMonitorBackendTest.Clock clock) { this.clock = clock; }
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public int encodedBytes(InventoryS2CPacket packet) { throw new AssertionError("must use effective caps"); }
		@Override public int encodedBytes(InventoryS2CPacket packet, int display, int frame) {
			measurements++; clock.time += 11; return packet.encodedSize(display, frame);
		}
		@Override public InventorySyncPublisher.SendDecision beforeSend(SyncPublisher.Context context, InventoryS2CPacket packet,
			@Nullable InventoryPublicationEvidence evidence) {
			proofs++;
			if (defer || !packet.entries().isEmpty() && dataSent >= dataAllowance) return InventorySyncPublisher.SendDecision.DEFER;
			clock.proofToSend = true; return InventorySyncPublisher.SendDecision.ALLOW;
		}
		@Override public void send(UUID recipient, InventoryS2CPacket packet) {
			assertTrue(clock.proofToSend); clock.proofToSend = false; clock.time += 13;
			if (fail) { fail = false; throw new IllegalStateException("handoff outcome unknown"); }
			sent.add(packet);
			if (!packet.entries().isEmpty()) dataSent++;
			var callback = onSend; onSend = () -> {}; callback.run();
		}
		@Override public TransportHandoff sendObserved(UUID recipient, InventoryS2CPacket packet) { send(recipient, packet); return handoff; }
	}

	@NotNullByDefault
	static final class Fixture implements AutoCloseable {
		final InventoryMonitorBackendTest.Clock clock = new InventoryMonitorBackendTest.Clock();
		final ServerInventoryMonitor monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), clock);
		final RetainedMemoryLedger memory = new RetainedMemoryLedger(16_000_000);
		final Transport transport = new Transport(clock);
		final InventorySettings settings = InventorySettings.serverDefaults();
		final InventorySyncPublisher publisher = new InventorySyncPublisher(memory, transport, BudgetDelaySink.NONE, monitor);
		final SyncPublisher.Context context = InventorySyncPublisherTest.context("preview/fixed", 1);
		Fixture(boolean enabled) {
			monitor.configure(enabled, false); publisher.advance(0, settings);
			assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "fixed", null)));
		}
		void publish(long revision, long amount) { InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(revision, 1, 0, amount)); }
		SyncPublisher.Context tracking() {
			var tracking = InventorySyncPublisherTest.context("tracking/fixed", 2);
			assertTrue(publisher.register(tracking, InventorySyncPublisherTest.route(new MarkerId(9), "tracking")));
			return tracking;
		}
		void both() { monitor.configure(true, true); publisher.advance(1, settings); }
		MonitorSample sample(long tick) { publisher.sampleMonitor(); monitor.memory(memory); return java.util.Objects.requireNonNull(monitor.completeTick(tick)); }
		@Override public void close() { publisher.close(); assertEquals(0, memory.retained()); assertEquals(0, memory.reserved()); }
	}

	@Test void allNormalHandoffOutcomesPreserveBusinessProgressAndDoNotInventUnknownBytes() {
		@Nullable InventoryS2CPacket expected = null;
		for (TransportHandoff outcome : TransportHandoff.values()) try (var f = new Fixture(true)) {
			f.transport.handoff = outcome; f.publish(1, 7); f.publisher.drain();
			assertFalse(f.publisher.pending(f.context)); assertEquals(1, f.transport.sent.size());
			var packet = f.transport.sent.getFirst();
			if (expected == null) expected = packet; else assertEquals(expected, packet, "full packet, including keys and checksum");
			f.publisher.drain(); assertEquals(1, f.transport.sent.size(), "NOT and UNOBSERVED are observations, not a new retry policy");
			var sample = f.sample(0); var lane = sample.preview(); long bytes = packet.encodedSize(f.publisher.effectiveDisplayBytes(), f.publisher.effectiveFrameBytes());
			assertEquals(bytes, lane.counter(Counter.BUDGET_CHARGED_BYTES).value());
			assertEquals(1, lane.counter(Counter.FRAMES_PREVIEW).value());
			assertEquals(1, lane.counter(Counter.PUBLICATIONS_INSTALLED).value());
			assertEquals(1, lane.counter(Counter.CUTS_COMPLETED).value());
			if (outcome == TransportHandoff.HANDED_OFF) {
				assertEquals(bytes, lane.counter(Counter.HANDOFF_BYTES).value()); assertEquals(1, lane.counter(Counter.HANDOFF_FRAMES).value());
			} else {
				assertNull(lane.counter(Counter.HANDOFF_BYTES), "unknown is omitted, never fabricated as observed zero");
				assertEquals(1, lane.counter(outcome == TransportHandoff.UNOBSERVED ? Counter.UNOBSERVED_HANDOFF_FRAMES : Counter.NOT_HANDED_OFF_FRAMES).value());
			}
			if (outcome == TransportHandoff.UNOBSERVED) assertEquals(bytes, lane.counter(Counter.UNOBSERVED_HANDOFF_CANDIDATE_BYTES).value());
			else { assertNull(lane.counter(Counter.UNOBSERVED_HANDOFF_FRAMES)); assertNull(lane.counter(Counter.UNOBSERVED_HANDOFF_CANDIDATE_BYTES)); }
			if (outcome != TransportHandoff.NOT_HANDED_OFF) assertNull(lane.counter(Counter.NOT_HANDED_OFF_FRAMES));
		}
	}

	@Test void throwingTransportKeepsConservativeChargeAndRetryButSeparatesKnownAndUnknownHandoff() {
		try (var f = new Fixture(true)) {
			f.publish(1, 7); f.transport.fail = true; f.publisher.drain();
			assertTrue(f.publisher.pending(f.context)); assertTrue(f.transport.sent.isEmpty());
			var failed = f.sample(0);
			assertEquals(0, failed.preview().counter(Counter.CUTS_COMPLETED).value());
			assertQueues(failed, Lane.PREVIEW, 0, 1);
			f.publisher.drain(); assertFalse(f.publisher.pending(f.context)); assertEquals(1, f.transport.sent.size());
			var lane = f.sample(1).preview(); long bytes = f.transport.sent.getFirst().encodedSize(f.publisher.effectiveDisplayBytes(), f.publisher.effectiveFrameBytes());
			assertEquals(1, lane.counter(Counter.CUTS_COMPLETED).value());
			assertEquals(2 * bytes, lane.counter(Counter.BUDGET_CHARGED_BYTES).value());
			assertEquals(bytes, lane.counter(Counter.HANDOFF_BYTES).value());
			assertEquals(bytes, lane.counter(Counter.UNOBSERVED_HANDOFF_CANDIDATE_BYTES).value());
			assertEquals(1, lane.counter(Counter.HANDOFF_FRAMES).value()); assertEquals(1, lane.counter(Counter.UNOBSERVED_HANDOFF_FRAMES).value());
			assertEquals(2, f.transport.measurements, "one existing measurement per attempt, no monitor encoding");
		}
	}

	private static void assertQueues(MonitorSample sample, Lane lane, long pending, long admitted) {
		var values = lane == Lane.PREVIEW ? sample.preview() : sample.tracking();
		assertNotNull(values.gauge(Gauge.PENDING_ENTRIES), lane + " pending baseline must be observed");
		assertNotNull(values.gauge(Gauge.ADMITTED_FRAMES), lane + " admitted baseline must be observed");
		assertEquals(pending, values.gauge(Gauge.PENDING_ENTRIES).value(), lane + " pending entries");
		assertEquals(admitted, values.gauge(Gauge.ADMITTED_FRAMES).value(), lane + " remaining admitted frames");
	}
	private static SyncPublisher.Outcome offer(Fixture f, SyncPublisher.Context context, CaptureResult result, long work) {
		var payload = (CaptureResult.OpaqueKeyedFragment) result.payload().orElseThrow();
		return f.publisher.publish(result, new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, payload.entries().keySet()),
			context, new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, work)));
	}
	private static CaptureResult fragment(long revision, boolean complete, String... keys) {
		Map<String, CaptureResult.OpaqueValue> values = new LinkedHashMap<>();
		for (String key : keys) values.put(key, InventoryDomainCodec.encode(new InventoryDomainCodec.Item(
			new InventoryScanner.Key("minecraft:stone", key), revision, "stone", null, false)));
		return capture(revision, complete, values);
	}
	private static CaptureResult capture(long revision, boolean complete, Map<String, CaptureResult.OpaqueValue> values) {
		return new CaptureResult(Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, values)),
			new CaptureResult.Coverage("inventory", revision, values.size(), OptionalLong.of(values.size())), CaptureResult.Availability.READABLE,
			complete ? CaptureResult.Completeness.COMPLETE : CaptureResult.Completeness.CONTINUE, CaptureResult.Consistency.EVENTUAL,
			Optional.empty(), Optional.empty());
	}

	@Test void observedEmptyQueuesAndCreatedStagingDoNotInventRegularEntriesOrFrames() {
		try (var f = new Fixture(true)) {
			f.both(); assertQueues(f.sample(0), Lane.PREVIEW, 0, 0); assertQueues(f.sample(1), Lane.TRACKING, 0, 0);
			assertTrue(f.publisher.control(f.context.recipient(), InventoryS2CPacket.offer(7, f.publisher.offer(5, 3, 5, 0)).stamp(100, 1)));
			try (var staging = f.publisher.reserveInitial(500, 100).orElseThrow()) {
				var sample = f.sample(2); assertQueues(sample, Lane.PREVIEW, 0, 0); assertQueues(sample, Lane.TRACKING, 0, 0);
				assertEquals(500, sample.tracking().gauge(Gauge.QUEUE_LOGICAL_COST).value());
				assertEquals(0, sample.tracking().counter(Counter.PUBLICATIONS_INSTALLED).value()); assertEquals(0, sample.tracking().counter(Counter.CUTS_COMPLETED).value());
				staging.settle(400, 50);
				assertEquals(400, f.sample(3).tracking().gauge(Gauge.QUEUE_LOGICAL_COST).value());
			}
			f.publisher.close(); var sample = f.sample(4); assertQueues(sample, Lane.PREVIEW, 0, 0); assertQueues(sample, Lane.TRACKING, 0, 0);
		}
	}

	@Test void pendingCountsActualLatestKeysAcrossContextsAndAdmissionDefers() {
		try (var f = new Fixture(true)) {
			f.both(); var tracking = f.tracking();
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(1, false, "a", "b", "c"));
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(2, false, "b", "d"));
			InventorySyncPublisherTest.publish(f.publisher, tracking, fragment(1, true, "a", "b"));
			var peer = InventorySyncPublisherTest.context("preview/peer", 3);
			assertTrue(f.publisher.register(peer, new InventorySyncPublisher.Route(7, 100, 1, 3, null, "peer", null)));
			InventorySyncPublisherTest.publish(f.publisher, peer, fragment(1, false, "x", "y"));
			var pending = f.sample(0); assertQueues(pending, Lane.PREVIEW, 6, 0); assertQueues(pending, Lane.TRACKING, 2, 0);
			assertEquals(3, pending.preview().counter(Counter.PUBLICATIONS_INSTALLED).value());
			assertEquals(1, pending.tracking().counter(Counter.PUBLICATIONS_INSTALLED).value());
			f.memory.setCap(f.memory.retained()); f.publisher.drain();
			var deferred = f.sample(1); assertQueues(deferred, Lane.PREVIEW, 6, 0); assertQueues(deferred, Lane.TRACKING, 2, 0);
			f.memory.setCap(16_000_000); f.transport.defer = true; f.publisher.drain();
			var admitted = f.sample(2); assertQueues(admitted, Lane.PREVIEW, 0, 6);
			// The Tracking initial fence has not handed off, so its entries are not admitted yet.
			assertQueues(admitted, Lane.TRACKING, 2, 0);
			f.transport.defer = false; f.transport.dataAllowance = 0; f.publisher.drain();
			assertQueues(f.sample(3), Lane.TRACKING, 0, 2);
		}
	}

	@Test void partialSuccessShrinksFramesButNotWholeCutCostAndNewPendingCoexists() {
		try (var f = new Fixture(true)) {
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(1, false, "a", "b", "c"));
			long cost = f.sample(0).preview().gauge(Gauge.QUEUE_LOGICAL_COST).value();
			f.transport.dataAllowance = 1; f.publisher.drain();
			var partial = f.sample(1); assertQueues(partial, Lane.PREVIEW, 0, 2);
			assertEquals(cost, partial.preview().gauge(Gauge.QUEUE_LOGICAL_COST).value()); assertEquals(0, partial.preview().counter(Counter.CUTS_COMPLETED).value());
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(2, false, "a", "new"));
			assertQueues(f.sample(2), Lane.PREVIEW, 2, 2);
			f.transport.dataAllowance = Integer.MAX_VALUE; f.publisher.drain();
			var first = f.sample(3); assertQueues(first, Lane.PREVIEW, 2, 0); assertEquals(1, first.preview().counter(Counter.CUTS_COMPLETED).value());
			f.publisher.drain(); var second = f.sample(4); assertQueues(second, Lane.PREVIEW, 0, 0);
			assertEquals(2, second.preview().counter(Counter.CUTS_COMPLETED).value());
		}
	}

	@Test void thrownMultipartTailNeverCompletesBeforeTheSameTailRetriesSuccessfully() {
		try (var f = new Fixture(true)) {
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(1, true, "a", "b", "c"));
			f.transport.dataAllowance = 2; f.publisher.drain(); assertQueues(f.sample(0), Lane.PREVIEW, 0, 1);
			f.transport.dataAllowance = Integer.MAX_VALUE; f.transport.fail = true; f.publisher.drain();
			var failed = f.sample(1); assertQueues(failed, Lane.PREVIEW, 0, 1); assertEquals(0, failed.preview().counter(Counter.CUTS_COMPLETED).value());
			assertEquals(2, f.transport.sent.size());
			f.publisher.drain(); var completed = f.sample(2); assertQueues(completed, Lane.PREVIEW, 0, 0);
			assertEquals(1, completed.preview().counter(Counter.CUTS_COMPLETED).value());
			assertEquals(List.of(0, 1, 2), f.transport.sent.stream().map(InventoryS2CPacket::partIndex).toList());
			f.publisher.drain(); assertEquals(1, f.sample(3).preview().counter(Counter.CUTS_COMPLETED).value());
		}
	}

	@Test void cancellationWithdrawalRebaseInvalidityAndGroupRetirementClearBothQueueForms() {
		for (String action : List.of("cancel", "withdraw", "rebase", "invalid-status", "invalid-result", "retire")) try (var f = new Fixture(true)) {
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(1, false, "a", "b", "c"));
			f.transport.dataAllowance = 1; f.publisher.drain();
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(2, false, "a", "new"));
			assertQueues(f.sample(0), Lane.PREVIEW, 2, 2);
			switch (action) {
				case "cancel" -> f.publisher.cancel(f.context);
				case "withdraw" -> f.publisher.withdraw(f.context);
				case "rebase" -> f.publisher.rebase(f.context, 2);
				case "invalid-status" -> f.publisher.status(f.context, InventoryS2CPacket.Status.INVALID);
				case "invalid-result" -> assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publish(
					new CaptureResult(Optional.empty(), new CaptureResult.Coverage("inventory", 3, 0, OptionalLong.empty()),
						CaptureResult.Availability.UNAVAILABLE, CaptureResult.Completeness.INCOMPLETE, CaptureResult.Consistency.UNKNOWN, Optional.empty(), Optional.empty()),
					new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, java.util.Set.of()), f.context,
					new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L))));
				case "retire" -> f.publisher.retireGroup(f.context, "minecraft:stone");
				default -> fail(action);
			}
			assertQueues(f.sample(1), Lane.PREVIEW, 0, 0);
			assertEquals(0, f.sample(2).preview().gauge(Gauge.QUEUE_LOGICAL_COST).value(), action);
		}
	}

	@Test void oversizedPreviewDropClearsEntriesAndNeverCompletesTheIncompleteControl() {
		try (var f = new Fixture(true)) {
			InventorySyncPublisherTest.publish(f.publisher, f.context, InventorySyncPublisherTest.capture(1, 1, 4096));
			assertQueues(f.sample(0), Lane.PREVIEW, 1, 0); f.publisher.drain();
			var sample = f.sample(1); assertQueues(sample, Lane.PREVIEW, 0, 0);
			assertEquals(InventoryS2CPacket.Status.INCOMPLETE, f.transport.sent.getLast().status());
			assertEquals(0, sample.preview().counter(Counter.CUTS_COMPLETED).value());
		}
	}

	@Test void groupFoldCountsInstalledSuccessorSizesAndCancellationPreservesThePeer() {
		try (var f = new Fixture(true)) {
			var peer = InventorySyncPublisherTest.context("preview/peer", 3);
			assertTrue(f.publisher.register(peer, new InventorySyncPublisher.Route(7, 100, 1, 3, null, "peer", null)));
			var values = new LinkedHashMap<String, CaptureResult.OpaqueValue>();
			values.put("stone-a", InventoryDomainCodec.encode(new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "a"), 2, "stone", null, false)));
			values.put("stone-b", InventoryDomainCodec.encode(new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "b"), 3, "stone", null, false)));
			values.put("dirt", InventoryDomainCodec.encode(new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:dirt", "d"), 7, "dirt", null, false)));
			InventorySyncPublisherTest.publish(f.publisher, f.context, capture(1, false, values));
			InventorySyncPublisherTest.publish(f.publisher, peer, fragment(1, false, "peer"));
			assertQueues(f.sample(0), Lane.PREVIEW, 4, 0);
			@Unmodifiable Map<String, CaptureResult.OpaqueValue> folded = Map.of("all-stone", InventoryDomainCodec.encode(new InventoryDomainCodec.Item(
				new InventoryScanner.Key("minecraft:stone", ""), 5, "stone", null, true)));
			InventorySyncPublisherTest.publish(f.publisher, f.context, capture(2, false, folded));
			assertQueues(f.sample(1), Lane.PREVIEW, 3, 0); // aggregate + unrelated dirt + peer
			f.transport.dataAllowance = 0; f.publisher.drain(); assertQueues(f.sample(2), Lane.PREVIEW, 0, 3);
			f.publisher.cancel(f.context); assertQueues(f.sample(3), Lane.PREVIEW, 0, 1);
			f.publisher.cancel(peer); assertQueues(f.sample(4), Lane.PREVIEW, 0, 0);
		}
	}

	@Test void enablingAfterTheTickLoopWaitsForARealBaselineAndNeverReplaysPriorEvents() {
		try (var f = new Fixture(false)) {
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(1, false, "a", "b", "c"));
			f.transport.dataAllowance = 1; f.publisher.drain();
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(2, false, "d", "e"));
			int before = f.clock.reads; f.publisher.sampleMonitor(); assertEquals(before, f.clock.reads);
			f.monitor.configure(true, true); f.publisher.advance(0, f.settings);
			var unobserved = f.sample(0); assertNull(unobserved.preview().gauge(Gauge.PENDING_ENTRIES)); assertNull(unobserved.preview().gauge(Gauge.ADMITTED_FRAMES));
			assertEquals(0, unobserved.preview().counter(Counter.PUBLICATIONS_INSTALLED).value()); assertEquals(0, unobserved.preview().counter(Counter.CUTS_COMPLETED).value());
			f.publisher.advance(1, f.settings); var observed = f.sample(1); assertQueues(observed, Lane.PREVIEW, 2, 2); assertQueues(observed, Lane.TRACKING, 0, 0);
			f.monitor.configure(false, false); f.publisher.withdraw(f.context);
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(3, false, "x"));
			f.monitor.configure(true, true); f.publisher.advance(2, f.settings);
			var restarted = f.sample(2); assertQueues(restarted, Lane.PREVIEW, 1, 0);
			assertEquals(0, restarted.preview().counter(Counter.PUBLICATIONS_INSTALLED).value()); assertEquals(0, restarted.preview().counter(Counter.CUTS_COMPLETED).value());
			f.monitor.resetGeneration(9); f.publisher.advance(3, f.settings); assertQueues(f.sample(3), Lane.PREVIEW, 1, 0);
			f.publisher.cancel(f.context); assertQueues(f.sample(4), Lane.PREVIEW, 0, 0);
		}
	}

	@Test void generationResetClearsCompletedEventsAndSingleLaneMasksKeepTheirOwnQueue() {
		try (var f = new Fixture(true)) {
			f.both(); var tracking = f.tracking();
			f.publish(1, 7); f.publisher.drain();
			assertEquals(1, f.sample(0).preview().counter(Counter.CUTS_COMPLETED).value());
			f.monitor.configure(false, true); f.publisher.advance(2, f.settings);
			InventorySyncPublisherTest.publish(f.publisher, tracking, fragment(1, true, "a", "b"));
			f.publish(2, 8); var trackingOnly = f.sample(1);
			assertNull(trackingOnly.preview()); assertQueues(trackingOnly, Lane.TRACKING, 2, 0);
			assertEquals(0, trackingOnly.tracking().counter(Counter.CUTS_COMPLETED).value());
			f.monitor.configure(true, false); f.publisher.advance(3, f.settings);
			var previewOnly = f.sample(2); assertNull(previewOnly.tracking()); assertQueues(previewOnly, Lane.PREVIEW, 1, 0);
			assertEquals(0, previewOnly.preview().counter(Counter.PUBLICATIONS_INSTALLED).value()); assertEquals(0, previewOnly.preview().counter(Counter.CUTS_COMPLETED).value());
			f.monitor.resetGeneration(9); f.publisher.advance(4, f.settings);
			var reset = f.sample(3); assertQueues(reset, Lane.PREVIEW, 1, 0); assertEquals(0, reset.preview().counter(Counter.CUTS_COMPLETED).value());
		}
	}

	@Test void onlySuccessfulNewInstallsCountIncludingNoDeltaRevisionsAndBootstrap() {
		try (var f = new Fixture(true)) {
			f.both(); var tracking = f.tracking();
			var capture = InventorySyncPublisherTest.capture(2, 1, 0, 7);
			assertInstanceOf(SyncPublisher.Outcome.Deferred.class, offer(f, tracking, capture, 0));
			try (var staging = f.publisher.reserveInitial(f.settings.trackingRollingQueueBytes(), 0).orElseThrow()) {
				staging.settle(f.settings.trackingRollingQueueBytes(), 0);
				assertInstanceOf(SyncPublisher.Outcome.Deferred.class, offer(f, tracking, capture, 256));
			}
			f.memory.setCap(f.memory.retained()); assertInstanceOf(SyncPublisher.Outcome.Deferred.class, offer(f, tracking, capture, 256));
			f.memory.setCap(16_000_000); assertEquals(0, f.sample(0).tracking().counter(Counter.PUBLICATIONS_INSTALLED).value());
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, offer(f, tracking, capture, 256));
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, offer(f, tracking, capture, 0));
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, offer(f, tracking, InventorySyncPublisherTest.capture(1, 1, 0, 8), 0));
			assertEquals(1, f.sample(1).tracking().counter(Counter.PUBLICATIONS_INSTALLED).value());
			f.publisher.drain();
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, offer(f, tracking, InventorySyncPublisherTest.capture(3, 1, 0, 7), 256));
			assertQueues(f.sample(2), Lane.TRACKING, 0, 0);
			assertEquals(2, f.sample(3).tracking().counter(Counter.PUBLICATIONS_INSTALLED).value());
			var payload = (CaptureResult.OpaqueKeyedFragment) InventorySyncPublisherTest.capture(1, 1, 0, 9).payload().orElseThrow();
			var seed = InventorySyncPublisherTest.context("tracking/seed", 4);
			assertTrue(f.publisher.register(seed, InventorySyncPublisherTest.route(new MarkerId(10), "seed")));
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publishBootstrap(payload, 1, InventoryS2CPacket.Status.UPDATING,
				new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, payload.entries().keySet()), seed,
				new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L))));
			assertEquals(3, f.sample(4).tracking().counter(Counter.PUBLICATIONS_INSTALLED).value());
			var unavailable = new CaptureResult(Optional.empty(), new CaptureResult.Coverage("inventory", 4, 0, OptionalLong.empty()),
				CaptureResult.Availability.INVALID, CaptureResult.Completeness.INCOMPLETE, CaptureResult.Consistency.UNKNOWN, Optional.empty(), Optional.empty());
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publish(unavailable,
				new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, java.util.Set.of()), tracking,
				new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L))));
			f.publisher.drain(); var invalid = f.sample(5); assertEquals(3, invalid.tracking().counter(Counter.PUBLICATIONS_INSTALLED).value());
		}
	}

	@Test void emptyPreviewAndTrackingNoDeltaPromoteOnceWithoutCountingStatusOrHeartbeatAgain() {
		try (var f = new Fixture(true)) {
			f.both(); var tracking = f.tracking(); f.settings.getTracking().setHeartbeatPeriods(1);
			f.publisher.drain(); assertEquals(0, f.sample(0).tracking().counter(Counter.CUTS_COMPLETED).value(), "initial fence is not a cut");
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(1, true));
			InventorySyncPublisherTest.publish(f.publisher, tracking, InventorySyncPublisherTest.capture(1, 2, 0, 7));
			f.transport.dataAllowance = 1; f.publisher.drain();
			var partial = f.sample(1); assertEquals(1, partial.preview().counter(Counter.CUTS_COMPLETED).value()); assertEquals(0, partial.tracking().counter(Counter.CUTS_COMPLETED).value());
			f.transport.dataAllowance = Integer.MAX_VALUE; f.publisher.drain();
			assertEquals(1, f.sample(2).tracking().counter(Counter.CUTS_COMPLETED).value());
			InventorySyncPublisherTest.publish(f.publisher, tracking, InventorySyncPublisherTest.capture(2, 2, 0, 7));
			assertQueues(f.sample(3), Lane.TRACKING, 0, 0); f.publisher.drain();
			assertEquals(2, f.sample(4).tracking().counter(Counter.CUTS_COMPLETED).value());
			for (int tick = 2; tick < 5; tick++) {
				f.publisher.status(tracking, InventoryS2CPacket.Status.UNCERTAIN); f.publisher.advance(tick * 3L, f.settings); f.publisher.drain();
			}
			assertTrue(f.transport.sent.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.HEARTBEAT));
			assertEquals(2, f.sample(5).tracking().counter(Counter.CUTS_COMPLETED).value());
		}
	}

	@Test void incompleteBootstrapStatusWithdrawsBothFormsWithoutCreatingAnotherInstallOrCut() {
		try (var f = new Fixture(true)) {
			f.both(); var tracking = f.tracking();
			for (int revision = 1; revision <= 2; revision++) {
				var payload = (CaptureResult.OpaqueKeyedFragment) InventorySyncPublisherTest.capture(revision, 1, 0, revision).payload().orElseThrow();
				assertInstanceOf(SyncPublisher.Outcome.Accepted.class, f.publisher.publishBootstrap(payload, revision, InventoryS2CPacket.Status.UPDATING,
					new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, payload.entries().keySet()), tracking,
					new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L))));
				if (revision == 1) { f.transport.dataAllowance = 0; f.publisher.drain(); }
			}
			assertQueues(f.sample(0), Lane.TRACKING, 1, 1);
			f.publisher.status(tracking, InventoryS2CPacket.Status.INCOMPLETE); f.publisher.drain();
			var incomplete = f.sample(1); assertQueues(incomplete, Lane.TRACKING, 0, 0);
			assertEquals(2, incomplete.tracking().counter(Counter.PUBLICATIONS_INSTALLED).value()); assertEquals(0, incomplete.tracking().counter(Counter.CUTS_COMPLETED).value());
			assertEquals(InventoryS2CPacket.Status.INCOMPLETE, f.transport.sent.getLast().status());
		}
	}

	@Test void deferredOldCutAndNewPendingKeepIndependentCountsAcrossPartialSuccessAndThrow() {
		try (var f = new Fixture(true)) {
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(1, false, "a", "b", "c"));
			f.transport.dataAllowance = 0; f.publisher.drain();
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(2, false, "a"));
			f.publisher.drain(); var deferred = f.sample(0); assertQueues(deferred, Lane.PREVIEW, 1, 3);
			long cost = deferred.preview().gauge(Gauge.QUEUE_LOGICAL_COST).value();
			f.transport.dataAllowance = 1; f.publisher.drain(); var partial = f.sample(1); assertQueues(partial, Lane.PREVIEW, 1, 2);
			assertEquals(cost, partial.preview().gauge(Gauge.QUEUE_LOGICAL_COST).value());
			f.transport.dataAllowance = Integer.MAX_VALUE; f.transport.fail = true; f.publisher.drain();
			var failed = f.sample(2); assertQueues(failed, Lane.PREVIEW, 1, 2);
			assertEquals(cost, failed.preview().gauge(Gauge.QUEUE_LOGICAL_COST).value()); assertEquals(0, failed.preview().counter(Counter.CUTS_COMPLETED).value());
			f.publisher.drain(); var first = f.sample(3); assertQueues(first, Lane.PREVIEW, 1, 0); assertEquals(1, first.preview().counter(Counter.CUTS_COMPLETED).value());
		}
	}

	@Test void emptyInstalledCutCountsOnlyAfterSuccessfulControlAndNotDuplicateAcceptance() {
		try (var f = new Fixture(true)) {
			var empty = fragment(1, true); InventorySyncPublisherTest.publish(f.publisher, f.context, empty);
			f.transport.fail = true; f.publisher.drain(); var failed = f.sample(0);
			assertEquals(1, failed.preview().counter(Counter.PUBLICATIONS_INSTALLED).value()); assertEquals(0, failed.preview().counter(Counter.CUTS_COMPLETED).value());
			assertQueues(failed, Lane.PREVIEW, 0, 0);
			f.publisher.drain(); assertEquals(1, f.sample(1).preview().counter(Counter.CUTS_COMPLETED).value());
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, offer(f, f.context, empty, 0)); f.publisher.drain();
			var duplicate = f.sample(2); assertEquals(1, duplicate.preview().counter(Counter.PUBLICATIONS_INSTALLED).value());
			assertEquals(1, duplicate.preview().counter(Counter.CUTS_COMPLETED).value()); assertEquals(1, f.transport.sent.size());
		}
	}

	@Test void sameValueTrackingRevisionNeedsItsOwnSuccessfulControlBeforeTheSecondCut() {
		try (var f = new Fixture(true)) {
			f.both(); var tracking = f.tracking();
			InventorySyncPublisherTest.publish(f.publisher, tracking, InventorySyncPublisherTest.capture(1, 1, 0, 7)); f.publisher.drain();
			InventorySyncPublisherTest.publish(f.publisher, tracking, InventorySyncPublisherTest.capture(2, 1, 0, 7));
			f.transport.fail = true; f.publisher.drain(); var failed = f.sample(0); assertQueues(failed, Lane.TRACKING, 0, 0);
			assertEquals(2, failed.tracking().counter(Counter.PUBLICATIONS_INSTALLED).value()); assertEquals(1, failed.tracking().counter(Counter.CUTS_COMPLETED).value());
			f.publisher.drain(); var complete = f.sample(1); assertEquals(2, complete.tracking().counter(Counter.CUTS_COMPLETED).value());
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, offer(f, tracking, InventorySyncPublisherTest.capture(2, 1, 0, 7), 0));
			f.publisher.drain(); assertEquals(2, f.sample(2).tracking().counter(Counter.CUTS_COMPLETED).value());
		}
	}

	@Test void disabledInstallAndAdmissionCanCompleteInTheNewCollectionWithoutReplayingTheInstall() {
		try (var f = new Fixture(false)) {
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(1, true, "a", "b"));
			f.transport.dataAllowance = 0; f.publisher.drain();
			f.monitor.configure(true, false); f.publisher.advance(1, f.settings); assertQueues(f.sample(0), Lane.PREVIEW, 0, 2);
			f.transport.dataAllowance = Integer.MAX_VALUE; f.publisher.drain(); var complete = f.sample(1);
			assertEquals(0, complete.preview().counter(Counter.PUBLICATIONS_INSTALLED).value()); assertEquals(1, complete.preview().counter(Counter.CUTS_COMPLETED).value());
		}
	}

	@Test void sendCallbackGenerationChangeAndPeerReplacementCannotPublishAPartialQueueBaseline() {
		try (var f = new Fixture(true)) {
			var peer = InventorySyncPublisherTest.context("preview/old-peer", 2);
			assertTrue(f.publisher.register(peer, new InventorySyncPublisher.Route(7, 100, 1, 2, null, "old", null)));
			InventorySyncPublisherTest.publish(f.publisher, peer, fragment(1, false, "peer-a", "peer-b"));
			InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(1, false, "a", "b", "c"));
			assertQueues(f.sample(0), Lane.PREVIEW, 5, 0);
			f.transport.dataAllowance = 1;
			f.transport.onSend = () -> {
				f.monitor.configure(true, true); f.publisher.cancel(peer);
				var replacement = InventorySyncPublisherTest.context("preview/new-peer", 3);
				assertTrue(f.publisher.register(replacement, new InventorySyncPublisher.Route(7, 100, 1, 3, null, "new", null)));
				InventorySyncPublisherTest.publish(f.publisher, replacement, fragment(1, false, "new-a", "new-b"));
			};
			f.publisher.drain(); var callback = f.sample(1);
			assertNull(callback.preview().gauge(Gauge.PENDING_ENTRIES)); assertNull(callback.preview().gauge(Gauge.ADMITTED_FRAMES));
			f.publisher.advance(1, f.settings); var baseline = f.sample(2); assertQueues(baseline, Lane.PREVIEW, 2, 2);
			assertEquals(1, baseline.preview().counter(Counter.PUBLICATIONS_INSTALLED).value()); assertEquals(0, baseline.preview().counter(Counter.CUTS_COMPLETED).value());
		}
	}

	@Test void collectionChangeInsideTheSuccessfulFinalSendCountsTheDeliveredTransitionInTheNewWindow() {
		try (var f = new Fixture(true)) {
			f.publish(1, 7); f.transport.onSend = () -> f.monitor.configure(true, true); f.publisher.drain();
			var complete = f.sample(0); assertEquals(0, complete.preview().counter(Counter.PUBLICATIONS_INSTALLED).value());
			assertEquals(1, complete.preview().counter(Counter.CUTS_COMPLETED).value());
			f.publisher.advance(1, f.settings); assertQueues(f.sample(1), Lane.PREVIEW, 0, 0);
		}
	}

	@Test void cancelledCurrentSendCannotSubtractTheSurvivingPeersAdmittedFrame() {
		try (var f = new Fixture(true)) {
			var peer = reentrantPeer(f);
			f.transport.dataAllowance = 1; f.transport.onSend = () -> f.publisher.cancel(f.context);
			f.publisher.drain();
			assertTrue(f.publisher.pending(peer)); assertFalse(f.publisher.pending(f.context));
			assertQueues(f.sample(0), Lane.PREVIEW, 0, 1);
			f.publisher.advance(1, f.settings); assertQueues(f.sample(1), Lane.PREVIEW, 0, 1);
		}
	}

	@Test void drainingThePeerAfterCurrentSendCancellationCannotMakeTheQueueGaugeNegative() {
		try (var f = new Fixture(true)) {
			var peer = reentrantPeer(f);
			f.transport.dataAllowance = 1; f.transport.onSend = () -> f.publisher.cancel(f.context); f.publisher.drain();
			f.publisher.advance(1, f.settings); f.transport.dataAllowance = 2; f.publisher.drain();
			assertFalse(f.publisher.pending(peer)); assertEquals(2, f.transport.sent.size());
			var complete = assertDoesNotThrow(() -> f.sample(0)); assertQueues(complete, Lane.PREVIEW, 0, 0);
			assertEquals(2, complete.preview().counter(Counter.PUBLICATIONS_INSTALLED).value());
			assertEquals(1, complete.preview().counter(Counter.CUTS_COMPLETED).value(), "the cancelled cut was not promoted");
		}
	}

	@Test void cancelledCurrentStateCannotContributeThroughAnEqualContextReplacement() {
		try (var f = new Fixture(true)) {
			var peer = reentrantPeer(f);
			f.transport.dataAllowance = 1; f.transport.onSend = () -> {
				f.publisher.cancel(f.context);
				assertTrue(f.publisher.register(f.context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "replacement", null)));
				InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(1, false, "replacement-a", "replacement-b"));
			};
			f.publisher.drain(); assertQueues(f.sample(0), Lane.PREVIEW, 2, 1);
			f.publisher.advance(1, f.settings); f.publisher.drain(); assertQueues(f.sample(1), Lane.PREVIEW, 0, 3);
			f.transport.dataAllowance = Integer.MAX_VALUE; f.publisher.drain();
			var complete = f.sample(2); assertQueues(complete, Lane.PREVIEW, 0, 0);
			assertFalse(f.publisher.pending(peer)); assertFalse(f.publisher.pending(f.context)); assertEquals(4, f.transport.sent.size());
			assertEquals(3, complete.preview().counter(Counter.PUBLICATIONS_INSTALLED).value()); assertEquals(2, complete.preview().counter(Counter.CUTS_COMPLETED).value());
		}
	}

	@Test void registeredWithdrawalOrRebaseDuringSendLeavesNoCutForTheOldReturnedPartIndex() {
		for (boolean rebase : new boolean[] { false, true }) try (var f = new Fixture(true)) {
			var peer = reentrantPeer(f);
			var successor = rebase ? new SyncPublisher.Context(f.context.consumerId(), f.context.recipient(), f.context.sessionView(), 2, f.context.baseline()) : f.context;
			f.transport.dataAllowance = 1; f.transport.onSend = () -> {
				if (rebase) f.publisher.rebase(f.context, 2); else f.publisher.withdraw(f.context);
			};
			f.publisher.drain(); assertQueues(f.sample(0), Lane.PREVIEW, 0, 1);
			f.publisher.advance(1, f.settings); assertQueues(f.sample(1), Lane.PREVIEW, 0, 1);
			InventorySyncPublisherTest.publish(f.publisher, successor, fragment(2, false, "fresh-a", "fresh-b"));
			assertQueues(f.sample(2), Lane.PREVIEW, 2, 1);
			f.transport.dataAllowance = Integer.MAX_VALUE; f.publisher.drain(); var complete = f.sample(3);
			assertQueues(complete, Lane.PREVIEW, 0, 0); assertFalse(f.publisher.pending(peer)); assertFalse(f.publisher.pending(successor));
			assertEquals(3, complete.preview().counter(Counter.PUBLICATIONS_INSTALLED).value()); assertEquals(2, complete.preview().counter(Counter.CUTS_COMPLETED).value());
		}
	}

	private static SyncPublisher.Context reentrantPeer(Fixture f) {
		var peer = InventorySyncPublisherTest.context("preview/surviving-peer", 2);
		assertTrue(f.publisher.register(peer, new InventorySyncPublisher.Route(7, 100, 1, 2, null, "peer", null)));
		InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(1, false, "a"));
		InventorySyncPublisherTest.publish(f.publisher, peer, fragment(1, false, "b"));
		return peer;
	}

	static Trace reentrantTrace(boolean enabled, String action) throws Exception {
		try (var f = new Fixture(enabled)) {
			reentrantPeer(f); var queues = new ArrayList<Long>(); var charges = new ArrayList<Long>();
			f.transport.dataAllowance = 1; f.transport.onSend = () -> {
				switch (action) {
					case "cancel" -> f.publisher.cancel(f.context);
					case "replace" -> {
						f.publisher.cancel(f.context);
						assertTrue(f.publisher.register(f.context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "replacement", null)));
						InventorySyncPublisherTest.publish(f.publisher, f.context, fragment(1, false, "replacement"));
					}
					case "withdraw" -> f.publisher.withdraw(f.context);
					case "rebase" -> f.publisher.rebase(f.context, 2);
					default -> fail(action);
				}
			};
			for (int tick = 0; tick < 3; tick++) {
				f.publisher.advance(tick, f.settings); if (tick > 0) f.transport.dataAllowance = Integer.MAX_VALUE;
				f.publisher.drain(); queues.add((long) InventoryBackendTest.field(f.publisher, "previewQueued"));
				charges.add(InventoryPublicationAuthorizationTest.wireSpent(f.publisher));
				if (enabled) assertQueues(f.sample(tick), Lane.PREVIEW, tick == 0 && action.equals("replace") ? 1 : 0, tick == 0 ? 1 : 0);
				else { f.publisher.sampleMonitor(); assertNull(f.monitor.completeTick(tick)); }
			}
			if (!enabled) assertEquals(0, f.clock.reads);
			return new Trace(f.transport.sent, queues, charges, f.memory.retained(), f.transport.measurements, f.transport.proofs);
		}
	}
	@Test void sendCallbackCancellationReplacementWithdrawalAndRebaseKeepOffOnBusinessTrajectoriesIdentical() throws Exception {
		for (String action : List.of("cancel", "replace", "withdraw", "rebase")) {
			var off = reentrantTrace(false, action); var on = reentrantTrace(true, action); assertEquals(off, on, action);
			assertEquals(action.equals("replace") ? 3 : 2, on.packets().stream().filter(packet -> !packet.entries().isEmpty()).count(), action);
			// Legacy withdrawal may also send its retained empty control; monitoring does not suppress it.
			assertEquals(action.equals("replace") || action.equals("withdraw") ? 3 : 2, on.packets().size(), action);
		}
	}

	@NotNullByDefault
	record Trace(@Unmodifiable List<InventoryS2CPacket> packets, @Unmodifiable List<Long> queues, @Unmodifiable List<Long> charges,
		long retained, int measurements, int proofs) {
		Trace { packets = List.copyOf(packets); queues = List.copyOf(queues); charges = List.copyOf(charges); }
	}
	static Trace trace(boolean enabled) throws Exception {
		try (var f = new Fixture(enabled)) {
			var queues = new ArrayList<Long>(); var charges = new ArrayList<Long>();
			for (int tick = 0; tick < 4; tick++) {
				f.publisher.advance(tick, f.settings); f.publish(tick + 1, tick + 7); f.transport.defer = tick == 0;
				f.publisher.drain(); f.transport.defer = false; f.transport.fail = tick == 1; f.publisher.drain();
				queues.add((long) InventoryBackendTest.field(f.publisher, "previewQueued"));
				charges.add(InventoryPublicationAuthorizationTest.wireSpent(f.publisher));
				f.clock.time += 100; f.publisher.sampleMonitor(); f.monitor.completeTick(tick);
			}
			if (!enabled) assertEquals(0, f.clock.reads, "disabled publication, queue mutations and sampling read no monitor clock");
			return new Trace(f.transport.sent, queues, charges, f.memory.retained(), f.transport.measurements, f.transport.proofs);
		}
	}
	@Test void offAndOnKeepFullFixedKeyPacketsChecksumQueueAndBudgetTrajectoriesIdentical() throws Exception {
		var off = trace(false); var on = trace(true); assertEquals(off, on);
		assertEquals(4, off.packets().size()); assertTrue(off.packets().stream().allMatch(packet -> !packet.entries().isEmpty() && packet.checksum() != 0));
		assertTrue(off.charges().getLast() > off.charges().getFirst()); assertTrue(off.measurements() > 4);
		assertTrue(off.queues().stream().allMatch(value -> value == 0), "every retried fixed-key cut completes");
	}

	@Test void alreadyQueuedBaselineAndUniqueRetainedMemoryRemainImmutableAcrossCapReductionAndRelease() {
		try (var f = new Fixture(false)) {
			f.publish(1, 7); long queued = (long) uncheckedField(f.publisher, "previewQueued"); assertTrue(queued > 0);
			f.monitor.configure(true, false); var baseline = f.sample(0);
			assertEquals(queued, baseline.preview().gauge(Gauge.QUEUE_LOGICAL_COST).value());
			assertNull(baseline.preview().counter(Counter.BUDGET_CHARGED_BYTES));
		}
		var clock = new InventoryMonitorBackendTest.Clock(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), clock);
		var memory = new RetainedMemoryLedger(100); var shared = memory.tryReserve(40).orElseThrow(); shared.commit(40);
		var firstCursor = memory.tryReserve(5).orElseThrow(); firstCursor.commit(5);
		var secondCursor = memory.tryReserve(5).orElseThrow(); secondCursor.commit(5);
		var reservation = memory.tryReserve(20).orElseThrow(); memory.setCap(60);
		monitor.configure(true, false); monitor.memory(memory); var first = java.util.Objects.requireNonNull(monitor.completeTick(0));
		assertEquals(50, first.shared().memory().retained()); assertEquals(20, first.shared().memory().reserved()); assertEquals(-10, first.shared().memory().remaining());
		assertEquals(60, first.shared().memory().cap()); assertEquals(0, first.shared().memory().admissibleRemaining());
		firstCursor.close(); monitor.memory(memory); var second = java.util.Objects.requireNonNull(monitor.completeTick(1)); assertEquals(-5, second.shared().memory().remaining());
		reservation.close(); monitor.memory(memory); var third = java.util.Objects.requireNonNull(monitor.completeTick(2)); assertEquals(15, third.shared().memory().remaining());
		assertEquals(0, second.shared().memory().admissibleRemaining()); assertEquals(15, third.shared().memory().admissibleRemaining());
		assertEquals(-10, first.shared().memory().remaining()); assertEquals(-5, second.shared().memory().remaining());
		secondCursor.close(); shared.close(); assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	private static Object uncheckedField(Object owner, String name) {
		try { return InventoryBackendTest.field(owner, name); }
		catch (Exception failure) { throw new AssertionError(failure); }
	}
}
