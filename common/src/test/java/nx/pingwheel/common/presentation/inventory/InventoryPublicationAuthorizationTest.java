package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Immutable historical cuts delivered through the production packet writer/reader and client barrier. */
@NotNullByDefault
class InventoryPublicationAuthorizationTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@NotNullByDefault
	static final class Evidence implements InventoryPublicationEvidence {
		int references = 1, calls, releases;
		InventorySharedRead.Check check = InventorySharedRead.Check.VALID;
		@Override public InventoryValidationBudget.Cost validationCost() { return new InventoryValidationBudget.Cost(64, 64, 8); }
		@Override public int probeCalls() { return 1; }
		@Override public InventorySharedRead.Check validate(InventoryValidationBudget.Allowance allowance) { calls++; allowance.debit(validationCost()); return check; }
		@Override public InventoryPublicationEvidence retain() { assertTrue(references > 0); references++; return this; }
		@Override public void close() { assertTrue(references > 0); references--; if (references == 0) releases++; }
	}
	@NotNullByDefault
	static final class Transport implements InventorySyncPublisher.Transport {
		final List<InventoryS2CPacket> sent = new ArrayList<>();
		final List<InventoryPublicationEvidence> checked = new ArrayList<>();
		InventorySyncPublisher.SendDecision decision = InventorySyncPublisher.SendDecision.ALLOW;
		boolean block, measureRevokes;
		int measurements;
		@Nullable Runnable afterSend;
		@Nullable Evidence revoked;
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public int encodedBytes(InventoryS2CPacket packet) { throw new AssertionError("wrong measurement overload"); }
		@Override public int encodedBytes(InventoryS2CPacket packet, int display, int frame) {
			measurements++; if (measureRevokes && InventorySyncPublisher.requiresSource(packet)) java.util.Objects.requireNonNull(revoked).check = InventorySharedRead.Check.AUTHORITY_DENIED;
			return packet.encodedSize(display, frame);
		}
		@Override public InventorySyncPublisher.SendDecision beforeSend(SyncPublisher.Context context, InventoryS2CPacket packet, @Nullable InventoryPublicationEvidence evidence) {
			if (!InventorySyncPublisher.requiresSource(packet)) return InventorySyncPublisher.SendDecision.ALLOW;
			if (block) return InventorySyncPublisher.SendDecision.DEFER;
			assertNotNull(evidence); checked.add(evidence);
			var check = evidence.validate(new InventoryValidationBudget.Allowance(evidence.validationCost()));
			return check == InventorySharedRead.Check.VALID ? decision : InventorySyncPublisher.SendDecision.DENY;
		}
		@Override public void send(UUID recipient, InventoryS2CPacket packet) { sent.add(InventoryPublicationIntegrationTest.wire(packet)); if (afterSend != null) afterSend.run(); }
	}
	@NotNullByDefault
	static final class Fixture implements AutoCloseable {
		final InventorySettings settings = InventorySettings.serverDefaults();
		final RetainedMemoryLedger memory = new RetainedMemoryLedger(16_000_000);
		final Transport transport = new Transport();
		final InventorySyncPublisher publisher = new InventorySyncPublisher(memory, transport);
		final SyncPublisher.Context context = InventorySyncPublisherTest.context("tracking/1", 1);
		final InventoryPublicationIntegrationTest.Endpoint endpoint = InventoryPublicationIntegrationTest.negotiated(new MarkerId(1), settings);
		Fixture() { settings.getTracking().setHeartbeatPeriods(1); publisher.advance(0, settings); assertTrue(publisher.register(context, InventorySyncPublisherTest.route(new MarkerId(1), "target"))); }
		void offer(long revision, long count, Evidence evidence, int entries) {
			var values = new java.util.LinkedHashMap<String, CaptureResult.OpaqueValue>();
			for (int index = 0; index < entries; index++) values.put("key" + index, InventoryDomainCodec.encode(new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "exact" + index), count, "stone", null, false)));
			var result = new CaptureResult(Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, values)), new CaptureResult.Coverage("inventory", revision, 9, OptionalLong.of(9)),
				CaptureResult.Availability.READABLE, CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, publisher.publish(result, InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP,
				new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, values.keySet()), context, new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L)), evidence));
		}
		void drain() { publisher.drain(); transport.sent.forEach(endpoint.client::accept); transport.sent.clear(); }
		@Override public void close() { publisher.close(); assertEquals(0, memory.retained()); assertEquals(0, memory.reserved()); }
	}
	@Test void admittedOldCutCannotBorrowNewOfferProofAndDeniedAttemptsSpendNoWireBytes() throws ReflectiveOperationException {
		var old = new Evidence(); var next = new Evidence();
		try (var f = new Fixture()) {
			f.transport.block = true; f.offer(1, 7, old, 2); f.drain();
			assertEquals(3, old.references, "offered and admitted each pin this exact cut");
			f.offer(2, 11, next, 2); assertEquals(2, old.references); assertEquals(2, next.references);
			old.check = InventorySharedRead.Check.AUTHORITY_DENIED; f.transport.block = false;
			long before = wireSpent(f.publisher); f.drain();
			assertEquals(before, wireSpent(f.publisher)); assertFalse(f.endpoint.client.tracking(new MarkerId(1)).complete());
			assertTrue(f.endpoint.client.tracking(new MarkerId(1)).entries().isEmpty()); assertEquals(0, next.calls);
			assertTrue(f.transport.checked.stream().allMatch(e -> e == old));
			f.publisher.status(f.context, InventoryS2CPacket.Status.INVALID); f.drain(); assertEquals(1, old.references); assertEquals(1, next.references);
		} old.close(); next.close(); assertEquals(1, old.releases); assertEquals(1, next.releases);
	}
	@Test void deliveredHeartbeatUsesDeliveredProofRatherThanLatestOfferedRevision() throws ReflectiveOperationException {
		var old = new Evidence(); var next = new Evidence();
		try (var f = new Fixture()) {
			f.offer(1, 7, old, 1); f.drain(); assertTrue(f.endpoint.client.tracking(new MarkerId(1)).complete());
			f.offer(2, 7, next, 1); // unchanged value, no data; new control still waits on its own proof
			f.publisher.gateData(f.context.recipient()); f.publisher.advance(3, f.settings);
			old.check = InventorySharedRead.Check.STRUCTURE_INVALID; f.transport.checked.clear(); long before = wireSpent(f.publisher); f.drain();
			assertEquals(before, wireSpent(f.publisher)); assertEquals(List.of(old), f.transport.checked);
			assertEquals(0, next.calls); assertEquals(7, f.endpoint.client.tracking(new MarkerId(1)).entries().getFirst().count());
		} old.close(); next.close();
	}
	@Test void revocationAfterFirstIrreversiblePartBlocksSecondAndCompletionWithoutClosingClientBarrier() {
		var evidence = new Evidence();
		try (var f = new Fixture()) {
			f.offer(1, 7, evidence, 2);
			f.transport.afterSend = () -> { if (f.transport.sent.stream().anyMatch(p -> !p.entries().isEmpty())) evidence.check = InventorySharedRead.Check.AUTHORITY_DENIED; };
			f.drain(); assertFalse(f.endpoint.client.tracking(new MarkerId(1)).complete());
			assertEquals(1, f.transport.checked.stream().filter(e -> e == evidence).count() - 1, "second part is rechecked after the first handoff");
			f.transport.afterSend = null; f.publisher.rebase(f.context, 2);
			f.publisher.status(new SyncPublisher.Context(f.context.consumerId(), f.context.recipient(), f.context.sessionView(), 2, f.context.baseline()), InventoryS2CPacket.Status.INVALID); f.drain();
			assertTrue(f.endpoint.client.tracking(new MarkerId(1)).grey()); assertFalse(f.endpoint.client.tracking(new MarkerId(1)).complete());
		} evidence.close();
	}
	@Test void effectiveBudgetMeasurementCanRevokeSourceAndDeferredFrameResumesWithoutAdvancing() throws ReflectiveOperationException {
		var evidence = new Evidence();
		try (var f = new Fixture()) {
			f.offer(1, 7, evidence, 1); f.transport.revoked = evidence; f.transport.measureRevokes = true; f.drain();
			assertTrue(f.transport.measurements > 0); assertTrue(evidence.calls > 0); assertFalse(f.endpoint.client.tracking(new MarkerId(1)).complete());
			f.transport.measureRevokes = false; evidence.check = InventorySharedRead.Check.VALID; f.transport.decision = InventorySyncPublisher.SendDecision.DEFER;
			long before = wireSpent(f.publisher); f.drain(); assertEquals(before, wireSpent(f.publisher)); assertTrue(f.publisher.pending(f.context));
			f.transport.decision = InventorySyncPublisher.SendDecision.ALLOW; f.publisher.advance(3, f.settings); f.drain();
			assertTrue(f.endpoint.client.tracking(new MarkerId(1)).complete()); assertEquals(7, f.endpoint.client.tracking(new MarkerId(1)).entries().getFirst().count());
		} evidence.close();
	}
	@Test void predicateCoversProvisionalQuantitiesAndSuccessCertificatesButNotRecoveryOrFailures() {
		for (var status : InventoryS2CPacket.Status.values()) {
			var control = InventoryS2CPacket.status(7, 0, new MarkerId(1), 1, 1, 9, status, 0).stamp(100, 1);
			assertEquals(status != InventoryS2CPacket.Status.INVALID && status != InventoryS2CPacket.Status.INCOMPLETE, InventorySyncPublisher.requiresSource(control), status.name());
		}
		assertFalse(InventorySyncPublisher.requiresSource(InventoryS2CPacket.status(7, 0, new MarkerId(1), 1, 1, 0, InventoryS2CPacket.Status.UPDATING, 0).stamp(100, 1)));
	}
	@Test void rejectedAndDeferredOffersDoNotAcquireEvidenceAndRepeatedRebaseCancelReleaseExactOwners() {
		var evidence = new Evidence();
		try (var f = new Fixture()) {
			f.memory.setCap(f.memory.retained());
			var result = new CaptureResult(Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID,
				Map.of("selected", InventoryDomainCodec.encode(new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "exact"), 7, "stone", null, false))))),
				new CaptureResult.Coverage("inventory", 1, 1, OptionalLong.of(1)), CaptureResult.Availability.READABLE, CaptureResult.Completeness.COMPLETE,
				CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
			assertInstanceOf(SyncPublisher.Outcome.Deferred.class, f.publisher.publish(result, InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP,
				new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, Set.of("selected")), f.context,
				new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L)), evidence));
			assertEquals(1, evidence.references); assertEquals(0, f.memory.reserved()); f.memory.setCap(16_000_000);
			f.transport.block = true; f.offer(1, 7, evidence, 1); f.drain(); assertEquals(3, evidence.references);
			f.publisher.rebase(f.context, 2); assertEquals(1, evidence.references);
			var fresh = new SyncPublisher.Context(f.context.consumerId(), f.context.recipient(), f.context.sessionView(), 2, f.context.baseline());
			f.publisher.cancel(fresh); f.publisher.cancel(fresh); assertEquals(1, evidence.references);
		} evidence.close(); assertEquals(1, evidence.releases);
	}
	static long wireSpent(InventorySyncPublisher publisher) throws ReflectiveOperationException {
		var field = InventorySyncPublisher.class.getDeclaredField("clientBytes"); field.setAccessible(true);
		@SuppressWarnings("unchecked") var bytes = (Map<String, Long>) field.get(publisher); return bytes.values().stream().mapToLong(Long::longValue).sum();
	}
}
