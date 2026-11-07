package nx.pingwheel.common.presentation.inventory;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused production-boundary regressions for the frozen inventory control
 * path. These reuse the existing headless fixture without changing it.
 */
class InventoryFrozenControlTest {
	private static final UUID A = InventoryBackendTest.A;
	private static final UUID B = InventoryBackendTest.B;

	@Test void firstHelloUsesLiveHostSessionAuthority() {
		try (var fixture = new InventoryBackendTest.Fixture()) {
			fixture.hello(A);
			assertTrue(fixture.host.packets.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.OFFER),
				"the first HELLO must establish a session from host.session, not from a not-yet-created cache");
		}
	}

	@Test void sameEpochResetOfOneRecipientDoesNotRewriteAnotherRecipient() {
		try (var fixture = new InventoryBackendTest.Fixture()) {
			fixture.open();
			fixture.backend.handle(A, fixture.select(1), 1, fixture.settings);
			fixture.backend.tick(2, fixture.settings);
			int start = fixture.host.packets.size();
			// Same presentation epoch, new view for A only.
			fixture.host.policies.put(A, new InventoryBackend.Policy(100, 2, Set.of("entity_block")));
			fixture.backend.tick(3, fixture.settings);
			fixture.backend.tick(4, fixture.settings);
			for (int index = start; index < fixture.host.packets.size(); index++) {
				if (!fixture.host.recipients.get(index).equals(B)) continue;
				InventoryS2CPacket packet = fixture.host.packets.get(index);
				assertNotEquals(2L, packet.view(), "A's view rebuild must not migrate B's tracking context");
				assertFalse(packet.kind() == InventoryS2CPacket.Kind.SNAPSHOT,
					"B must not be re-registered or rebaselined by A's RESET");
			}
		}
	}

	@Test void normalSameEpochResetRepublishesPreviewWithoutWaitingForSizeReconfiguration() {
		try (var fixture = new InventoryBackendTest.Fixture()) {
			fixture.open();
			InventoryS2CPacket before = fixture.preview();
			long oldBaseline = before.baselineId();
			fixture.host.policies.put(A, new InventoryBackend.Policy(100, 2, Set.of("entity_block")));
			int start = fixture.host.packets.size();
			fixture.backend.tick(1, fixture.settings);
			fixture.backend.tick(2, fixture.settings);
			fixture.backend.tick(3, fixture.settings);
			@Nullable InventoryS2CPacket after = fixture.host.packets.subList(start, fixture.host.packets.size()).stream()
				.filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && packet.view() == 2
					&& !packet.entries().isEmpty()).findFirst().orElse(null);
			assertTrue(after != null, "a normal reset must complete registration, fence handoff and republish");
			assertTrue(after.baselineId() > oldBaseline);
			assertTrue(after.entries().stream().anyMatch(entry -> entry.count() == 7));
		}
	}

	@Test void closeAfterDeferredOpenedAckRemovesPendingRetry() {
		class DeferringHost extends InventoryBackendTest.Host {
			int openedAttempts;
			@Override public void send(UUID player, InventoryS2CPacket packet) {
				if (packet.kind() == InventoryS2CPacket.Kind.OPENED) {
					openedAttempts++;
					throw new IllegalStateException("opened ack deferred");
				}
				super.send(player, packet);
			}
		}
		DeferringHost host = new DeferringHost();
		InventorySettings settings = InventorySettings.serverDefaults();
		try (var backend = new InventoryBackend(host, new InventoryRuntime(input ->
			Optional.of(new InventoryRuntimeTest.Source(input, new AtomicInteger(), 1)), 16_000_000), 1)) {
			backend.handle(A, InventoryC2SPacket.hello(), 0, settings);
			long epoch = host.epoch(A);
			backend.handle(A, InventoryC2SPacket.open(epoch, 100, 1, 1, InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings);
			assertTrue(host.openedAttempts > 0, "the deferred ACK must remain retryable");
			int beforeClose = host.openedAttempts;
			backend.handle(A, InventoryC2SPacket.close(epoch, 1).stamp(100, 1), 1, settings);
			int afterClose = host.openedAttempts;
			backend.tick(1, settings);
			backend.tick(2, settings);
			assertEquals(afterClose, host.openedAttempts, "CLOSE must remove the pending OPENED and cancel its exact control");
			assertTrue(host.packets.stream().noneMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW
				&& !packet.entries().isEmpty()), "a closed zero/any-mask request must release its Preview lane");
			assertTrue(beforeClose >= afterClose);
		}
	}
}
