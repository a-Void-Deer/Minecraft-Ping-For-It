package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryPublicationReservationTest {
	static Object field(Object value, String name) throws ReflectiveOperationException { var field = value.getClass().getDeclaredField(name); field.setAccessible(true); return java.util.Objects.requireNonNull(field.get(value)); }
	static CostLedger ledger(InventoryRuntime runtime) throws ReflectiveOperationException { return (CostLedger) field(runtime, "physical"); }
	static final CostLedger.Counter ITEM = new CostLedger.Counter("inventory/item_work", CostLedger.Unit.WORK);
	@Test void protectedGrantSurvivesExhaustedNormalCreditAndUnusedGrantClosesWithoutRefundingAttempts() throws ReflectiveOperationException {
		var settings = InventorySettings.serverDefaults(); var evidence = new InventoryPublicationAuthorizationTest.Evidence();
		var runtime = new InventoryRuntime(input -> Optional.empty(), settings.pendingMemoryBytes());
		try (runtime) {
			runtime.advance(0, settings); var held = runtime.reservePublication(evidence).orElseThrow();
			var ledger = ledger(runtime);
			@Unmodifiable Map<CostLedger.Counter, Long> amounts = Map.of(ITEM, ledger.remaining(ITEM), InventorySourceAccess.PROBES, ledger.remaining(InventorySourceAccess.PROBES), InventorySourceAccess.PROVIDER_WORK, ledger.remaining(InventorySourceAccess.PROVIDER_WORK));
			ledger.tryReserve(amounts).orElseThrow().commit(amounts);
			assertTrue(runtime.reservePublication(evidence).isEmpty()); assertEquals(0, evidence.calls);
			assertEquals(InventorySharedRead.Check.VALID, held.validate()); held.close(); held.close(); assertEquals(1, evidence.calls);
			assertEquals(ledger.limit(ITEM), ledger.used(ITEM)); assertEquals(ledger.limit(InventorySourceAccess.PROBES), ledger.used(InventorySourceAccess.PROBES));
			assertEquals(0, runtime.memory().reserved()); runtime.advance(1, settings);
			long retained = runtime.memory().retained(); try (var unused = runtime.reservePublication(evidence).orElseThrow()) { assertTrue(runtime.memory().reserved() > 0); }
			assertEquals(0, ledger(runtime).used(ITEM)); assertEquals(0, ledger(runtime).used(InventorySourceAccess.PROBES)); assertEquals(retained, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
		} evidence.close(); assertEquals(1, evidence.releases);
	}
	@Test void oneShortMetadataWorkOrProbeCreditDefersBeforeCallbacksAndNoPhysicalSlotIsNeeded() throws ReflectiveOperationException {
		for (String counter : List.of("item", "probe", "provider", "memory")) {
			var settings = InventorySettings.serverDefaults(); var evidence = new InventoryPublicationAuthorizationTest.Evidence();
			try (var runtime = new InventoryRuntime(input -> Optional.empty(), settings.pendingMemoryBytes())) {
				runtime.advance(0, settings); var ledger = ledger(runtime);
				if (counter.equals("memory")) runtime.memory().setCap(0);
				else {
					var key = counter.equals("item") ? ITEM : counter.equals("probe") ? InventorySourceAccess.PROBES : InventorySourceAccess.PROVIDER_WORK;
					long required = counter.equals("item") ? evidence.validationCost().workUnits() : counter.equals("probe") ? 1 : InventorySourceAccess.PROVIDER_CALL_WORK;
					@Unmodifiable Map<CostLedger.Counter, Long> amounts = Map.of(key, ledger.remaining(key) - required + 1); ledger.tryReserve(amounts).orElseThrow().commit(amounts);
				}
				assertTrue(runtime.reservePublication(evidence).isEmpty()); assertEquals(0, evidence.calls); assertEquals(0, runtime.memory().reserved());
				runtime.memory().setCap(settings.pendingMemoryBytes()); runtime.advance(1, settings);
				@Unmodifiable Map<CostLedger.Counter, Long> physical = Map.of(InventorySourceAccess.PHYSICAL, ledger(runtime).remaining(InventorySourceAccess.PHYSICAL)); ledger(runtime).tryReserve(physical).orElseThrow().commit(physical);
				try (var grant = runtime.reservePublication(evidence).orElseThrow()) { assertEquals(InventorySharedRead.Check.VALID, grant.validate()); }
			} evidence.close();
		}
	}
	@Test void backendRotatesFreshPendingContextsBeforeContinuousCaptureAndSameTickIngressCannotReserveAgain() throws ReflectiveOperationException {
		var settings = InventorySettings.serverDefaults();
		settings.getPreview().setMaxTargetsPerClient(nx.pingwheel.common.config.IntLimit.finite(4));
		@NotNullByDefault class Host extends InventoryBackendTest.Host {
			boolean block = true, exhaust;
			@Nullable InventoryRuntime runtime;
			@Override public InventorySyncPublisher.SendDecision beforeSend(SyncPublisher.Context context, InventoryS2CPacket packet, @Nullable InventoryPublicationEvidence evidence) {
				return block && InventorySyncPublisher.requiresSource(packet) ? InventorySyncPublisher.SendDecision.DEFER : InventorySyncPublisher.SendDecision.ALLOW;
			}
			@Override public int encodedBytes(InventoryS2CPacket packet, int display, int frame) {
				if (exhaust && InventorySyncPublisher.requiresSource(packet)) try {
					var ledger = ledger(java.util.Objects.requireNonNull(runtime));
					@Unmodifiable Map<CostLedger.Counter, Long> amounts = Map.of(InventorySourceAccess.PROBES, ledger.remaining(InventorySourceAccess.PROBES), InventorySourceAccess.PROVIDER_WORK, ledger.remaining(InventorySourceAccess.PROVIDER_WORK));
					ledger.tryReserve(amounts).orElseThrow().commit(amounts);
				} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
				return packet.encodedSize(display, frame);
			}
		}
		var host = new Host(); var reads = new java.util.concurrent.atomic.AtomicInteger();
		var runtime = new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 1)), settings.pendingMemoryBytes()); host.runtime = runtime;
		try (var backend = new InventoryBackend(host, runtime, 1)) {
			for (UUID player : List.of(InventoryBackendTest.A, InventoryBackendTest.B)) backend.handle(player, InventoryC2SPacket.hello(), 0, settings);
			// Pending previews have their own real request contexts and exact source evidence.
			for (int request = 1; request <= 3; request++) backend.handle(InventoryBackendTest.A,
				InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, request, InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings);
			backend.tick(0, settings); assertTrue(host.packets.stream().noneMatch(packet -> !packet.entries().isEmpty()));
			host.block = false; host.exhaust = true;
			var serviced = new java.util.HashSet<Long>();
			for (int tick = 1; tick <= 6 && serviced.size() < 3; tick++) {
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.close(host.epoch(InventoryBackendTest.A), 999).stamp(100, 1), tick, settings);
				Object opportunity = field(backend, "publicationOpportunity");
				var oldLedger = ledger(runtime);
				for (int ingress = 0; ingress < 4; ingress++) backend.handle(InventoryBackendTest.A, InventoryC2SPacket.close(host.epoch(InventoryBackendTest.A), 999).stamp(100, 1), tick, settings);
				assertSame(opportunity, field(backend, "publicationOpportunity")); assertSame(oldLedger, ledger(runtime));
				backend.tick(tick, settings);
				for (var packet : host.packets) if (!packet.entries().isEmpty()) serviced.add(packet.requestId());
				assertEquals(0, runtime.memory().reserved()); assertTrue(oldLedger.used(InventorySourceAccess.PROBES) <= oldLedger.limit(InventorySourceAccess.PROBES));
			}
			assertEquals(Set.of(1L, 2L, 3L), serviced); assertEquals(1, reads.get(), "publication-only progress cannot recapture terminal previews");
		} assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void legacyHistoricalPublicationEvidenceSurvivesSourceReplacementWithoutPinningRoundOrDecodedPages() throws ReflectiveOperationException {
		var settings = InventorySettings.serverDefaults(); var reads = new java.util.concurrent.atomic.AtomicInteger();
		var open = new java.util.concurrent.atomic.AtomicInteger(); boolean[] originalValid = {true}; int[] generation = {0};
		var runtime = new InventoryRuntime(input -> {
			boolean original = generation[0] == 0; open.incrementAndGet();
			return Optional.of(new InventoryRuntimeTest.Source(input, reads, 9) {
				@Override public boolean valid() { return !original || originalValid[0]; }
				@Override public void close() { open.decrementAndGet(); }
			});
		}, settings.pendingMemoryBytes());
		InventoryPublicationEvidence evidence;
		try (runtime) {
			runtime.advance(0, settings); var consumer = runtime.attach(new InventorySourceInput(InventoryRuntimeTest.TARGET, InventoryBackendTest.A, BlockFace.NORTH), new InventoryRuntime.TrackingSubject(nx.pingwheel.common.marker.TargetKey.from(InventoryRuntimeTest.TARGET))).orElseThrow();
			while (runtime.step(consumer, 3).orElseThrow().result().completeness() == nx.pingwheel.common.presentation.source.CaptureResult.Completeness.CONTINUE) {}
			consumer.retainCompleted(); evidence = runtime.publicationEvidence(consumer, InventoryRuntime.EvidenceScope.COMPLETED).orElseThrow(); Object round = field(consumer, "round");
			consumer.restart(); assertTrue(((List<?>) field(round, "slots")).isEmpty()); generation[0]++;
			consumer.close(); assertEquals(0, field(runtime, "retainedRounds")); assertEquals(1, open.get(), "only historical source metadata remains");
			assertEquals(InventorySharedRead.Check.VALID, evidence.validate(new InventoryValidationBudget.Allowance(evidence.validationCost())));
			originalValid[0] = false; assertEquals(InventorySharedRead.Check.UNAVAILABLE, evidence.validate(new InventoryValidationBudget.Allowance(evidence.validationCost())));
			evidence.close(); assertEquals(0, open.get());
		} assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void selectedRouteOpportunityChecksTheActualNewerCutRatherThanCachingTheSelectedProof() {
		var settings = InventorySettings.serverDefaults(); var old = new InventoryPublicationAuthorizationTest.Evidence(); var next = new InventoryPublicationAuthorizationTest.Evidence();
		try (var runtime = new InventoryRuntime(input -> Optional.empty(), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); try (var grant = runtime.reservePublication(old).orElseThrow()) {
				assertTrue(grant.forEvidence(next)); next.check = InventorySharedRead.Check.STRUCTURE_INVALID;
				assertEquals(InventorySharedRead.Check.STRUCTURE_INVALID, grant.validate(next)); assertEquals(0, old.calls); assertEquals(1, next.calls);
			}
		} old.close(); next.close();
	}
}
