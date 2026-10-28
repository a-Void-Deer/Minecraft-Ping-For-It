package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inventory/shared-source contracts: cancellation isolates demand, period usage
 * is not refundable, memory refusal is retryable, and historical clients cannot
 * exhaust bounded live admission. All pressure and observations use public seams.
 */
@NotNullByDefault
class InventoryBackendLifecycleTest {

	private static final UUID OWNER = new UUID(71, 1), OTHER = new UUID(71, 2);
	private static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	private static final int PERIOD = 4;

	@Test
	void duplicateOpenDoesNotRestartPaidProgressAndLastCloseStopsAllSourceWork() {
		try (Fixture f = new Fixture(3)) {
			f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
			f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(1));
			long epoch = f.hello(OWNER, 0);
			f.open(OWNER, epoch, 1, 0);
			f.open(OWNER, epoch, 2, 0);
			f.tick(0);
			assertEquals(1, f.state.reads, "duplicate requests share this client's one paid slot");
			assertEquals(1, f.latestEntry(OWNER, epoch, 1).count());
			assertEquals(1, f.latestEntry(OWNER, epoch, 2).count());
			assertFalse(f.complete(OWNER, epoch, 2));

			long retained = f.memory().retained();
			f.open(OWNER, epoch, 1, 1);
			f.tick(1);
			assertEquals(2, f.host.opens, "replayed OPEN cannot repeat target admission");
			assertEquals(1, f.state.reads);
			assertEquals(retained, f.memory().retained(), "replayed OPEN owns no second request or progress allocation");

			f.closeRequest(OWNER, epoch, 1, 1);
			long afterClose = f.memory().retained();
			int releases = f.state.releases;
			f.closeRequest(OWNER, epoch, 1, 1);
			assertEquals(afterClose, f.memory().retained(), "duplicate CLOSE cannot release a peer's ownership");
			assertEquals(releases, f.state.releases);
			int afterCancellation = f.host.sent.size();
			f.tick(PERIOD);
			assertEquals(2, f.state.reads, "the remaining request continues, not restarts, the sweep");
			f.tick(2L * PERIOD);
			assertTrue(f.complete(OWNER, epoch, 2));
			assertEquals(6, f.latestEntry(OWNER, epoch, 2).count(), "the three fixture slots contain 1 + 2 + 3");
			assertEquals(3, f.state.reads);
			assertTrue(f.host.sent.subList(afterCancellation, f.host.sent.size()).stream()
				.noneMatch(delivery -> delivery.packet().kind() == InventoryS2CPacket.Kind.PREVIEW
					&& delivery.packet().requestId() == 1), "cancelled preview data cannot escape later drains");

			f.closeRequest(OWNER, epoch, 2, 2L * PERIOD);
			long afterLastClose = f.memory().retained();
			int afterLastRelease = f.state.releases;
			f.closeRequest(OWNER, epoch, 2, 2L * PERIOD);
			assertEquals(afterLastClose, f.memory().retained());
			assertEquals(afterLastRelease, f.state.releases);
			assertEquals(0, f.state.liveSources);
			int resolutions = f.state.resolutions, validations = f.state.validations;
			f.tick(2L * PERIOD + 1);
			f.tick(3L * PERIOD);
			assertEquals(resolutions, f.state.resolutions, "last CLOSE stops recovery and source resolution too");
			assertEquals(validations, f.state.validations);
			assertEquals(3, f.state.reads);
			f.backend.disconnect(OWNER);
			f.tick(4L * PERIOD);
			assertEquals(0, f.memory().retained(), "period-owned quota tickets may outlive CLOSE, but not their period");
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void closeAndReopenCannotRefundClientOrGlobalPeriodSlots(boolean globalLimit) {
		try (Fixture f = new Fixture(2)) {
			f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(globalLimit ? 8 : 1));
			f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(globalLimit ? 1 : 8));
			long epoch = f.hello(OWNER, 0);
			f.open(OWNER, epoch, 1, 0);
			f.tick(0);
			assertEquals(1, f.state.reads);
			f.closeRequest(OWNER, epoch, 1, 1);
			f.open(OWNER, epoch, 2, 1);
			long otherEpoch = 0;
			if (globalLimit) {
				otherEpoch = f.hello(OTHER, 1);
				f.open(OTHER, otherEpoch, 1, 1);
			}
			f.tick(1);
			assertEquals(1, f.state.reads, "closing a request cannot buy another slot in the same period");
			assertTrue(f.previews(OWNER, epoch, 2).stream().allMatch(packet -> packet.entries().isEmpty() && !packet.completeScan()));
			if (globalLimit) assertTrue(f.previews(OTHER, otherEpoch, 1).stream()
				.allMatch(packet -> packet.entries().isEmpty() && !packet.completeScan()), "another UUID cannot spend the departed request's global usage again");

			f.tick(PERIOD);
			assertEquals(2, f.state.reads, "the next period admits exactly one new slot, not a refund plus a new allowance");
			long admitted = f.previews(OWNER, epoch, 2).stream().filter(packet -> !packet.entries().isEmpty()).count();
			if (globalLimit) admitted += f.previews(OTHER, otherEpoch, 1).stream().filter(packet -> !packet.entries().isEmpty()).count();
			assertEquals(1, admitted);
		}
	}

	@Test
	void reconnectGetsANewEpochWithoutRefundingGlobalUsageOrAcceptingOldSessionPackets() {
		try (Fixture f = new Fixture(2)) {
			f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(8));
			f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(1));
			long oldEpoch = f.hello(OWNER, 0);
			f.open(OWNER, oldEpoch, 1, 0);
			f.tick(0);
			assertEquals(1, f.state.reads);
			f.backend.disconnect(OWNER);
			long disconnected = f.memory().retained();
			int releases = f.state.releases;
			f.backend.disconnect(OWNER);
			assertEquals(disconnected, f.memory().retained());
			assertEquals(releases, f.state.releases);
			assertEquals(0, f.state.liveSources);
			int afterDisconnect = f.host.sent.size();

			long epoch = f.hello(OWNER, 1);
			assertNotEquals(oldEpoch, epoch);
			f.open(OWNER, oldEpoch, 99, 1);
			assertEquals(1, f.host.opens, "an old epoch cannot bind a source or advance the new session's request fence");
			f.open(OWNER, epoch, 1, 1);
			f.closeRequest(OWNER, oldEpoch, 1, 1);
			f.tick(1);
			assertEquals(1, f.state.reads, "a reconnect is not new global period credit");
			assertFalse(f.complete(OWNER, epoch, 1));
			f.tick(PERIOD);
			assertEquals(2, f.state.reads);
			assertEquals(1, f.latestEntry(OWNER, epoch, 1).count(), "old CLOSE must not cancel the new request with the same ID");
			assertTrue(f.host.sent.subList(afterDisconnect, f.host.sent.size()).stream()
				.allMatch(delivery -> delivery.packet().epoch() == epoch), "no old preview or queued control crosses reconnect");
		}
	}

	@Test
	void memoryDeferKeepsTheOpenRequestAndRetriesWithoutEmptyCompletionOrSourceWork() {
		try (Fixture f = new Fixture(2)) {
			f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
			long epoch = f.hello(OWNER, 0);
			f.open(OWNER, epoch, 1, 0);
			long occupied = f.memory().remaining();
			try (RetainedMemoryLedger.Ticket pressure = f.memory().tryReserve(occupied).orElseThrow()) {
				pressure.commit(occupied);
				f.tick(0);
				f.tick(1);
				assertEquals(0, f.state.resolutions, "retained pressure defers before resolver or validation callbacks");
				assertEquals(0, f.state.validations);
				assertEquals(0, f.state.reads);
				assertTrue(f.previews(OWNER, epoch, 1).isEmpty(), "admission refusal is not an empty or terminal source result");
				assertEquals(0, f.memory().reserved());
			}
			f.tick(2);
			assertEquals(1, f.state.reads);
			assertFalse(f.complete(OWNER, epoch, 1));
			assertEquals(1, f.latestEntry(OWNER, epoch, 1).count());
			f.tick(PERIOD);
			assertTrue(f.complete(OWNER, epoch, 1));
			assertEquals(3, f.latestEntry(OWNER, epoch, 1).count());
			assertEquals(2, f.state.reads);
			assertEquals(1, f.host.opens, "the admitted request resumes without another OPEN or target binding");
		}
	}

	@Test
	void historicalUuidChurnReturnsToAnchoredCostAfterEachPaidPeriod() {
		try (Fixture f = new Fixture(1)) {
			f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
			long anchorEpoch = f.hello(OWNER, 0);
			f.open(OWNER, anchorEpoch, 1, 0);
			f.tick(0);
			assertTrue(f.complete(OWNER, anchorEpoch, 1));
			f.tick(PERIOD);
			long anchored = f.memory().retained();
			// Hundreds of historical identities, but only the anchor and one guest live at once.
			for (int index = 0; index < 300; index++) {
				UUID guest = new UUID(72, index);
				long now = (index + 1L) * PERIOD;
				long epoch = f.hello(guest, now);
				f.open(guest, epoch, 1, now);
				f.tick(now);
				assertTrue(f.complete(guest, epoch, 1), "historical UUIDs cannot consume live session or logical-subject capacity");
				assertEquals(1, f.latestEntry(guest, epoch, 1).count());
				f.backend.disconnect(guest);
				long afterDisconnect = f.memory().retained();
				int releases = f.state.releases;
				f.backend.disconnect(guest);
				assertEquals(afterDisconnect, f.memory().retained(), "duplicate disconnect cannot double-release retained tickets");
				assertEquals(releases, f.state.releases);
				assertEquals(1, f.state.liveSources, "the guest cannot release the anchor's source");
				f.tick(now + PERIOD);
				assertEquals(anchored, f.memory().retained(), "departed UUID accounting retires with its paid period, not with all peers");
				assertEquals(0, f.memory().reserved());
				f.host.sent.clear();
			}
			assertEquals(301, f.state.reads, "the anchor has no periodic preview capture; each guest pays its own owner-scoped sweep");
			f.backend.disconnect(OWNER);
			int resolutions = f.state.resolutions, validations = f.state.validations;
			f.tick(302L * PERIOD);
			assertEquals(0, f.memory().retained());
			assertEquals(0, f.state.liveSources);
			assertEquals(resolutions, f.state.resolutions);
			assertEquals(validations, f.state.validations);
		}
	}

	@NotNullByDefault
	private record Delivery(UUID recipient, InventoryS2CPacket packet) {}

	@NotNullByDefault
	private static final class Host implements InventoryBackend.Host {
		private final List<Delivery> sent = new ArrayList<>();
		private final InventoryBackend.Policy policy = new InventoryBackend.Policy(100, 1, Set.of("entity_block"));
		private int opens;

		@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return Optional.of(policy); }
		@Override public Optional<InventoryBackend.Opened> open(UUID player, Target requested) {
			opens++;
			return Optional.of(new InventoryBackend.Opened(requested, "entity_block", "attention"));
		}
		@Override public boolean annotationAllowed(InventorySourceInput input, String pingType) { return false; }
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			throw new AssertionError("preview lifecycle must not create a marker");
		}
		@Override public boolean knows(UUID player, MarkerId marker) { return false; }
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public int encodedBytes(InventoryS2CPacket packet) {
			return packet.encodedSize(InventoryS2CPacket.MAX_DISPLAY_BYTES, InventoryS2CPacket.MAX_FRAME_BYTES);
		}
		@Override public void send(UUID recipient, InventoryS2CPacket packet) {
			assertFalse(packet.isCorrupt());
			sent.add(new Delivery(recipient, packet));
		}
	}

	@NotNullByDefault
	private static final class State {
		private int resolutions, validations, reads, releases, liveSources;
	}

	@NotNullByDefault
	private static final class Source implements InventorySourceAccess.Source {
		private final InventorySourceInput input;
		private final State state;
		private final int slots, displayBytes;
		private boolean closed;

		private Source(InventorySourceInput input, State state, int slots, int displayBytes) {
			this.input = input; this.state = state; this.slots = slots; this.displayBytes = displayBytes;
			state.resolutions++; state.liveSources++;
		}
		@Override public SourceKey key() { return new SourceKey("test", "inventory", "lifecycle", input.viewKey()); }
		@Override public boolean valid() {
			assertFalse(closed, "released source evidence cannot be probed");
			state.validations++;
			return true;
		}
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return slots; }
		@Override public int displayBytes() { return displayBytes; }
		@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
		@Override public InventoryDomainCodec.Item read(int slot) {
			assertFalse(closed, "released source evidence cannot be read");
			assertTrue(slot >= 0 && slot < slots);
			state.reads++;
			return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), slot + 1L, "stone", null, false);
		}
		@Override public void close() {
			assertFalse(closed, "every resolved source has exactly one owner and one release");
			closed = true; state.releases++; state.liveSources--;
		}
	}

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		private final Host host = new Host();
		private final State state = new State();
		private final InventorySettings settings = InventorySettings.serverDefaults();
		private final InventoryBackend backend;

		private Fixture(int slots) {
			settings.setPendingMemoryMiB(64);
			settings.setPhysicalSlotsPerTick(IntLimit.finite(8));
			settings.getPreview().setPeriodTicks(PERIOD);
			settings.getTracking().setPeriodTicks(PERIOD);
			settings.getPreview().setMaxTargetsPerClient(IntLimit.finite(4));
			settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(8));
			settings.getPreview().setMaxSlotsServer(IntLimit.finite(16));
			settings.getPreview().setMaxVariantsPerClientPeriod(IntLimit.finite(8));
			settings.getPreview().getClientByteMultiplier().setUnlimited(true);
			settings.getPreview().getGlobalByteMultiplier().setUnlimited(true);
			backend = new InventoryBackend(host, new InventoryRuntime((input, displayBytes) ->
				Optional.of(new Source(input, state, slots, displayBytes)), settings.pendingMemoryBytes()), 1);
		}
		private RetainedMemoryLedger memory() { return backend.runtime().memory(); }
		private void tick(long now) {
			backend.tick(now, settings);
			assertEquals(0, memory().reserved(), "no admission workspace survives a backend tick");
		}
		private long hello(UUID player, long now) {
			int start = host.sent.size();
			backend.handle(player, InventoryC2SPacket.hello(), now, settings);
			return host.sent.subList(start, host.sent.size()).stream()
				.filter(delivery -> delivery.recipient().equals(player) && delivery.packet().kind() == InventoryS2CPacket.Kind.OFFER)
				.mapToLong(delivery -> delivery.packet().epoch()).findFirst().orElseThrow();
		}
		private void open(UUID player, long epoch, long request, long now) {
			backend.handle(player, InventoryC2SPacket.open(epoch, 100, 1, request, TARGET, BlockFace.NORTH), now, settings);
		}
		private void closeRequest(UUID player, long epoch, long request, long now) {
			backend.handle(player, InventoryC2SPacket.close(epoch, request).stamp(100, 1), now, settings);
		}
		private @Unmodifiable List<InventoryS2CPacket> previews(UUID player, long epoch, long request) {
			return host.sent.stream().filter(delivery -> delivery.recipient().equals(player))
				.map(Delivery::packet).filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW
					&& packet.epoch() == epoch && packet.requestId() == request).toList();
		}
		private boolean complete(UUID player, long epoch, long request) {
			return previews(player, epoch, request).stream().anyMatch(InventoryS2CPacket::completeScan);
		}
		private InventoryS2CPacket.Entry latestEntry(UUID player, long epoch, long request) {
			return previews(player, epoch, request).stream().filter(packet -> !packet.entries().isEmpty())
				.reduce((previous, next) -> next).orElseThrow().entries().getFirst();
		}
		@Override public void close() {
			backend.close();
			assertEquals(0, state.liveSources);
			assertEquals(state.resolutions, state.releases);
			assertEquals(0, memory().reserved());
			assertEquals(0, memory().retained());
		}
	}
}
