package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventoryPreviewServerTest {

	private static final UUID PLAYER = new UUID(1, 2);
	private static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	private static final InventoryScanner.Key STONE = new InventoryScanner.Key("minecraft:stone", "plain");
	private static final InventoryScanner.Key DIRT = new InventoryScanner.Key("minecraft:dirt", "plain");
	private static final InventoryScanner.Key SAND = new InventoryScanner.Key("minecraft:sand", "plain");
	private static InventoryC2SPacket openPacket(long epoch, long request) {
		return InventoryC2SPacket.open(epoch, 100, 1, request, TARGET, nx.pingwheel.common.domain.BlockFace.NORTH);
	}

	@Test
	void negotiationIsStableAndUnknownEpochsCannotAccessWorld() {
		Fixture f = new Fixture(stack(STONE, 5));
		f.server.handle(PLAYER, openPacket(9, 1), 0, f.settings);
		f.server.tick(0, f.settings);
		assertEquals(0, f.host.resolutions);
		long epoch = f.hello();
		assertTrue(epoch > 0);
		f.server.handle(PLAYER, openPacket(epoch + 1, 1), 0, f.settings);
		f.server.tick(1, f.settings);
		assertEquals(0, f.host.resolutions);
		f.server.handle(PLAYER, InventoryC2SPacket.hello(), 40, f.settings);
		assertEquals(epoch, f.host.packets.getLast().epoch());
	}

	@Test
	void openIsScheduledAndPermissionDenialNeverReadsSlots() {
		Fixture f = new Fixture(stack(STONE, 5));
		f.host.allowed = false;
		f.open(1);
		assertEquals(0, f.host.resolutions);
		f.server.tick(0, f.settings);
		assertEquals(0, f.host.reads);
		InventoryS2CPacket denial = f.data().getLast();
		assertEquals(InventoryS2CPacket.Status.UNAVAILABLE, denial.status());
		assertFalse(denial.completeScan());
		assertTrue(denial.entries().isEmpty());
	}

	@Test
	void duplicateRequestsReuseOnlyThisClientsPaidCoverage() throws ReflectiveOperationException {
		Fixture f = new Fixture(stack(STONE, 1), stack(DIRT, 2), stack(SAND, 3));
		f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(2));
		f.open(1);
		f.open(2);
		f.server.tick(0, f.settings);
		assertEquals(2, f.host.reads);
		assertEquals(2, f.data().stream().filter(packet -> packet.requestId() == 2).flatMap(packet -> packet.entries().stream()).count());
		assertEquals(2, field(f.server, "globalSlots"), "duplicate delivery consumes only one client's logical prefix");
		UUID other = new UUID(3, 4);
		f.open(other, 3, 0);
		f.server.tick(0, f.settings);
		assertEquals(2, f.items(3).size());
		assertEquals(4, field(f.server, "globalSlots"), "a different client independently pays for the same physical slots");
		f.server.tick(3, f.settings);
		assertEquals(3, f.host.reads);
		assertTrue(f.complete(1));
		assertTrue(f.complete(2));
	}

	@Test
	void differentClientsPayIndependentLogicalProgressForASharedRead() {
		Fixture f = new Fixture(stack(STONE, 1), stack(DIRT, 2), stack(SAND, 3));
		f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(2));
		f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(3));
		f.open(1);
		UUID other = new UUID(3, 4);
		f.server.handle(other, InventoryC2SPacket.hello(), 0, f.settings);
		long otherEpoch = f.host.packets.getLast().epoch();
		f.server.handle(other, openPacket(otherEpoch, 2), 0, f.settings);
		f.server.tick(0, f.settings);
		assertEquals(2, f.host.reads);
		assertEquals(3, f.items().size(), "global logical limit includes both clients' independent progress");
		assertFalse(f.complete(1));
		assertFalse(f.complete(2));
		f.server.tick(3, f.settings);
		assertEquals(3, f.host.reads, "three physical reads serve six charged logical slots");
		assertEquals(6, f.items().size());
		assertTrue(f.complete(1));
		assertTrue(f.complete(2));
	}

	@Test
	void duplicateCloseAndReopenPreserveOnlyRemainingRequestsPaidCoverage() throws ReflectiveOperationException {
		Fixture f = new Fixture(stack(STONE, 1), stack(DIRT, 2), stack(SAND, 3));
		f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(1));
		long epoch = f.open(1);
		f.open(2);
		UUID other = new UUID(3, 4);
		f.open(other, 99, 0);
		f.server.tick(0, f.settings);
		assertEquals(1, f.items(1).size());
		assertEquals(1, f.items(2).size());
		assertEquals(2, progressSubjects(f.server));

		f.server.handle(PLAYER, InventoryC2SPacket.close(epoch, 1), 1, f.settings);
		long afterClose = retainedBytes(f.server);
		f.server.handle(PLAYER, InventoryC2SPacket.close(epoch, 1), 1, f.settings);
		assertEquals(afterClose, retainedBytes(f.server), "duplicate close releases nothing twice");
		f.server.handle(PLAYER, openPacket(epoch, 3), 1, f.settings);
		f.server.tick(1, f.settings);
		assertEquals(1, f.items(3).size(), "the remaining duplicate keeps this client's paid prefix");
		assertEquals(1, f.host.reads);
		assertEquals(2, progressSubjects(f.server));

		f.server.handle(PLAYER, InventoryC2SPacket.close(epoch, 2), 1, f.settings);
		f.server.handle(PLAYER, InventoryC2SPacket.close(epoch, 3), 1, f.settings);
		assertEquals(1, progressSubjects(f.server), "another client keeps the round, not the departing client's claim");
		f.server.handle(PLAYER, openPacket(epoch, 4), 1, f.settings);
		f.server.tick(1, f.settings);
		assertTrue(f.items(4).isEmpty(), "rejoining cannot reuse a retired prefix or refund period slots");
		assertTrue(f.items(99).isEmpty(), "retiring a claim does not refund the global slot charge");
		assertEquals(1, f.host.reads);
	}

	@Test
	void disconnectAndReconnectDoNotRefundGlobalPeriodProgress() throws ReflectiveOperationException {
		Fixture f = new Fixture(stack(STONE, 1), stack(DIRT, 2));
		f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(1));
		f.open(1);
		UUID other = new UUID(3, 4);
		f.open(other, 2, 0);
		f.server.tick(0, f.settings);
		f.server.disconnect(PLAYER);
		assertEquals(1, progressSubjects(f.server));
		f.open(PLAYER, 3, 1);
		f.server.tick(1, f.settings);
		assertTrue(f.items(2).isEmpty());
		assertTrue(f.items(3).isEmpty(), "a fresh session cannot spend already-charged global slots again");
		assertEquals(1, f.host.reads, "the other client keeps the physical observation alive");
	}

	@Test
	void clientProgressOccupiesMemoryOnceAndLastClientRequestReleasesIt() throws ReflectiveOperationException {
		Fixture f = new Fixture(stack(STONE, 1), stack(DIRT, 2));
		f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(1));
		f.open(1);
		f.server.tick(0, f.settings);
		long anchorMemory = retainedBytes(f.server);
		UUID other = new UUID(3, 4);
		long otherEpoch = f.open(other, 2, 0);
		long beforeAttach = retainedBytes(f.server);
		f.server.tick(0, f.settings);
		long progressCost = retainedBytes(f.server) - beforeAttach;
		assertTrue(progressCost > 0, "even quota-deferred recipient progress is retained and accounted");
		assertTrue(f.items(2).isEmpty());
		assertEquals(2, progressSubjects(f.server));

		long beforeOpen = retainedBytes(f.server);
		f.server.handle(other, openPacket(otherEpoch, 3), 0, f.settings);
		long requestCost = retainedBytes(f.server) - beforeOpen;
		f.server.tick(0, f.settings);
		assertEquals(beforeOpen + requestCost, retainedBytes(f.server), "duplicates share the same progress allocation");
		f.server.handle(other, InventoryC2SPacket.close(otherEpoch, 2), 0, f.settings);
		assertEquals(beforeOpen, retainedBytes(f.server), "closing one duplicate leaves the shared progress allocated");
		assertEquals(2, progressSubjects(f.server));
		f.server.handle(other, InventoryC2SPacket.close(otherEpoch, 3), 0, f.settings);
		assertEquals(beforeOpen - requestCost - progressCost, retainedBytes(f.server));
		assertEquals(1, progressSubjects(f.server));
		f.server.disconnect(other);
		assertEquals(anchorMemory, retainedBytes(f.server));
		f.server.reset();
		assertEquals(0, retainedBytes(f.server));
		assertEquals(0, progressSubjects(f.server));
		assertTrue(rounds(f.server).isEmpty());
	}

	@Test
	void memoryPressureDefersNewClientProgressUntilRoomIsReleased() throws ReflectiveOperationException {
		Fixture f = new Fixture(stack(STONE, 1));
		f.settings.setPendingMemoryMiB(1);
		f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(1));
		f.settings.getPreview().getClientByteMultiplier().setUnlimited(true);
		f.settings.getPreview().getGlobalByteMultiplier().setUnlimited(true);
		f.open(1);
		f.open(new UUID(3, 4), 2, 0);
		f.server.tick(0, f.settings);
		UUID pending = new UUID(5, 6);
		f.open(pending, 3, 0);
		List<UUID> padding = new ArrayList<>();
		for (int i = 0; i < 2048; i++) {
			UUID player = new UUID(100, i);
			int packets = f.host.packets.size();
			f.server.handle(player, InventoryC2SPacket.hello(), 0, f.settings);
			if (f.host.packets.size() == packets) break;
			padding.add(player);
		}
		assertFalse(padding.isEmpty());
		long fullMemory = retainedBytes(f.server);
		f.server.tick(0, f.settings);
		assertEquals(2, progressSubjects(f.server), "no new claim may allocate beyond pending-memory admission");
		assertEquals(fullMemory, retainedBytes(f.server));
		assertTrue(f.items(3).isEmpty());
		assertEquals(1, f.host.reads);

		f.server.disconnect(padding.getLast());
		long availableMemory = retainedBytes(f.server);
		f.server.tick(0, f.settings);
		assertEquals(3, progressSubjects(f.server));
		assertTrue(retainedBytes(f.server) > availableMemory);
		padding.forEach(f.server::disconnect); // A scanned entry needs its own independent retained-memory room.
		f.server.tick(3, f.settings);
		f.server.tick(6, f.settings);
		assertTrue(f.complete(3), "a deferred claim can resume once retained memory is available");
		assertEquals(1, f.host.reads, "admission recovery uses the existing physical observation");
		f.server.reset();
		assertEquals(0, retainedBytes(f.server));
	}

	@Test
	void disconnectedClientsDoNotAccumulateClaimsOnAnAnchoredRound() throws ReflectiveOperationException {
		Fixture f = new Fixture(stack(STONE, 1));
		f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(2));
		f.open(1);
		f.server.tick(0, f.settings);
		long anchorMemory = retainedBytes(f.server);
		// More distinct historical clients than the concurrency cap, with only two connected at once.
		for (int i = 0; i < 1100; i++) {
			UUID player = new UUID(100, i);
			long tick = 3L * (i + 1);
			f.open(player, i + 2, tick);
			f.server.tick(tick, f.settings);
			assertTrue(f.complete(i + 2));
			assertEquals(2, progressSubjects(f.server));
			f.server.disconnect(player);
			f.server.disconnect(player);
			assertEquals(1, progressSubjects(f.server), "departed UUIDs must not remain on the shared round");
			assertEquals(anchorMemory, retainedBytes(f.server), "churn returns to the anchor's retained cost");
			f.host.packets.clear();
		}
		assertEquals(1, f.host.reads, "all independent clients still share one physical slot read");
		f.server.disconnect(PLAYER);
		assertEquals(0, retainedBytes(f.server));
		assertEquals(0, progressSubjects(f.server));
		assertTrue(rounds(f.server).isEmpty());
		int probes = f.host.resolutions;
		f.server.tick(3303, f.settings);
		assertEquals(probes, f.host.resolutions);
	}

	@Test
	void invalidatingOneClientDoesNotRetireAnotherClientsPaidCoverage() throws ReflectiveOperationException {
		Fixture f = new Fixture(stack(STONE, 1), stack(DIRT, 2));
		f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		f.settings.getPreview().setMaxSlotsServer(IntLimit.finite(2));
		long epoch = f.open(1);
		UUID other = new UUID(3, 4);
		f.open(other, 2, 0);
		f.server.tick(0, f.settings);
		assertEquals(2, progressSubjects(f.server));
		f.host.deniedPlayer = other;
		f.server.tick(1, f.settings);
		assertEquals(1, progressSubjects(f.server));
		assertEquals(InventoryS2CPacket.Status.INVALID,
			f.data().stream().filter(packet -> packet.requestId() == 2).toList().getLast().status());
		f.server.handle(PLAYER, openPacket(epoch, 3), 1, f.settings);
		f.server.tick(1, f.settings);
		assertEquals(1, f.items(3).size());
		assertEquals(1, f.host.reads);
		f.host.allowed = false;
		f.server.tick(1, f.settings);
		assertEquals(0, progressSubjects(f.server));
		assertTrue(rounds(f.server).isEmpty());
		f.server.reset();
		assertEquals(0, retainedBytes(f.server));
	}

	@Test
	void completionWaitsForEveryPendingVariantToBeSent() {
		Fixture f = new Fixture(stack(STONE, 1), stack(DIRT, 2), stack(SAND, 3));
		f.settings.getPreview().setMaxVariantsPerClientPeriod(IntLimit.finite(1));
		f.open(1);
		f.server.tick(0, f.settings);
		assertEquals(3, f.host.reads);
		assertEquals(1, f.items().size());
		assertFalse(f.complete(1));
		f.server.tick(1, f.settings);
		assertEquals(1, f.items().size());
		f.server.tick(3, f.settings);
		assertEquals(2, f.items().size());
		assertFalse(f.complete(1));
		f.server.tick(6, f.settings);
		assertEquals(3, f.items().size());
		assertTrue(f.complete(1));
		assertEquals(3, f.host.reads, "fragment delivery must not rescan the source");
	}

	@Test
	void invalidationWithdrawsOldQueuedValuesBeforeItsControl() {
		Fixture f = new Fixture(stack(STONE, 1), stack(DIRT, 2));
		f.settings.getPreview().setMaxVariantsPerClientPeriod(IntLimit.finite(1));
		f.open(1);
		f.server.tick(0, f.settings);
		int delivered = f.items().size();
		f.host.allowed = false;
		f.server.tick(1, f.settings);
		assertEquals(delivered, f.items().size());
		assertEquals(InventoryS2CPacket.Status.INVALID, f.data().getLast().status());
		assertFalse(f.data().getLast().completeScan());
		f.host.allowed = true;
		f.server.tick(3, f.settings);
		assertEquals(delivered, f.items().size());
	}

	@Test
	void closingOneConsumerDoesNotStopAnotherAndLastCloseStopsProbes() {
		Fixture f = new Fixture(stack(STONE, 1), stack(DIRT, 2));
		f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		long epoch = f.open(1);
		f.open(2);
		f.server.tick(0, f.settings);
		f.server.handle(PLAYER, InventoryC2SPacket.close(epoch, 1), 1, f.settings);
		f.server.tick(3, f.settings);
		f.server.tick(6, f.settings);
		assertTrue(f.complete(2));
		assertEquals(2, f.host.reads);
		f.server.handle(PLAYER, InventoryC2SPacket.close(epoch, 2), 6, f.settings);
		int probes = f.host.resolutions;
		f.server.tick(9, f.settings);
		assertEquals(probes, f.host.resolutions);
	}

	@Test
	void reopeningDoesNotReplenishClientPeriodQuota() {
		Fixture f = new Fixture(stack(STONE, 1), stack(DIRT, 2));
		f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		long epoch = f.open(1);
		f.server.tick(0, f.settings);
		f.server.handle(PLAYER, InventoryC2SPacket.close(epoch, 1), 1, f.settings);
		f.server.handle(PLAYER, openPacket(epoch, 2), 1, f.settings);
		f.server.tick(1, f.settings);
		assertEquals(1, f.host.reads);
		f.server.tick(3, f.settings);
		assertEquals(2, f.host.reads);
	}

	@Test
	void resyncContinuesAnAdmittedSweepAndDisconnectReleasesIt() {
		Fixture f = new Fixture(stack(STONE, 1), stack(DIRT, 2));
		f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		long epoch = f.open(1);
		f.server.tick(0, f.settings);
		f.server.handle(PLAYER, InventoryC2SPacket.resync(epoch, 1), 1, f.settings);
		f.server.tick(3, f.settings);
		assertEquals(2, f.host.reads);
		assertTrue(f.complete(1));
		f.server.disconnect(PLAYER);
		int probes = f.host.resolutions;
		f.server.tick(6, f.settings);
		assertEquals(probes, f.host.resolutions);
	}

	@Test
	void completeEmptyObservationIsDistinctFromDeniedSource() {
		Fixture f = new Fixture();
		f.open(1);
		f.server.tick(0, f.settings);
		assertEquals(0, f.host.reads);
		assertTrue(f.complete(1));
		assertTrue(f.items().isEmpty());
		assertEquals(InventoryS2CPacket.Status.UNCERTAIN, f.data().getLast().status());
	}

	@Test
	void exactLongCountsSurvivePublication() {
		long count = (1L << 53) + 19;
		Fixture f = new Fixture(stack(STONE, count));
		f.open(1);
		f.server.tick(0, f.settings);
		assertEquals(count, f.items().getFirst().count());
	}

	@Test
	void unavailableReadCannotPublishZerosOrPriorPartialCounts() {
		Fixture f = new Fixture(stack(STONE, 2), stack(DIRT, 3));
		f.host.failAt = 1;
		f.open(1);
		f.server.tick(0, f.settings);
		assertTrue(f.items().isEmpty());
		assertEquals(InventoryS2CPacket.Status.UNAVAILABLE, f.data().getLast().status());
		assertFalse(f.complete(1));
	}

	@Test
	void unsupportedSelectionCannotTriggerSourceWork() {
		Fixture f = new Fixture(stack(STONE, 2));
		long epoch = f.hello();
		f.server.handle(PLAYER, InventoryC2SPacket.select(epoch, 99, "forged", "minecraft:stone", "attention"), 0, f.settings);
		f.server.tick(0, f.settings);
		assertEquals(0, f.host.resolutions);
		assertEquals(0, f.host.reads);
	}

	private static InventoryScanner.Stack stack(InventoryScanner.Key key, long count) {
		return new InventoryScanner.Stack(key, count);
	}

	// Narrow ledger/lifetime probes: publication alone cannot detect retained departed UUIDs.
	private static Object field(Object owner, String name) throws ReflectiveOperationException {
		var field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static long retainedBytes(InventoryPreviewServer server) throws ReflectiveOperationException {
		return (long) field(server, "retainedBytes");
	}

	private static Map<?, ?> rounds(InventoryPreviewServer server) throws ReflectiveOperationException {
		return (Map<?, ?>) field(server, "rounds");
	}

	private static int progressSubjects(InventoryPreviewServer server) throws ReflectiveOperationException {
		int subjects = 0;
		for (Object session : ((Map<?, ?>) field(server, "sessions")).values()) {
			subjects += ((Map<?, ?>) field(session, "claimedProgress")).size();
		}
		return subjects;
	}

	private static final class Fixture {
		final FakeHost host;
		final InventoryPreviewServer server;
		final InventorySettings settings = InventorySettings.serverDefaults();
		long epoch;
		Fixture(InventoryScanner.Stack... values) {
			host = new FakeHost(List.of(values));
			server = new InventoryPreviewServer(host, 7);
			settings.getPreview().setPeriodTicks(3);
			settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(12));
			settings.getPreview().setMaxSlotsServer(IntLimit.finite(24));
			settings.setPhysicalSlotsPerTick(IntLimit.finite(12));
		}
		long hello() {
			if (epoch == 0) {
				server.handle(PLAYER, InventoryC2SPacket.hello(), 0, settings);
				epoch = host.packets.getFirst().epoch();
			}
			return epoch;
		}
		long open(long id) {
			long epoch = hello();
			server.handle(PLAYER, openPacket(epoch, id), 0, settings);
			return epoch;
		}
		long open(UUID player, long id, long tick) {
			int before = host.packets.size();
			server.handle(player, InventoryC2SPacket.hello(), tick, settings);
			assertTrue(host.packets.size() > before, "fixture client must receive an offer");
			long epoch = host.packets.getLast().epoch();
			server.handle(player, openPacket(epoch, id), tick, settings);
			return epoch;
		}
		List<InventoryS2CPacket> data() {
			return host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW).toList();
		}
		List<InventoryS2CPacket.Entry> items() { return data().stream().flatMap(packet -> packet.entries().stream()).toList(); }
		List<InventoryS2CPacket.Entry> items(long id) {
			return data().stream().filter(packet -> packet.requestId() == id).flatMap(packet -> packet.entries().stream()).toList();
		}
		boolean complete(long id) { return data().stream().anyMatch(packet -> packet.requestId() == id && packet.completeScan()); }
	}

	private static final class FakeHost implements InventoryPreviewServer.Host {
		final List<InventoryScanner.Stack> values;
		final List<InventoryS2CPacket> packets = new ArrayList<>();
		int reads, resolutions;
		int failAt = -1;
		boolean allowed = true;
		UUID deniedPlayer;
		FakeHost(List<InventoryScanner.Stack> values) { this.values = values; }
		@Override public Optional<InventoryPreviewServer.Resolved> resolve(UUID player, Target target) {
			resolutions++;
			if (!allowed || player.equals(deniedPlayer)) return Optional.empty();
			InventoryScanner.Source source = new InventoryScanner.Source() {
				@Override public int slots() { return values.size(); }
				@Override public boolean stableCursor() { return true; }
				@Override public boolean stableSnapshot() { return false; }
				@Override public InventoryScanner.Stack read(int slot) {
					reads++;
					if (slot == failAt) throw new IllegalStateException("source became unreadable");
					return values.get(slot);
				}
			};
			return Optional.of(new InventoryPreviewServer.Resolved(new SourceKey("fixture", "inventory", "chest", "public"), source,
				key -> new InventoryPreviewServer.Display(key.itemId(), key.itemId(), null, false)));
		}
		@Override public int encodedBytes(InventoryS2CPacket packet) {
			FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
			try { packet.write(buffer); return buffer.readableBytes(); }
			finally { buffer.release(); }
		}
		@Override public void send(UUID player, InventoryS2CPacket packet) { packets.add(packet); }
	}
}
