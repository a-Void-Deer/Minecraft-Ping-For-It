package nx.pingwheel.common.presentation.inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.ByteMultiplier;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cancellation ownership and OPENED-before-terminal ordering across a real same-epoch client reset. */
@NotNullByDefault
class InventoryPreviewResetLifecycleTest {
	private static final UUID A = InventoryBackendTest.A;
	private static final UUID B = InventoryBackendTest.B;
	private static final int PERIOD = InventoryLimits.MAX_PERIOD_TICKS;

	@Test
	void closeInFlightBeforeAndAfterServerRefreshReleasesCapacityAcrossTwoResets() throws Exception {
		try (var f = new InventoryTerminalPreviewResetTest.Fixture(1)) {
			f.settings.getPreview().setPeriodTicks(PERIOD);
			f.settings.getPreview().setMaxTargetsPerClient(IntLimit.finite(2));
			var endpoint = new InventoryTerminalPreviewResetTest.Endpoint(A);
			f.hello(A, 0);
			endpoint.drain(f.host);
			for (int race = 0; race < 2; race++) {
				long now = (long) race * PERIOD;
				long request = endpoint.open();
				handle(f, A, endpoint.last(InventoryC2SPacket.Kind.OPEN), now);
				f.tick(now);
				endpoint.drain(f.host);
				assertEquals(1, requests(f, A).size());
				assertFalse(endpoint.client.selectable(request).isEmpty());

				long nextView = race + 2L;
				// In one race the server tick has already rebuilt its view; in the
				// other, CLOSE ingress itself discovers the advance. Neither RESET
				// has reached the client when it forgets the local request.
				f.host.policies.put(A, policy(nextView));
				if (race == 0) f.tick(now + 1);
				InventoryC2SPacket close = endpoint.close(request);
				assertEquals(nextView - 1, close.view());
				assertFalse(endpoint.client.hasPreview(request));
				long retained = f.backend.runtime().memory().retained();
				handle(f, A, close, now + 1);
				assertTrue(requests(f, A).isEmpty(), "same-epoch CLOSE must retire the server-owned request");
				assertTrue(f.backend.runtime().memory().retained() < retained, "cancellation releases retained ownership immediately");
				endpoint.reset(nextView);
				endpoint.drain(f.host);
				assertFalse(endpoint.client.hasPreview(request), "a late replacement OPENED cannot recreate the closed client request");
			}
			f.tick(10_001);
			endpoint.drain(f.host);
			for (int slot = 0; slot < 2; slot++) {
				long request = endpoint.open();
				handle(f, A, endpoint.last(InventoryC2SPacket.Kind.OPEN), 10_001);
				endpoint.drain(f.host);
				assertTrue(requests(f, A).containsKey(request), "both target slots remain reusable after both cancellation races");
			}
			assertEquals(2, requests(f, A).size());
			assertEquals(0, f.backend.runtime().memory().reserved());
		}
	}

	@Test
	void oldViewCancellationKeepsEpochOwnerRequestAndActionFences() throws Exception {
		try (var f = new InventoryTerminalPreviewResetTest.Fixture(1)) {
			f.settings.getPreview().setPeriodTicks(PERIOD);
			f.settings.getPreview().setMaxTargetsPerClient(IntLimit.finite(2));
			var endpoint = new InventoryTerminalPreviewResetTest.Endpoint(A);
			f.hello(A, 0); f.hello(B, 0);
			endpoint.drain(f.host);
			long first = endpoint.open();
			handle(f, A, endpoint.last(InventoryC2SPacket.Kind.OPEN), 0);
			f.open(B, first, 0);
			f.tick(0);
			endpoint.drain(f.host);
			var row = endpoint.client.selectable(first).getFirst();
			var cut = endpoint.client.preview(first);
			long epoch = f.host.epoch(A);
			f.host.policies.put(A, policy(2));
			f.tick(1);

			InventoryC2SPacket oldClose = InventoryC2SPacket.close(epoch, first).stamp(100, 1);
			handle(f, A, InventoryC2SPacket.close(epoch + 1000, first).stamp(100, 1), 1);
			handle(f, A, oldClose.stamp(99, 1), 1);
			handle(f, A, oldClose.stamp(100, 3), 1);
			handle(f, B, oldClose, 1);
			handle(f, A, InventoryC2SPacket.close(epoch, first + 99).stamp(100, 1), 1);
			assertTrue(requests(f, A).containsKey(first));
			assertTrue(requests(f, B).containsKey(first), "another connection's inventory epoch cannot cancel this owner's request");
			handle(f, B, InventoryC2SPacket.close(f.host.epoch(B), first).stamp(100, 1), 1);
			assertTrue(requests(f, B).isEmpty());
			assertTrue(requests(f, A).containsKey(first), "an authenticated peer's same-ID CLOSE releases only that peer's request");

			int beforeAction = f.host.packets.size();
			handle(f, A, InventoryC2SPacket.select(epoch, 100, 1, 1, first,
				cut.baselineId(), cut.statusRevision(), row.entryKey(), "attention"), 1);
			handle(f, A, InventoryC2SPacket.open(epoch, 100, 1, first + 1,
				InventoryRuntimeTest.TARGET, BlockFace.NORTH), 1);
			assertEquals(beforeAction, f.host.packets.size(), "old-view SELECT/OPEN remain rejected before action handling");
			assertTrue(f.host.store.allMarkers().isEmpty());
			assertEquals(1, requests(f, A).size());

			endpoint.reset(2); endpoint.drain(f.host);
			long second = endpoint.open();
			handle(f, A, endpoint.last(InventoryC2SPacket.Kind.OPEN), 1);
			assertTrue(requests(f, A).containsKey(second), "stale OPEN cannot consume the next request fence");
			handle(f, A, oldClose, 1);
			handle(f, A, oldClose, 1);
			handle(f, A, InventoryC2SPacket.open(epoch, 100, 2, first,
				InventoryRuntimeTest.TARGET, BlockFace.NORTH), 1);
			assertEquals(Set.of(second), requests(f, A).keySet(), "retired request IDs cannot be reused or cancel a newer request");

			f.backend.disconnect(A);
			f.hello(A, PERIOD);
			long replacementEpoch = f.host.epoch(A);
			assertNotEquals(epoch, replacementEpoch);
			handle(f, A, InventoryC2SPacket.open(replacementEpoch, 100, 2, first,
				InventoryRuntimeTest.TARGET, BlockFace.NORTH), PERIOD);
			handle(f, A, oldClose, PERIOD);
			assertTrue(requests(f, A).containsKey(first), "old connection CLOSE cannot kill a same-ID successor");
			f.host.policies.put(A, new InventoryBackend.Policy(101, 1, Set.of("entity_block")));
			f.tick(PERIOD + 1);
			handle(f, A, InventoryC2SPacket.open(replacementEpoch, 101, 1, second,
				InventoryRuntimeTest.TARGET, BlockFace.NORTH), PERIOD + 1);
			handle(f, A, InventoryC2SPacket.close(replacementEpoch, second).stamp(100, 1), PERIOD + 1);
			assertTrue(requests(f, A).containsKey(second), "presentation epoch changes remain strictly fenced too");
		}
	}

	@Test
	void globalResetOfNinetyOneSessionsRetainsTerminalUntilReplacementOpenedHandoff() throws Exception {
		try (var f = new InventoryTerminalPreviewResetTest.Fixture(4)) {
			f.settings.getPreview().setPeriodTicks(PERIOD);
			f.settings.setPhysicalSlotsPerTick(IntLimit.finite(InventoryLimits.MIN_ORDINARY_COUNT));
			f.settings.getPreview().setGlobalByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.125")));
			f.settings.validate();
			List<UUID> players = new ArrayList<>();
			// All 91 sessions complete ordinary HELLO handshakes. Only the last
			// owns a Preview: this reaches the real global control bound without
			// inflating memory limits or injecting transport failures.
			for (int index = 0; index < 90; index++) {
				UUID player = new UUID(91, index);
				players.add(player); f.host.policies.put(player, policy(1));
				f.hello(player, (long) index * PERIOD);
				assertTrue(f.host.epoch(player) != 0);
			}
			players.add(A);
			long now = 90L * PERIOD;
			var endpoint = new InventoryTerminalPreviewResetTest.Endpoint(A);
			f.hello(A, now); endpoint.drain(f.host);
			long request = endpoint.open();
			handle(f, A, endpoint.last(InventoryC2SPacket.Kind.OPEN), now);
			f.tick(now); endpoint.drain(f.host);
			assertFalse(endpoint.client.selectable(request).isEmpty());
			assertTrue(endpoint.client.preview(request).completeScan());
			// This completed Preview checks idle health only once per period. In
			// its next due period, legitimate negotiation traffic consumes the wire
			// budget before the source check; it does not advance Preview sampling.
			long invalidTick = now + PERIOD + 1;
			for (UUID player : players) f.hello(player, invalidTick);
			int invalidStart = f.host.packets.size();
			int beforeInvalidValidation = f.validations.get();
			f.sourceValid.set(false); f.tick(invalidTick);
			assertTrue(f.validations.get() > beforeInvalidValidation, "the completed Preview's idle health check must be due");
			assertTrue(context(f, A).stateFence() > 1, "source invalidity must actually retire the Preview before RESET");
			assertFalse(f.hasPreviewStatus(A, request, InventoryS2CPacket.Status.INVALID, invalidStart));
			assertTrue(publisher(f).controlPending(context(f, A)));
			int reads = f.reads.get(), validations = f.validations.get();

			// Ingress drains only generic controls, leaving the terminal Preview
			// pending. Clear the earlier wire-deferred negotiations in a new legal
			// period before resetting, so they cannot alter control admission order.
			f.settings.getPreview().setGlobalByteMultiplier(ByteMultiplier.finite(BigDecimal.ONE));
			f.settings.validate();
			long resetTick = now + 2L * PERIOD;
			int periodStart = f.host.packets.size();
			f.hello(A, resetTick);
			assertTrue(((Map<?, ?>) InventoryBackendTest.field(publisher(f), "controls")).isEmpty());
			assertTrue(publisher(f).controlPending(context(f, A)));
			endpoint.drain(f.host);
			for (UUID player : players) f.host.policies.put(player, policy(2));
			endpoint.reset(2);
			// Wire capacity remains available; the refusal is the actual 256-entry
			// generic control queue (91 * 3 session controls + OPENED).
			int resetStart = f.host.packets.size();
			f.tick(resetTick);
			endpoint.drain(f.host);
			assertEquals(-1, packetIndex(f, A, request, InventoryS2CPacket.Kind.OPENED, resetStart));
			assertFalse(f.hasPreviewStatus(A, request, InventoryS2CPacket.Status.INVALID, resetStart),
				"terminal PREVIEW must not precede its request's replacement OPENED");
			assertEquals(256, f.host.packets.size() - resetStart, "the real saturated generic queue drains successfully");
			SyncPublisher.Context terminal = context(f, A);
			assertTrue(publisher(f).controlPending(terminal), "waiting for OPENED must retain both terminal context and pending status");
			long encodedBytes = f.host.packets.subList(periodStart, f.host.packets.size()).stream()
				.mapToLong(packet -> packet.encodedSize((int) f.settings.effectiveDisplayBytes(), (int) f.settings.effectiveFrameBytes())).sum();
			assertTrue(encodedBytes < f.settings.previewGlobalPeriodBytes() - f.settings.previewClientPeriodBytes(),
				"actual encoded traffic leaves wire headroom; queue admission, not wire shortage, blocked OPENED");

			f.tick(resetTick + 1); endpoint.drain(f.host);
			int opened = packetIndex(f, A, request, InventoryS2CPacket.Kind.OPENED, resetStart);
			int invalid = packetIndex(f, A, request, InventoryS2CPacket.Kind.PREVIEW, resetStart);
			assertTrue(opened >= 0 && invalid > opened, "successful replacement OPENED handoff must precede terminal INVALID");
			assertEquals(InventoryS2CPacket.Status.INVALID, f.host.packets.get(invalid).status());
			assertEquals(terminal, context(f, A), "retry preserves the same pending terminal context");
			assertFalse(publisher(f).controlPending(terminal));
			assertTrue(endpoint.client.preview(request).invalid());
			assertEquals(InventoryS2CPacket.Status.INVALID, endpoint.client.preview(request).status());
			assertTrue(endpoint.client.selectable(request).isEmpty(), "old rows never regain selectable references");
			int delivered = f.host.packets.size();
			f.tick(resetTick + 2); f.tick(10_001); endpoint.drain(f.host);
			assertFalse(f.hasPreviewStatus(A, request, InventoryS2CPacket.Status.INVALID, delivered), "terminal status stops retrying after handoff");
			assertEquals(reads, f.reads.get(), "terminal reset/retry never rescans the retired source");
			assertEquals(validations, f.validations.get(), "terminal reset/retry never probes the retired source");
			assertEquals(0, f.backend.runtime().memory().reserved());
		}
	}

	private static InventoryBackend.Policy policy(long view) {
		return new InventoryBackend.Policy(100, view, Set.of("entity_block"));
	}

	private static void handle(InventoryTerminalPreviewResetTest.Fixture f, UUID player, InventoryC2SPacket packet, long tick) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(buffer);
			InventoryC2SPacket decoded = InventoryC2SPacket.readSafe(buffer);
			assertFalse(decoded.isCorrupt()); assertFalse(buffer.isReadable());
			f.backend.handle(player, decoded, tick, f.settings);
		} finally { buffer.release(); }
	}

	private static Object session(InventoryTerminalPreviewResetTest.Fixture f, UUID player) throws Exception {
		Map<?, ?> sessions = (Map<?, ?>) InventoryBackendTest.field(f.backend, "sessions");
		return java.util.Objects.requireNonNull(sessions.get(player));
	}

	private static Map<?, ?> requests(InventoryTerminalPreviewResetTest.Fixture f, UUID player) throws Exception {
		return (Map<?, ?>) InventoryBackendTest.field(session(f, player), "requests");
	}

	private static SyncPublisher.Context context(InventoryTerminalPreviewResetTest.Fixture f, UUID player) throws Exception {
		Map<?, ?> previews = (Map<?, ?>) InventoryBackendTest.field(session(f, player), "previews");
		assertEquals(1, previews.size());
		return (SyncPublisher.Context) InventoryBackendTest.field(previews.values().iterator().next(), "context");
	}

	private static InventorySyncPublisher publisher(InventoryTerminalPreviewResetTest.Fixture f) throws Exception {
		return (InventorySyncPublisher) InventoryBackendTest.field(f.backend, "publisher");
	}

	private static int packetIndex(InventoryTerminalPreviewResetTest.Fixture f, UUID player, long request,
		InventoryS2CPacket.Kind kind, int start) {
		for (int index = start; index < f.host.packets.size(); index++) {
			InventoryS2CPacket packet = f.host.packets.get(index);
			if (f.host.recipients.get(index).equals(player) && packet.requestId() == request && packet.kind() == kind) return index;
		}
		return -1;
	}
}
