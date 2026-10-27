package nx.pingwheel.common.presentation.inventory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerIdSource;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Backend to Publisher to wire to ClientInventory coverage for live
 * effective size-pair reconfiguration: detection, recipient gating, detached
 * display reprojection, per-ID folding, fresh fences and no resurrection.
 */
class InventoryReconfigurationTest {
	static final UUID A = new UUID(1, 1), B = new UUID(2, 2);
	static final String ITEM_ID = "minecraft:stone";

	@BeforeAll static void bootStrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	static int utf8(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

	/** Real oversized JSON carrying a raw multibyte component payload. */
	static String componentJson(String marker, int bytes) {
		ItemStack stack = new ItemStack(Items.STONE);
		CompoundTag payload = new CompoundTag();
		payload.putString("data", marker.repeat(bytes));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(payload));
		// Independently bounded display fixture. Native item admission may simplify
		// large resident identities; these tests exercise retained JSON reprojection.
		return ItemStack.SINGLE_ITEM_CODEC.encodeStart(RegistryAccess.EMPTY.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), stack).result().orElseThrow().toString();
	}

	/** Real JSON whose display-only custom name can be dropped while a small component stays. */
	static String namedComponentJson(String marker, int nameChars, int dataBytes) {
		ItemStack stack = new ItemStack(Items.STONE);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(marker.repeat(nameChars)));
		CompoundTag payload = new CompoundTag();
		payload.putString("data", "x".repeat(dataBytes));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(payload));
		return ItemStack.SINGLE_ITEM_CODEC.encodeStart(RegistryAccess.EMPTY.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), stack).result().orElseThrow().toString();
	}

	static InventoryDomainCodec.Item item(String variant, long count, String json) {
		return new InventoryDomainCodec.Item(new InventoryScanner.Key(ITEM_ID, variant), count, "block.minecraft.stone", json, false);
	}

	/** A same-slot filler of another item id; it advances a bounded sweep without joining the selected id's total. */
	static InventoryDomainCodec.Item filler(String itemId, String variant, long count) {
		return new InventoryDomainCodec.Item(new InventoryScanner.Key(itemId, variant), count, itemId, "{\"id\":\"" + itemId + "\"}", false);
	}

	static class Host implements InventoryBackend.Host {
		final List<InventoryS2CPacket> packets = new ArrayList<>(); final List<UUID> recipients = new ArrayList<>();
		final ServerMarkerStore store = new ServerMarkerStore(new MarkerIdSource());
		final Map<UUID, InventoryBackend.Policy> policies = new HashMap<>();
		final InventoryRuntime.DisplayReprojector reprojector = (display, cap) -> InventoryItemCodec.reproject(display, RegistryAccess.EMPTY, cap);
		long expiry = 400;
		boolean failOffers;
		int offerAttempts;
		Host() { policies.put(A, new InventoryBackend.Policy(100, 1, Set.of("entity_block"))); policies.put(B, policies.get(A)); }
		@Override public Optional<InventoryBackend.Policy> policy(UUID player) { return Optional.ofNullable(policies.get(player)); }
		@Override public Optional<InventoryBackend.Opened> open(UUID player, nx.pingwheel.common.domain.Target requested) { return Optional.of(new InventoryBackend.Opened(InventoryRuntimeTest.TARGET, "entity_block", "attention")); }
		@Override public boolean annotationAllowed(InventorySourceInput input, String type) { return "attention".equals(type); }
		@Override public boolean knows(UUID player, MarkerId marker) { return store.allMarkers().stream().anyMatch(m -> m.id().equals(marker) && m.recipients().contains(player)); }
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public Optional<InventoryRuntime.DisplayReprojector> displayReprojector() { return Optional.of(reprojector); }
		@Override public int encodedBytes(InventoryS2CPacket packet) { FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer()); try { packet.write(buf); return buf.readableBytes(); } finally { buf.release(); } }
		@Override public void send(UUID player, InventoryS2CPacket packet) {
			if (packet.kind() == InventoryS2CPacket.Kind.OFFER) {
				offerAttempts++;
				if (failOffers) throw new IllegalStateException("offer seam backpressure");
			}
			assertFalse(packet.isCorrupt(), packet.toString());
			packets.add(packet); recipients.add(player);
		}
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			var type = TargetTypeCatalog.builtIn().findById(frozen.targetType()).orElseThrow();
			var reject = admission.prepare(frozen.target(), type.id(), List.of(A, B));
			if (reject != null) return new InventoryBackend.Created(null, reject);
			var marker = store.create(player, frozen.target(), type, type.defaultPingType(), new MarkerAnchor(1, 2, 3), 1, expiry, List.of(A, B)).marker();
			return new InventoryBackend.Created(marker, null);
		}
		long epoch(UUID player) { for (int i = packets.size() - 1; i >= 0; i--) if (recipients.get(i).equals(player) && packets.get(i).kind() == InventoryS2CPacket.Kind.OFFER) return packets.get(i).epoch(); throw new AssertionError("no offer for " + player); }
		InventoryS2CPacket offer(UUID player) { for (int i = packets.size() - 1; i >= 0; i--) if (recipients.get(i).equals(player) && packets.get(i).kind() == InventoryS2CPacket.Kind.OFFER) return packets.get(i); throw new AssertionError("no offer for " + player); }
		List<InventoryS2CPacket> after(UUID player, int index) { List<InventoryS2CPacket> frames = new ArrayList<>(); for (int i = index; i < packets.size(); i++) if (recipients.get(i).equals(player)) frames.add(packets.get(i)); return frames; }
	}

	static class Source implements InventorySourceAccess.Source {
		final InventorySourceInput input; final AtomicInteger reads; final List<InventoryDomainCodec.Item> slots;
		int cap;
		Source(InventorySourceInput input, AtomicInteger reads, List<InventoryDomainCodec.Item> slots, int cap) { this.input = input; this.reads = reads; this.slots = slots; this.cap = cap; }
		@Override public int displayBytes() { return cap; }
		// Tightening only updates cap; the fixture has no catalogue or source-side parser callback.
		@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
		@Override public void tightenDisplay(int ceiling) { cap = Math.min(cap, ceiling); }
		@Override public SourceKey key() { return new SourceKey("test", "inventory", "reconfig-container", input.viewKey()); }
		@Override public boolean valid() { return true; }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return slots.size(); }
		@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return slots.get(slot); }
		@Override public InventorySourceAccess.Page enumerate(int limit) {
			var values = new ArrayList<InventoryDomainCodec.Item>();
			for (int i = 0; i < Math.min(limit, slots.size()); i++) values.add(read(i));
			return new InventorySourceAccess.Page(values, slots.size() <= limit);
		}
	}

	static class Fixture implements AutoCloseable {
		final Host host = new Host(); final InventorySettings settings = InventorySettings.serverDefaults(); final AtomicInteger reads = new AtomicInteger();
		List<InventoryDomainCodec.Item> slots = List.of();
		long now;
		final InventoryBackend backend;
		Fixture() {
			InventoryRuntime.DisplayReprojector port = (display, cap) -> InventoryItemCodec.reproject(display, RegistryAccess.EMPTY, cap);
			backend = new InventoryBackend(host, new InventoryRuntime((i, cap) -> Optional.of(new Source(i, reads, slots, cap)), 16_000_000, port), 1);
		}
		void begin() {
			// A ten-tick period keeps the initial size reconciliation before the next sweep.
			// Tests that need a partial replacement explicitly lower the tracking period.
			settings.getPreview().setPeriodTicks(10); settings.getTracking().setPeriodTicks(10);
			backend.handle(A, InventoryC2SPacket.hello(), now, settings);
			backend.handle(B, InventoryC2SPacket.hello(), now, settings);
			backend.handle(A, InventoryC2SPacket.open(host.epoch(A), 100, 1, 1, InventoryRuntimeTest.TARGET, BlockFace.NORTH), now, settings);
			tick();
		}
		/** Scales the five independent send-byte multipliers; the size scales are separate budgets. */
		void scaleBudgets(int multiplier) {
			var value = BigDecimal.valueOf(multiplier);
			settings.getPreview().setClientByteMultiplier(nx.pingwheel.common.config.ByteMultiplier.finite(value));
			settings.getPreview().setGlobalByteMultiplier(nx.pingwheel.common.config.ByteMultiplier.finite(value));
			settings.getTracking().setStreamByteMultiplier(nx.pingwheel.common.config.ByteMultiplier.finite(value));
			settings.getTracking().setSnapshotByteMultiplier(nx.pingwheel.common.config.ByteMultiplier.finite(value));
			settings.getTracking().setGlobalByteMultiplier(nx.pingwheel.common.config.ByteMultiplier.finite(value));
		}
		void tick() { backend.tick(now++, settings); }
		void handle(UUID player, InventoryC2SPacket packet) { backend.handle(player, packet, now++, settings); }
		InventoryS2CPacket preview() { return host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).reduce((a, b) -> b).orElseThrow(); }
		List<InventoryS2CPacket> previewFrames() { return host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).toList(); }
		InventoryC2SPacket select(long commit, InventoryS2CPacket reference) { return InventoryC2SPacket.select(host.epoch(A), 100, 1, commit, 1, reference.baselineId(), reference.statusRevision(), reference.entries().getFirst().key(), "attention"); }
		@Override public void close() { backend.close(); }
	}

	static InventoryS2CPacket wire(InventoryS2CPacket packet) {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		try { packet.write(buf); var decoded = InventoryS2CPacket.readSafe(buf); assertFalse(decoded.isCorrupt()); assertFalse(buf.isReadable()); return decoded; }
		finally { buf.release(); }
	}

	static final class Endpoint {
		final List<IPacket> requests = new ArrayList<>();
		final ClientInventory client = new ClientInventory(requests::add);
		final UUID recipient;
		int cursor;
		Endpoint(UUID recipient, MarkerSnapshot marker) {
			this.recipient = recipient;
			client.presentationReset(100, 1);
			if (marker != null) client.markerCreated(marker);
		}
		void drain(Host host) {
			for (; cursor < host.packets.size(); cursor++) if (host.recipients.get(cursor).equals(recipient)) client.accept(wire(host.packets.get(cursor)));
		}
	}

	@Test void higherConfiguredCapCapturesAndDeliversTheFullRetainedDisplayThroughTheWire() {
		try (Fixture f = new Fixture()) {
			f.scaleBudgets(4);
			f.settings.setDisplayByteMultiplier(BigDecimal.valueOf(2));
			f.settings.setFrameByteMultiplier(BigDecimal.valueOf(2));
			String json = componentJson("q", 5000);
			assertTrue(utf8(json) > 4096 && utf8(json) <= 8192, "fixture must exceed the default display cap but fit the configured one");
			f.slots = List.of(item("variant-a", 5, json));
			f.begin();
			var offer = f.host.offer(A).offer();
			assertEquals(8192, offer.effectiveDisplayBytes());
			assertEquals(65536, offer.effectiveFrameBytes());
			var preview = f.preview();
			assertTrue(preview.entries().getFirst().displayJson().contains("q".repeat(5000)), "the configured cap retains the full component payload");
			assertFalse(preview.entries().getFirst().fallback());
			assertTrue(preview.encodedSize(offer.effectiveDisplayBytes(), offer.effectiveFrameBytes()) <= offer.effectiveFrameBytes());

			var endpoint = new Endpoint(A, null);
			assertEquals(1, endpoint.client.open(InventoryRuntimeTest.TARGET, BlockFace.NORTH, "entity_block"));
			endpoint.drain(f.host);
			var accepted = endpoint.client.preview(1);
			assertFalse(accepted.entries().isEmpty());
			assertTrue(accepted.entries().getFirst().displayJson().contains("q".repeat(5000)), "the client reconstructs the retained component payload");
			assertEquals(8192, endpoint.client.offer().effectiveDisplayBytes());
		}
	}

	@Test void loweringGatesDataUntilTheOfferHandsOffThenRebaselinesPreviewAndTracking() {
		try (Fixture f = new Fixture()) {
			f.scaleBudgets(4);
			f.settings.setDisplayByteMultiplier(BigDecimal.valueOf(2));
			f.settings.setFrameByteMultiplier(BigDecimal.valueOf(2));
			f.slots = List.of(item("variant-a", 5, componentJson("q", 5000)));
			f.begin();
			var reference = f.preview();
			assertFalse(reference.entries().getFirst().fallback());
			f.handle(A, f.select(1, reference));
			f.tick();
			var marker = f.host.store.allMarkers().getFirst();
			var snapshot = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && marker.id().equals(p.markerId())).findFirst().orElseThrow();
			long oldBaseline = snapshot.baselineId();
			assertEquals(5, snapshot.entries().getFirst().count());
			assertFalse(snapshot.entries().getFirst().fallback());
			var endpoint = new Endpoint(A, MarkerSnapshot.from(marker));
			assertEquals(1, endpoint.client.open(InventoryRuntimeTest.TARGET, BlockFace.NORTH, "entity_block"));
			endpoint.drain(f.host);

			f.settings.setDisplayByteMultiplier(BigDecimal.ONE);
			f.settings.setFrameByteMultiplier(BigDecimal.ONE);
			f.host.failOffers = true;
			int change = f.host.packets.size();
			f.tick(); f.tick();
			f.handle(A, InventoryC2SPacket.resync(f.host.epoch(A), 1).stamp(100, 1));
			assertTrue(f.host.offerAttempts >= 2, "the new OFFER is retried while its transport handoff keeps failing");
			assertTrue(f.host.after(A, change).stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW
				|| p.kind() == InventoryS2CPacket.Kind.SNAPSHOT || p.kind() == InventoryS2CPacket.Kind.STREAM),
				"a failed OFFER handoff keeps every new item frame blocked");

			f.host.failOffers = false;
			for (int i = 0; i < 8; i++) f.tick();
			var frames = f.host.after(A, change);
			var offer = frames.getFirst();
			assertEquals(InventoryS2CPacket.Kind.OFFER, offer.kind());
			assertEquals(4096, offer.offer().effectiveDisplayBytes());
			assertEquals(32768, offer.offer().effectiveFrameBytes());

			var status = frames.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.STATUS && marker.id().equals(p.markerId())).findFirst().orElseThrow();
			assertEquals(InventoryS2CPacket.Status.UPDATING, status.status());
			var replacement = frames.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && marker.id().equals(p.markerId())).findFirst().orElseThrow();
			assertEquals(status.baselineId(), replacement.baselineId());
			assertNotEquals(oldBaseline, replacement.baselineId());
			assertFalse(replacement.entries().getFirst().fallback(), "complete display stripping preserves exact identity");
			assertEquals(InventoryS2CPacket.Status.UNCERTAIN, replacement.entries().getFirst().quality());
			assertEquals(5, replacement.entries().getFirst().count());

			var fence = frames.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && p.entries().isEmpty()).findFirst().orElseThrow();
			var replacementPreview = frames.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			assertTrue(frames.indexOf(fence) < frames.indexOf(replacementPreview), "the fresh empty PREVIEW fence precedes replacement data");
			assertFalse(replacementPreview.entries().getFirst().fallback());
			assertFalse(replacementPreview.entries().getFirst().replaceGroup());
			assertEquals(reference.entries().getFirst().key(), replacementPreview.entries().getFirst().key());
			assertEquals(5, replacementPreview.entries().getFirst().count());

			assertEquals(1, f.host.store.size());
			assertEquals(marker.id(), f.host.store.allMarkers().getFirst().id());
			assertEquals(400, f.host.store.allMarkers().getFirst().expiresAtTick());

			endpoint.drain(f.host);
			var tracking = endpoint.client.tracking(marker.id());
			assertTrue(tracking.complete());
			assertEquals(5, tracking.entries().getFirst().count());
			var previewView = endpoint.client.preview(1);
			assertFalse(previewView.entries().isEmpty());
			assertFalse(previewView.entries().getFirst().fallback());
			assertEquals(5, previewView.entries().getFirst().count());
		}
	}

	@Test void partialNameStrippingKeepsTheExactVariantKeyCountAndPresentation() {
		try (Fixture f = new Fixture()) {
			f.scaleBudgets(4);
			f.settings.setDisplayByteMultiplier(BigDecimal.valueOf(2));
			f.settings.setFrameByteMultiplier(BigDecimal.valueOf(2));
			String json = namedComponentJson("n", 2000, 3000);
			assertTrue(utf8(json) > 4096 && utf8(json) <= 8192);
			f.slots = List.of(item("variant-a", 5, json));
			f.begin();
			var reference = f.preview();
			String key = reference.entries().getFirst().key();
			assertTrue(reference.entries().getFirst().displayJson().contains("n".repeat(2000)));

			f.settings.setDisplayByteMultiplier(BigDecimal.ONE);
			f.settings.setFrameByteMultiplier(BigDecimal.ONE);
			int change = f.host.packets.size();
			for (int i = 0; i < 8; i++) f.tick();
			var frames = f.host.after(A, change);
			var replacement = frames.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			var entry = replacement.entries().getFirst();
			assertEquals(key, entry.key(), "partial display-only stripping never changes the exact variant key");
			assertEquals(5, entry.count(), "partial display-only stripping never changes the count");
			assertFalse(entry.fallback(), "a partial display omission is not the component-too-long fallback");
			assertFalse(entry.replaceGroup());
			assertFalse(entry.displayJson().contains("n".repeat(2000)), "the oversized display-only name is omitted");
			assertTrue(entry.displayJson().contains("x".repeat(3000)), "the retained component survives");
			assertTrue(utf8(entry.displayJson()) <= 4096);
		}
	}

	@Test void fullDisplayStrippingPreservesBothExactVariantsAndTheLastCompletedSelectedCount() {
		try (Fixture f = new Fixture()) {
			f.scaleBudgets(4);
			f.settings.setDisplayByteMultiplier(BigDecimal.valueOf(2));
			f.settings.setFrameByteMultiplier(BigDecimal.valueOf(2));
			f.slots = List.of(item("variant-a", 5, componentJson("a", 5000)), item("variant-b", 2, componentJson("b", 5000)));
			f.begin();
			var parts = f.previewFrames();
			assertEquals(2, parts.size(), "both exact variants are initially retained in their own bounded parts");
			var reference = parts.stream().filter(p -> p.entries().getFirst().count() == 5).findFirst().orElseThrow();
			// The completed observation's next sweep is due after one period so a partial sweep can be in flight.
			f.settings.getTracking().setPeriodTicks(1);
			f.handle(A, f.select(1, reference));
			f.tick();
			var marker = f.host.store.allMarkers().getFirst();
			var snapshot = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && marker.id().equals(p.markerId())).findFirst().orElseThrow();
			assertEquals(5, snapshot.entries().getFirst().count(), "the first tracking observation is the exact selected variant");
			assertFalse(snapshot.entries().getFirst().fallback());

			// A partial replacement sweep accumulates only part of the all-ID total.
			f.settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
			f.tick(); f.tick();
			f.settings.setDisplayByteMultiplier(BigDecimal.ONE);
			f.settings.setFrameByteMultiplier(BigDecimal.ONE);
			int change = f.host.packets.size();
			for (int i = 0; i < 8; i++) f.tick();
			var frames = f.host.after(A, change);
			var replacement = frames.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && marker.id().equals(p.markerId())).findFirst().orElseThrow();
			assertFalse(replacement.entries().getFirst().fallback());
			assertEquals(5, replacement.entries().getFirst().count(), "display omission keeps the completed selected count, never the all-ID total");
			assertEquals(InventoryS2CPacket.Status.UNCERTAIN, replacement.entries().getFirst().quality());

			var replacementPreview = frames.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			assertEquals(1, replacementPreview.entries().size(), "each exact variant keeps its own bounded part");
			assertFalse(replacementPreview.entries().getFirst().replaceGroup());
			var previewEntries = frames.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW).flatMap(p -> p.entries().stream()).toList();
			assertEquals(Set.of(2L, 5L), previewEntries.stream().map(InventoryS2CPacket.Entry::count).collect(java.util.stream.Collectors.toSet()));
			assertTrue(previewEntries.stream().noneMatch(InventoryS2CPacket.Entry::fallback));

			// A later cap increase never resurrects the omitted display data.
			int raised = f.host.packets.size();
			f.settings.setDisplayByteMultiplier(BigDecimal.valueOf(2));
			f.settings.setFrameByteMultiplier(BigDecimal.valueOf(2));
			for (int i = 0; i < 4; i++) f.tick();
			var afterRaise = f.host.after(A, raised);
			assertTrue(afterRaise.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW).flatMap(p -> p.entries().stream())
				.noneMatch(entry -> entry.displayJson() != null && (entry.displayJson().contains("a".repeat(5000)) || entry.displayJson().contains("b".repeat(5000)))), "an increase never resurrects retained omitted display");
		}
	}

	@Test void actualIdentityRefusalDuringPartialTrackingKeepsCompletedAllIdEvidenceAcrossRecipientResyncAndExpiry() {
		try (Fixture f = new Fixture()) {
			f.slots = List.of(item("selected", 5, null), item("other", 7, null), filler("minecraft:dirt", "filler", 1));
			f.begin(); var reference = f.previewFrames().stream().filter(packet -> packet.entries().getFirst().count() == 5).findFirst().orElseThrow();
			f.settings.getTracking().setPeriodTicks(1); f.handle(A, f.select(1, reference)); f.tick();
			var marker = f.host.store.allMarkers().getFirst(); var a = new Endpoint(A, MarkerSnapshot.from(marker)); var b = new Endpoint(B, MarkerSnapshot.from(marker));
			a.drain(f.host); b.drain(f.host); assertEquals(5, a.client.tracking(marker.id()).entries().getFirst().count());
			long oldA = a.client.tracking(marker.id()).baselineId(), oldB = b.client.tracking(marker.id()).baselineId();
			var slots = new ArrayList<InventoryDomainCodec.Item>();
			slots.add(new InventoryDomainCodec.Item(new InventoryScanner.Key(ITEM_ID, ""), 20, "stone", null, true));
			for (int i = 0; i < 30; i++) slots.add(filler("minecraft:dirt", "filler" + i, 1));
			slots.add(item("selected", 3, null)); f.slots = slots;
			f.settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
			for (int i = 0; i < 4; i++) f.tick();
			a.drain(f.host); b.drain(f.host);
			assertTrue(a.client.tracking(marker.id()).entries().getFirst().fallback());
			assertEquals(12, a.client.tracking(marker.id()).entries().getFirst().count(), "retirement uses last completed all-ID evidence, never the partial 20");
			assertNotEquals(oldA, a.client.tracking(marker.id()).baselineId()); assertNotEquals(oldB, b.client.tracking(marker.id()).baselineId());
			long foldedB = b.client.tracking(marker.id()).baselineId();
			f.handle(A, InventoryC2SPacket.resync(f.host.epoch(A), 0, marker.id()).stamp(100, 1)); f.tick();
			a.drain(f.host); b.drain(f.host);
			assertEquals(12, a.client.tracking(marker.id()).entries().getFirst().count()); assertTrue(a.client.tracking(marker.id()).entries().getFirst().fallback());
			assertEquals(foldedB, b.client.tracking(marker.id()).baselineId(), "A's repair never resets B");
			for (int i = 0; i < 40; i++) f.tick(); a.drain(f.host); b.drain(f.host);
			assertEquals(23, a.client.tracking(marker.id()).entries().getFirst().count());
			assertEquals(23, b.client.tracking(marker.id()).entries().getFirst().count());
			assertEquals(400, marker.expiresAtTick()); assertEquals(InventoryRuntimeTest.TARGET, marker.target());
			f.backend.handle(A, InventoryC2SPacket.close(f.host.epoch(A), 1).stamp(100, 1), 399, f.settings);
			f.backend.tick(400, f.settings); int reads = f.reads.get(); f.backend.tick(500, f.settings); assertEquals(reads, f.reads.get());
		}
	}

	@Test void deferredReprojectionKeepsTheGateRecoversAndRejectsAStaleSelect() {
		InventoryRuntime runtime;
		try (Fixture f = new Fixture()) {
			runtime = f.backend.runtime();
			f.scaleBudgets(32);
			f.settings.setPendingMemoryMiB(64);
			f.settings.setDisplayByteMultiplier(BigDecimal.valueOf(16));
			f.settings.setFrameByteMultiplier(BigDecimal.valueOf(16));
			var slots = new ArrayList<InventoryDomainCodec.Item>();
			for (int i = 0; i < 11; i++) slots.add(item("variant-" + i, 1, componentJson(String.valueOf((char) ('a' + i)), 60000)));
			f.slots = slots;
			f.begin();
			var reference = f.preview();
			assertFalse(reference.entries().isEmpty());

			// Lower the shared pending-memory bound so the measured reprojection workspace cannot be admitted.
			f.settings.setPendingMemoryMiB(4);
			f.tick();
			f.settings.setDisplayByteMultiplier(BigDecimal.ONE);
			f.settings.setFrameByteMultiplier(BigDecimal.ONE);
			int change = f.host.packets.size();
			f.tick(); // detect + refused OFFER enqueue
			f.tick(); // the bounded refresh intent still waits for memory
			assertEquals(0, f.backend.runtime().memory().reserved(), "a deferred reprojection leaks no workspace ticket");
			assertTrue(f.host.after(A, change).stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()),
				"the data gate stays closed while reprojection is deferred");

			// Recovering the memory budget completes the same reprojection and reopens the gate.
			f.settings.setPendingMemoryMiB(64);
			for (int i = 0; i < 8; i++) f.tick();
			var frames = f.host.after(A, change);
			var replacement = frames.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			assertFalse(replacement.entries().getFirst().fallback());
			assertEquals(1, replacement.entries().getFirst().count());

			// A SELECT against the pre-reconfiguration fence is stale and never creates a marker.
			f.handle(A, f.select(1, reference));
			f.tick();
			assertEquals(0, f.host.store.size(), "a stale SELECT against the replaced baseline is rejected");
			var selected = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.REJECT || p.kind() == InventoryS2CPacket.Kind.SELECTED).reduce((a, b) -> b).orElseThrow();
			assertEquals(InventoryS2CPacket.Kind.REJECT, selected.kind());

			// A disconnect during the same gated session cleans its pending gate and work.
			f.backend.disconnect(A);
			assertEquals(0, f.backend.runtime().memory().reserved());
		}
		assertEquals(0, runtime.memory().retained());
		assertEquals(0, runtime.memory().reserved());
	}

	@Test void supersededLowerOfferCannotUnblockAfterThePairReturnsHigher() {
		try (Fixture f = new Fixture()) {
			f.scaleBudgets(4);
			f.settings.setDisplayByteMultiplier(BigDecimal.valueOf(2));
			f.settings.setFrameByteMultiplier(BigDecimal.valueOf(2));
			f.slots = List.of(item("variant-a", 5, componentJson("q", 5000)));
			f.begin();
			int change = f.host.packets.size();

			f.host.failOffers = true;
			f.settings.setDisplayByteMultiplier(BigDecimal.ONE);
			f.settings.setFrameByteMultiplier(BigDecimal.ONE);
			f.tick(); // the superseded lower OFFER is enqueued but its handoff fails
			f.settings.setDisplayByteMultiplier(BigDecimal.valueOf(2));
			f.settings.setFrameByteMultiplier(BigDecimal.valueOf(2));
			f.tick(); // the pair returns higher before the lower OFFER ever handed off
			f.host.failOffers = false;
			for (int i = 0; i < 6; i++) f.tick();

			var frames = f.host.after(A, change);
			var offer = frames.getFirst();
			assertEquals(InventoryS2CPacket.Kind.OFFER, offer.kind(), "the superseded lower OFFER never becomes the accepted pair");
			assertEquals(8192, offer.offer().effectiveDisplayBytes());
			assertEquals(65536, offer.offer().effectiveFrameBytes());
			assertTrue(frames.stream().noneMatch(p -> (p.kind() == InventoryS2CPacket.Kind.PREVIEW
				|| p.kind() == InventoryS2CPacket.Kind.SNAPSHOT || p.kind() == InventoryS2CPacket.Kind.STREAM)
				&& !p.entries().isEmpty() && p.entries().getFirst().fallback()),
				"the superseded lower pair never reprojected retained data");
		}
	}

	@Test void multibyteDisplayIsMeasuredInUtf8BytesAcrossTheWire() {
		try (Fixture f = new Fixture()) {
			f.scaleBudgets(4);
			f.settings.setDisplayByteMultiplier(BigDecimal.valueOf(2));
			f.settings.setFrameByteMultiplier(BigDecimal.valueOf(2));
			String json = componentJson("\u00e9", 2500); // 2500 characters, 5000 UTF-8 bytes
			assertTrue(json.contains("\u00e9"));
			assertTrue(utf8(json) > 4096 && json.length() < utf8(json));
			f.slots = List.of(item("variant-a", 5, json));
			f.begin();
			var preview = f.preview();
			assertTrue(preview.entries().getFirst().displayJson().contains("\u00e9".repeat(2500)), "the multibyte payload survives the wire");
			assertFalse(preview.entries().getFirst().fallback());

			f.settings.setDisplayByteMultiplier(BigDecimal.ONE);
			f.settings.setFrameByteMultiplier(BigDecimal.ONE);
			int change = f.host.packets.size();
			for (int i = 0; i < 8; i++) f.tick();
			var replacement = f.host.after(A, change).stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty()).findFirst().orElseThrow();
			assertFalse(replacement.entries().getFirst().fallback(), "the byte cap simplifies display without changing identity");
			assertTrue(utf8(replacement.entries().getFirst().displayJson()) <= 4096);
		}
	}

	@Test void pendingCutSelectIsRejectedAndTheFreshReferenceAfterRebaselineStillCreates() {
		try (Fixture f = new Fixture()) {
			f.scaleBudgets(4);
			f.settings.setDisplayByteMultiplier(BigDecimal.valueOf(2));
			f.settings.setFrameByteMultiplier(BigDecimal.valueOf(2));
			String json = namedComponentJson("n", 2000, 3000);
			assertTrue(utf8(json) > 4096 && utf8(json) <= 8192, "fixture must exceed the default display cap but fit the configured one");
			f.slots = List.of(item("variant-a", 5, json));
			f.begin();
			var reference = f.preview();
			String key = reference.entries().getFirst().key();
			assertTrue(reference.entries().getFirst().displayJson().contains("n".repeat(2000)));

			// The pair lowers while the updated OFFER never hands off: the data gate
			// holds item frames but cannot fence this SELECT.
			f.host.failOffers = true;
			f.settings.setDisplayByteMultiplier(BigDecimal.ONE);
			f.settings.setFrameByteMultiplier(BigDecimal.ONE);
			int change = f.host.packets.size();
			f.handle(A, f.select(1, reference));
			f.tick(); f.tick();
			assertEquals(0, f.host.store.size(), "the affected stale SELECT never installs an oversized tracking presentation");
			var rejected = f.host.packets.stream().filter(p -> p.kind() == InventoryS2CPacket.Kind.REJECT || p.kind() == InventoryS2CPacket.Kind.SELECTED)
				.reduce((a, b) -> b).orElseThrow();
			assertEquals(InventoryS2CPacket.Kind.REJECT, rejected.kind(), "the superseded cut is answered with the recorded rejection");
			assertEquals(1, rejected.commitId());
			assertTrue(f.host.after(A, change).stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT),
				"a rejected cut leaves no tracking sidecar behind");

			// The handoff recovers: the replacement fence and the reprojected reference arrive.
			f.host.failOffers = false;
			for (int i = 0; i < 8; i++) f.tick();
			var replacement = f.host.after(A, change).stream()
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty())
				.findFirst().orElseThrow();
			var entry = replacement.entries().getFirst();
			assertEquals(key, entry.key(), "partial display-only stripping never changes the opaque entry identity");
			assertEquals(5, entry.count(), "partial display-only stripping never changes the count");
			assertFalse(entry.fallback());
			assertFalse(entry.displayJson().contains("n".repeat(2000)), "the oversized display-only name is omitted");
			assertTrue(entry.displayJson().contains("x".repeat(3000)), "the retained component survives");
			assertTrue(utf8(entry.displayJson()) <= 4096);

			// The rejected attempt stayed with the preview: the fresh reference still creates.
			f.handle(A, f.select(2, replacement));
			assertEquals(1, f.host.store.size(), "the fresh reference after the replacement baseline still creates the Ping");
			var marker = f.host.store.allMarkers().getFirst();
			f.tick();
			var snapshot = f.host.packets.stream()
				.filter(p -> p.completeScan() && !p.entries().isEmpty() && marker.id().equals(p.markerId()))
				.reduce((a, b) -> b).orElseThrow();
			assertEquals(5, snapshot.entries().getFirst().count());
			assertFalse(snapshot.entries().getFirst().fallback());
			assertTrue(utf8(snapshot.entries().getFirst().displayJson()) <= 4096,
				"the installed tracking presentation fits the effective ceiling");
		}
	}

	@Test void deferredPendingCutSelectIsRejectedAndTheSimplifiedExactReferenceStillCreates() throws Exception {
		try (Fixture f = new Fixture()) {
			f.scaleBudgets(32);
			f.settings.setPendingMemoryMiB(64);
			f.settings.setDisplayByteMultiplier(BigDecimal.valueOf(16));
			f.settings.setFrameByteMultiplier(BigDecimal.valueOf(16));
			var slots = new ArrayList<InventoryDomainCodec.Item>();
			for (int i = 0; i < 11; i++) slots.add(item("variant-" + i, 1, componentJson(String.valueOf((char) ('a' + i)), 60000)));
			f.slots = slots;
			f.begin();
			var reference = f.preview();
			assertFalse(reference.entries().isEmpty());

			// The measured reprojection workspace cannot be admitted, so the pending
			// reconciliation stays DEFERRED while its data gate holds item frames.
			f.settings.setPendingMemoryMiB(4);
			f.tick();
			f.settings.setDisplayByteMultiplier(BigDecimal.ONE);
			f.settings.setFrameByteMultiplier(BigDecimal.ONE);
			int change = f.host.packets.size();
			f.tick(); // detect + OFFER handoff
			f.tick(); // measured reprojection attempt defers
			assertEquals(0, f.backend.runtime().memory().reserved(), "a deferred reprojection leaks no workspace ticket");

			// The exact reference cannot be published under the pending ceiling.
			f.handle(A, f.select(1, reference));
			f.tick();
			assertEquals(0, f.host.store.size(), "a SELECT during DEFERRED reprojection never installs an oversized tracking presentation");
			assertTrue(f.backend.runtime().memory().remaining() < 0);
			assertEquals(0, f.backend.runtime().memory().reserved());
			assertTrue(f.host.after(A, change).stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.REJECT || p.kind() == InventoryS2CPacket.Kind.SELECTED),
				"the recorded result's wire enqueue waits for resource recovery");
			var sessionsField = InventoryBackend.class.getDeclaredField("sessions"); sessionsField.setAccessible(true);
			Object session = ((Map<?, ?>) sessionsField.get(f.backend)).get(A);
			var commitsField = session.getClass().getDeclaredField("commits"); commitsField.setAccessible(true);
			Object result = ((Map<?, ?>) commitsField.get(session)).get(1L);
			assertNotNull(result, "the bounded committed-result owner survives refused queue admission");
			var pendingField = result.getClass().getDeclaredField("pending"); pendingField.setAccessible(true);
			var queuedField = result.getClass().getDeclaredField("queued"); queuedField.setAccessible(true);
			var packetField = result.getClass().getDeclaredField("packet"); packetField.setAccessible(true);
			assertTrue(pendingField.getBoolean(result)); assertFalse(queuedField.getBoolean(result));
			assertEquals(InventoryS2CPacket.Kind.REJECT, ((InventoryS2CPacket) packetField.get(result)).kind());
			assertTrue(f.host.after(A, change).stream().noneMatch(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT));

			// Recovering the memory budget completes the same reprojection.
			f.settings.setPendingMemoryMiB(64);
			for (int i = 0; i < 8; i++) f.tick();
			var rejected = f.host.after(A, change).stream().map(InventoryPublicationIntegrationTest::wire)
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.REJECT || p.kind() == InventoryS2CPacket.Kind.SELECTED).toList();
			assertEquals(1, rejected.size(), "the stored outcome retries without another SELECT and clears after handoff");
			assertEquals(InventoryS2CPacket.Kind.REJECT, rejected.getFirst().kind());
			assertEquals(1, rejected.getFirst().commitId());
			assertEquals(reference.requestId(), rejected.getFirst().requestId());
			assertFalse(pendingField.getBoolean(result)); assertFalse(queuedField.getBoolean(result));
			assertEquals(0, f.host.store.size(), "delivering a recorded rejection never retries CREATE");
			var replacement = f.host.after(A, change).stream()
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.PREVIEW && !p.entries().isEmpty())
				.findFirst().orElseThrow();
			var entry = replacement.entries().getFirst();
			assertFalse(entry.fallback(), "complete display stripping preserves the exact reference");
			assertEquals(1, entry.count());
			assertEquals(InventoryS2CPacket.Status.UNCERTAIN, entry.quality());

			// The remaining unused attempt still creates from the fresh exact replacement reference.
			f.handle(A, f.select(2, replacement));
			assertEquals(1, f.host.store.size(), "the fresh exact reference after the replacement baseline still creates the Ping");
			var marker = f.host.store.allMarkers().getFirst();
			f.tick();
			var snapshot = f.host.packets.stream()
				.filter(p -> p.completeScan() && !p.entries().isEmpty() && marker.id().equals(p.markerId()))
				.reduce((a, b) -> b).orElseThrow();
			assertFalse(snapshot.entries().getFirst().fallback(), "the installed tracking keeps its exact selected identity");
			assertEquals(1, snapshot.entries().getFirst().count());
			assertEquals(InventoryS2CPacket.Status.UNCERTAIN, snapshot.entries().getFirst().quality());
		}
	}

	@Test void resyncAfterDisplayTighteningBeforeTheNextCompleteSweepKeepsTheLastCompletedExactCount() {
		try (Fixture f = new Fixture()) {
			f.scaleBudgets(4);
			f.settings.setDisplayByteMultiplier(BigDecimal.valueOf(2));
			f.settings.setFrameByteMultiplier(BigDecimal.valueOf(2));
			var slots = new ArrayList<InventoryDomainCodec.Item>();
			slots.add(item("variant-a", 5, componentJson("a", 5000)));
			for (int i = 0; i < 5; i++) slots.add(filler("minecraft:dirt", "filler-" + i, 1));
			slots.add(item("variant-b", 2, componentJson("b", 5000)));
			slots.add(filler("minecraft:dirt", "filler-tail", 1));
			f.slots = slots;
			f.begin();
			var reference = f.previewFrames().stream().filter(p -> p.entries().getFirst().count() == 5).findFirst().orElseThrow();
			f.settings.getTracking().setPeriodTicks(1);
			f.handle(A, f.select(1, reference));
			f.tick();
			var marker = f.host.store.allMarkers().getFirst();
			var initial = f.host.packets.stream()
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && marker.id().equals(p.markerId()))
				.findFirst().orElseThrow();
			assertEquals(5, initial.entries().getFirst().count(), "the first complete observation is the exact selected variant");
			assertFalse(initial.entries().getFirst().fallback());
			var endpoint = new Endpoint(A, MarkerSnapshot.from(marker));
			endpoint.drain(f.host);

			// A bounded replacement sweep is in flight when the pair lowers, so no
			// newer complete all-ID total exists yet.
			f.settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
			f.tick(); // restart + the selected variant
			f.tick(); // a filler only; the in-progress total stays 5
			f.settings.setDisplayByteMultiplier(BigDecimal.ONE);
			f.settings.setFrameByteMultiplier(BigDecimal.ONE);
			int change = f.host.packets.size();
			f.tick(); // detect + OFFER handoff + a filler
			f.tick(); // reconcile: fold, rebaseline, a filler
			f.tick(); // the replacement fence clears and the folded snapshot is delivered
			f.tick(); // a filler; the sweep is still incomplete

			// RESYNC before that sweep can complete must keep the last completed total.
			int resyncMark = f.host.packets.size();
			f.handle(A, InventoryC2SPacket.resync(f.host.epoch(A), 0, marker.id()).stamp(100, 1));
			f.tick();
			var replacement = f.host.after(A, resyncMark).stream()
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && marker.id().equals(p.markerId()))
				.findFirst().orElseThrow();
			assertFalse(replacement.entries().getFirst().fallback(), "RESYNC preserves exact selected identity after display tightening");
			assertEquals(InventoryS2CPacket.Status.UNCERTAIN, replacement.entries().getFirst().quality());
			assertEquals(5, replacement.entries().getFirst().count(), "the last completed exact count survives a partial sweep");

			// The completing sweep publishes the same single folded total, never a group double count.
			for (int i = 0; i < 4; i++) f.tick();
			var latest = f.host.after(A, resyncMark).stream()
				.filter(p -> p.kind() == InventoryS2CPacket.Kind.SNAPSHOT && marker.id().equals(p.markerId()))
				.reduce((a, b) -> b).orElseThrow();
			assertEquals(5, latest.entries().getFirst().count());
			assertFalse(latest.entries().getFirst().fallback());
			assertEquals(1, f.host.store.size());
			assertEquals(marker.id(), f.host.store.allMarkers().getFirst().id());
			assertEquals(400, f.host.store.allMarkers().getFirst().expiresAtTick());

			endpoint.drain(f.host);
			var tracking = endpoint.client.tracking(marker.id());
			assertTrue(tracking.complete());
			assertEquals(1, tracking.entries().size(), "the folded group replaces the exact variant instead of double counting");
			assertEquals(5, tracking.entries().getFirst().count());
			assertFalse(tracking.entries().getFirst().fallback());
			assertEquals(InventoryS2CPacket.Status.UNCERTAIN, tracking.entries().getFirst().quality());
		}
	}
}
