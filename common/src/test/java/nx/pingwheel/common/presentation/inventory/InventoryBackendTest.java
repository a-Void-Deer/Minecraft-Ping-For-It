package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.*;
import nx.pingwheel.common.network.*;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryBackendTest {
	static final UUID A = new UUID(1, 1), B = new UUID(2, 2);
	static class Host implements InventoryBackend.Host {
		final List<InventoryS2CPacket> packets = new ArrayList<>(); final List<UUID> recipients = new ArrayList<>();
		final ServerMarkerStore store = new ServerMarkerStore(new MarkerIdSource());
		final Map<UUID, InventoryBackend.Policy> policies = new java.util.HashMap<>();
		long expiry = 80;
		boolean failPrepared;
		boolean failResultSend;
		InventoryRuntime.DisplayReprojector projector;
		Host() { policies.put(A, new InventoryBackend.Policy(100, 1, Set.of("entity_block"))); policies.put(B, policies.get(A)); }
		@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return Optional.ofNullable(policies.get(player)); }
		@Override public Optional<InventoryBackend.Opened> open(UUID player, nx.pingwheel.common.domain.Target requested) { return Optional.of(new InventoryBackend.Opened(InventoryRuntimeTest.TARGET, "entity_block", "attention")); }
		@Override public boolean annotationAllowed(InventorySourceInput input, String type) { return Set.of("attention", "danger").contains(type); }
		@Override public boolean knows(UUID player, MarkerId marker) { return store.allMarkers().stream().anyMatch(m -> m.id().equals(marker) && m.recipients().contains(player)); }
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public Optional<InventoryRuntime.DisplayReprojector> displayReprojector() { return Optional.ofNullable(projector); }
		@Override public int encodedBytes(InventoryS2CPacket packet) { FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer()); try { packet.write(buf); return buf.readableBytes(); } finally { buf.release(); } }
		@Override public void send(UUID player, InventoryS2CPacket packet) {
			assertFalse(packet.isCorrupt(), packet.toString());
			if (failResultSend && (packet.kind() == InventoryS2CPacket.Kind.SELECTED || packet.kind() == InventoryS2CPacket.Kind.REJECT))
				throw new IllegalStateException("result transport refused");
			packets.add(packet); recipients.add(player);
		}
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			var type = TargetTypeCatalog.builtIn().findById(frozen.targetType()).orElseThrow();
			var reject = admission.prepare(frozen.target(), type.id(), List.of(A, B));
			if (reject != null) return new InventoryBackend.Created(null, reject);
			if (failPrepared) return new InventoryBackend.Created(null, MarkerRejectReason.INVALID_REQUEST);
			var marker = store.create(player, frozen.target(), type, type.defaultPingType(), new MarkerAnchor(1, 2, 3), 1, expiry, List.of(A, B)).marker();
			return new InventoryBackend.Created(marker, null);
		}
		long epoch(UUID player) { for (int i = packets.size() - 1; i >= 0; i--) if (recipients.get(i).equals(player) && packets.get(i).kind() == InventoryS2CPacket.Kind.OFFER) return packets.get(i).epoch(); throw new AssertionError(); }
	}
	static class Fixture implements AutoCloseable {
		final Host host = new Host(); final InventorySettings settings = InventorySettings.serverDefaults(); final AtomicInteger reads = new AtomicInteger();
		final AtomicInteger validations = new AtomicInteger();
		boolean valid = true; long amount = 7;
		boolean stripped;
		String itemId = "minecraft:stone";
		final InventoryBackend backend = new InventoryBackend(host, new InventoryRuntime(i -> Optional.of(new InventoryRuntimeTest.Source(i, reads, 1) {
			@Override public boolean valid() { validations.incrementAndGet(); return Fixture.this.valid; }
			@Override public InventoryDomainCodec.Item read(int slot) {
				reads.incrementAndGet(); var item = InventoryRuntimeTest.item(amount);
				return amount == 0 ? null : new InventoryDomainCodec.Item(new InventoryScanner.Key(itemId, stripped ? "" : item.key().componentsKey()), item.count(), item.label(), item.displayJson(), stripped);
			}
		}), 16000000), 1);
		void hello(UUID player) { backend.handle(player, InventoryC2SPacket.hello(), 0, settings); }
		InventoryS2CPacket preview() { return host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).reduce((a, b) -> b).orElseThrow(); }
		InventoryC2SPacket select(long commit) { var p = preview(); return InventoryC2SPacket.select(host.epoch(A), 100, 1, commit, 1, p.baselineId(), p.statusRevision(), p.entries().getFirst().key(), "danger"); }
		void open() { hello(A); hello(B); backend.handle(A, InventoryC2SPacket.open(host.epoch(A), 100, 1, 1, InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings); backend.tick(0, settings); }
		@Override public void close() { backend.close(); }
	}
	@Test void immediateSingleSelectionCreatesOnceAndInitialTrackingDoesNotReusePreviewCount() {
		try (Fixture f = new Fixture()) {
			f.open(); var packet = f.select(1); f.backend.handle(A, packet, 1, f.settings);
			assertEquals(1, f.host.store.size()); var marker = f.host.store.allMarkers().getFirst();
			assertEquals("attention", marker.pingType().id()); assertTrue(marker.properties().isEmpty());
			assertTrue(f.host.packets.stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT));
			f.backend.handle(A, packet, 1, f.settings); f.backend.handle(A, f.select(2), 1, f.settings); assertEquals(1, f.host.store.size());
			f.amount = 0; f.backend.tick(2, f.settings);
			var snapshots = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT).toList();
			assertEquals(2, snapshots.size(), "owner's read shared to both authorized frozen audience members");
			assertTrue(snapshots.stream().flatMap(p -> p.entries().stream()).allMatch(e -> e.count() == 0 && "danger".equals(e.itemPingType())));
			f.backend.handle(A, InventoryC2SPacket.close(f.host.epoch(A), 1).stamp(100, 1), 2, f.settings);
			int reads = f.reads.get(); f.backend.tick(5, f.settings); assertTrue(f.reads.get() > reads, "CLOSE releases preview, not tracking");
		}
	}
	@Test void invalidityRecoveryAndHardExpiryDoNotChangeMarkerLifetimeOrProbeAfterDeadline() {
		try (Fixture f = new Fixture()) {
			f.host.expiry = 12; f.open(); f.backend.handle(A, f.select(1), 1, f.settings); f.backend.tick(2, f.settings);
			var marker = f.host.store.allMarkers().getFirst(); f.valid = false; f.backend.tick(3, f.settings);
			assertTrue(f.host.packets.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.STATUS && p.status() == InventoryS2CPacket.Status.INVALID));
			assertEquals(12, marker.expiresAtTick()); assertEquals(1, f.host.store.size());
			f.valid = true; f.backend.tick(6, f.settings);
			assertTrue(f.host.packets.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && p.statusRevision() > 1));
			f.backend.handle(A, InventoryC2SPacket.close(f.host.epoch(A), 1).stamp(100, 1), 7, f.settings);
			int reads = f.reads.get(), validations = f.validations.get(); f.backend.tick(12, f.settings); f.backend.tick(30, f.settings);
			assertEquals(reads, f.reads.get()); assertEquals(validations, f.validations.get(), "hard expiry removes all tracking evidence before any validation or recovery probe");
		}
	}
	@Test void revokedViewPurgesQueuesWithoutChangingFrozenAudience() {
		try (Fixture f = new Fixture()) {
			f.open(); f.backend.handle(A, f.select(1), 1, f.settings); f.backend.tick(2, f.settings);
			long epoch = f.host.epoch(B);
			f.host.policies.put(B, new InventoryBackend.Policy(100, 2, Set.of()));
			int start = f.host.packets.size(); f.amount = 9; f.backend.tick(5, f.settings);
			var revoked = new ArrayList<InventoryS2CPacket>();
			for (int i = start; i < f.host.packets.size(); i++) if (f.host.recipients.get(i).equals(B)) revoked.add(f.host.packets.get(i));
			// Revocation fences the purged view with its POLICY first and only then
			// re-advertises the current view's negotiation OFFER; no revoked inventory
			// data frame may follow and the frozen marker audience stays untouched.
			assertEquals(List.of(InventoryS2CPacket.Kind.POLICY, InventoryS2CPacket.Kind.OFFER),
				revoked.stream().map(InventoryS2CPacket::kind).toList());
			assertEquals(Set.of(), revoked.getFirst().allowedTypes());
			for (var packet : revoked) {
				assertEquals(epoch, packet.epoch());
				assertEquals(100, packet.presentationEpoch());
				assertEquals(2, packet.view());
			}
			assertEquals(List.of(A, B), f.host.store.allMarkers().getFirst().recipients());
			assertTrue(f.backend.tracksInventory(f.host.store.allMarkers().getFirst().id()), "the other recipient's tracking sidecar survives");
		}
	}
	@SuppressWarnings("unchecked")
	@Test void aggregateDisplayWorkRefusalKeepsRetirementAndRetriesRetainedCountWithMandatoryRemovals() throws Exception {
		try (Fixture f = new Fixture()) {
			f.itemId = "minecraft:shulker_box"; f.open(); Object preview = retainedPreview(f.backend);
			String oldToken = f.preview().entries().getFirst().key(); int reads = f.reads.get();
			var retire = InventoryBackend.class.getDeclaredMethod("retirePreviewGroup", preview.getClass(), String.class); retire.setAccessible(true);
			var witnesses = (Map<InventoryScanner.Key, Integer>) field(preview, "witnesses");
			assertFalse(witnesses.isEmpty(), "the initial preview must have observed the item at a witness slot");
			int witnessSlot = witnesses.values().iterator().next();
			retire.invoke(f.backend, preview, f.itemId);
			var key = new InventoryScanner.Key(f.itemId, "");
			var counts = (Map<InventoryScanner.Key, InventoryDomainCodec.Item>) field(preview, "counts");
			var totals = (Map<String, InventoryDomainCodec.Item>) field(preview, "totals");
			counts.put(key, new InventoryDomainCodec.Item(key, 7, "stone", null, true));
			// The live fold records the empty-key witness for the group when it installs
			// its folded count; this manual retirement completes that same retained
			// evidence so the aggregate stays selectable through an authoritative witness.
			witnesses.put(key, witnessSlot);
			totals.put(f.itemId, new InventoryDomainCodec.Item(key, 7, "INVALID LABEL", null, true)); // factory invocation trap
			field(preview, "revision", (long) field(preview, "revision") + 1);
			var runtime = f.backend.runtime();
			var ledger = (nx.pingwheel.common.presentation.source.CostLedger) field(runtime, "physical");
			var work = new nx.pingwheel.common.presentation.source.CostLedger.Counter("inventory/item_work", nx.pingwheel.common.presentation.source.CostLedger.Unit.WORK);
			assertTrue(runtime.claimDetachedItemWork(ledger.remaining(work)));
			var publish = InventoryBackend.class.getDeclaredMethod("publishPreview", preview.getClass()); publish.setAccessible(true);
			assertInstanceOf(SyncPublisher.Outcome.Deferred.class, publish.invoke(f.backend, preview));
			assertTrue(((Set<?>) field(preview, "retired")).contains(oldToken));
			assertFalse(((Map<?, ?>) field(preview, "references")).containsKey(oldToken));
			assertEquals(7, counts.get(key).count()); assertEquals("", counts.get(key).key().componentsKey());
			assertEquals(0, runtime.memory().reserved());
			totals.put(f.itemId, new InventoryDomainCodec.Item(key, 7, "stone", null, true));
			runtime.advance(1, f.settings); int start = f.host.packets.size();
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, publish.invoke(f.backend, preview));
			((InventorySyncPublisher) field(f.backend, "publisher")).drain();
			var entry = f.host.packets.subList(start, f.host.packets.size()).stream().flatMap(packet -> packet.entries().stream()).findFirst().orElseThrow();
			assertEquals(7, entry.count()); assertTrue(entry.fallback()); assertNotEquals(oldToken, entry.key());
			assertEquals(f.itemId, entry.itemId());
			var json = com.google.gson.JsonParser.parseString(entry.displayJson()).getAsJsonObject();
			assertTrue(json.getAsJsonObject("components").has("!minecraft:lore"));
			assertTrue(json.getAsJsonObject("components").has("!minecraft:container"));
			assertEquals(reads, f.reads.get(), "publication retry consumes retained evidence without recapture");
			assertEquals(0, runtime.memory().reserved());
			f.backend.handle(A, f.select(1), 1, f.settings);
			var outcome = f.host.packets.stream().filter(packet -> packet.commitId() == 1
					&& (packet.kind() == InventoryS2CPacket.Kind.SELECTED || packet.kind() == InventoryS2CPacket.Kind.REJECT))
				.reduce((a, b) -> b).orElseThrow();
			assertEquals(InventoryS2CPacket.Kind.SELECTED, outcome.kind(),
				() -> "aggregate SELECT refused: " + outcome.rejection() + " remaining=" + f.backend.runtime().memory().remaining());
			assertEquals(1, f.host.store.size(), "the retained aggregate reference must commit one tracking sidecar");
			f.backend.tick(2, f.settings);
			var tracked = f.host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.SNAPSHOT)
				.flatMap(packet -> packet.entries().stream()).findFirst().orElseThrow();
			assertEquals(7, tracked.count()); assertTrue(tracked.fallback()); assertEquals("aggregate", tracked.key());
			var trackedComponents = com.google.gson.JsonParser.parseString(tracked.displayJson()).getAsJsonObject().getAsJsonObject("components");
			assertTrue(trackedComponents.has("!minecraft:lore")); assertTrue(trackedComponents.has("!minecraft:container"));
		}
	}
	@Test void closeAndReopenDetermineComponentFoldingAfresh() {
		try (Fixture f = new Fixture()) {
			f.stripped = true; f.open(); assertTrue(f.preview().entries().getFirst().fallback());
			f.backend.handle(A, InventoryC2SPacket.close(f.host.epoch(A), 1).stamp(100, 1), 1, f.settings);
			f.stripped = false;
			f.backend.handle(A, InventoryC2SPacket.open(f.host.epoch(A), 100, 1, 2, InventoryRuntimeTest.TARGET, BlockFace.NORTH), 1, f.settings);
			f.backend.tick(1, f.settings); assertFalse(f.preview().entries().getFirst().fallback());
		}
	}
	@Test void freshResyncDropsTrackingFoldButDoesNotResetAnotherRecipient() {
		try (Fixture f = new Fixture()) {
			f.open(); f.backend.handle(A, f.select(1), 1, f.settings);
			f.stripped = true; f.backend.tick(2, f.settings);
			f.stripped = false; f.backend.tick(5, f.settings);
			int start = f.host.packets.size();
			var oldB = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT).findFirst().orElseThrow().baselineId();
			f.backend.handle(A, InventoryC2SPacket.resync(f.host.epoch(A), 0, f.host.store.allMarkers().getFirst().id()).stamp(100, 1), 6, f.settings);
			f.backend.tick(6, f.settings);
			var replacement = f.host.packets.subList(start, f.host.packets.size()).stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && !p.entries().isEmpty()).findFirst().orElseThrow();
			assertFalse(replacement.entries().getFirst().fallback()); assertNotEquals(oldB, replacement.baselineId());
			assertEquals(1, f.host.store.size()); assertEquals(80, f.host.store.allMarkers().getFirst().expiresAtTick());
		}
	}
	@Test void liveSettingsRefreshOfferWithoutChangingSessionOrMarkerLifetime() {
		try (Fixture f = new Fixture()) {
			f.open(); long epoch = f.host.epoch(A); f.backend.handle(A, f.select(1), 1, f.settings);
			int start = f.host.packets.size();
			f.settings.getTracking().setPeriodTicks(7); f.settings.setPendingMemoryMiB(4); f.backend.tick(2, f.settings);
			assertTrue(f.backend.runtime().memory().remaining() < 0);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.OFFER));
			f.settings.getTracking().setPeriodTicks(9); f.backend.tick(3, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.OFFER));
			assertEquals(epoch, f.host.epoch(A));
			assertEquals(80, f.host.store.allMarkers().getFirst().expiresAtTick());
			assertEquals(4L * 1024 * 1024, f.backend.runtime().memory().cap());
			f.settings.setPendingMemoryMiB(16); f.backend.tick(4, f.settings);
			var offers = f.host.packets.subList(start, f.host.packets.size()).stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.OFFER).toList();
			assertFalse(offers.isEmpty());
			assertTrue(offers.stream().allMatch(p -> p.offer().trackingPeriodTicks() == 9), "recovery advertises only the latest settings");
			assertEquals(epoch, f.host.epoch(A)); assertEquals(80, f.host.store.allMarkers().getFirst().expiresAtTick());
		}
	}
	@Test void staleSelectedItemIsRejectedAtOneBoundedWitnessWithoutCreatingOrLaterRetrying() {
		try (Fixture f = new Fixture()) {
			f.open(); var selection = f.select(1); f.amount = 0; int reads = f.reads.get();
			f.backend.handle(A, selection, 1, f.settings); assertEquals(0, f.host.store.size());
			assertEquals(reads + 1, f.reads.get(), "immediate indexed witness, not unbounded inventory sweep");
			f.amount = 9; f.backend.handle(A, selection, 2, f.settings); f.backend.tick(5, f.settings);
			assertEquals(0, f.host.store.size(), "failed release is replayed, never automatically retried");
			assertEquals(InventoryS2CPacket.Kind.REJECT, f.host.packets.stream().filter(p -> p.commitId() == 1).findFirst().orElseThrow().kind());
		}
	}
	@Test void refusedResultEnqueueRetriesTheRecordedOutcomeOnceWithoutRepeatingSelection() {
		try (Fixture f = new Fixture()) {
			f.open(); var selection = f.select(1);
			var padding = f.backend.runtime().memory().tryReserve(5L * 1024 * 1024).orElseThrow();
			padding.commit(5L * 1024 * 1024); f.settings.setPendingMemoryMiB(4);
			int start = f.host.packets.size(); f.amount = 0;
			f.backend.handle(A, selection, 1, f.settings); f.backend.tick(2, f.settings);
			assertEquals(0, f.host.store.size()); assertEquals(0, f.backend.runtime().memory().reserved());
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().noneMatch(p -> p.commitId() == 1));
			// Source recovery cannot turn the recorded rejection into another create attempt.
			f.amount = 7; padding.close(); f.settings.setPendingMemoryMiB(16);
			f.backend.tick(3, f.settings); f.backend.tick(4, f.settings);
			var results = f.host.packets.subList(start, f.host.packets.size()).stream().map(InventoryPublicationIntegrationTest::wire)
				.filter(p -> p.commitId() == 1).toList();
			assertEquals(1, results.size()); assertEquals(InventoryS2CPacket.Kind.REJECT, results.getFirst().kind());
			assertEquals(selection.requestId(), results.getFirst().requestId()); assertEquals(selection.epoch(), results.getFirst().epoch());
			assertEquals(0, f.host.store.size());
			f.backend.handle(A, selection, 5, f.settings);
			assertEquals(2, f.host.packets.subList(start, f.host.packets.size()).stream().filter(p -> p.commitId() == 1).count(),
				"an explicit replay remains supported after automatic handoff completion");
			assertEquals(0, f.host.store.size());
		}
	}
	@Test void refusedResultsCannotEscapeAnOldPresentationViewOrDisconnectedSession() {
		for (boolean disconnect : List.of(false, true)) try (Fixture f = new Fixture()) {
			f.open(); var selection = f.select(1);
			var padding = f.backend.runtime().memory().tryReserve(5L * 1024 * 1024).orElseThrow();
			padding.commit(5L * 1024 * 1024); f.settings.setPendingMemoryMiB(4); f.amount = 0;
			f.backend.handle(A, selection, 1, f.settings);
			if (disconnect) f.backend.disconnect(A);
			else f.host.policies.put(A, new InventoryBackend.Policy(100, 2, Set.of("entity_block")));
			int start = f.host.packets.size(); padding.close(); f.settings.setPendingMemoryMiB(16);
			f.backend.tick(2, f.settings); f.backend.tick(3, f.settings);
			if (disconnect) f.backend.handle(A, InventoryC2SPacket.hello(), 4, f.settings);
			else f.backend.handle(A, selection.stamp(100, 2), 4, f.settings);
			f.backend.tick(5, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().noneMatch(p -> p.commitId() == 1),
				"retained send intent never crosses a session or presentation fence");
			assertEquals(0, f.host.store.size());
		}
	}
	@Test void queuedSelectedResultSurvivesTransportRefusalWithoutDuplicatingMarkerCreation() {
		try (Fixture f = new Fixture()) {
			f.open(); var selection = f.select(1); f.host.failResultSend = true;
			int start = f.host.packets.size(); f.backend.handle(A, selection, 1, f.settings);
			assertEquals(1, f.host.store.size()); var marker = f.host.store.allMarkers().getFirst();
			assertTrue(f.backend.tracksInventory(marker.id()), "the committed sidecar precedes result publication");
			f.backend.handle(A, selection, 1, f.settings); f.backend.tick(2, f.settings);
			assertEquals(1, f.host.store.size());
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().noneMatch(p -> p.commitId() == 1));
			f.host.failResultSend = false; f.backend.tick(3, f.settings); f.backend.tick(4, f.settings);
			var results = f.host.packets.subList(start, f.host.packets.size()).stream().map(InventoryPublicationIntegrationTest::wire)
				.filter(p -> p.commitId() == 1).toList();
			assertEquals(1, results.size()); assertEquals(InventoryS2CPacket.Kind.SELECTED, results.getFirst().kind());
			assertEquals(marker.id(), results.getFirst().markerId()); assertEquals(1, f.host.store.size());
		}
	}
	@Test void selectedZeroNeedsCompleteScanNotEmptyContainerAndFailedPreparationReleasesSidecar() {
		try (Fixture f = new Fixture()) {
			f.open(); long before = f.backend.runtime().memory().retained(); f.host.failPrepared = true;
			f.backend.handle(A, f.select(1), 1, f.settings); assertEquals(0, f.host.store.size());
			assertEquals(before, f.backend.runtime().memory().retained(), "failed prepared consumer and recipient reservations are released");
			assertEquals(0, f.backend.runtime().memory().reserved());
		}
		try (Fixture f = new Fixture()) {
			f.open(); f.backend.handle(A, f.select(1), 1, f.settings); f.itemId = "minecraft:dirt"; f.amount = 31; f.backend.tick(2, f.settings);
			assertTrue(f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT).flatMap(p -> p.entries().stream()).allMatch(e -> e.count() == 0));
			assertEquals(1, f.host.store.size());
		}
	}
	@Test void defaultFiniteMemoryCanAdmitSmallPreviewAndImmediateTracking() {
		try (Fixture f = new Fixture()) {
			f.open(); assertFalse(f.preview().entries().isEmpty());
			f.backend.handle(A, f.select(1), 1, f.settings); assertEquals(1, f.host.store.size());
			f.backend.handle(A, InventoryC2SPacket.close(f.host.epoch(A), 1).stamp(100, 1), 1, f.settings);
			f.backend.tick(2, f.settings); assertTrue(f.host.packets.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT));
			assertEquals(0, f.backend.runtime().memory().reserved());
		}
	}
	@Test void duplicatePreviewAndIndependentFacesShareClientVariantAllowanceWithoutHidingAnEntry() {
		try (Fixture f = new Fixture()) {
			f.settings.getPreview().setMaxVariantsPerClientPeriod(nx.pingwheel.common.config.IntLimit.finite(1)); f.open();
			f.backend.handle(A, InventoryC2SPacket.open(f.host.epoch(A), 100, 1, 2, InventoryRuntimeTest.TARGET, BlockFace.SOUTH), 1, f.settings);
			f.backend.tick(1, f.settings);
			var second = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.requestId() == 2 && !p.entries().isEmpty()).findFirst().orElseThrow();
			assertNotEquals(InventoryS2CPacket.Status.INCOMPLETE, second.status()); assertEquals(7, second.entries().getFirst().count());
		}
	}
	@Test void invalidPreviewWithoutCloseReleasesPhysicalRoundAndEntriesSoUnrelatedPreviewCanProgress() {
		try (Fixture f = new Fixture()) {
			f.open(); long before = f.backend.runtime().memory().retained(); f.valid = false; f.backend.tick(1, f.settings);
			assertTrue(f.backend.runtime().memory().retained() < before - 280000, "probe invalidation releases provider round/pages and preview entries without CLOSE");
			assertTrue(f.host.packets.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.status() == InventoryS2CPacket.Status.INVALID));
			f.valid = true; f.backend.handle(B, InventoryC2SPacket.open(f.host.epoch(B), 100, 1, 1, InventoryRuntimeTest.TARGET, BlockFace.SOUTH), 2, f.settings); f.backend.tick(2, f.settings);
			assertTrue(f.host.packets.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty() && p.epoch() == f.host.epoch(B)));
			assertEquals(0, f.backend.runtime().memory().reserved());
		}
	}
	@Test void helloAndLiveRefreshKeepPositiveIntMaxResyncPeriodsExactInTheExistingSession() {
		for (int periods : new int[] {72_001, Integer.MAX_VALUE}) try (Fixture f = new Fixture()) {
			f.settings.getTracking().setResyncMinPeriods(periods); f.hello(A);
			long epoch = f.host.epoch(A);
			var offered = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.OFFER).reduce((a, b) -> b).orElseThrow();
			assertEquals(periods, InventoryPublicationIntegrationTest.wire(offered).offer().resyncMinPeriods());
			int replacement = periods == Integer.MAX_VALUE ? 72_001 : Integer.MAX_VALUE;
			f.settings.getTracking().setResyncMinPeriods(replacement); assertDoesNotThrow(() -> f.backend.tick(1, f.settings));
			assertEquals(epoch, f.host.epoch(A));
			var refreshed = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.OFFER).reduce((a, b) -> b).orElseThrow();
			assertEquals(replacement, InventoryPublicationIntegrationTest.wire(refreshed).offer().resyncMinPeriods());
		}
	}
	@Test void actualResyncWithIntMaxPeriodsTimesMaxTrackingPeriodRejectsImmediateRepeatWithoutOverflow() {
		try (Fixture f = new Fixture()) {
			f.host.expiry = Long.MAX_VALUE; f.settings.getTracking().setPeriodTicks(72_000);
			f.settings.getTracking().setResyncMinPeriods(Integer.MAX_VALUE); f.open(); f.backend.handle(A, f.select(1), 1, f.settings); f.backend.tick(2, f.settings);
			var marker = f.host.store.allMarkers().getFirst();
			var resync = InventoryC2SPacket.resync(f.host.epoch(A), 0, marker.id()).stamp(100, 1);
			int start = f.host.packets.size(); f.backend.handle(A, resync, 3, f.settings); f.backend.tick(3, f.settings);
			var replacement = f.host.packets.subList(start, f.host.packets.size()).stream()
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && marker.id().equals(p.markerId())).findFirst().orElseThrow();
			start = f.host.packets.size(); f.backend.handle(A, resync, 4, f.settings); f.backend.tick(4, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT
				|| p.kind() == InventoryS2CPacket.Kind.STATUS && p.status() == InventoryS2CPacket.Status.UPDATING && p.baselineId() != replacement.baselineId()), "the long product must not wrap to an already elapsed cooldown");
			long deadline = 3L + (long) Integer.MAX_VALUE * 72_000;
			f.backend.handle(A, resync, deadline - 1, f.settings); f.backend.tick(deadline - 1, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT).allMatch(p -> p.baselineId() == replacement.baselineId()));
			start = f.host.packets.size(); f.backend.handle(A, resync, deadline, f.settings); f.backend.tick(deadline, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && p.baselineId() != replacement.baselineId()));
			assertEquals(Long.MAX_VALUE, marker.expiresAtTick());
		}
	}
	@Test void advertisedOfferCarriesTheEffectiveSizePairAndPreviewFramesStayWithinIt() {
		try (Fixture f = new Fixture()) {
			f.settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(2));
			f.settings.setFrameByteMultiplier(java.math.BigDecimal.valueOf(2));
			f.open();
			var offer = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.OFFER).reduce((a, b) -> b).orElseThrow().offer();
			assertEquals(8192, offer.effectiveDisplayBytes());
			assertEquals(65536, offer.effectiveFrameBytes());
			var data = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).toList();
			assertFalse(data.isEmpty());
			assertTrue(data.stream().allMatch(p -> p.encodedSize(offer.effectiveDisplayBytes(), offer.effectiveFrameBytes()) <= offer.effectiveFrameBytes()),
				"live frames are written under the advertised effective pair");
		}
	}
	@Test void nullMarkerResyncCoalescesWithinNegotiatedCooldownAndIgnoresUnknownOrClosedRequests() {
		try (Fixture f = new Fixture()) {
			f.open();
			long epoch = f.host.epoch(A);
			int start = f.host.packets.size();
			f.backend.handle(A, InventoryC2SPacket.resync(epoch, 1).stamp(100, 1), 1, f.settings);
			f.backend.tick(1, f.settings);
			var first = f.host.packets.subList(start, f.host.packets.size()).stream().map(InventoryPublicationIntegrationTest::wire)
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).toList();
			assertEquals(1, first.size());
			long repaired = first.getFirst().watermark();
			start = f.host.packets.size();
			f.backend.handle(A, InventoryC2SPacket.resync(epoch, 1).stamp(100, 1), 2, f.settings);
			f.backend.handle(A, InventoryC2SPacket.resync(epoch, 1).stamp(100, 1), 3, f.settings);
			f.backend.tick(4, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()),
				"repeats inside the negotiated cooldown coalesce instead of republishing");
			start = f.host.packets.size();
			f.backend.handle(A, InventoryC2SPacket.resync(epoch, 1).stamp(100, 1), 16, f.settings);
			f.backend.tick(16, f.settings);
			var second = f.host.packets.subList(start, f.host.packets.size()).stream().map(InventoryPublicationIntegrationTest::wire)
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).toList();
			assertEquals(1, second.size());
			assertTrue(second.getFirst().watermark() > repaired, "the next accepted request republishes at a strictly newer watermark");
			start = f.host.packets.size();
			f.backend.handle(A, InventoryC2SPacket.resync(epoch, 99).stamp(100, 1), 20, f.settings);
			f.backend.handle(A, InventoryC2SPacket.close(epoch, 1).stamp(100, 1), 20, f.settings);
			f.backend.handle(A, InventoryC2SPacket.resync(epoch, 1).stamp(100, 1), 20, f.settings);
			f.backend.tick(20, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()),
				"unknown and already-closed request ids are ignored without allocating a repair");
		}
	}
	@Test void scheduledRequestRepairReplaysInvalidWithoutProbingOrRepublishingClearedReferences() {
		try (Fixture f = new Fixture()) {
			f.open();
			f.valid = false; f.backend.tick(1, f.settings);
			assertTrue(f.host.packets.stream().anyMatch(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.status() == InventoryS2CPacket.Status.INVALID));
			int start = f.host.packets.size(), validations = f.validations.get();
			f.backend.handle(A, InventoryC2SPacket.resync(f.host.epoch(A), 1).stamp(100, 1), 2, f.settings);
			assertEquals(start, f.host.packets.size(), "ingress only schedules; it performs no synchronous frame or source work");
			f.backend.tick(2, f.settings);
			var replayed = f.host.packets.subList(start, f.host.packets.size()).stream().map(InventoryPublicationIntegrationTest::wire)
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.requestId() == 1).toList();
			assertTrue(replayed.stream().anyMatch(p -> p.status() == InventoryS2CPacket.Status.INVALID));
			assertTrue(replayed.stream().allMatch(p -> p.entries().isEmpty()), "an invalid terminal request replays control only, never a valid quantity");
			assertEquals(validations, f.validations.get(), "an invalid terminal replay needs no evidence probe");
			assertEquals(0, f.backend.runtime().memory().reserved());
		}
	}
	@Test void scheduledRequestRepairReplaysUpdatingWhileTheInitialScanContinues() {
		var host = new Host(); var settings = InventorySettings.serverDefaults(); var reads = new AtomicInteger();
		var memory = new java.util.concurrent.atomic.AtomicReference<nx.pingwheel.common.presentation.source.RetainedMemoryLedger>();
		boolean[] deferPreparation = {true}; var preparations = new AtomicInteger();
		var runtime = new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 1) {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return deferPreparation[0] ? memory.get().remaining() + 1 : 0; }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() { preparations.incrementAndGet(); return Optional.empty(); }
				});
			}
		}), 16_000_000);
		memory.set(runtime.memory());
		try (var backend = new InventoryBackend(host, runtime, 1)) {
			backend.handle(A, InventoryC2SPacket.hello(), 0, settings);
			long epoch = host.epoch(A);
			backend.handle(A, InventoryC2SPacket.open(epoch, 100, 1, 1, InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings);
			// The source's plan exceeds the available ledger by one byte: the evidence
			// probe succeeds, but initial preparation defers before capture or slot reads.
			backend.handle(A, InventoryC2SPacket.resync(epoch, 1).stamp(100, 1), 0, settings);
			int start = host.packets.size();
			backend.tick(0, settings);
			var replayed = host.packets.subList(start, host.packets.size()).stream().map(InventoryPublicationIntegrationTest::wire).toList();
			assertEquals(0, reads.get(), "the memory-deferred plan leaves the first observation unadmitted");
			assertEquals(0, preparations.get(), "capture does not run before memory admission");
			var updating = replayed.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.entries().isEmpty()).findFirst().orElseThrow();
			assertEquals(InventoryS2CPacket.Status.UPDATING, updating.status());
			// The admitted plan keeps the fixture's established live route. The replay
			// neither restarts nor brackets its initial scan when admission returns.
			deferPreparation[0] = false;
			start = host.packets.size();
			backend.tick(1, settings);
			var observed = host.packets.subList(start, host.packets.size()).stream().map(InventoryPublicationIntegrationTest::wire)
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).toList();
			assertEquals(1, observed.size());
			assertEquals(7, observed.getFirst().entries().getFirst().count());
			assertEquals(1, reads.get(), "one physical observation, never a recapture");
			assertEquals(1, preparations.get(), "one admitted preparation, never a recapture");
		}
	}
	@Test void markerScopedResyncKeepsExistingRepairAndNeverFallsBackToThePreview() {
		try (Fixture f = new Fixture()) {
			f.open(); f.backend.handle(A, f.select(1), 1, f.settings); f.backend.tick(2, f.settings);
			int start = f.host.packets.size();
			f.backend.handle(A, InventoryC2SPacket.resync(f.host.epoch(A), 1, new MarkerId(999)).stamp(100, 1), 3, f.settings);
			f.backend.tick(3, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW),
				"a marker id selects the marker path exclusively, even when that marker is unknown");
		}
	}
	@Test void scheduledRequestRepairWaitsForAdmittedPartsAndRepublishesOnlyAfterTheyComplete() {
		class BlockingHost extends Host {
			boolean blocked = true;
			@Override public void send(UUID player, InventoryS2CPacket packet) {
				if (blocked && packet.kind() == InventoryS2CPacket.Kind.PREVIEW && !packet.entries().isEmpty())
					throw new IllegalStateException("application seam backpressure");
				super.send(player, packet);
			}
		}
		var host = new BlockingHost(); var settings = InventorySettings.serverDefaults(); var reads = new AtomicInteger();
		try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 1)), 16_000_000), 1)) {
			backend.handle(A, InventoryC2SPacket.hello(), 0, settings);
			long epoch = host.epoch(A);
			backend.handle(A, InventoryC2SPacket.open(epoch, 100, 1, 1, InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings);
			backend.tick(0, settings);
			assertTrue(host.packets.stream().noneMatch(p -> !p.entries().isEmpty()), "the first admitted preview part is not delivered");
			int readsBefore = reads.get();
			backend.handle(A, InventoryC2SPacket.resync(epoch, 1).stamp(100, 1), 1, settings);
			int start = host.packets.size();
			backend.tick(1, settings);
			assertTrue(host.packets.subList(start, host.packets.size()).stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()),
				"a scheduled repair never discards or replaces an admitted part");
			host.blocked = false;
			backend.tick(2, settings);
			var delivered = host.packets.subList(start, host.packets.size()).stream().map(InventoryPublicationIntegrationTest::wire)
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).toList();
			assertEquals(1, delivered.size());
			assertEquals(1, delivered.getFirst().watermark(), "the admitted original part is retried, not rebuilt at a newer revision");
			start = host.packets.size();
			backend.tick(3, settings);
			var repaired = host.packets.subList(start, host.packets.size()).stream().map(InventoryPublicationIntegrationTest::wire)
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).toList();
			assertEquals(1, repaired.size());
			assertEquals(2, repaired.getFirst().watermark());
			assertEquals(7, repaired.getFirst().entries().getFirst().count());
			assertEquals(readsBefore, reads.get(), "a corrective resend performs no new source read");
		}
	}

	@Test void retainedRepairRebasesOnceThroughDeferredAndRejectedPublicationAndClearsOnlyOnAcceptance() throws Exception {
		try (Fixture f = new Fixture()) {
			f.open();
			var sessionsField = InventoryBackend.class.getDeclaredField("sessions"); sessionsField.setAccessible(true);
			Object session = ((Map<?, ?>) sessionsField.get(f.backend)).get(A);
			var previewsField = session.getClass().getDeclaredField("previews"); previewsField.setAccessible(true);
			Object preview = ((Map<?, ?>) previewsField.get(session)).get(1L);
			var contextField = preview.getClass().getDeclaredField("context"); contextField.setAccessible(true);
			var context = (SyncPublisher.Context) contextField.get(preview);
			var repairField = preview.getClass().getDeclaredField("repairRebased"); repairField.setAccessible(true);
			var scheduledField = preview.getClass().getDeclaredField("repairScheduled"); scheduledField.setAccessible(true);
			var publisherField = InventoryBackend.class.getDeclaredField("publisher"); publisherField.setAccessible(true);
			var publisher = (InventorySyncPublisher) publisherField.get(f.backend);
			var repair = InventoryBackend.class.getDeclaredMethod("repairPreview", preview.getClass()); repair.setAccessible(true);
			f.backend.handle(A, InventoryC2SPacket.resync(f.host.epoch(A), 1).stamp(100, 1), 1, f.settings);
			var memory = f.backend.runtime().memory(); memory.setCap(0);
			repair.invoke(f.backend, preview);
			assertTrue(repairField.getBoolean(preview)); assertTrue(scheduledField.getBoolean(preview));
			long retained = memory.retained();
			// Install a sentinel offer in the equal-fence publisher context. A second
			// reset would remove it; a rejected/deferred replacement must keep it.
			memory.setCap(16_000_000);
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.entries(1,
				Map.of("sentinel", new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:dirt", "exact"), 3, "dirt", null, false))));
			var checkedField = preview.getClass().getDeclaredField("checkedTick"); checkedField.setAccessible(true); checkedField.setLong(preview, 1);
			publisher.drain();
			long sentinel = memory.retained(); assertTrue(sentinel > retained);
			// Clear pending traffic while retaining its offered ownership, so repair
			// can retry. This is an explicit publisher admission fixture, no recapture.
			publisher.withdraw(context); memory.setCap(0); repair.invoke(f.backend, preview);
			assertEquals(sentinel, memory.retained(), "a retry cannot perform another equal-fence rebase");
			assertTrue(repairField.getBoolean(preview)); assertTrue(scheduledField.getBoolean(preview));
			publisher.cancel(context); memory.setCap(16_000_000); repair.invoke(f.backend, preview);
			assertTrue(repairField.getBoolean(preview)); assertTrue(scheduledField.getBoolean(preview), "Rejected does not finish a repair");
			assertTrue(publisher.register(context, new InventorySyncPublisher.Route(f.host.epoch(A), 100, 1, 1, null, "fixture", null)));
			repair.invoke(f.backend, preview);
			assertFalse(repairField.getBoolean(preview)); assertFalse(scheduledField.getBoolean(preview));
			assertEquals(1, f.reads.get()); assertEquals(0, f.host.store.size());
		}
	}

	@Test void completedEmptyRetainedPreviewRepairKeepsItsCompleteEmptyMeaningWithoutReads() {
		try (Fixture f = new Fixture()) {
			f.amount = 0; f.open(); int reads = f.reads.get();
			int start = f.host.packets.size();
			f.backend.handle(A, InventoryC2SPacket.resync(f.host.epoch(A), 1).stamp(100, 1), 1, f.settings); f.backend.tick(1, f.settings);
			var replay = f.host.packets.subList(start, f.host.packets.size()).stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW).findFirst().orElseThrow();
			assertTrue(replay.completeScan()); assertTrue(replay.entries().isEmpty()); assertTrue(replay.watermark() > 1);
			assertEquals(reads, f.reads.get()); assertEquals(0, f.host.store.size());
		}
	}

	static Object field(Object owner, String name) throws Exception {
		var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
	}
	static void field(Object owner, String name, Object value) throws Exception {
		var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
	}
	static Object retainedPreview(InventoryBackend backend) throws Exception {
		Object session = ((Map<?, ?>) field(backend, "sessions")).get(A);
		return ((Map<?, ?>) field(session, "previews")).get(1L);
	}
	@SuppressWarnings("unchecked")
	@Test void metadataOldInputAdmissionDefersBeforeParserAndRetriesTheSameTokensWithoutReading() throws Exception {
		try (Fixture f = new Fixture()) {
			f.open(); Object preview = retainedPreview(f.backend);
			var counts = (Map<InventoryScanner.Key, InventoryDomainCodec.Item>) field(preview, "counts");
			var totals = (Map<String, InventoryDomainCodec.Item>) field(preview, "totals");
			var references = (Map<String, InventorySelection>) field(preview, "references");
			var key = counts.keySet().iterator().next(); var token = references.keySet().iterator().next();
			String old = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_data\":{\"text\":\"" + "x".repeat(5000) + "\"}}}";
			var item = new InventoryDomainCodec.Item(key, 7, "stone", old, false);
			counts.put(key, item); totals.put(key.itemId(), item);
			references.put(token, new InventorySelection(key, key.itemId(), "stone", old, false, "attention"));
			field(preview, "metadataCeiling", 8192);
			AtomicInteger parses = new AtomicInteger();
			f.host.projector = (display, cap) -> {
				parses.incrementAndGet();
			return new nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.Display(display.itemId(), display.label(), "{}", true);
			};
			var reproject = InventoryBackend.class.getDeclaredMethod("reprojectPreviewMetadata", preview.getClass(), int.class); reproject.setAccessible(true);
			var memory = f.backend.runtime().memory(); long retained = memory.retained();
			memory.setCap(retained + 16_384); int reads = f.reads.get();
			Object consumer = field(preview, "consumer"); Object cursor = field(consumer, "cursor");
			assertEquals(false, reproject.invoke(f.backend, preview, 1024));
			assertEquals(0, parses.get(), "a tiny output does not pay for parsing a larger OLD retained input");
			assertSame(counts, field(preview, "counts")); assertSame(item, counts.get(key));
			assertEquals(old, references.get(token).displayJson()); assertEquals(retained, memory.retained()); assertEquals(0, memory.reserved());
			memory.setCap(16_000_000);
			assertEquals(true, reproject.invoke(f.backend, preview, 1024));
			var fresh = (Map<InventoryScanner.Key, InventoryDomainCodec.Item>) field(preview, "counts");
			var refs = (Map<String, InventorySelection>) field(preview, "references");
			assertEquals(3, parses.get(), "one sequential parser per count, total and reference, with no repeated scan");
			assertEquals(Set.of(key), fresh.keySet()); assertEquals(Set.of(token), refs.keySet());
			assertEquals(7, fresh.get(key).count()); assertFalse(fresh.get(key).aggregateRequired()); assertFalse(refs.get(token).aggregate());
			assertEquals(reads, f.reads.get()); assertEquals(cursor, field(consumer, "cursor")); assertEquals(0, memory.reserved());
		}
	}
	@SuppressWarnings("unchecked")
	@Test void metadataOldInputWorkRefusalHasZeroParsesAndRetriesAllThreeActualInputs() throws Exception {
		try (Fixture f = new Fixture()) {
			f.open(); Object preview = retainedPreview(f.backend);
			var counts = (Map<InventoryScanner.Key, InventoryDomainCodec.Item>) field(preview, "counts");
			var totals = (Map<String, InventoryDomainCodec.Item>) field(preview, "totals");
			var references = (Map<String, InventorySelection>) field(preview, "references");
			var key = counts.keySet().iterator().next(); var token = references.keySet().iterator().next();
			String old = "x".repeat(30_000); var item = new InventoryDomainCodec.Item(key, 9007199254740993L, "stone", old, false);
			counts.put(key, item); totals.put(key.itemId(), item); references.put(token, new InventorySelection(key, key.itemId(), "stone", old, false, "attention"));
			field(preview, "metadataCeiling", 32768);
			var parses = new AtomicInteger(); f.host.projector = (display, cap) -> {
				parses.incrementAndGet(); return new nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.Display(display.itemId(), display.label(), null, false);
			};
			var runtime = f.backend.runtime();
			var ledger = (nx.pingwheel.common.presentation.source.CostLedger) field(runtime, "physical");
			var work = new nx.pingwheel.common.presentation.source.CostLedger.Counter("inventory/item_work", nx.pingwheel.common.presentation.source.CostLedger.Unit.WORK);
			assertTrue(runtime.claimDetachedItemWork(ledger.remaining(work)));
			var reproject = InventoryBackend.class.getDeclaredMethod("reprojectPreviewMetadata", preview.getClass(), int.class); reproject.setAccessible(true);
			Object consumer = field(preview, "consumer"), cursor = field(consumer, "cursor"); long retained = runtime.memory().retained(); int reads = f.reads.get();
			assertEquals(false, reproject.invoke(f.backend, preview, 1024)); assertEquals(0, parses.get());
			assertSame(counts, field(preview, "counts")); assertSame(item, counts.get(key)); assertEquals(old, references.get(token).displayJson());
			assertEquals(32768, field(preview, "metadataCeiling")); assertEquals(retained, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
			runtime.advance(f.settings.getPreview().getPeriodTicks(), f.settings);
			assertEquals(true, reproject.invoke(f.backend, preview, 1024)); assertEquals(3, parses.get(), "count, total and reference each invoke the parser, even with one shared JSON");
			var fresh = (Map<InventoryScanner.Key, InventoryDomainCodec.Item>) field(preview, "counts");
			var refs = (Map<String, InventorySelection>) field(preview, "references");
			assertEquals(Set.of(key), fresh.keySet()); assertEquals(Set.of(token), refs.keySet()); assertEquals(item.count(), fresh.get(key).count());
			assertFalse(fresh.get(key).aggregateRequired()); assertFalse(refs.get(token).aggregate()); assertNull(fresh.get(key).displayJson());
			assertEquals(reads, f.reads.get()); assertEquals(cursor, field(consumer, "cursor")); assertEquals(0, runtime.memory().reserved());
		}
	}
	@SuppressWarnings("unchecked")
	@Test void failedMetadataProjectionDoesNotInstallAnEarlierReplacementOrHideExcessDemand() throws Exception {
		try (Fixture f = new Fixture()) {
			f.open(); Object preview = retainedPreview(f.backend);
			var counts = (Map<InventoryScanner.Key, InventoryDomainCodec.Item>) field(preview, "counts");
			var totals = (Map<String, InventoryDomainCodec.Item>) field(preview, "totals");
			var key = counts.keySet().iterator().next();
			String old = "x".repeat(5000); var item = new InventoryDomainCodec.Item(key, 7, "stone", old, false);
			counts.put(key, item); totals.put(key.itemId(), item); field(preview, "metadataCeiling", 8192);
			AtomicInteger parses = new AtomicInteger();
			f.host.projector = (display, cap) -> new nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.Display(
				display.itemId(), display.label(), parses.incrementAndGet() == 1 ? "{}" : "z".repeat(cap + 1), false);
			var reproject = InventoryBackend.class.getDeclaredMethod("reprojectPreviewMetadata", preview.getClass(), int.class); reproject.setAccessible(true);
			long retained = f.backend.runtime().memory().retained();
			var ledger = (nx.pingwheel.common.presentation.source.CostLedger) field(f.backend.runtime(), "physical");
			var work = new nx.pingwheel.common.presentation.source.CostLedger.Counter("inventory/item_work", nx.pingwheel.common.presentation.source.CostLedger.Unit.WORK);
			long used = ledger.used(work);
			var owners = List.copyOf((List<?>) field(preview, "entries"));
			var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> reproject.invoke(f.backend, preview, 1024));
			assertInstanceOf(IllegalStateException.class, failure.getCause());
			assertSame(counts, field(preview, "counts")); assertSame(item, counts.get(key)); assertSame(item, totals.get(key.itemId()));
			assertEquals(owners, field(preview, "entries"), "failed projection retains its original sampling owners");
			assertEquals(retained, f.backend.runtime().memory().retained()); assertEquals(0, f.backend.runtime().memory().reserved());
			assertTrue(ledger.used(work) > used, "attempted invalid OLD-input projection is never refunded when validation throws");
		}
	}
	@Test void previewAdmissionRefusalKeepsItsPaidCursorAndExistingOpaqueIdentityUntilRoomReturns() throws Exception {
		try (Fixture f = new Fixture()) {
			f.open(); Object preview = retainedPreview(f.backend); Object consumer = field(preview, "consumer");
			Object cursor = field(consumer, "cursor"); Object keys = Map.copyOf((Map<?, ?>) field(preview, "keys"));
			Object references = Map.copyOf((Map<?, ?>) field(preview, "references")); int reads = f.reads.get();
			var sample = InventoryBackend.class.getDeclaredMethod("samplePreview", UUID.class, preview.getClass()); sample.setAccessible(true);
			var memory = f.backend.runtime().memory(); long retained = memory.retained(); memory.setCap(retained);
			sample.invoke(f.backend, A, preview);
			assertEquals(cursor, field(consumer, "cursor")); assertEquals(keys, field(preview, "keys")); assertEquals(references, field(preview, "references"));
			assertEquals(reads, f.reads.get()); assertEquals(retained, memory.retained()); assertEquals(0, memory.reserved());
			memory.setCap(16_000_000); sample.invoke(f.backend, A, preview);
			assertEquals(cursor, field(consumer, "cursor")); assertEquals(keys, field(preview, "keys"));
			assertEquals(reads, f.reads.get(), "consumed observation is reused, never recaptured on metadata admission retry");
			assertEquals(0, memory.reserved());
		}
	}
	@SuppressWarnings("unchecked")
	@Test void backendRetirementUsesThePrepaidRequestSlabEvenWhenProjectionMemoryIsFull() throws Exception {
		try (Fixture f = new Fixture()) {
			f.open(); Object preview = retainedPreview(f.backend);
			var refs = (Map<String, InventorySelection>) field(preview, "references");
			String token = refs.keySet().iterator().next(); String id = refs.get(token).itemId();
			var memory = f.backend.runtime().memory(); memory.setCap(memory.retained()); long charged = memory.retained();
			var retire = InventoryBackend.class.getDeclaredMethod("retirePreviewGroup", preview.getClass(), String.class); retire.setAccessible(true);
			retire.invoke(f.backend, preview, id);
			assertFalse(refs.containsKey(token)); assertTrue(((Set<?>) field(preview, "retired")).contains(token));
			assertTrue(((Set<?>) field(preview, "folded")).contains(id));
			assertTrue(memory.retained() <= charged, "retirement grows only prepaid indexes and may release obsolete data owners");
			assertEquals(0, memory.reserved());
			memory.setCap(memory.retained());
			var publish = InventoryBackend.class.getDeclaredMethod("publishPreview", preview.getClass()); publish.setAccessible(true);
			assertInstanceOf(SyncPublisher.Outcome.Deferred.class, publish.invoke(f.backend, preview));
			assertTrue(((Set<?>) field(preview, "retired")).contains(token), "publication refusal cannot undo the exact-token fence");
		}
	}
	@SuppressWarnings("unchecked")
	@Test void duplicateOnlyPreviewPagesReplaceOneProjectionTicketInsteadOfAccumulatingHistory() throws Exception {
		var host = new Host(); var settings = InventorySettings.serverDefaults(); var reads = new AtomicInteger();
		settings.setPhysicalSlotsPerTick(nx.pingwheel.common.config.IntLimit.finite(1));
		try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 8) {
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return InventoryRuntimeTest.item(7); }
		}), 16_000_000), 1)) {
			backend.handle(A, InventoryC2SPacket.hello(), 0, settings);
			backend.handle(A, InventoryC2SPacket.open(host.epoch(A), 100, 1, 1, InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings);
			for (int tick = 0; tick < 8; tick++) {
				backend.tick(tick, settings); Object preview = retainedPreview(backend);
				assertEquals(1, ((List<?>) field(preview, "entries")).size(), "one current projection owner, independent of duplicate page count");
				assertEquals(1, field(preview, "categories"));
				var counts = (Map<InventoryScanner.Key, InventoryDomainCodec.Item>) field(preview, "counts");
				assertEquals((tick + 1L) * 7L, counts.values().iterator().next().count());
				assertEquals(0, backend.runtime().memory().reserved());
			}
			assertEquals(8, reads.get());
		}
	}
	@SuppressWarnings("unchecked")
	@Test void completedPreviewTighteningReplacesSamplingOwnershipExactlyAndRepeatedTighteningStaysStable() throws Exception {
		try (Fixture f = new Fixture()) {
			f.open(); Object preview = retainedPreview(f.backend); assertEquals(true, field(preview, "terminal"));
			var counts = (Map<InventoryScanner.Key, InventoryDomainCodec.Item>) field(preview, "counts");
			var totals = (Map<String, InventoryDomainCodec.Item>) field(preview, "totals");
			var references = (Map<String, InventorySelection>) field(preview, "references");
			var key = counts.keySet().iterator().next(); String token = references.keySet().iterator().next();
			String old = "x".repeat(5000); var item = new InventoryDomainCodec.Item(key, 7, "stone", old, false);
			counts.put(key, item); totals.put(key.itemId(), item);
			references.put(token, new InventorySelection(key, key.itemId(), "stone", old, false, "attention"));
			field(preview, "metadataCeiling", 8192);
			f.host.projector = (display, cap) -> new nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.Display(
				display.itemId(), display.label(), "x".repeat(cap), display.aggregateRequired());
			var sampling = (List<nx.pingwheel.common.presentation.source.RetainedMemoryLedger.Ticket>) field(preview, "entries");
			var oldTickets = List.copyOf(sampling); assertEquals(1, oldTickets.size());
			var memory = f.backend.runtime().memory(); long before = memory.retained();
			var measured = InventoryBackend.class.getDeclaredMethod("previewRetainedBytes", preview.getClass()); measured.setAccessible(true);
			var reproject = InventoryBackend.class.getDeclaredMethod("reprojectPreviewMetadata", preview.getClass(), int.class); reproject.setAccessible(true);
			long samplingCharge = (long) field(oldTickets.getFirst(), "charged");
			assertEquals(true, reproject.invoke(f.backend, preview, 2048));
			assertTrue(sampling.isEmpty(), "completed previews must release the superseded sampling owner without another scan");
			long current = (long) measured.invoke(null, preview);
			assertEquals(before - samplingCharge + current, memory.retained(), "exactly one full current metadata owner remains");
			long stable = memory.retained(); oldTickets.forEach(nx.pingwheel.common.presentation.source.RetainedMemoryLedger.Ticket::close);
			assertEquals(stable, memory.retained(), "closing the superseded owner again cannot refund a live version");
			Object metadata = field(preview, "metadataMemory");
			assertEquals(true, reproject.invoke(f.backend, preview, 1024));
			long tighter = (long) measured.invoke(null, preview);
			assertEquals(stable - current + tighter, memory.retained()); assertTrue(sampling.isEmpty());
			((nx.pingwheel.common.presentation.source.RetainedMemoryLedger.Ticket) metadata).close();
			long settled = memory.retained();
			assertEquals(true, reproject.invoke(f.backend, preview, 1024)); assertEquals(settled, memory.retained());
			assertEquals(Set.of(token), ((Map<?, ?>) field(preview, "references")).keySet());
			assertEquals(1, f.reads.get()); assertEquals(0, memory.reserved());
		}
	}

	@Test void trackingOldInputWorkRefusalKeepsPresentationTicketAndCompletedCount() throws Exception {
		try (Fixture f = new Fixture()) {
			f.open(); f.backend.handle(A, f.select(1), 1, f.settings); f.backend.tick(2, f.settings);
			Object lease = ((Map<?, ?>) field(f.backend, "tracking")).values().iterator().next();
			Object selection = field(lease, "selection"); long count = (long) field(lease, "completedCount");
			var retained = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.Display("minecraft:stone", "stone", "x".repeat(30_000), false);
			field(lease, "selectionPresentation", retained); field(lease, "presentationCeiling", 32768);
			Object ticket = field(lease, "presentationMemory"); int reads = f.reads.get();
			var parses = new AtomicInteger(); f.host.projector = (display, cap) -> {
				parses.incrementAndGet(); return new nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.Display(display.itemId(), display.label(), null, false);
			};
			var runtime = f.backend.runtime(); var ledger = (nx.pingwheel.common.presentation.source.CostLedger) field(runtime, "physical");
			var work = new nx.pingwheel.common.presentation.source.CostLedger.Counter("inventory/item_work", nx.pingwheel.common.presentation.source.CostLedger.Unit.WORK);
			assertTrue(runtime.claimDetachedItemWork(ledger.remaining(work)));
			var reproject = InventoryBackend.class.getDeclaredMethod("reprojectTrackingPresentation", lease.getClass(), int.class); reproject.setAccessible(true);
			assertEquals(false, reproject.invoke(f.backend, lease, 1024)); assertEquals(0, parses.get());
			assertSame(retained, field(lease, "selectionPresentation")); assertSame(ticket, field(lease, "presentationMemory")); assertEquals(32768, field(lease, "presentationCeiling"));
			assertEquals(count, field(lease, "completedCount")); assertEquals(0, runtime.memory().reserved());
			runtime.advance(2L + f.settings.getTracking().getPeriodTicks(), f.settings);
			assertEquals(true, reproject.invoke(f.backend, lease, 1024)); assertEquals(1, parses.get());
			assertNull(((nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.Display) field(lease, "selectionPresentation")).displayJson());
			assertSame(selection, field(lease, "selection")); assertEquals(count, field(lease, "completedCount")); assertEquals(reads, f.reads.get()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void trackingLargeDisplayValidationDefersBeforeConstructionAndRetriesWithoutChangingSelectionOrCount() throws Exception {
		try (Fixture f = new Fixture()) {
			f.settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(2));
			f.settings.setFrameByteMultiplier(java.math.BigDecimal.valueOf(2));
			f.settings.getTracking().setStreamByteMultiplier(nx.pingwheel.common.config.ByteMultiplier.finite(java.math.BigDecimal.valueOf(4)));
			f.settings.getTracking().setSnapshotByteMultiplier(nx.pingwheel.common.config.ByteMultiplier.finite(java.math.BigDecimal.valueOf(4)));
			f.open(); f.backend.handle(A, f.select(1), 1, f.settings); f.backend.tick(2, f.settings);
			Object lease = ((Map<?, ?>) field(f.backend, "tracking")).values().iterator().next();
			Object recipient = ((Map<?, ?>) field(lease, "recipients")).get(A);
			Object selection = field(lease, "selection");
			String json = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_data\":{\"text\":\"" + "x".repeat(5000) + "\"}}}";
			field(lease, "selectionPresentation", new nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.Display("minecraft:stone", "stone", json, false));
			field(lease, "revision", 2L);
			var publish = InventoryBackend.class.getDeclaredMethod("publishTracking", lease.getClass(), recipient.getClass()); publish.setAccessible(true);
			var publisher = (InventorySyncPublisher) field(f.backend, "publisher");
			var context = (SyncPublisher.Context) field(recipient, "context");
			var memory = f.backend.runtime().memory(); long retained = memory.retained(); int reads = f.reads.get();
			memory.setCap(retained + 4096L);
			// Negative count is a constructor trap: if Item construction runs under
			// the old fixed grant, validation throws instead of an honest defer.
			field(lease, "completedCount", -1L);
			assertDoesNotThrow(() -> publish.invoke(f.backend, lease, recipient));
			field(lease, "completedCount", 7L);
			assertDoesNotThrow(() -> publish.invoke(f.backend, lease, recipient));
			assertFalse(publisher.pending(context)); assertEquals(retained, memory.retained()); assertEquals(0, memory.reserved());
			var staging = InventoryBackend.class.getDeclaredMethod("trackingStagingBytes", String.class, String.class); staging.setAccessible(true);
			long validation = (long) staging.invoke(null, "stone", json);
			assertTrue(validation > json.length()); memory.setCap(retained + validation);
			publish.invoke(f.backend, lease, recipient); // validation fits; nested encode admission still refuses
			assertFalse(publisher.pending(context)); assertEquals(retained, memory.retained()); assertEquals(0, memory.reserved());
			memory.setCap(16_000_000); publish.invoke(f.backend, lease, recipient); assertTrue(publisher.pending(context));
			int start = f.host.packets.size(); publisher.drain();
			var sent = f.host.packets.subList(start, f.host.packets.size()).stream().flatMap(packet -> packet.entries().stream())
				.filter(entry -> json.equals(entry.displayJson())).findFirst().orElseThrow();
			assertEquals(7, sent.count()); assertFalse(sent.fallback()); assertSame(selection, field(lease, "selection"));
			assertEquals(7L, field(lease, "completedCount")); assertEquals(reads, f.reads.get()); assertEquals(0, memory.reserved());
		}
	}
}
