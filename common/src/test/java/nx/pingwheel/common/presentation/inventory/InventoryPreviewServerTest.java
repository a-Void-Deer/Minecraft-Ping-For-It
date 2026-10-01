package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
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
	private static final Target TARGET = new Target.LocationTarget("minecraft:overworld", 1, 2, 3);
	private static final InventoryScanner.Key STONE = new InventoryScanner.Key("minecraft:stone", "plain");
	private static final InventoryScanner.Key DIRT = new InventoryScanner.Key("minecraft:dirt", "plain");
	private static final InventoryScanner.Key SAND = new InventoryScanner.Key("minecraft:sand", "plain");

	@Test
	void negotiationIsStableAndUnknownEpochsCannotAccessWorld() {
		Fixture f = new Fixture(stack(STONE, 5));
		f.server.handle(PLAYER, InventoryC2SPacket.open(9, 1, TARGET), 0, f.settings);
		f.server.tick(0, f.settings);
		assertEquals(0, f.host.resolutions);
		long epoch = f.hello();
		assertTrue(epoch > 0);
		f.server.handle(PLAYER, InventoryC2SPacket.open(epoch + 1, 1, TARGET), 0, f.settings);
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
	void sharedPhysicalObservationStillChargesEachLogicalConsumer() {
		Fixture f = new Fixture(stack(STONE, 1), stack(DIRT, 2), stack(SAND, 3));
		f.settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(2));
		f.open(1);
		f.open(2);
		f.server.tick(0, f.settings);
		assertEquals(2, f.host.reads);
		assertFalse(f.data().stream().anyMatch(packet -> packet.requestId() == 2 && !packet.entries().isEmpty()));
		f.server.tick(3, f.settings);
		assertEquals(3, f.host.reads);
		assertFalse(f.complete(2));
		f.server.tick(6, f.settings);
		assertEquals(3, f.host.reads, "both consumers share the same three physical reads");
		assertTrue(f.complete(1));
		assertTrue(f.complete(2));
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
		f.server.handle(PLAYER, InventoryC2SPacket.open(epoch, 2, TARGET), 1, f.settings);
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
			server.handle(PLAYER, InventoryC2SPacket.open(epoch, id, TARGET), 0, settings);
			return epoch;
		}
		List<InventoryS2CPacket> data() {
			return host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW).toList();
		}
		List<InventoryS2CPacket.Entry> items() { return data().stream().flatMap(packet -> packet.entries().stream()).toList(); }
		boolean complete(long id) { return data().stream().anyMatch(packet -> packet.requestId() == id && packet.completeScan()); }
	}

	private static final class FakeHost implements InventoryPreviewServer.Host {
		final List<InventoryScanner.Stack> values;
		final List<InventoryS2CPacket> packets = new ArrayList<>();
		int reads, resolutions;
		int failAt = -1;
		boolean allowed = true;
		FakeHost(List<InventoryScanner.Stack> values) { this.values = values; }
		@Override public Optional<InventoryPreviewServer.Resolved> resolve(UUID player, Target target) {
			resolutions++;
			if (!allowed) return Optional.empty();
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
