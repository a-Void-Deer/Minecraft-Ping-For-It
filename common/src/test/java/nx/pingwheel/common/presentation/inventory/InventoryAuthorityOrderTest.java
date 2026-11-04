package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.AuthoritativeTargetValidation;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerCreationService;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.ValidatedMarkerTarget;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CostLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryAuthorityOrderTest {
	private static final InventoryValidationBudget.Cost RESOLUTION = new InventoryValidationBudget.Cost(96, 64, 7);
	private static final InventoryValidationBudget.Cost RESOLUTION_ATTEMPT = new InventoryValidationBudget.Cost(32, 64, 3);
	private static final InventoryValidationBudget.Cost VALIDATION = new InventoryValidationBudget.Cost(80, 48, 11);
	private static final InventoryValidationBudget.Cost VALIDATION_ATTEMPT = new InventoryValidationBudget.Cost(40, 48, 5);
	private record MetadataAttempt(String event, int source, InventoryValidationBudget.Allowance allowance,
		long itemWork, long probeUse, long probeReservations) {}
	private static CostLedger ledger(InventoryRuntime runtime) {
		try { return InventoryRuntimeTest.itemLedger(runtime); }
		catch (Exception failure) { throw new AssertionError(failure); }
	}
	private static long reserved(CostLedger ledger, CostLedger.Counter counter) {
		return ledger.limit(counter) - ledger.used(counter) - ledger.remaining(counter);
	}
	static final class OrderedHost extends InventoryBackendTest.Host {
		final List<String> events = new ArrayList<>();
		boolean rateLimited, channelDisabled, targetGone, readable = true, annotation = true;
		boolean failPublication;
		boolean revokeAtAnnotation;
		@Nullable InventoryBackend backend;
		InventoryBackend backend() { return java.util.Objects.requireNonNull(backend); }
		long admittedProbeReservations, admittedWorkReservations;
		final MarkerCreationService service;
		OrderedHost() {
			var type = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
			service = new MarkerCreationService(store, (target, match) -> {
				events.add("classification"); return new ResolvedTarget(target, type);
			}, PingTypeCatalog.builtIn(), (owner, target) -> {
				events.add("target");
				return targetGone ? AuthoritativeTargetValidation.rejected(MarkerRejectReason.TARGET_GONE)
					: AuthoritativeTargetValidation.accepted(new ValidatedMarkerTarget(target, TargetMatchContext.none(), new MarkerAnchor(1, 2, 3), new TargetNameJson("{\"text\":\"Chest\"}")));
			});
		}
		@Override public boolean annotationAllowed(InventorySourceInput input, String type) {
			events.add("annotation"); if (revokeAtAnnotation) readable = false; return annotation;
		}
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			events.add("rate"); if (rateLimited) return new InventoryBackend.Created(null, MarkerRejectReason.RATE_LIMITED);
			events.add("channel"); if (channelDisabled) return new InventoryBackend.Created(null, MarkerRejectReason.CHANNEL_DISABLED);
			events.add("audience");
			var result = service.createDedicated(null, player, frozen.target(), frozen.defaultPingType(), 1, 80, List.of(InventoryBackendTest.A, InventoryBackendTest.B),
				(target, key, type, owner, audience) -> {
					events.add("dedicated"); assertEquals(0, store.size(), "sidecar must prepare before storage even with no SECTION intents");
					@Nullable var rejected = admission.prepare(target, key, type, audience);
					return rejected == null ? MarkerCreationService.AdmissionResult.accepted(List.of(), java.util.Map.of()) : MarkerCreationService.AdmissionResult.rejected(rejected);
				});
			if (!result.isAccepted()) return new InventoryBackend.Created(null, result.rejectReason().orElseThrow());
			var ledger = ledger(backend().runtime());
			admittedProbeReservations = reserved(ledger, InventorySourceAccess.PROBES);
			admittedWorkReservations = reserved(ledger, InventoryRuntimeTest.itemWorkCounter());
			events.add("stored");
			return new InventoryBackend.Created(result.creation().orElseThrow().marker(), null, result.targetName().orElseThrow());
		}
		@Override public void publishCreated(InventoryBackend.Created created) {
			events.add("basic");
			assertNotNull(created.authoritativeName(), "committed publication carries the authoritative name");
			assertTrue(backend().tracksInventory(created.marker().id()),
				"the tracking sidecar is installed before the first CREATED");
			if (failPublication) throw new IllegalStateException("publication failed after commit");
		}
	}
	static final class Fixture implements AutoCloseable {
		final OrderedHost host = new OrderedHost(); final AtomicInteger reads = new AtomicInteger(); final InventorySettings settings = InventorySettings.serverDefaults();
		final AtomicInteger snapshotPlans = new AtomicInteger();
		final List<MetadataAttempt> attempts = new ArrayList<>();
		int sourceSequence;
		void metadata(String event, int source, InventoryValidationBudget.Cost admitted,
			InventoryValidationBudget.Cost attempted, InventoryValidationBudget.Allowance allowance) {
			var runtime = host.backend().runtime(); var ledger = ledger(runtime);
			assertTrue(reserved(ledger, InventoryRuntimeTest.itemWorkCounter()) >= admitted.workUnits(), "item work is reserved before " + event);
			assertTrue(runtime.memory().reserved() >= admitted.workspaceBytes(), "metadata scratch precedes " + event);
			allowance.debit(attempted);
			attempts.add(new MetadataAttempt(event, source, allowance, ledger.used(InventoryRuntimeTest.itemWorkCounter()),
				ledger.used(InventorySourceAccess.PROBES), reserved(ledger, InventorySourceAccess.PROBES)));
			host.events.add(event);
		}
		final InventoryBackend backend = new InventoryBackend(host, new InventoryRuntime(new InventoryRuntime.SourceResolver() {
			@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return RESOLUTION; }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int displayBytes) {
				throw new AssertionError("resolution must keep its caller-paid allowance");
			}
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int displayBytes, InventoryValidationBudget.Allowance allowance) {
				int source = ++sourceSequence;
				metadata("source", source, RESOLUTION, RESOLUTION_ATTEMPT, allowance);
				return Optional.of(new InventoryRuntimeTest.Source(input, reads, 1) {
					@Override public InventoryValidationBudget.Cost evidenceValidationCost() { return VALIDATION; }
					@Override public InventoryValidationBudget.Cost discoveryValidationCost() { return VALIDATION.times(2); }
					@Override public boolean valid() { throw new AssertionError("metadata validation must keep its caller-paid allowance"); }
					@Override public boolean valid(InventoryValidationBudget.Allowance grant) {
						metadata("read-safety", source, VALIDATION, VALIDATION_ATTEMPT, grant); return host.readable;
					}
					@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
						snapshotPlans.incrementAndGet(); return Optional.empty();
					}
					@Override public InventoryDomainCodec.@Nullable Item read(int slot) { host.events.add("witness"); return super.read(slot); }
				});
			}
		}, 16000000), 1);
		{ host.backend = backend; }
		InventoryC2SPacket selected;
		Fixture() {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1, InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings); backend.tick(0, settings);
			var preview = host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			selected = InventoryC2SPacket.select(host.epoch(InventoryBackendTest.A), 100, 1, 1, 1, preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger");
			host.events.clear();
			attempts.clear();
		}
		MarkerRejectReason reject() { backend.handle(InventoryBackendTest.A, selected, 1, settings); return host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.REJECT).reduce((a, b) -> b).orElseThrow().rejection(); }
		InventoryS2CPacket lastOutcome(long commit) {
			return host.packets.stream().filter(p -> p.commitId() == commit
				&& (p.kind() == InventoryS2CPacket.Kind.SELECTED || p.kind() == InventoryS2CPacket.Kind.REJECT))
				.reduce((a, b) -> b).orElseThrow();
		}
		@Override public void close() { backend.close(); }
	}
	@Test void rateAndChannelRemainFirstRejectionForBothReadableAndGoneSourcesWithNoProviderOrAnnotationCalls() {
		for (boolean readable : List.of(true, false)) {
			try (var f = new Fixture()) {
				f.host.readable = readable; f.host.targetGone = !readable; f.host.rateLimited = true; f.host.channelDisabled = true; int before = f.reads.get();
				assertEquals(MarkerRejectReason.RATE_LIMITED, f.reject()); assertEquals(List.of("rate"), f.host.events); assertEquals(before, f.reads.get()); assertEquals(0, f.host.store.size());
				f.backend.handle(InventoryBackendTest.A, f.selected, 2, f.settings); assertEquals(List.of("rate"), f.host.events, "replay is not another gate check or queued retry");
			}
			try (var f = new Fixture()) {
				f.host.readable = readable; f.host.targetGone = !readable; f.host.channelDisabled = true; int before = f.reads.get();
				assertEquals(MarkerRejectReason.CHANNEL_DISABLED, f.reject()); assertEquals(List.of("rate", "channel"), f.host.events); assertEquals(before, f.reads.get()); assertEquals(0, f.host.store.size());
			}
		}
	}
	@Test void authoritativeGoneTargetPrecedesSourceAdmissionAndReturnsTargetGoneUnchanged() {
		try (var f = new Fixture()) {
			f.host.targetGone = true; f.host.readable = false; int before = f.reads.get();
			assertEquals(MarkerRejectReason.TARGET_GONE, f.reject()); assertEquals(List.of("rate", "channel", "audience", "target"), f.host.events); assertEquals(before, f.reads.get()); assertEquals(0, f.host.store.size());
		}
	}
	@Test void committedValidationPreviewEvidenceAndAnnotationPrecedeStorageWithoutAContentWitness() {
		try (var f = new Fixture()) {
			int before = f.reads.get(), beforePlans = f.snapshotPlans.get(); f.backend.handle(InventoryBackendTest.A, f.selected, 1, f.settings);
			// The extra pair acquires independent committed-input metadata for the frozen
			// receipt, not another preview/content witness. Audience opportunities only reserve.
			assertEquals(List.of("rate", "channel", "audience", "target", "classification", "dedicated", "source", "read-safety", "read-safety", "annotation",
				"source", "read-safety", "stored", "basic", "source", "read-safety"), f.host.events);
			@Unmodifiable List<String> callbacks = f.attempts.stream().map(MetadataAttempt::event).toList();
			assertEquals(List.of("source", "read-safety", "read-safety", "source", "read-safety", "source", "read-safety"), callbacks);
			assertEquals(f.attempts.get(0).source(), f.attempts.get(1).source());
			assertSame(f.attempts.get(0).allowance(), f.attempts.get(1).allowance(), "input resolution/validation share one cumulative allowance");
			assertNotEquals(f.attempts.get(1).source(), f.attempts.get(2).source(), "preview authority checks its retained source");
			assertEquals(f.attempts.get(3).source(), f.attempts.get(4).source(), "receipt checks its independently resolved binding");
			assertNotEquals(f.attempts.get(2).source(), f.attempts.get(3).source());
			assertNotSame(f.attempts.get(3).allowance(), f.attempts.get(4).allowance(), "publication validation has a separately paid opportunity");
			assertNotEquals(f.attempts.get(3).source(), f.attempts.get(5).source(), "postpublication check is current, not reuse of the receipt proof");
			assertSame(f.attempts.get(5).allowance(), f.attempts.get(6).allowance());
			var ledger = ledger(f.backend.runtime());
			long snapshotWork = InventoryCreationSnapshot.validationWorkspace(Math.toIntExact(f.settings.effectiveDisplayBytes()));
			assertEquals(snapshotWork + RESOLUTION_ATTEMPT.workUnits() + 2 * VALIDATION_ATTEMPT.workUnits(), f.attempts.get(3).itemWork());
			assertEquals(f.attempts.get(3).itemWork() + RESOLUTION_ATTEMPT.workUnits(), f.attempts.get(4).itemWork(), "resolution settles once before binding validation");
			assertEquals(4, f.attempts.get(3).probeUse());
			assertEquals(1, f.attempts.get(4).probeReservations(), "binding validity is prepaid before the callback");
			assertEquals(2, f.host.admittedProbeReservations, "two authorized audience opportunities remain reserved, not attempted before storage");
			assertEquals(2 * VALIDATION.workUnits(), f.host.admittedWorkReservations);
			assertEquals(6, ledger.used(InventorySourceAccess.PROBES), "input+preview+annotation+binding resolve/validate+postpublication probe");
			assertEquals(6 * InventorySourceAccess.PROVIDER_CALL_WORK, ledger.used(InventorySourceAccess.PROVIDER_WORK));
			assertEquals(snapshotWork + 3 * RESOLUTION_ATTEMPT.workUnits() + 4 * VALIDATION_ATTEMPT.workUnits(), ledger.used(InventoryRuntimeTest.itemWorkCounter()),
				"each attempted metadata prefix is paid once; unused validation headroom/audience opportunities are refunded");
			for (var counter : List.of(InventorySourceAccess.PROBES, InventorySourceAccess.PROVIDER_WORK, InventoryRuntimeTest.itemWorkCounter()))
				assertEquals(0, reserved(ledger, counter));
			assertEquals(0, ledger.used(InventorySourceAccess.PHYSICAL), "SELECT never visits another content slot");
			assertEquals(beforePlans, f.snapshotPlans.get(), "metadata checks never reenter capture discovery");
			assertEquals(before, f.reads.get(), "immediate metadata authority, not a content witness or queued create"); assertEquals(1, f.host.store.size()); assertEquals(0, f.backend.runtime().memory().reserved());
		}
		try (var f = new Fixture()) { f.host.readable = false; assertEquals(MarkerRejectReason.INVALID_REQUEST, f.reject()); assertFalse(f.host.events.contains("witness")); assertFalse(f.host.events.contains("annotation")); assertEquals(0, f.host.store.size()); }
		try (var f = new Fixture()) { f.host.annotation = false; assertEquals(MarkerRejectReason.INVALID_PING_TYPE, f.reject()); assertEquals(0, f.host.store.size()); assertEquals(0, f.backend.runtime().memory().reserved()); }
	}
	@Test void receiptBindingRevalidatesAfterAnnotationAndPaysFailedMetadataWithoutReadingContents() {
		try (var f = new Fixture()) {
			f.host.revokeAtAnnotation = true; int before = f.reads.get();
			assertEquals(MarkerRejectReason.INVALID_REQUEST, f.reject());
			assertEquals(List.of("rate", "channel", "audience", "target", "classification", "dedicated", "source", "read-safety", "read-safety",
				"annotation", "source", "read-safety"), f.host.events, "receipt binding is independent, current and strictly pre-storage");
			var ledger = ledger(f.backend.runtime());
			assertEquals(5, ledger.used(InventorySourceAccess.PROBES));
			assertEquals(5 * InventorySourceAccess.PROVIDER_CALL_WORK, ledger.used(InventorySourceAccess.PROVIDER_WORK));
			assertEquals(InventoryCreationSnapshot.validationWorkspace(Math.toIntExact(f.settings.effectiveDisplayBytes()))
				+ 2 * RESOLUTION_ATTEMPT.workUnits() + 3 * VALIDATION_ATTEMPT.workUnits(), ledger.used(InventoryRuntimeTest.itemWorkCounter()),
				"even invalid binding attempts remain paid, without unused reservation headroom");
			assertEquals(before, f.reads.get()); assertEquals(0, ledger.used(InventorySourceAccess.PHYSICAL));
			assertEquals(0, f.host.store.size()); assertEquals(0, f.backend.runtime().memory().reserved());
		}
	}
	@Test void publicationFailureAfterCommitKeepsCommittedCreateAndNeverRetriesOrRejects() {
		try (var logs = new InventoryCaptureDiagnosticsTest.Capture(); var f = new Fixture()) {
			f.host.failPublication = true;
			int before = f.reads.get();
			f.backend.handle(InventoryBackendTest.A, f.selected, 1, f.settings);
			var marker = f.host.store.allMarkers().getFirst();
			var outcome = f.lastOutcome(1);
			assertEquals(InventoryS2CPacket.Kind.SELECTED, outcome.kind(), "a committed create is never reported as a retryable rejection");
			assertEquals(marker.id(), outcome.markerId());
			assertEquals(1, f.host.store.size());
			assertTrue(f.backend.tracksInventory(marker.id()), "the tracking sidecar survives the failed publication");
			assertEquals(before, f.reads.get(), "publication and create add no content getter");
			int events = f.host.events.size();
			f.backend.handle(InventoryBackendTest.A, f.selected, 1, f.settings);
			assertEquals(1, f.host.store.size(), "a replayed outcome never re-creates the marker");
			assertEquals(events, f.host.events.size(), "a replayed outcome never re-enters admission or publication");
			assertEquals(InventoryS2CPacket.Kind.SELECTED, f.lastOutcome(1).kind());
			logs.report("publish-created", org.apache.logging.log4j.Level.ERROR, "publication failed after commit",
				"committed marker=" + marker.id(), "request=" + f.selected.requestId(), "commit=" + f.selected.commitId(),
				"owner=" + InventoryBackendTest.A, "at " + getClass().getName() + "$OrderedHost.publishCreated");
		}
	}
	@Test void sidecarQueryFollowsTheCommittedTrackingLeaseAndItsRemoval() {
		try (var f = new Fixture()) {
			assertFalse(f.backend.tracksInventory(null));
			f.backend.handle(InventoryBackendTest.A, f.selected, 1, f.settings);
			var marker = f.host.store.allMarkers().getFirst();
			assertTrue(f.backend.tracksInventory(marker.id()), "the committed selection is queryable without a duplicate metadata map");
			f.backend.remove(marker.id());
			assertFalse(f.backend.tracksInventory(marker.id()), "removal retires the sidecar discriminator");
		}
	}
}
