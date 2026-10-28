package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.config.ByteMultiplier;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Replays the production publisher's ordered wire frames, not hand-ordered status/data fixtures. */
class InventoryPublicationIntegrationTest {
	@Test void genericEventualEmptyAndAbsentZeroStayUncertainWhileExplicitAtomicAdapterKeepsReady() {
		for (boolean atomic : new boolean[] {false, true}) {
			var host = new InventoryBackendTest.Host(); var settings = InventorySettings.serverDefaults();
			var reads = new java.util.concurrent.atomic.AtomicInteger(); boolean[] empty = {false};
			try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 1) {
				@Override public InventoryDomainCodec.@org.jetbrains.annotations.Nullable Item read(int slot) {
					reads.incrementAndGet(); return empty[0] ? null : InventoryRuntimeTest.item(7);
				}
				@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
					if (!atomic) return Optional.empty();
					return Optional.of(new InventorySourceAccess.SnapshotPlan() {
						@Override public long memoryUpperBoundBytes() { return 4096; }
						@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
							boolean capturedEmpty = empty[0];
							return Optional.of(new InventoryRuntimeTest.Snapshot() {
								@Override public int slots() { return 1; }
								@Override public InventoryDomainCodec.@org.jetbrains.annotations.Nullable Item read(int slot) { return capturedEmpty ? null : InventoryRuntimeTest.item(7); }
								@Override public long retainedBytes() { return 128; }
								@Override public InventorySourceAccess.SnapshotEvidence evidence() { return InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED; }
							});
						}
					});
				}
			}), settings.pendingMemoryBytes()), 1)) {
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings); long epoch = host.epoch(InventoryBackendTest.A);
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(epoch, 100, 1, 1, InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH), 0, settings);
				backend.tick(0, settings); var preview = host.packets.stream().filter(packet -> !packet.entries().isEmpty()).findFirst().orElseThrow();
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.select(epoch, 100, 1, 1, 1, preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger"), 1, settings);
				var marker = host.store.allMarkers().getFirst(); var endpoint = new Endpoint(InventoryBackendTest.A, MarkerSnapshot.from(marker));
				empty[0] = true; backend.tick(2, settings); endpoint.drain(host);
				var expected = atomic ? InventoryS2CPacket.Status.READY : InventoryS2CPacket.Status.UNCERTAIN;
				var tracked = endpoint.client.tracking(marker.id()); assertTrue(tracked.complete()); assertEquals(expected, tracked.status());
				assertEquals(0, tracked.entries().getFirst().count()); assertEquals(expected, tracked.entries().getFirst().quality());
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(epoch, 100, 1, 2, InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH), 3, settings); backend.tick(3, settings);
				var emptyPreview = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && packet.requestId() == 2).reduce((a, b) -> b).orElseThrow();
				assertEquals(expected, emptyPreview.status()); assertTrue(emptyPreview.completeScan()); assertTrue(emptyPreview.entries().isEmpty());
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.resync(epoch, 0, marker.id()).stamp(100, 1), 4, settings); backend.tick(4, settings); endpoint.drain(host);
				assertEquals(expected, endpoint.client.tracking(marker.id()).status()); assertEquals(expected, endpoint.client.tracking(marker.id()).entries().getFirst().quality());
			}
		}
	}

	@Test void actualCanonicalRefusalRetiresExactSelectionAndKeepsTheAuthoritativeLongGroupTotal() {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
		var catalog = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.Catalog(net.minecraft.core.RegistryAccess.EMPTY);
		var rejected = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
		var payload = new net.minecraft.nbt.CompoundTag(); payload.putString("data", "\u0800".repeat(23000));
		rejected.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(payload));
		var host = new InventoryBackendTest.Host(); var settings = InventorySettings.serverDefaults();
		settings.getPreview().setPeriodTicks(1); settings.setPhysicalSlotsPerTick(nx.pingwheel.common.config.IntLimit.finite(1));
		var reads = new java.util.concurrent.atomic.AtomicInteger(); long firstAmount = 9_007_199_254_740_993L;
		try (var logs = new InventoryDiagnosticsTestCapture(); var backend = new InventoryBackend(host,
			new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 2) {
				@Override public InventoryDomainCodec.Item read(int slot) {
					reads.incrementAndGet();
					var encoded = catalog.encode(slot == 0 ? new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE) : rejected);
					var display = encoded.display();
					return new InventoryDomainCodec.Item(encoded.key(), slot == 0 ? firstAmount : 7, display.label(), display.displayJson(), display.aggregateRequired());
				}
			}), 16_000_000), 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			long epoch = host.epoch(InventoryBackendTest.A);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(epoch, 100, 1, 1, InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH), 0, settings);
			backend.tick(0, settings);
			var old = host.packets.stream().filter(packet -> !packet.entries().isEmpty()).findFirst().orElseThrow();
			assertFalse(old.entries().getFirst().fallback());
			backend.tick(1, settings);
			var folded = host.packets.stream().filter(packet -> packet.entries().stream().anyMatch(InventoryS2CPacket.Entry::fallback)).reduce((a, b) -> b).orElseThrow();
			assertEquals(1, folded.entries().size()); assertEquals(firstAmount + 7, folded.entries().getFirst().count());
			assertNotEquals(old.entries().getFirst().key(), folded.entries().getFirst().key());
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.select(epoch, 100, 1, 1, 1, old.baselineId(), old.statusRevision(), old.entries().getFirst().key(), "danger"), 2, settings);
			assertEquals(0, host.store.size()); assertEquals(2, reads.get(), "a retired exact reference is rejected before a new witness");
			assertTrue(host.packets.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.REJECT));
			assertEquals(1, logs.events().size()); assertTrue(logs.messages().contains("canonical byte bound=65536"), logs.messages());
			assertNull(logs.events().getFirst().getThrown());
		}
	}

	static InventoryS2CPacket wire(InventoryS2CPacket packet) {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(buf); var decoded = InventoryS2CPacket.readSafe(buf);
			assertFalse(decoded.isCorrupt()); assertFalse(buf.isReadable()); return decoded;
		} finally { buf.release(); }
	}
	static final class Endpoint {
		final List<IPacket> requests = new ArrayList<>();
		final ClientInventory client = new ClientInventory(requests::add);
		final UUID recipient;
		int cursor;
		Endpoint(UUID recipient, MarkerSnapshot marker) {
			this.recipient = recipient; client.presentationReset(100, 1); client.markerCreated(marker);
		}
		void drain(InventoryBackendTest.Host host) { drain(host, p -> true); }
		void drain(InventoryBackendTest.Host host, java.util.function.Predicate<InventoryS2CPacket> applied) {
			for (; cursor < host.packets.size(); cursor++) if (host.recipients.get(cursor).equals(recipient)) {
				var decoded = wire(host.packets.get(cursor)); if (applied.test(decoded)) client.accept(decoded);
			}
		}
		void tickTo(int from, int to) { for (int i = from; i < to; i++) client.tick(true); }
		List<InventoryC2SPacket> repairs() { return requests.stream().filter(p -> p instanceof InventoryC2SPacket i && i.kind() == InventoryC2SPacket.Kind.RESYNC).map(p -> (InventoryC2SPacket) p).toList(); }
	}
	static void count(ClientInventory client, MarkerId marker, long expected) {
		assertTrue(client.tracking(marker).complete()); assertFalse(client.tracking(marker).grey());
		assertEquals(1, client.tracking(marker).entries().size()); assertEquals(expected, client.tracking(marker).entries().getFirst().count());
	}
	@Test void firstTrackingIdentityRefusalReportsIncompleteAcrossFailedSweepsThenCompletesOnlySuccessfulEvidence() {
		for (boolean overflow : List.of(false, true)) {
			class Host extends InventoryBackendTest.Host {
				boolean refuseFailureControl = true;
				@Override public void send(UUID recipient, InventoryS2CPacket packet) {
					if (refuseFailureControl && packet.kind() == InventoryS2CPacket.Kind.STATUS && packet.status() == InventoryS2CPacket.Status.INCOMPLETE)
						throw new IllegalStateException("failure control backpressure");
					super.send(recipient, packet);
				}
			}
			var host = new Host(); var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(64);
			var reads = new java.util.concurrent.atomic.AtomicInteger(); int[] phase = {0};
			try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> {
				int observation = phase[0];
				return Optional.of(new InventoryRuntimeTest.Source(input, reads, 2) {
					@Override public boolean stableCursor() { return observation != 1 || overflow; }
					@Override public InventoryDomainCodec.Item read(int slot) {
						reads.incrementAndGet();
						if (observation == 0) return slot == 0 ? InventoryRuntimeTest.item(7) : null;
						long amount = observation == 1 ? (slot == 0 ? (overflow ? Long.MAX_VALUE : 5) : 1) : (slot == 0 ? 9 : 4);
						return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", slot == 0 ? "" : "safe"),
							amount, "stone", null, slot == 0);
					}
					@Override public InventorySourceAccess.Page enumerate(int limit) {
						var values = new ArrayList<InventoryDomainCodec.Item>();
						for (int slot = 0; slot < Math.min(limit, 2); slot++) values.add(read(slot));
						return new InventorySourceAccess.Page(values, observation != 1 && limit >= 2);
					}
				});
			}, settings.pendingMemoryBytes()), 1)) {
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings); long epoch = host.epoch(InventoryBackendTest.A);
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(epoch, 100, 1, 1, InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH), 0, settings); backend.tick(0, settings);
				var preview = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && !packet.entries().isEmpty()).findFirst().orElseThrow();
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.select(epoch, 100, 1, 1, 1, preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger"), 1, settings);
				var marker = host.store.allMarkers().getFirst(); var endpoint = new Endpoint(InventoryBackendTest.A, MarkerSnapshot.from(marker));
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.close(epoch, 1).stamp(100, 1), 1, settings);
				phase[0] = 1; InventoryPublicationTicks.withoutScanSlots(backend, 1, settings);
				int start = host.packets.size(); backend.tick(2, settings); endpoint.drain(host);
				var fence = host.packets.stream().filter(packet -> marker.id().equals(packet.markerId()) && packet.kind() == InventoryS2CPacket.Kind.STATUS).findFirst().orElseThrow();
				assertEquals(InventoryS2CPacket.Status.UPDATING, fence.status()); assertEquals(0, fence.watermark());
				assertFalse(endpoint.client.tracking(marker.id()).complete());
				host.refuseFailureControl = false;
				for (int tick : new int[] {3, 5, 8}) {
					int before = host.packets.size(); backend.tick(tick, settings); endpoint.drain(host);
					assertEquals(InventoryS2CPacket.Status.INCOMPLETE, endpoint.client.tracking(marker.id()).status());
					assertFalse(endpoint.client.tracking(marker.id()).complete()); assertEquals(7, endpoint.client.tracking(marker.id()).entries().getFirst().count());
					assertTrue(host.packets.subList(before, host.packets.size()).stream().anyMatch(packet -> marker.id().equals(packet.markerId()) && packet.status() == InventoryS2CPacket.Status.INCOMPLETE));
				}
				assertTrue(host.packets.subList(start, host.packets.size()).stream().filter(packet -> marker.id().equals(packet.markerId()))
					.allMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.STATUS && packet.entries().isEmpty() && packet.watermark() == 0 && packet.checksum() == 0), "failure never publishes partial quantities or a completed digest");
				phase[0] = 2; backend.tick(11, settings); endpoint.drain(host);
				var recovered = endpoint.client.tracking(marker.id()); assertTrue(recovered.complete()); assertEquals(1, recovered.entries().size());
				assertTrue(recovered.entries().getFirst().fallback()); assertEquals(13, recovered.entries().getFirst().count());
				assertTrue(recovered.baselineId() > fence.baselineId()); assertEquals(fence.statusRevision(), recovered.statusRevision());
				assertEquals(1, host.store.size()); assertEquals(80, marker.expiresAtTick());
			}
		}
	}

	@Test void historicalFoldChurnStopsAtTheRequestCategoryBoundWithoutReusingRetiredTokens() throws Exception {
		var host = new InventoryBackendTest.Host(); var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(64);
		var unlimitedBytes = ByteMultiplier.one(); unlimitedBytes.setUnlimited(true);
		settings.getPreview().setClientByteMultiplier(unlimitedBytes); settings.getPreview().setGlobalByteMultiplier(unlimitedBytes);
		settings.getPreview().setPeriodTicks(1); settings.setPhysicalSlotsPerTick(nx.pingwheel.common.config.IntLimit.finite(1));
		var unlimited = nx.pingwheel.common.config.IntLimit.unlimited(1024);
		settings.getPreview().setMaxVariantsPerClientPeriod(unlimited); settings.getPreview().setMaxSlotsPerClient(unlimited); settings.getPreview().setMaxSlotsServer(unlimited);
		var reads = new java.util.concurrent.atomic.AtomicInteger();
		try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 260) {
			@Override public InventoryDomainCodec.Item read(int slot) {
				reads.incrementAndGet(); boolean aggregate = slot % 2 != 0;
				return new InventoryDomainCodec.Item(new InventoryScanner.Key("test:group_" + slot / 2, aggregate ? "" : "aggregate"), 1, "group", null, aggregate);
			}
		}), settings.pendingMemoryBytes()), 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			var requests = new ArrayList<IPacket>(); var client = new ClientInventory(requests::add); client.presentationReset(100, 1);
			host.packets.forEach(packet -> client.accept(wire(packet))); client.open(InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH, "entity_block");
			backend.handle(InventoryBackendTest.A, (InventoryC2SPacket) requests.getFirst(), 0, settings);
			int cursor = host.packets.size();
			for (int tick = 0; tick < 270; tick++) {
				backend.tick(tick, settings);
				for (; cursor < host.packets.size(); cursor++) client.accept(wire(host.packets.get(cursor)));
			}
			var sessionsField = InventoryBackend.class.getDeclaredField("sessions"); sessionsField.setAccessible(true);
			Object session = ((Map<?, ?>) sessionsField.get(backend)).get(InventoryBackendTest.A);
			var previewsField = session.getClass().getDeclaredField("previews"); previewsField.setAccessible(true);
			Object preview = ((Map<?, ?>) previewsField.get(session)).get(1L);
			var categories = preview.getClass().getDeclaredField("categories"); categories.setAccessible(true); assertEquals(256, categories.getInt(preview));
			var keys = preview.getClass().getDeclaredField("keys"); keys.setAccessible(true); assertEquals(256, ((Map<?, ?>) keys.get(preview)).size());
			var retired = preview.getClass().getDeclaredField("retired"); retired.setAccessible(true); assertEquals(128, ((Set<?>) retired.get(preview)).size());
			var view = client.preview(1); assertEquals(InventoryS2CPacket.Status.INCOMPLETE, view.status()); assertFalse(view.completeScan());
			assertEquals(128, view.entries().size()); assertTrue(view.entries().stream().allMatch(entry -> entry.fallback() && entry.count() == 2));
			assertTrue(view.entries().stream().noneMatch(entry -> entry.itemId().equals("test:group_128")), "retirement never refunds history capacity for new groups");
			assertEquals(257, reads.get(), "the first category beyond the lifetime limit terminates the paid scan");
		}
	}

	@Test void backendRetiresTrackingBaselineAfterItsFenceHandsOffWhileExactDataIsStillAdmitted() {
		class Host extends InventoryBackendTest.Host {
			boolean blockExact = true;
			@Override public void send(UUID recipient, InventoryS2CPacket packet) {
				if (blockExact && packet.markerId() != null && packet.entries().stream().anyMatch(entry -> !entry.fallback()))
					throw new IllegalStateException("exact baseline part backpressure");
				super.send(recipient, packet);
			}
		}
		var host = new Host(); var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(64);
		boolean[] refused = {false}; var reads = new java.util.concurrent.atomic.AtomicInteger();
		try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> {
			boolean aggregate = refused[0];
			return Optional.of(new InventoryRuntimeTest.Source(input, reads, 1) {
				@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", aggregate ? "" : "plain"), aggregate ? 19 : 7, "stone", null, aggregate); }
			});
		}, settings.pendingMemoryBytes()), 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings); long epoch = host.epoch(InventoryBackendTest.A);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(epoch, 100, 1, 1, InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH), 0, settings); backend.tick(0, settings);
			var preview = host.packets.stream().filter(packet -> !packet.entries().isEmpty()).findFirst().orElseThrow();
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.select(epoch, 100, 1, 1, 1, preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger"), 1, settings);
			var marker = host.store.allMarkers().getFirst(); var endpoint = new Endpoint(InventoryBackendTest.A, MarkerSnapshot.from(marker));
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.close(epoch, 1).stamp(100, 1), 1, settings);
			backend.tick(2, settings); endpoint.drain(host);
			long oldBaseline = endpoint.client.tracking(marker.id()).baselineId(); assertFalse(endpoint.client.tracking(marker.id()).complete());
			assertTrue(host.packets.stream().noneMatch(packet -> marker.id().equals(packet.markerId()) && !packet.entries().isEmpty()));
			refused[0] = true; int start = host.packets.size(); backend.tick(5, settings);
			host.blockExact = false; backend.tick(6, settings); endpoint.drain(host);
			var view = endpoint.client.tracking(marker.id()); assertTrue(view.complete()); assertEquals(19, view.entries().getFirst().count()); assertTrue(view.entries().getFirst().fallback());
			assertNotEquals(oldBaseline, view.baselineId());
			assertTrue(host.packets.subList(start, host.packets.size()).stream().filter(packet -> marker.id().equals(packet.markerId())).flatMap(packet -> packet.entries().stream()).noneMatch(entry -> !entry.fallback()));
			assertEquals(1, host.store.size()); assertEquals(80, marker.expiresAtTick());
		}
	}

	@Test void retiredSelectCannotSpendFreshAttemptWhenBackendReplacementPublicationWasMemoryRefused() {
		var host = new InventoryBackendTest.Host(); var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(64);
		settings.getPreview().setPeriodTicks(1); settings.setPhysicalSlotsPerTick(nx.pingwheel.common.config.IntLimit.finite(1));
		var reads = new java.util.concurrent.atomic.AtomicInteger(); var memory = new java.util.concurrent.atomic.AtomicReference<RetainedMemoryLedger>();
		boolean[] refusePublication = {true};
		var runtime = new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 2) {
			@Override public InventoryDomainCodec.Item read(int slot) {
				reads.incrementAndGet();
				if (slot == 1 && refusePublication[0]) memory.get().setCap(0); // Already-admitted source work settles; following publication cannot reserve.
				return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", slot == 0 ? "plain" : ""), slot == 0 ? 5 : 7, "stone", null, slot == 1);
			}
		}), settings.pendingMemoryBytes()); memory.set(runtime.memory());
		try (var backend = new InventoryBackend(host, runtime, 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings); long epoch = host.epoch(InventoryBackendTest.A);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(epoch, 100, 1, 1, InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH), 0, settings); backend.tick(0, settings);
			var old = host.packets.stream().filter(packet -> !packet.entries().isEmpty()).findFirst().orElseThrow(); int start = host.packets.size();
			backend.tick(1, settings); assertEquals(0, runtime.memory().cap()); assertEquals(start, host.packets.size(), "retirement fences the original cut even when Backend cannot encode its replacement");
			int before = reads.get(); var stale = InventoryC2SPacket.select(epoch, 100, 1, 1, 1, old.baselineId(), old.statusRevision(), old.entries().getFirst().key(), "danger");
			backend.handle(InventoryBackendTest.A, stale, 2, settings); backend.handle(InventoryBackendTest.A, stale, 2, settings);
			assertEquals(before, reads.get()); assertEquals(0, host.store.size());
			assertEquals(2, host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.REJECT && packet.commitId() == 1).count());
			refusePublication[0] = false; backend.tick(3, settings);
			var aggregate = host.packets.stream().filter(packet -> packet.entries().stream().anyMatch(InventoryS2CPacket.Entry::fallback)).reduce((a, b) -> b).orElseThrow();
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.select(epoch, 100, 1, 2, 1, aggregate.baselineId(), aggregate.statusRevision(), aggregate.entries().getFirst().key(), "danger"), 4, settings);
			assertEquals(1, host.store.size()); assertEquals(before, reads.get());
		}
	}
	@Test void identityRefusalInEveryDiscoveryOrderAndPageWidthCountsEachLongSlotOnceAndRejectsLateExactFrames() {
		long[] amounts = {9_007_199_254_740_993L, 7, 11};
		for (int[] order : List.of(new int[] {0, 1, 2}, new int[] {0, 2, 1}, new int[] {1, 0, 2},
			new int[] {1, 2, 0}, new int[] {2, 0, 1}, new int[] {2, 1, 0})) for (int width : new int[] {1, 2, 64}) {
			var host = new InventoryBackendTest.Host(); var settings = InventorySettings.serverDefaults();
			settings.getPreview().setPeriodTicks(1); settings.setPhysicalSlotsPerTick(nx.pingwheel.common.config.IntLimit.finite(width));
			var reads = new java.util.concurrent.atomic.AtomicInteger();
			try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 4) {
				@Override public InventoryDomainCodec.Item read(int slot) {
					reads.incrementAndGet();
					if (slot == 3) return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:dirt", "plain"), 19, "dirt", null, false);
					int variant = order[slot];
					return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", variant == 2 ? "" : "v" + variant),
						amounts[variant], "stone", null, variant == 2);
				}
			}), 16_000_000), 1)) {
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
				var requests = new ArrayList<IPacket>(); var client = new ClientInventory(requests::add); client.presentationReset(100, 1);
				for (var packet : host.packets) client.accept(wire(packet));
				long request = client.open(InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH, "entity_block");
				backend.handle(InventoryBackendTest.A, (InventoryC2SPacket) requests.getFirst(), 0, settings);
				int cursor = host.packets.size(); var exact = new ArrayList<InventoryS2CPacket>();
				for (int tick = 0; tick < 8; tick++) {
					backend.tick(tick, settings);
					for (; cursor < host.packets.size(); cursor++) {
						var packet = wire(host.packets.get(cursor));
						if (packet.entries().stream().anyMatch(entry -> entry.itemId().equals("minecraft:stone") && !entry.fallback())) exact.add(packet);
						client.accept(packet);
					}
				}
				var view = client.preview(request); assertTrue(view.completeScan()); assertEquals(2, view.entries().size());
				var aggregate = view.entries().stream().filter(entry -> entry.itemId().equals("minecraft:stone")).findFirst().orElseThrow();
				assertTrue(aggregate.fallback()); assertEquals(amounts[0] + amounts[1] + amounts[2], aggregate.count());
				assertEquals(19, view.entries().stream().filter(entry -> entry.itemId().equals("minecraft:dirt")).findFirst().orElseThrow().count());
				for (var packet : exact) client.accept(packet);
				assertEquals(view.entries(), client.preview(request).entries(), "retired exact replay cannot reappear after group replacement");
				assertEquals(4, reads.get());
			}
		}
	}

	@Test void retiredExactSelectBeforeAggregateHandoffRejectsAndReplaysWithoutSpendingTheFreshAggregateAttempt() {
		class Host extends InventoryBackendTest.Host {
			boolean blockAggregate = true;
			@Override public void send(UUID recipient, InventoryS2CPacket packet) {
				if (blockAggregate && packet.kind() == InventoryS2CPacket.Kind.PREVIEW && packet.entries().stream().anyMatch(InventoryS2CPacket.Entry::fallback))
					throw new IllegalStateException("withheld aggregate handoff");
				super.send(recipient, packet);
			}
		}
		var host = new Host(); var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(nx.pingwheel.common.config.IntLimit.finite(1));
		settings.getPreview().setPeriodTicks(1); var reads = new java.util.concurrent.atomic.AtomicInteger();
		try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 2) {
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", slot == 0 ? "exact" : ""), slot == 0 ? 5 : 7, "stone", null, slot == 1); }
		}), 16_000_000), 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			long epoch = host.epoch(InventoryBackendTest.A);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(epoch, 100, 1, 1, InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH), 0, settings);
			backend.tick(0, settings);
			var old = host.packets.stream().filter(packet -> !packet.entries().isEmpty()).findFirst().orElseThrow();
			backend.tick(1, settings); int before = reads.get();
			var retired = InventoryC2SPacket.select(epoch, 100, 1, 1, 1, old.baselineId(), old.statusRevision(), old.entries().getFirst().key(), "danger");
			backend.handle(InventoryBackendTest.A, retired, 2, settings); backend.handle(InventoryBackendTest.A, retired, 2, settings);
			assertEquals(before, reads.get(), "retired SELECT has no authoritative witness"); assertEquals(0, host.store.size());
			assertEquals(2, host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.REJECT && packet.commitId() == 1).count());
			host.blockAggregate = false; backend.tick(3, settings);
			var aggregate = host.packets.stream().filter(packet -> packet.entries().stream().anyMatch(InventoryS2CPacket.Entry::fallback)).reduce((a, b) -> b).orElseThrow();
			var fresh = InventoryC2SPacket.select(epoch, 100, 1, 2, 1, aggregate.baselineId(), aggregate.statusRevision(), aggregate.entries().getFirst().key(), "danger");
			backend.handle(InventoryBackendTest.A, fresh, 4, settings); backend.handle(InventoryBackendTest.A, fresh, 4, settings);
			assertEquals(1, host.store.size()); assertEquals(before, reads.get(), "fresh aggregate creates once with metadata validation only");
		}
	}

	@Test void foldedQuotaCountsOneCategoryForLaterSafeVariantsAndOverflowNeverPublishesWrappedOrCompleteTotals() {
		for (boolean overflow : List.of(false, true)) {
			var host = new InventoryBackendTest.Host(); var settings = InventorySettings.serverDefaults();
			settings.getPreview().setMaxVariantsPerClientPeriod(nx.pingwheel.common.config.IntLimit.finite(1));
			var reads = new java.util.concurrent.atomic.AtomicInteger();
			try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 4) {
				@Override public InventoryDomainCodec.Item read(int slot) {
					reads.incrementAndGet(); return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", slot == 0 ? "" : "safe" + slot),
						overflow && slot == 0 ? Long.MAX_VALUE : 1, "stone", null, slot == 0);
				}
			}), 16_000_000), 1)) {
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
				var requests = new ArrayList<IPacket>(); var client = new ClientInventory(requests::add); client.presentationReset(100, 1);
				host.packets.forEach(packet -> client.accept(wire(packet))); client.open(InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH, "entity_block");
				backend.handle(InventoryBackendTest.A, (InventoryC2SPacket) requests.getFirst(), 0, settings);
				int start = host.packets.size(); backend.tick(0, settings);
				for (var packet : host.packets.subList(start, host.packets.size())) client.accept(wire(packet));
				var view = client.preview(1); assertEquals(1, view.entries().size()); assertTrue(view.entries().getFirst().fallback());
				if (overflow) {
					assertFalse(view.completeScan()); assertEquals(InventoryS2CPacket.Status.INCOMPLETE, view.status());
					assertEquals(Long.MAX_VALUE, view.entries().getFirst().count(), "only the admitted prefix survives overflow");
				} else { assertTrue(view.completeScan()); assertEquals(4, view.entries().getFirst().count()); }
			}
		}
	}

	@Test void exactCategoryQuotaIsNotRefundedWhenAnIdentityRefusalRetiresItsRows() {
		var host = new InventoryBackendTest.Host(); var settings = InventorySettings.serverDefaults();
		settings.getPreview().setMaxVariantsPerClientPeriod(nx.pingwheel.common.config.IntLimit.finite(1));
		var reads = new java.util.concurrent.atomic.AtomicInteger();
		try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 2) {
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", slot == 0 ? "exact" : ""), 5, "stone", null, slot == 1); }
		}), 16_000_000), 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1, InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH), 0, settings);
			backend.tick(0, settings);
			var packets = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW).toList();
			assertTrue(packets.stream().noneMatch(InventoryS2CPacket::completeScan));
			assertTrue(packets.stream().allMatch(packet -> packet.entries().isEmpty()), "retired exact and unadmitted aggregate cannot escape as complete data");
			assertEquals(InventoryS2CPacket.Status.INCOMPLETE, packets.getLast().status());
		}
	}

	@Test void partiallyScannedRetainedPreviewKeepsPaidCursorAndTruthfulUpdatingStatusAcrossTicks() {
		var host = new InventoryBackendTest.Host(); var settings = InventorySettings.serverDefaults();
		settings.getPreview().setPeriodTicks(1); settings.setPhysicalSlotsPerTick(nx.pingwheel.common.config.IntLimit.finite(1));
		var reads = new java.util.concurrent.atomic.AtomicInteger();
		try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 4) {
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "exact"), slot + 1, "stone", null, false); }
		}), 16_000_000), 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			var requests = new ArrayList<IPacket>(); var client = new ClientInventory(requests::add); client.presentationReset(100, 1);
			host.packets.forEach(packet -> client.accept(wire(packet))); client.open(InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH, "entity_block");
			backend.handle(InventoryBackendTest.A, (InventoryC2SPacket) requests.getFirst(), 0, settings);
			int cursor = host.packets.size(); backend.tick(0, settings);
			for (; cursor < host.packets.size(); cursor++) client.accept(wire(host.packets.get(cursor)));
			var first = client.preview(1); assertFalse(first.completeScan());
			backend.tick(1, settings); // the original scan continues: slot 1 is consumed exactly once
			for (; cursor < host.packets.size(); cursor++) client.accept(wire(host.packets.get(cursor)));
			backend.tick(1, settings); // a repeated tick cannot refund the physical allowance
			for (; cursor < host.packets.size(); cursor++) client.accept(wire(host.packets.get(cursor)));
			var partial = client.preview(1); assertFalse(partial.completeScan()); assertEquals(InventoryS2CPacket.Status.UPDATING, partial.status());
			assertEquals(InventoryS2CPacket.Status.UNCERTAIN, partial.entries().getFirst().quality(), "generic partial quantities cannot claim verified or complete quality");
			assertEquals(3, partial.entries().getFirst().count()); assertEquals(first.entries().getFirst().key(), partial.entries().getFirst().key());
			assertEquals(first.baselineId(), partial.baselineId()); assertEquals(2, reads.get());
			for (int tick = 2; tick < 5; tick++) backend.tick(tick, settings);
			for (; cursor < host.packets.size(); cursor++) client.accept(wire(host.packets.get(cursor)));
			assertTrue(client.preview(1).completeScan()); assertEquals(10, client.preview(1).entries().getFirst().count()); assertEquals(4, reads.get());
		}
	}
	@Test void smallRecoveryFencePrecedesSnapshotAndEachRecipientReplacesGreyBaselineWithoutOldStreamResurrection() {
		try (var f = new InventoryBackendTest.Fixture()) {
			f.open(); f.backend.handle(InventoryBackendTest.A, f.select(1), 1, f.settings);
			InventoryPublicationTicks.withoutScanSlots(f.backend, 1, f.settings);
			var marker = f.host.store.allMarkers().getFirst(); var a = new Endpoint(InventoryBackendTest.A, MarkerSnapshot.from(marker)); var b = new Endpoint(InventoryBackendTest.B, MarkerSnapshot.from(marker));
			f.backend.tick(2, f.settings); a.drain(f.host); b.drain(f.host); count(a.client, marker.id(), 7); count(b.client, marker.id(), 7);
			f.amount = 8; f.backend.tick(5, f.settings); a.drain(f.host); b.drain(f.host);
			@org.jetbrains.annotations.Unmodifiable List<InventoryS2CPacket> oldStreams = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STREAM).toList(); assertEquals(4, oldStreams.size());
			long baselineA = a.client.tracking(marker.id()).baselineId(), baselineB = b.client.tracking(marker.id()).baselineId();
			f.valid = false; f.backend.tick(6, f.settings); a.drain(f.host); b.drain(f.host);
			assertTrue(a.client.tracking(marker.id()).grey()); assertEquals(8, a.client.tracking(marker.id()).entries().getFirst().count()); assertTrue(b.client.tracking(marker.id()).grey());
			int start = f.host.packets.size(); f.valid = true; f.amount = 11; f.backend.tick(9, f.settings);
			for (UUID recipient : List.of(InventoryBackendTest.A, InventoryBackendTest.B)) {
				List<InventoryS2CPacket> recovery = new ArrayList<>();
				for (int i = start; i < f.host.packets.size(); i++) if (recipient.equals(f.host.recipients.get(i)) && marker.id().equals(f.host.packets.get(i).markerId())) recovery.add(wire(f.host.packets.get(i)));
				assertEquals(InventoryS2CPacket.Kind.STATUS, recovery.getFirst().kind()); assertEquals(InventoryS2CPacket.Status.UPDATING, recovery.getFirst().status());
				assertEquals(0, recovery.getFirst().watermark()); assertEquals(3, recovery.getFirst().statusRevision());
				assertEquals(InventoryS2CPacket.Kind.SNAPSHOT, recovery.get(1).kind()); assertEquals(recovery.getFirst().baselineId(), recovery.get(1).baselineId());
			}
			a.drain(f.host); count(a.client, marker.id(), 11); assertTrue(b.client.tracking(marker.id()).grey(), "A's receipt never rebases B's client");
			b.drain(f.host); count(b.client, marker.id(), 11);
			assertNotEquals(baselineA, a.client.tracking(marker.id()).baselineId()); assertNotEquals(baselineB, b.client.tracking(marker.id()).baselineId());
			for (var old : oldStreams) { a.client.accept(wire(old)); b.client.accept(wire(old)); }
			for (int now = 12; now <= 39; now += 3) { f.backend.tick(now, f.settings); a.drain(f.host); b.drain(f.host); }
			count(a.client, marker.id(), 11); count(b.client, marker.id(), 11); assertTrue(a.repairs().isEmpty()); assertTrue(b.repairs().isEmpty());
			long bRecovered = b.client.tracking(marker.id()).baselineId();
			f.backend.handle(InventoryBackendTest.A, InventoryC2SPacket.resync(f.host.epoch(InventoryBackendTest.A), 0, marker.id()).stamp(100, 1), 40, f.settings);
			f.backend.tick(40, f.settings); a.drain(f.host); b.drain(f.host); count(a.client, marker.id(), 11); assertEquals(bRecovered, b.client.tracking(marker.id()).baselineId());
		}
	}
	@Test void missingApplicationStreamIsRepairedByRealCompletionControlsAtNormalHeartbeatCadenceAndCooldownIsBounded() {
		try (var f = new InventoryBackendTest.Fixture()) {
			f.host.expiry = 200; f.settings.getTracking().setHeartbeatPeriods(16); f.open(); f.backend.handle(InventoryBackendTest.A, f.select(1), 1, f.settings);
			var marker = f.host.store.allMarkers().getFirst(); var a = new Endpoint(InventoryBackendTest.A, MarkerSnapshot.from(marker)); var b = new Endpoint(InventoryBackendTest.B, MarkerSnapshot.from(marker));
			f.backend.tick(2, f.settings); a.drain(f.host); b.drain(f.host); count(a.client, marker.id(), 7);
			long oldA = a.client.tracking(marker.id()).baselineId(), oldB = b.client.tracking(marker.id()).baselineId();
			f.amount = 9; f.backend.tick(5, f.settings); int[] withheld = {0};
			a.drain(f.host, p -> { if (p.kind() == InventoryS2CPacket.Kind.STREAM) { withheld[0]++; return false; } return true; }); b.drain(f.host);
			assertEquals(1, withheld[0], "application seam omission, not a transport-loss claim"); assertEquals(7, a.client.tracking(marker.id()).entries().getFirst().count());
			assertEquals(1, a.repairs().size(), "the completion digest exposes the omitted stream without a same-cut periodic heartbeat"); assertTrue(b.repairs().isEmpty());
			int previous = 0;
			for (int now = 8; now <= 32; now += 3) {
				a.tickTo(previous, now); b.tickTo(previous, now); previous = now;
				f.backend.tick(now, f.settings); a.drain(f.host); b.drain(f.host);
				assertTrue(a.repairs().size() <= 1 + now / 15, "one repair per negotiated five-period cooldown");
			}
			assertEquals(3, a.repairs().size()); count(b.client, marker.id(), 9); assertEquals(oldB, b.client.tracking(marker.id()).baselineId());
			f.backend.handle(InventoryBackendTest.A, a.repairs().getLast(), 33, f.settings); f.backend.tick(33, f.settings); a.drain(f.host); b.drain(f.host);
			count(a.client, marker.id(), 9); assertNotEquals(oldA, a.client.tracking(marker.id()).baselineId()); assertEquals(oldB, b.client.tracking(marker.id()).baselineId());
			int repairs = a.repairs().size();
			for (int now = 36; now <= 120; now += 3) { a.tickTo(previous, now); b.tickTo(previous, now); previous = now; f.backend.tick(now, f.settings); a.drain(f.host); b.drain(f.host); }
			count(a.client, marker.id(), 9); assertEquals(repairs, a.repairs().size()); assertTrue(b.repairs().isEmpty());
		}
	}
	static CaptureResult value(long watermark, long count) {
		var item = InventoryRuntimeTest.item(count);
		return new CaptureResult(Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, Map.of("selected", InventoryDomainCodec.encode(item)))),
			new CaptureResult.Coverage("inventory", watermark, 1, OptionalLong.of(1)), CaptureResult.Availability.READABLE,
			CaptureResult.Completeness.COMPLETE, CaptureResult.Consistency.EVENTUAL, Optional.empty(), Optional.empty());
	}
	@Test void trackingRetirementFencesApplicationWithheldBaselineAndStreamPartsBeforeFreshAggregateSnapshot() {
		for (boolean committed : List.of(false, true)) {
			var settings = InventorySettings.serverDefaults(); var transport = new InventorySyncPublisherTest.Transport();
			var memory = new RetainedMemoryLedger(16_000_000); var marker = new MarkerId(1); var endpoint = negotiated(marker, settings);
			try (var publisher = new InventorySyncPublisher(memory, transport)) {
				publisher.advance(0, settings); var oldContext = InventorySyncPublisherTest.context("tracking/1", 1);
				publisher.register(oldContext, InventorySyncPublisherTest.route(marker, "target"));
				InventorySyncPublisherTest.publish(publisher, oldContext, InventorySyncPublisherTest.capture(1, 2, 0)); publisher.drain();
				var old = new ArrayList<InventoryS2CPacket>();
				for (var packet : transport.sent) {
					if (!packet.entries().isEmpty()) old.add(wire(packet));
					if (committed || packet.kind() != InventoryS2CPacket.Kind.SNAPSHOT || packet.partIndex() == 0) endpoint.client.accept(wire(packet));
				}
				transport.sent.clear();
				if (committed) {
					publisher.advance(3, settings); InventorySyncPublisherTest.publish(publisher, oldContext, InventorySyncPublisherTest.capture(2, 2, 8)); publisher.drain();
					for (var packet : transport.sent) {
						if (!packet.entries().isEmpty()) old.add(wire(packet));
						if (packet.kind() != InventoryS2CPacket.Kind.STREAM || packet.partIndex() == 0) endpoint.client.accept(wire(packet));
					}
					transport.sent.clear();
				}
				assertTrue(publisher.retireGroup(oldContext, "minecraft:stone"));
				publisher.drain(); assertTrue(transport.sent.isEmpty(), "no obsolete completion or exact handoff after retirement");
				// This is the same cancel/register/fence protocol used by Backend's
				// per-recipient rebaselineTracking, with no source capture involved.
				publisher.cancel(oldContext); var fresh = InventorySyncPublisherTest.context("tracking/1", 2);
				publisher.register(fresh, InventorySyncPublisherTest.route(marker, "target"));
				var aggregate = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", ""), 37, "stone", null, true);
				var result = InventorySyncPublisherTest.entries(3, Map.of("aggregate", aggregate));
				memory.setCap(memory.retained());
				assertInstanceOf(SyncPublisher.Outcome.Deferred.class, publisher.publish(result,
					new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, Set.of("aggregate")), fresh,
					new nx.pingwheel.common.presentation.source.CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L))));
				memory.setCap(16_000_000); publisher.advance(6, settings); InventorySyncPublisherTest.publish(publisher, fresh, result); publisher.drain();
				assertEquals(InventoryS2CPacket.Kind.STATUS, transport.sent.getFirst().kind());
				assertEquals(2, transport.sent.getFirst().baselineId());
				transport.sent.forEach(packet -> endpoint.client.accept(wire(packet)));
				count(endpoint.client, marker, 37); assertTrue(endpoint.client.tracking(marker).entries().getFirst().fallback());
				old.forEach(endpoint.client::accept); count(endpoint.client, marker, 37);
				assertEquals(2, endpoint.client.tracking(marker).baselineId());
			}
			assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
		}
	}
	static Endpoint negotiated(MarkerId marker, InventorySettings settings) {
		var snapshot = new MarkerSnapshot(marker, InventoryBackendTest.A, InventoryRuntimeTest.TARGET, "entity_block", "attention", new nx.pingwheel.common.marker.MarkerAnchor(1, 2, 3), 0, 200);
		var endpoint = new Endpoint(InventoryBackendTest.A, snapshot);
		endpoint.client.accept(wire(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(settings.getPreview().getPeriodTicks(),
			settings.getTracking().getPeriodTicks(), settings.getTracking().getResyncMinPeriods(), settings.getTracking().getHeartbeatPeriods())).stamp(100, 1)));
		endpoint.client.accept(wire(InventoryS2CPacket.policy(7, 100, 1, Set.of("entity_block")))); return endpoint;
	}
	@Test void publisherAndClientCloseUnchangedCutsAndRepairWithheldStreamEvenWhenPeriodicHeartbeatIsDisabled() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setHeartbeatPeriods(0);
		var transport = new InventorySyncPublisherTest.Transport(); var memory = new RetainedMemoryLedger(16000000); var marker = new MarkerId(1); var endpoint = negotiated(marker, settings);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			var context = InventorySyncPublisherTest.context("tracking/1", 1); publisher.advance(0, settings); publisher.register(context, InventorySyncPublisherTest.route(marker, "target"));
			InventorySyncPublisherTest.publish(publisher, context, value(1, 7)); publisher.drain(); transport.sent.forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear();
			publisher.advance(3, settings); InventorySyncPublisherTest.publish(publisher, context, value(2, 7)); publisher.drain(); transport.sent.forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear();
			assertEquals(2, endpoint.client.tracking(marker).watermark()); assertTrue(endpoint.repairs().isEmpty());
			publisher.advance(6, settings); InventorySyncPublisherTest.publish(publisher, context, value(3, 9)); publisher.drain();
			transport.sent.stream().filter(p -> p.kind() != InventoryS2CPacket.Kind.STREAM).forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear(); assertEquals(1, endpoint.repairs().size());
			publisher.advance(9, settings); InventorySyncPublisherTest.publish(publisher, context, value(4, 9)); publisher.drain(); transport.sent.forEach(p -> endpoint.client.accept(wire(p))); assertEquals(1, endpoint.repairs().size());
			assertEquals(7, endpoint.client.tracking(marker).entries().getFirst().count()); assertEquals(2, endpoint.client.tracking(marker).watermark(), "mismatch cannot fabricate a received cut");
		}
	}
	@Test void admittedMultipartBeyondFivePeriodsDefersFutureDigestAndRepeatedRequestsResumeSameBaseline() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(java.math.BigDecimal.valueOf(.25))); settings.getTracking().setHeartbeatPeriods(1);
		var transport = new InventorySyncPublisherTest.Transport(); var memory = new RetainedMemoryLedger(16000000); var marker = new MarkerId(1); var endpoint = negotiated(marker, settings);
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			var context = InventorySyncPublisherTest.context("tracking/1", 1); publisher.advance(0, settings); publisher.register(context, InventorySyncPublisherTest.route(marker, "target"));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 12, 2500));
			int received = 0;
			for (int now = 0; now <= 60; now += 3) {
				if (now > 0) endpoint.tickTo(now - 3, now); publisher.advance(now, settings);
				InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 12, 2500)); publisher.drain();
				for (; received < transport.sent.size(); received++) endpoint.client.accept(wire(transport.sent.get(received)));
				assertTrue(endpoint.repairs().isEmpty(), "admitted stable baseline is not a future-cut timeout");
				if (now <= 15) assertTrue(publisher.pending(context));
			}
			assertTrue(endpoint.client.tracking(marker).complete()); assertEquals(12, endpoint.client.tracking(marker).entries().size()); assertEquals(1, endpoint.client.tracking(marker).watermark());
		}
		class BackpressuredHost extends InventoryBackendTest.Host {
			boolean blocked = true; final List<Long> attempts = new ArrayList<>();
			@Override public void send(UUID player, InventoryS2CPacket packet) {
				if (player.equals(InventoryBackendTest.A) && packet.kind() == InventoryS2CPacket.Kind.SNAPSHOT) {
					attempts.add(packet.baselineId()); if (blocked) throw new IllegalStateException("application send seam backpressure");
				}
				super.send(player, packet);
			}
		}
		var host = new BackpressuredHost(); var reads = new java.util.concurrent.atomic.AtomicInteger();
		try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 1)), 16000000), 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1, InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH), 0, settings); backend.tick(0, settings);
			var preview = host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.select(host.epoch(InventoryBackendTest.A), 100, 1, 1, 1, preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger"), 1, settings);
			var stored = host.store.allMarkers().getFirst(); var client = new Endpoint(InventoryBackendTest.A, MarkerSnapshot.from(stored)); backend.tick(2, settings); client.drain(host);
			long baseline = host.attempts.getFirst(); assertFalse(client.client.tracking(stored.id()).complete());
			for (int now = 5; now <= 23; now += 3) {
				client.tickTo(now - 3, now); backend.handle(InventoryBackendTest.A, InventoryC2SPacket.resync(host.epoch(InventoryBackendTest.A), 0, stored.id()).stamp(100, 1), now, settings);
				backend.tick(now, settings); client.drain(host);
			}
			assertTrue(host.attempts.size() > 5); assertTrue(host.attempts.stream().allMatch(id -> id == baseline), "repeated RESYNC resumes the admitted baseline, not a new one");
			host.blocked = false; backend.tick(26, settings); client.drain(host); count(client.client, stored.id(), 7); assertEquals(baseline, client.client.tracking(stored.id()).baselineId()); assertTrue(client.repairs().isEmpty());
		}
	}
	@Test void futureHeartbeatWithoutAnyApplicationDeliveryRequestsBoundedRepairInsteadOfSilentEviction() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setHeartbeatPeriods(1); var transport = new InventorySyncPublisherTest.Transport(); var marker = new MarkerId(1); var endpoint = negotiated(marker, settings);
		try (var publisher = new InventorySyncPublisher(new RetainedMemoryLedger(16000000), transport)) {
			var context = InventorySyncPublisherTest.context("tracking/1", 1); publisher.advance(0, settings); publisher.register(context, InventorySyncPublisherTest.route(marker, "target"));
			InventorySyncPublisherTest.publish(publisher, context, value(1, 7)); publisher.drain(); transport.sent.forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear();
			for (int now = 3; now <= 33; now += 3) {
				endpoint.tickTo(now - 3, now); publisher.advance(now, settings); InventorySyncPublisherTest.publish(publisher, context, value(now / 3 + 1, 9)); publisher.drain();
				// Reject STREAM and completion STATUS at the application seam; pass only heartbeat progress.
				transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.HEARTBEAT).forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear();
			}
			assertEquals(2, endpoint.repairs().size()); assertEquals(7, endpoint.client.tracking(marker).entries().getFirst().count()); assertEquals(1, endpoint.client.tracking(marker).watermark());
		}
	}
	@Test void missingMultipartApplicationPartDefersFutureChecksumThenRepairsCompletedGapWithoutPartialComparison() {
		for (int resyncPeriods : new int[] {0, 5}) {
			var settings = InventorySettings.serverDefaults(); settings.getTracking().setHeartbeatPeriods(16);
			settings.getTracking().setPeriodTicks(3); settings.getTracking().setResyncMinPeriods(resyncPeriods);
			var transport = new InventorySyncPublisherTest.Transport(); var marker = new MarkerId(1); var endpoint = negotiated(marker, settings);
			try (var publisher = new InventorySyncPublisher(new RetainedMemoryLedger(16000000), transport)) {
				var context = InventorySyncPublisherTest.context("tracking/1", 1); publisher.advance(0, settings); publisher.register(context, InventorySyncPublisherTest.route(marker, "target"));
				InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 2, 0)); publisher.drain(); transport.sent.forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear();
				publisher.advance(3, settings); InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(2, 2, 550)); publisher.drain();
				var parts = transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STREAM).toList(); assertEquals(2, parts.size());
				var retainedPart = parts.getFirst(); int[] applied = {0};
				for (var packet : transport.sent) if (packet.kind() != InventoryS2CPacket.Kind.STREAM || applied[0]++ == 0) endpoint.client.accept(wire(packet));
				transport.sent.clear(); assertEquals(1, endpoint.client.tracking(marker).watermark()); assertTrue(endpoint.repairs().isEmpty(), "the future completed digest cannot compare the partial live projection");
				int deadline = resyncPeriods == 0 ? 9 : 15;
				endpoint.tickTo(0, deadline - 1); assertTrue(endpoint.repairs().isEmpty(), "the real multipart gap retains its independent waiting window");
				endpoint.tickTo(deadline - 1, deadline); assertEquals(1, endpoint.repairs().size(), "completed but unapplied gap repairs at the waiting boundary");
				endpoint.client.accept(wire(parts.getLast())); assertEquals(2, endpoint.client.tracking(marker).watermark()); int repairs = endpoint.repairs().size();
				endpoint.tickTo(deadline, 35); assertEquals(repairs, endpoint.repairs().size(), "receiving the missing admitted part clears the gap");
				endpoint.client.accept(wire(retainedPart)); assertEquals(2, endpoint.client.tracking(marker).watermark());
			}
		}
	}
	@Test void failedRecoveryFenceTransmissionBlocksAllBaselinePartsUntilSameFenceCanBeSent() {
		var settings = InventorySettings.serverDefaults(); var marker = new MarkerId(1);
		class FenceTransport extends InventorySyncPublisherTest.Transport {
			boolean blocked = true;
			@Override public void send(UUID recipient, InventoryS2CPacket packet) {
				if (blocked && packet.kind() == InventoryS2CPacket.Kind.STATUS && packet.status() == InventoryS2CPacket.Status.UPDATING) throw new IllegalStateException("fence send failure");
				super.send(recipient, packet);
			}
		}
		var transport = new FenceTransport();
		try (var publisher = new InventorySyncPublisher(new RetainedMemoryLedger(16000000), transport)) {
			publisher.advance(0, settings); var context = new SyncPublisher.Context("tracking/1", InventoryBackendTest.A, "100/1", 3, 6); publisher.register(context, InventorySyncPublisherTest.route(marker, "target"));
			InventorySyncPublisherTest.publish(publisher, context, value(2, 11)); publisher.drain(); assertTrue(transport.sent.isEmpty()); assertTrue(publisher.pending(context));
			transport.blocked = false; publisher.advance(3, settings); publisher.drain(); assertEquals(InventoryS2CPacket.Kind.STATUS, transport.sent.getFirst().kind()); assertEquals(InventoryS2CPacket.Kind.SNAPSHOT, transport.sent.get(1).kind());
		}
	}
	@Test void completedBaselineWithMissingApplicationPartRepairsButDuplicateControlsNeverResetItsAssemblyOrGapTimer() {
		var settings = InventorySettings.serverDefaults(); var transport = new InventorySyncPublisherTest.Transport(); var marker = new MarkerId(1); var endpoint = negotiated(marker, settings);
		try (var publisher = new InventorySyncPublisher(new RetainedMemoryLedger(16000000), transport)) {
			var context = InventorySyncPublisherTest.context("tracking/1", 1); publisher.advance(0, settings); publisher.register(context, InventorySyncPublisherTest.route(marker, "target"));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 2, 0)); publisher.drain();
			var missing = transport.sent.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && p.partIndex() == 1).findFirst().orElseThrow();
			transport.sent.stream().filter(p -> p != missing).forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear(); assertFalse(endpoint.client.tracking(marker).complete()); assertTrue(endpoint.repairs().isEmpty());
			for (int now = 3; now <= 33; now += 3) {
				endpoint.tickTo(now - 3, now); publisher.advance(now, settings); InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(now / 3 + 1, 2, 0)); publisher.drain();
				transport.sent.forEach(p -> endpoint.client.accept(wire(p))); transport.sent.clear();
			}
			assertEquals(2, endpoint.repairs().size(), "completion controls prove the missing part was transmitted, but repeated controls cannot postpone repair indefinitely");
			endpoint.client.accept(wire(missing)); assertTrue(endpoint.client.tracking(marker).complete()); assertEquals(2, endpoint.client.tracking(marker).entries().size()); assertEquals(12, endpoint.client.tracking(marker).watermark());
			int repairs = endpoint.repairs().size(); endpoint.tickTo(33, 70); assertEquals(repairs, endpoint.repairs().size());
		}
	}
	@Test void laterSharedRetirementWithdrawsAnEarlierCheckedTerminalPreviewBeforePublisherDrain() {
		var host = new InventoryBackendTest.Host(); var settings = InventorySettings.serverDefaults();
		settings.getPreview().setPeriodTicks(1); var reads = new java.util.concurrent.atomic.AtomicInteger();
		boolean[] invalidatingTick = {false}, readable = {true}; var checks = new java.util.concurrent.atomic.AtomicInteger();
		var runtime = new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 2) {
			@Override public boolean valid() {
				if (invalidatingTick[0] && checks.incrementAndGet() == 2) readable[0] = false;
				return readable[0];
			}
		}), 16_000_000);
		try (var backend = new InventoryBackend(host, runtime, 1)) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			for (int request = 1; request <= 2; request++) backend.handle(InventoryBackendTest.A,
				InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, request, InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH), 0, settings);
			// Admit both scanners before either finishes so they really share one round.
			settings.setPhysicalSlotsPerTick(nx.pingwheel.common.config.IntLimit.finite(1)); backend.tick(0, settings);
			backend.tick(1, settings); assertEquals(2, reads.get());
			assertEquals(Set.of(1L, 2L), host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.completeScan()).map(InventoryS2CPacket::requestId).collect(java.util.stream.Collectors.toSet()));
			int start = host.packets.size(); invalidatingTick[0] = true; backend.tick(2, settings);
			var after = host.packets.subList(start, host.packets.size()).stream().map(InventoryPublicationIntegrationTest::wire).toList();
			assertEquals(2, checks.get(), "first terminal consumer is admitted, second retires their shared evidence");
			assertEquals(Set.of(1L, 2L), after.stream().filter(p -> p.status() == InventoryS2CPacket.Status.INVALID).map(InventoryS2CPacket::requestId).collect(java.util.stream.Collectors.toSet()));
			assertTrue(after.stream().allMatch(p -> p.entries().isEmpty()), "earlier checkedTick cannot send stale valid state after the later shared retire");
			assertEquals(2, reads.get()); assertEquals(0, runtime.memory().reserved());
		}
		assertEquals(0, runtime.memory().retained());
	}
	@Test void cachedTrackingPublicationDefersWithZeroProviderCallsAndResumesSameBaselineWhenBudgetReturns() {
		try (var f = new InventoryBackendTest.Fixture()) {
			f.open(); f.backend.handle(InventoryBackendTest.A, f.select(1), 1, f.settings); f.backend.tick(2, f.settings);
			var marker = f.host.store.allMarkers().getFirst();
			long baseline = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && marker.id().equals(p.markerId())).findFirst().orElseThrow().baselineId();
			// A same-tick repeat must not reuse the previous checkedTick after work admission is exhausted.
			while (f.backend.runtime().preflight(() -> Optional.of(true)).isPresent()) {}
			int start = f.host.packets.size(), reads = f.reads.get(), validations = f.validations.get(); f.backend.tick(2, f.settings);
			assertEquals(start, f.host.packets.size()); assertEquals(reads, f.reads.get()); assertEquals(validations, f.validations.get(), "provider-work defer performs no source validity call");
			f.backend.tick(3, f.settings);
			assertTrue(f.host.packets.subList(start, f.host.packets.size()).stream().noneMatch(p -> p.status() == InventoryS2CPacket.Status.INVALID));
			assertTrue(f.host.packets.stream().filter(p -> marker.id().equals(p.markerId()) && p.epoch() == f.host.epoch(InventoryBackendTest.A)
				&& Set.of(InventoryS2CPacket.Kind.SNAPSHOT, InventoryS2CPacket.Kind.STREAM, InventoryS2CPacket.Kind.STATUS, InventoryS2CPacket.Kind.HEARTBEAT).contains(p.kind()))
				.allMatch(p -> p.baselineId() == baseline), "defer neither invalidates nor resets a healthy baseline");
		}
	}
	@Test void queuedFoldReplacementRetiresExactCutAndKeepsLatestRowsThroughQueueAndTransportRecoveryWithoutRecapture() {
		class Host extends InventoryBackendTest.Host {
			boolean blocked;
			final List<InventoryS2CPacket> attempts = new ArrayList<>();
			@Override public void send(UUID recipient, InventoryS2CPacket packet) {
				if (packet.kind() == InventoryS2CPacket.Kind.PREVIEW && !packet.entries().isEmpty()) {
					attempts.add(wire(packet));
					if (blocked) throw new IllegalStateException("retained preview handoff backpressure");
				}
				super.send(recipient, packet);
			}
		}
		var host = new Host(); var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(64);
		var delays = new InventoryBudgetDelayIntegrationTest.RecordingSink();
		settings.getPreview().setPeriodTicks(1); settings.setPhysicalSlotsPerTick(nx.pingwheel.common.config.IntLimit.finite(1));
		settings.getPreview().setClientByteMultiplier(ByteMultiplier.finite(java.math.BigDecimal.valueOf(16)));
		var reads = new java.util.concurrent.atomic.AtomicInteger(); var captures = new java.util.concurrent.atomic.AtomicInteger();
		try (var backend = new InventoryBackend(host, new InventoryRuntime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 11) {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return 0; }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() { captures.incrementAndGet(); return Optional.empty(); }
				});
			}
			@Override public InventoryDomainCodec.Item read(int slot) {
				reads.incrementAndGet();
				if (slot == 0 || slot == 2 || slot == 10) return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", slot == 10 ? "" : "exact"),
					slot == 0 ? 9_007_199_254_740_993L : slot == 2 ? 7 : 11, "stone", null, slot == 10);
				int row = slot == 1 || slot == 9 ? 0 : slot - 2;
				// Seven independently serviceable rows together exceed the small global queue.
				return new InventoryDomainCodec.Item(new InventoryScanner.Key("test:retained_" + row, "exact"), slot == 1 ? 3 : slot == 9 ? 11 : slot + 1,
					"r".repeat(1000), null, false);
			}
		}), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), delays), 1, delays)) {
			var requests = new ArrayList<IPacket>(); var client = new ClientInventory(requests::add); client.presentationReset(100, 1);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
			host.packets.forEach(packet -> client.accept(wire(packet)));
			long request = client.open(InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH, "entity_block");
			backend.handle(InventoryBackendTest.A, (InventoryC2SPacket) requests.getFirst(), 0, settings);
			int cursor = host.packets.size(); backend.tick(0, settings); backend.tick(1, settings);
			for (; cursor < host.packets.size(); cursor++) client.accept(wire(host.packets.get(cursor)));
			var original = client.preview(request); assertEquals(2, original.entries().size());
			String retiredKey = original.entries().stream().filter(entry -> entry.itemId().equals("minecraft:stone")).findFirst().orElseThrow().key();
			String survivingKey = original.entries().stream().filter(entry -> entry.itemId().equals("test:retained_0")).findFirst().orElseThrow().key();
			var oldExact = host.attempts.getFirst(); assertFalse(oldExact.entries().getFirst().fallback());
			host.blocked = true;
			for (int tick = 2; tick < 10; tick++) backend.tick(tick, settings);
			assertTrue(host.attempts.stream().anyMatch(packet -> packet.entries().stream().anyMatch(entry -> entry.key().equals(retiredKey) && entry.count() == 9_007_199_254_741_000L)),
				"the newer exact cut was admitted and attempted before identity refusal");
			settings.getPreview().setGlobalByteMultiplier(ByteMultiplier.finite(new java.math.BigDecimal("0.125")));
			int refusedPackets = host.packets.size(), retiredAttempts = host.attempts.size();
			backend.tick(10, settings);
			assertEquals(11, reads.get()); assertEquals(1, captures.get());
			assertTrue(delays.sawReason(nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module.INVENTORY_PREVIEW,
				nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason.QUEUE_CAPACITY), "the complete retained replacement must actually encounter queue admission pressure");
			assertEquals(refusedPackets, host.packets.size()); assertEquals(retiredAttempts, host.attempts.size(), "discovery immediately fences the admitted exact cut before replacement admission");
			long offers = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.OFFER).count();
			for (int tick : new int[] {11, 12, 25, 26, 40}) {
				backend.tick(tick, settings);
				assertEquals(refusedPackets, host.packets.size()); assertEquals(retiredAttempts, host.attempts.size(),
					"queue refusal neither retries retired exact parts nor sends their completion");
				assertEquals(11, reads.get()); assertEquals(1, captures.get(), "queue refusal keeps the original observation and paid cursor");
			}
			settings.getPreview().setGlobalByteMultiplier(ByteMultiplier.one()); backend.tick(41, settings);
			assertTrue(host.attempts.size() > retiredAttempts, "restoring queue capacity admits the retained replacement");
			long admittedWatermark = host.attempts.getLast().watermark();
			for (int tick : new int[] {42, 43, 56}) {
				int attempts = host.attempts.size(); backend.tick(tick, settings);
				assertTrue(host.attempts.size() > attempts, "transport still refuses the admitted replacement");
				assertEquals(refusedPackets, host.packets.size());
				assertEquals(admittedWatermark, host.attempts.getLast().watermark(), "normal ticks resume the immutable admitted cut");
			}
			int deliveredStart = host.packets.size(); host.blocked = false; backend.tick(57, settings);
			for (; cursor < host.packets.size(); cursor++) client.accept(wire(host.packets.get(cursor)));
			var delivered = client.preview(request); assertTrue(delivered.completeScan()); assertFalse(delivered.invalid());
			assertEquals(original.baselineId(), delivered.baselineId()); assertEquals(original.statusRevision(), delivered.statusRevision());
			assertEquals(admittedWatermark, delivered.watermark()); assertTrue(admittedWatermark > original.watermark());
			assertEquals(8, delivered.entries().size()); assertEquals(1, delivered.entries().stream().filter(ClientInventory.EntryView::fallback).count());
			for (var entry : delivered.entries()) {
				if (entry.itemId().equals("minecraft:stone")) {
					assertTrue(entry.fallback()); assertEquals(9_007_199_254_741_011L, entry.count(), "independent sum of the three authoritative long amounts");
					assertNotEquals(retiredKey, entry.key());
				} else {
					int row = Integer.parseInt(entry.itemId().substring("test:retained_".length()));
					assertFalse(entry.fallback()); assertEquals(row == 0 ? 14 : row + 3, entry.count(), "latest unrelated values survive cancelled admitted work");
					if (row == 0) assertEquals(survivingKey, entry.key(), "folding preserves the earlier unrelated selection reference");
				}
			}
			assertEquals(8, host.packets.subList(deliveredStart, host.packets.size()).stream().flatMap(packet -> packet.entries().stream()).count(), "one cumulative replacement, no duplicate queued copies");
			assertTrue(host.attempts.subList(retiredAttempts, host.attempts.size()).stream().flatMap(packet -> packet.entries().stream())
				.noneMatch(entry -> entry.itemId().equals("minecraft:stone") && !entry.fallback()));
			client.accept(wire(oldExact)); assertEquals(delivered.entries(), client.preview(request).entries(), "retired exact replay cannot resurrect a variant");
			int settled = host.packets.size(); backend.tick(58, settings); backend.tick(59, settings); backend.tick(60, settings); assertEquals(settled, host.packets.size(), "the accepted replacement cannot restart after handoff");
			assertEquals(11, reads.get()); assertEquals(1, captures.get()); assertEquals(offers, host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.OFFER).count());
			assertEquals(0, host.store.size());
		}
	}

	@Test void publisherWorkRefusalAfterExactRetirementKeepsLatestUnrelatedOfferUntilOneAggregateCanBeAdmitted() {
		// Backend supplies a fresh fixed publication grant; this exercises that independent public admission seam explicitly.
		class Transport extends InventorySyncPublisherTest.Transport {
			boolean blocked;
			@Override public void send(UUID recipient, InventoryS2CPacket packet) {
				if (blocked && !packet.entries().isEmpty()) throw new IllegalStateException("admitted exact cut backpressure");
				super.send(recipient, packet);
			}
		}
		var settings = InventorySettings.serverDefaults(); var transport = new Transport(); var memory = new RetainedMemoryLedger(16_000_000);
		var client = new ClientInventory(packet -> {}); client.presentationReset(100, 1);
		client.accept(wire(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(5, 3, 5, 16)).stamp(100, 1)));
		client.accept(wire(InventoryS2CPacket.policy(7, 100, 1, Set.of("entity_block"))));
		long request = client.open(InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH, "entity_block");
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings); var context = InventorySyncPublisherTest.context("preview/1", 1);
			assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, request, null, "preview", null)));
			var exact = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "exact"), 9_007_199_254_740_993L, "stone", null, false);
			var dirt = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:dirt", "exact"), 3, "dirt", null, false);
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.entries(1, Map.of("exact", exact, "dirt", dirt))); publisher.drain();
			transport.sent.forEach(packet -> client.accept(wire(packet))); var old = List.copyOf(transport.sent); transport.sent.clear();
			var before = client.preview(request); assertTrue(before.completeScan());
			transport.blocked = true;
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.entries(2, Map.of("exact",
				new InventoryDomainCodec.Item(exact.key(), 9_007_199_254_741_000L, "stone", null, false),
				"dirt", new InventoryDomainCodec.Item(dirt.key(), 99, "dirt", null, false)))); publisher.drain(); assertTrue(publisher.pending(context));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.entries(3,
				Map.of("dirt", new InventoryDomainCodec.Item(dirt.key(), 101, "dirt", null, false))));
			assertFalse(publisher.retireGroup(context, "minecraft:stone"));
			var aggregate = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", ""), 9_007_199_254_741_011L, "stone", null, true);
			var replacement = InventorySyncPublisherTest.entries(4, Map.of("aggregate", aggregate));
			var projection = new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, Set.of("aggregate"));
			for (int tick : new int[] {5, 10, 15}) {
				var work = new nx.pingwheel.common.presentation.source.CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 0L));
				assertInstanceOf(SyncPublisher.Outcome.Deferred.class, publisher.publish(replacement, projection, context, work));
				assertEquals(0, work.used(InventorySyncPublisher.PUBLICATIONS)); publisher.advance(tick, settings); publisher.drain();
				assertTrue(transport.sent.isEmpty(), "work refusal cannot hand off an obsolete part or completion");
			}
			var work = new nx.pingwheel.common.presentation.source.CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 1L));
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, publisher.publish(replacement, projection, context, work));
			assertEquals(1, work.used(InventorySyncPublisher.PUBLICATIONS)); transport.blocked = false; publisher.advance(20, settings); publisher.drain();
			transport.sent.forEach(packet -> client.accept(wire(packet))); var repaired = client.preview(request);
			assertTrue(repaired.completeScan()); assertEquals(2, repaired.entries().size()); assertEquals(before.baselineId(), repaired.baselineId()); assertEquals(before.statusRevision(), repaired.statusRevision());
			assertTrue(repaired.watermark() > before.watermark());
			assertEquals(1, transport.sent.stream().flatMap(packet -> packet.entries().stream()).filter(InventoryS2CPacket.Entry::fallback).count());
			assertTrue(repaired.entries().stream().anyMatch(entry -> entry.key().equals("aggregate") && entry.fallback() && entry.count() == 9_007_199_254_741_011L));
			assertTrue(repaired.entries().stream().anyMatch(entry -> entry.key().equals("dirt") && !entry.fallback() && entry.count() == 101), "restaging uses latest offered 101, not admitted 99 or delivered 3");
			old.forEach(packet -> client.accept(wire(packet))); assertEquals(repaired.entries(), client.preview(request).entries()); assertFalse(publisher.pending(context));
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}

	@Test void trackingRestartKeepsPublishedCompletedEvidenceUntilFreshAcceptedResultAndInvalidityFencesRealFrames() {
		var host = new InventoryBackendTest.Host(); host.expiry = 30;
		var settings = InventorySettings.serverDefaults(); var reads = new java.util.concurrent.atomic.AtomicInteger();
		boolean[] newObservation = {false}, oldValid = {true};
		try (var backend = new InventoryBackend(host, new InventoryRuntime(input -> {
			boolean fresh = newObservation[0];
			return Optional.of(new InventoryRuntimeTest.Source(input, reads, 2) {
				@Override public boolean valid() { return fresh || oldValid[0]; }
				@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return InventoryRuntimeTest.item(fresh ? 9 : 7); }
			});
		}, 16_000_000), 1)) {
			for (UUID player : List.of(InventoryBackendTest.A, InventoryBackendTest.B)) backend.handle(player, InventoryC2SPacket.hello(), 0, settings);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1, InventoryRuntimeTest.TARGET, nx.pingwheel.common.domain.BlockFace.NORTH), 0, settings); backend.tick(0, settings);
			var preview = host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.select(host.epoch(InventoryBackendTest.A), 100, 1, 1, 1, preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger"), 1, settings);
			var marker = host.store.allMarkers().getFirst(); var a = new Endpoint(InventoryBackendTest.A, MarkerSnapshot.from(marker)); var b = new Endpoint(InventoryBackendTest.B, MarkerSnapshot.from(marker));
			backend.tick(2, settings); a.drain(host); b.drain(host); count(a.client, marker.id(), 14); count(b.client, marker.id(), 14);
			long baselineA = a.client.tracking(marker.id()).baselineId();
			newObservation[0] = true; settings.setPhysicalSlotsPerTick(nx.pingwheel.common.config.IntLimit.finite(1));
			backend.tick(5, settings); a.drain(host); b.drain(host);
			assertEquals(14, a.client.tracking(marker.id()).entries().getFirst().count(), "in-progress replacement scan still publishes the old accepted complete result");
			int start = host.packets.size(), before = reads.get(); oldValid[0] = false; backend.tick(6, settings);
			var invalid = host.packets.subList(start, host.packets.size()).stream().map(InventoryPublicationIntegrationTest::wire).toList();
			assertEquals(before, reads.get(), "the new self-valid scanning source cannot authorize the old completed quantity");
			assertEquals(2, invalid.stream().filter(p -> marker.id().equals(p.markerId()) && p.kind() == InventoryS2CPacket.Kind.STATUS && p.status() == InventoryS2CPacket.Status.INVALID).count());
			assertTrue(invalid.stream().filter(p -> marker.id().equals(p.markerId())).allMatch(p -> p.entries().isEmpty()));
			a.drain(host); b.drain(host); assertTrue(a.client.tracking(marker.id()).grey()); assertTrue(b.client.tracking(marker.id()).grey());
			backend.tick(9, settings); backend.tick(10, settings); a.drain(host); b.drain(host);
			count(a.client, marker.id(), 18); count(b.client, marker.id(), 18); assertNotEquals(baselineA, a.client.tracking(marker.id()).baselineId());
			assertEquals(30, marker.expiresAtTick()); assertEquals(1, host.store.size()); assertEquals(InventoryRuntimeTest.TARGET, marker.target());
		}
	}
}
