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
		Host() { policies.put(A, new InventoryBackend.Policy(100, 1, Set.of("entity_block"))); policies.put(B, policies.get(A)); }
		@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return Optional.ofNullable(policies.get(player)); }
		@Override public Optional<InventoryBackend.Opened> open(UUID player, nx.pingwheel.common.domain.Target requested) { return Optional.of(new InventoryBackend.Opened(InventoryRuntimeTest.TARGET, "entity_block", "attention")); }
		@Override public boolean annotationAllowed(InventorySourceInput input, String type) { return Set.of("attention", "danger").contains(type); }
		@Override public boolean knows(UUID player, MarkerId marker) { return store.allMarkers().stream().anyMatch(m -> m.id().equals(marker) && m.recipients().contains(player)); }
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public int encodedBytes(InventoryS2CPacket packet) { FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer()); try { packet.write(buf); return buf.readableBytes(); } finally { buf.release(); } }
		@Override public void send(UUID player, InventoryS2CPacket packet) { assertFalse(packet.isCorrupt(), packet.toString()); packets.add(packet); recipients.add(player); }
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
				return amount == 0 ? null : new InventoryDomainCodec.Item(new InventoryScanner.Key(itemId, item.key().componentsKey()), item.count(), item.label(), item.displayJson(), stripped);
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
			f.host.policies.put(B, new InventoryBackend.Policy(100, 2, Set.of()));
			int start = f.host.packets.size(); f.amount = 9; f.backend.tick(5, f.settings);
			for (int i = start; i < f.host.packets.size(); i++) if (f.host.recipients.get(i).equals(B)) assertEquals(InventoryS2CPacket.Kind.POLICY, f.host.packets.get(i).kind());
			assertEquals(List.of(A, B), f.host.store.allMarkers().getFirst().recipients());
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
			f.settings.getTracking().setPeriodTicks(9); f.settings.setPendingMemoryMiB(4); f.backend.tick(2, f.settings);
			assertEquals(epoch, f.host.epoch(A));
			assertEquals(9, f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.OFFER).reduce((a, b) -> b).orElseThrow().offer().trackingPeriodTicks());
			assertEquals(80, f.host.store.allMarkers().getFirst().expiresAtTick());
			assertEquals(4L * 1024 * 1024, f.backend.runtime().memory().cap());
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
	@Test void minimumFiniteMemoryCanAdmitSmallPreviewAndImmediateTracking() {
		try (Fixture f = new Fixture()) {
			f.settings.setPendingMemoryMiB(1); f.open(); assertFalse(f.preview().entries().isEmpty());
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
}
