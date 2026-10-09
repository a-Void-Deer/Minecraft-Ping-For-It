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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryAuthorityOrderTest {
	static final class OrderedHost extends InventoryBackendTest.Host {
		final List<String> events = new ArrayList<>();
		boolean rateLimited, channelDisabled, targetGone, readable = true, annotation = true;
		boolean failPublication;
		InventoryBackend backend;
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
		@Override public boolean annotationAllowed(InventorySourceInput input, String type) { events.add("annotation"); return annotation; }
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			events.add("rate"); if (rateLimited) return new InventoryBackend.Created(null, MarkerRejectReason.RATE_LIMITED);
			events.add("channel"); if (channelDisabled) return new InventoryBackend.Created(null, MarkerRejectReason.CHANNEL_DISABLED);
			events.add("audience");
			var result = service.createDedicated(null, player, frozen.target(), frozen.defaultPingType(), 1, 80, List.of(InventoryBackendTest.A, InventoryBackendTest.B),
				(target, type, owner, audience, intents) -> {
					events.add("dedicated"); assertTrue(intents.isEmpty()); assertEquals(0, store.size(), "sidecar must prepare before storage even with no SECTION intents");
					var rejected = admission.prepare(target, type, audience);
					return rejected == null ? MarkerCreationService.AdmissionResult.accepted(List.of(), java.util.Map.of()) : MarkerCreationService.AdmissionResult.rejected(rejected);
				});
			if (!result.isAccepted()) return new InventoryBackend.Created(null, result.rejectReason().orElseThrow());
			events.add("stored");
			return new InventoryBackend.Created(result.creation().orElseThrow().marker(), null, result.targetName().orElseThrow());
		}
		@Override public void publishCreated(InventoryBackend.Created created) {
			events.add("basic");
			assertNotNull(created.authoritativeName(), "committed publication carries the authoritative name");
			assertTrue(backend.tracksInventory(created.marker().id()),
				"the tracking sidecar is installed before the first CREATED");
			if (failPublication) throw new IllegalStateException("publication failed after commit");
		}
	}
	static final class Fixture implements AutoCloseable {
		final OrderedHost host = new OrderedHost(); final AtomicInteger reads = new AtomicInteger(); final InventorySettings settings = InventorySettings.serverDefaults();
		final InventoryBackend backend = new InventoryBackend(host, new InventoryRuntime(input -> {
			host.events.add("source");
			return Optional.of(new InventoryRuntimeTest.Source(input, reads, 1) {
				@Override public boolean valid() { host.events.add("read-safety"); return host.readable; }
				@Override public InventoryDomainCodec.Item read(int slot) { host.events.add("witness"); return super.read(slot); }
			});
		}, 16000000), 1);
		{ host.backend = backend; }
		InventoryC2SPacket selected;
		Fixture() {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1, InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings); backend.tick(0, settings);
			var preview = host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			selected = InventoryC2SPacket.select(host.epoch(InventoryBackendTest.A), 100, 1, 1, 1, preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger");
			host.events.clear();
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
	@Test void sourceWitnessAndAnnotationAreInsideProductionDedicatedAdmissionAndBeforeStorage() {
		try (var f = new Fixture()) {
			int before = f.reads.get(); f.backend.handle(InventoryBackendTest.A, f.selected, 1, f.settings);
			assertEquals(List.of("rate", "channel", "audience", "target", "classification", "dedicated", "source", "read-safety", "source", "read-safety", "witness", "annotation", "stored", "basic"), f.host.events);
			assertEquals(before + 1, f.reads.get(), "one immediate witness, not a queued create or unbounded sweep"); assertEquals(1, f.host.store.size()); assertEquals(0, f.backend.runtime().memory().reserved());
		}
		try (var f = new Fixture()) { f.host.readable = false; assertEquals(MarkerRejectReason.INVALID_REQUEST, f.reject()); assertFalse(f.host.events.contains("witness")); assertFalse(f.host.events.contains("annotation")); assertEquals(0, f.host.store.size()); }
		try (var f = new Fixture()) { f.host.annotation = false; assertEquals(MarkerRejectReason.INVALID_PING_TYPE, f.reject()); assertEquals(0, f.host.store.size()); assertEquals(0, f.backend.runtime().memory().reserved()); }
	}
	@Test void publicationFailureAfterCommitKeepsCommittedCreateAndNeverRetriesOrRejects() {
		try (var f = new Fixture()) {
			f.host.failPublication = true;
			int before = f.reads.get();
			f.backend.handle(InventoryBackendTest.A, f.selected, 1, f.settings);
			var marker = f.host.store.allMarkers().getFirst();
			var outcome = f.lastOutcome(1);
			assertEquals(InventoryS2CPacket.Kind.SELECTED, outcome.kind(), "a committed create is never reported as a retryable rejection");
			assertEquals(marker.id(), outcome.markerId());
			assertEquals(1, f.host.store.size());
			assertTrue(f.backend.tracksInventory(marker.id()), "the tracking sidecar survives the failed publication");
			assertEquals(before + 1, f.reads.get(), "publication adds no source capture");
			int events = f.host.events.size();
			f.backend.handle(InventoryBackendTest.A, f.selected, 1, f.settings);
			assertEquals(1, f.host.store.size(), "a replayed outcome never re-creates the marker");
			assertEquals(events, f.host.events.size(), "a replayed outcome never re-enters admission or publication");
			assertEquals(InventoryS2CPacket.Kind.SELECTED, f.lastOutcome(1).kind());
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
