package nx.pingwheel.common.presentation.source;

import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.source.SyncPublisher.AuthorizedProjection;
import nx.pingwheel.common.presentation.source.SyncPublisher.Context;
import nx.pingwheel.common.presentation.source.SyncPublisher.Outcome;
import nx.pingwheel.common.presentation.source.SyncPublisher.PublicationForm;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncPublisherTest {

	private static final CostLedger.Counter WIRE = new CostLedger.Counter("client-a", CostLedger.Unit.WIRE_BYTES);

	@Test
	void keyedPublicationMergesPerKeyWithoutZeroingAbsentKeys() {
		var publisher = new FakeSyncPublisher();
		var ledger = new CostLedger(Map.of(WIRE, 512L));
		var consumer = context("preview:req-1", 1L);

		assertEquals(new Outcome.Accepted(), publisher.publish(keyedResult("test:a", 1.0), projection("test:a", "test:b"), consumer, ledger));
		assertEquals(new Outcome.Accepted(), publisher.publish(keyedResult("test:b", 2.0), projection("test:a", "test:b"), consumer, ledger));
		assertEquals(new Outcome.Accepted(), publisher.publish(keyedResult("test:a", 5.0), projection("test:a", "test:b"), consumer, ledger));

		assertEquals(Map.of("test:a", number(5.0), "test:b", number(2.0)), publisher.delivered.get("preview:req-1"),
			"an update for one key must not reset another key's absolute value");

		assertEquals(new Outcome.Accepted(), publisher.publish(keyedResult("test:secret", 9.0), projection("test:a", "test:b"), consumer, ledger));
		assertFalse(publisher.delivered.get("preview:req-1").containsKey("test:secret"),
			"a server projection filters unauthorized keys");
	}

	@Test
	void fenceRebaseAndCancelAreIsolatedPerConsumer() {
		var publisher = new FakeSyncPublisher();
		var ledger = new CostLedger(Map.of(WIRE, 512L));
		var trackingA = context("track:ping-1", 1L);
		var trackingB = context("track:ping-2", 1L);

		assertEquals(new Outcome.Accepted(), publisher.publish(keyedResult("test:a", 1.0), projection("test:a"), trackingA, ledger));
		assertEquals(new Outcome.Accepted(), publisher.publish(keyedResult("test:b", 2.0), projection("test:b"), trackingB, ledger));

		publisher.rebase(trackingA, 7L);
		assertEquals(new Outcome.Rejected(), publisher.publish(keyedResult("test:a", 3.0), projection("test:a"), trackingA, ledger));
		assertEquals(new Outcome.Accepted(), publisher.publish(keyedResult("test:b", 4.0), projection("test:b"), trackingB, ledger));

		publisher.cancel(trackingA);
		assertEquals(new Outcome.Rejected(), publisher.publish(keyedResult("test:a", 5.0), projection("test:a"), trackingA, ledger));
		assertEquals(new Outcome.Accepted(), publisher.publish(keyedResult("test:b", 6.0), projection("test:b"), trackingB, ledger));

		assertEquals(number(1.0), publisher.delivered.get("track:ping-1").get("test:a"),
			"a rebase or cancel must not discard another key's older delivered value");
		assertEquals(number(6.0), publisher.delivered.get("track:ping-2").get("test:b"),
			"consumer B keeps its own revision despite A's rebase and cancel");
	}

	@Test
	void budgetDeferKeepsTheObservationValidAndPublishesNothing() {
		var publisher = new FakeSyncPublisher();
		var ledger = new CostLedger(Map.of(WIRE, 0L));
		var result = keyedResult("test:a", 1.0);

		var outcome = publisher.publish(result, projection("test:a"), context("preview:req-2", 0L), ledger);

		assertEquals(new Outcome.Deferred(), outcome);
		assertFalse(outcome instanceof Outcome.Rejected);
		assertEquals(CaptureResult.Availability.READABLE, result.availability());
		assertEquals(CaptureResult.Completeness.COMPLETE, result.completeness());
		assertTrue(publisher.delivered.isEmpty());
	}

	@Test
	void unavailableControlIsNotDeferredAndCarriesNoData() {
		var publisher = new FakeSyncPublisher();
		var control = new CaptureResult(Optional.empty(),
			new CaptureResult.Coverage("demand-1", 0, 0, OptionalLong.empty()),
			CaptureResult.Availability.UNAVAILABLE, CaptureResult.Completeness.INCOMPLETE,
			CaptureResult.Consistency.UNKNOWN, Optional.empty(), Optional.empty());

		var outcome = publisher.publish(control, projection(), context("preview:req-3", 0L), new CostLedger(Map.of(WIRE, 0L)));

		assertEquals(new Outcome.Accepted(), outcome);
		assertFalse(outcome instanceof Outcome.Deferred, "an unavailable source maps to status, never to a budget defer");
		assertTrue(control.payload().isEmpty());
		assertTrue(publisher.delivered.isEmpty(), "control information must not generate data or a fake zero");
	}

	@Test
	void contextAndProjectionBoundsAreValidatedAndFrozen() {
		var keys = new LinkedHashSet<String>();
		keys.add("test:a");
		var projection = new AuthorizedProjection(PublicationForm.KEYED_ABSOLUTE, keys);

		keys.add("test:late");

		assertEquals(Set.of("test:a"), projection.authorizedKeys());
		assertThrows(UnsupportedOperationException.class, () -> projection.authorizedKeys().add("test:extra"));
		assertThrows(IllegalArgumentException.class, () -> new AuthorizedProjection(PublicationForm.SNAPSHOT_ONLY, Set.of(" ")));
		assertThrows(IllegalArgumentException.class, () -> new Context(" ", UUID.randomUUID(), "session-1", 0L, 0L));
		assertThrows(IllegalArgumentException.class, () -> new Context("preview:req-4", UUID.randomUUID(), "session-1", -1L, 0L));
		assertThrows(IllegalArgumentException.class, () -> new Context("preview:req-4", UUID.randomUUID(), "session-1", 0L, -1L));
		assertThrows(NullPointerException.class, () -> new Context("preview:req-4", null, "session-1", 0L, 0L));
	}

	private static CaptureResult keyedResult(String key, double value) {
		return new CaptureResult(
			Optional.of(new CaptureResult.KeyedFragment(Map.of(key, number(value)))),
			new CaptureResult.Coverage("demand-1", 1, 1, OptionalLong.of(1)),
			CaptureResult.Availability.READABLE,
			CaptureResult.Completeness.COMPLETE,
			CaptureResult.Consistency.EVENTUAL,
			Optional.empty(),
			Optional.empty());
	}

	private static PresentationValue number(double value) {
		return new PresentationValue.NumberValue(value);
	}

	private static AuthorizedProjection projection(String... keys) {
		return new AuthorizedProjection(PublicationForm.KEYED_ABSOLUTE, Set.of(keys));
	}

	private static Context context(String consumerId, long stateFence) {
		return new Context(consumerId, UUID.randomUUID(), "session-1", stateFence, 0L);
	}

	private static final class FakeSyncPublisher implements SyncPublisher {

		final Set<String> cancelled = new HashSet<>();
		final Map<String, Long> fences = new HashMap<>();
		final Map<String, Map<String, PresentationValue>> delivered = new LinkedHashMap<>();

		@Override
		public Outcome publish(CaptureResult result, AuthorizedProjection projection, Context context, CostLedger ledger) {
			String consumerId = context.consumerId();
			if (cancelled.contains(consumerId)) return new Outcome.Rejected();
			long knownFence = fences.getOrDefault(consumerId, context.stateFence());
			if (context.stateFence() < knownFence) return new Outcome.Rejected();
			if (result.availability() != CaptureResult.Availability.READABLE) return new Outcome.Accepted();
			if (!(result.payload().orElse(null) instanceof CaptureResult.KeyedFragment fragment)) return new Outcome.Rejected();

			var ticket = ledger.tryReserve(Map.of(WIRE, 64L));
			if (ticket.isEmpty()) return new Outcome.Deferred();
			ticket.get().commit(Map.of(WIRE, 64L));

			Map<String, PresentationValue> entries = delivered.computeIfAbsent(consumerId, key -> new LinkedHashMap<>());
			for (var entry : fragment.entries().entrySet()) {
				if (projection.authorizedKeys().contains(entry.getKey())) entries.put(entry.getKey(), entry.getValue());
			}
			return new Outcome.Accepted();
		}

		@Override
		public void cancel(Context context) {
			cancelled.add(context.consumerId());
		}

		@Override
		public void rebase(Context context, long newStateFence) {
			fences.put(context.consumerId(), newStateFence);
		}
	}
}
