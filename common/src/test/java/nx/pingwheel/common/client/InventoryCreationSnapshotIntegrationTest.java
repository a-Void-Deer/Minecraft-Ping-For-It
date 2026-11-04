package nx.pingwheel.common.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.locale.Language;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.chat.ContentChatComposer;
import nx.pingwheel.common.chat.ContentChatTemplate;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.client.spatial.NativeSelectorInput;
import nx.pingwheel.common.client.spatial.SelectorIntent;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.config.ByteMultiplier;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.cancel.CancellationContext;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import nx.pingwheel.common.interaction.state.TargetValidation;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationPropertySelection;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryBackend;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.inventory.InventorySyncPublisher;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.inventory.client.InventoryClientStore;
import nx.pingwheel.common.presentation.minecraft.PresentationServer;
import nx.pingwheel.common.render.InventoryTrackingRenderer;
import nx.pingwheel.common.render.SpatialOverlayRenderer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Headless real backend SELECT -> server initial -> encoded body -> runtime/composer/HUD projection.
 * Native source, world/player and final send ports reuse the existing bounded server fixture.
 * Bounded reflection reuses that private fixture and wraps the existing publisher transport port;
 * it does not replace policy, source validation, publication admission or packet encoding.
 * Oracles: D0018; inventory Creation snapshot receipt; presentation Receipt content descriptor;
 * names/chat Content receipt lifecycle. No socket, loader launch or rendered-pixel claim.
 */
@NotNullByDefault
class InventoryCreationSnapshotIntegrationTest {

	private static final UUID OWNER = new UUID(704, 2);
	private static final UUID RECEIVER = new UUID(800, 0);
	private static final String TYPE = "entity_block";
	private static @Nullable Language previousLanguage;
	private static @Unmodifiable Map<String, String> locale = Map.of();

	@BeforeAll static void bootstrap() throws IOException {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		Map<String, String> values = new LinkedHashMap<>();
		try (@Nullable InputStream input = InventoryCreationSnapshotIntegrationTest.class.getClassLoader().getResourceAsStream("assets/pingforit/lang/en_us.json")) {
			Language.loadFromJson(Objects.requireNonNull(input), values::put);
		}
		locale = Map.copyOf(values); previousLanguage = Language.getInstance();
		Language.inject(new ReceiptLanguage(locale, previousLanguage));
	}
	@AfterAll static void restoreLanguage() { if (previousLanguage != null) Language.inject(previousLanguage); }

	@ParameterizedTest @CsvSource({"ITEM,false", "ITEM,true", "FLUID,false", "FLUID,true"})
	void selectedFiveChatsImmediatelyThenRealTrackingNineOnlyChangesHud(InventoryResourceKind lane, boolean partial) throws Exception {
		try (var f = new Combined(Map.of(), OWNER)) {
			f.preview(lane, partial);
			long request = f.open();
			int reads = f.reads(lane);
			MarkerId marker = f.select(request, lane);
			assertEquals(reads, f.reads(lane), "SELECT receipt must use retained current Preview without a getter reread");
			Component chat = f.assertImmediate(marker, partial);
			String text = chat.getString();
			assertEquals(0, f.events.stream().filter(e -> e.packet() instanceof InventoryS2CPacket p
				&& marker.equals(p.markerId()) && !p.entries().isEmpty()).count(), "assertion is before the first Tracking send");
			f.state(lane, "amount", 9L);
			for (int tick = 1; tick <= 30 && !f.client.inventory.tracking(marker).complete(); tick++) f.tick(tick);
			assertTrue(f.client.inventory.tracking(marker).complete(), "actual backend/publisher must close fresh Tracking");
			assertEquals(9L, InventoryTrackingRenderer.lines(f.client.inventory.tracking(marker)).getFirst().count());
			f.replayAndRefresh(marker);
			assertEquals(1, f.client.feedback.content.size());
			assertSame(chat, f.client.feedback.content.getFirst()); assertEquals(text, chat.getString());
			assertEquals(1, f.client.feedback.sounds.size());
		}
	}

	@ParameterizedTest @CsvSource({"ITEM,false", "ITEM,true", "FLUID,false", "FLUID,true"})
	void twentyFourNormalHellosLeaveOldDenyButCreationStillChatsBeforeQueuedAllow(InventoryResourceKind lane, boolean partial) throws Exception {
		try (var f = new Combined(Map.of(lane.fieldId(), 2), RECEIVER)) {
			f.preview(lane, partial); long request = f.open();
			f.settings().getPreview().setGlobalByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.125")));
			for (int index = 0; index < 24; index++) f.join(new UUID(800, index));
			assertEquals(24, f.joined().size(), "24 distinct server players, one normal HELLO each");
			assertEquals(Optional.of(false), f.client.authorization(lane));
			assertTrue(f.events.stream().anyMatch(e -> e.recipient().equals(RECEIVER) && e.packet() instanceof InventoryS2CPacket p
				&& p.kind() == InventoryS2CPacket.Kind.POLICY && p.resourceKind() == lane && !p.allowedTypes().contains(TYPE)), "recipient actually received deny");
			for (Object player : f.joined().values()) put(player, "permission", 2);
			f.tick(0);
			InventoryS2CPacket pending = f.pending(RECEIVER, lane);
			assertTrue(pending.allowedTypes().contains(TYPE));
			assertTrue(f.publisher().controlQueued(RECEIVER, pending), "production byte congestion owns queued allow, no test transport withholding");
			put(f.serverFixture, "audience", List.of(OWNER, RECEIVER));
			MarkerId marker = f.select(request, lane);
			Component chat = f.assertImmediate(marker, partial);
			String text = chat.getString();
			assertEquals(Optional.of(false), f.client.authorization(lane), "creation does not globally clear old deny");
			assertTrue(f.publisher().controlQueued(RECEIVER, pending));
			Event created = f.created(marker);
			assertTrue(f.events.stream().noneMatch(e -> e.recipient().equals(RECEIVER) && e.packet() instanceof InventoryS2CPacket p
				&& p.kind() == InventoryS2CPacket.Kind.POLICY && p.resourceKind() == lane && p.allowedTypes().contains(TYPE)));
			// Replay a genuine production data cut, stamped for this receiver, while its deny is still installed.
			// It must be rejected even though this membership has a valid creation grant.
			f.state(lane, "amount", 9L);
			f.tick(1);
			InventoryS2CPacket data = f.events.stream().filter(e -> e.recipient().equals(OWNER))
				.map(Event::packet).filter(InventoryS2CPacket.class::isInstance).map(InventoryS2CPacket.class::cast)
				.filter(p -> marker.equals(p.markerId()) && !p.entries().isEmpty()).findFirst().orElseThrow();
			var receiverData = new InventoryS2CPacket(data.kind(), data.protocol(), f.client.inventory.epoch(), data.requestId(), data.markerId(),
				data.baselineId(), data.statusRevision(), data.watermark(), data.partIndex(), data.partCount(), data.completeScan(), data.status(),
				data.checksum(), data.entries(), data.offer(), f.client.presentation.epoch(), f.client.presentation.sessionView(), data.allowedTypes(),
				data.commitId(), data.rejection(), data.resourceKind());
			f.client.inventoryPacket(wire(receiverData));
			assertEquals(Optional.of(false), f.client.authorization(lane));
			assertEquals(0, f.client.inventory.stats().trackedChannels(), "scoped creation cannot admit normal data");
			assertEquals(5L, f.client.inventory.tracking(marker).entries().getFirst().count());
			for (int tick = 2; tick <= 180 && !f.client.inventory.tracking(marker).complete(); tick++) f.tick(tick);
			assertEquals(Optional.of(true), f.client.authorization(lane));
			assertTrue(f.client.inventory.tracking(marker).complete(), () -> "post-congestion tracking=" + f.client.inventory.tracking(marker)
				+ " offer=" + f.client.inventory.offer() + " stats=" + f.client.inventory.stats() + " packets=" + f.events.stream()
				.filter(e -> e.recipient().equals(RECEIVER) && e.packet() instanceof InventoryS2CPacket).toList());
			assertEquals(9L, InventoryTrackingRenderer.lines(f.client.inventory.tracking(marker)).getFirst().count());
			Event allow = f.events.stream().filter(e -> e.recipient().equals(RECEIVER) && e.packet() instanceof InventoryS2CPacket p
				&& p.kind() == InventoryS2CPacket.Kind.POLICY && p.resourceKind() == lane && p.allowedTypes().contains(TYPE)).findFirst().orElseThrow();
			assertTrue(created.order() < allow.order(), "actual target recipient CREATED handoff precedes POLICY");
			System.out.println("CONGESTION " + lane + " partial=" + partial + " recipient=" + RECEIVER + " CREATED@" + created.order() + " POLICY@" + allow.order());
			f.replayAndRefresh(marker);
			assertEquals(1, f.client.feedback.content.size()); assertSame(chat, f.client.feedback.content.getFirst()); assertEquals(text, chat.getString());
		}
	}

	@ParameterizedTest @CsvSource({"ITEM", "FLUID"})
	void currentRecipientDenialSendsOnlySuppressedBasicWithoutResourceOrChat(InventoryResourceKind lane) throws Exception {
		try (var f = new Combined(Map.of(lane.fieldId(), 2), RECEIVER)) {
			f.preview(lane, false); long request = f.open(); f.join(RECEIVER);
			put(f.serverFixture, "audience", List.of(OWNER, RECEIVER));
			MarkerId marker = f.select(request, lane);
			var initial = (PresentationS2CPacket) f.created(marker).packet();
			assertEquals(PresentationReceiptContent.Kind.SUPPRESSED, initial.content().kind());
			assertNull(initial.content().inventorySnapshot());
			var bytes = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
			try {
				initial.write(bytes);
				assertFalse(bytes.toString(java.nio.charset.StandardCharsets.ISO_8859_1).contains(lane == InventoryResourceKind.ITEM ? "minecraft:stone" : "minecraft:water"),
					"denied selected label/identity is absent from the actual atomic body");
			} finally { bytes.release(); }
			assertTrue(f.client.runtime.store().marker(marker).isPresent(), "Basic still inserts");
			assertTrue(f.client.feedback.content.isEmpty()); assertTrue(f.client.feedback.ordinary.isEmpty());
			assertTrue(f.client.inventory.tracking(marker).entries().isEmpty());
			assertNull(f.client.inventory.creationSnapshot(marker));
		}
	}

	@Test void newCreationGrantCannotClearAnotherMarkersDenial() throws Exception {
		try (var f = new Combined(Map.of(InventoryResourceKind.ITEM.fieldId(), 2), OWNER)) {
			f.preview(InventoryResourceKind.ITEM, false);
			MarkerId old = f.select(f.open(), InventoryResourceKind.ITEM); f.assertImmediate(old, false);
			put(get(f.serverFixture, "player"), "permission", 0); f.tick(1);
			assertNull(f.client.inventory.creationSnapshot(old));
			put(get(f.serverFixture, "player"), "permission", 2); f.tick(2);
			MarkerId fresh = f.select(f.open(), InventoryResourceKind.ITEM);
			assertNotNull(f.client.inventory.creationSnapshot(fresh));
			assertNull(f.client.inventory.creationSnapshot(old));
			assertEquals(Optional.of(false), f.client.inventory.receiptAuthorization(old, f.client.presentation.epoch(), f.client.presentation.sessionView(), TYPE));
			f.replayAndRefresh(old); assertEquals(2, f.client.feedback.content.size());
		}
	}

	@ParameterizedTest @CsvSource({"ITEM", "FLUID"})
	void completedPhysicalSweepWithoutCurrentPreviewCutChatsUncertainImmediately(InventoryResourceKind lane) throws Exception {
		try (var f = new Combined(Map.of(), OWNER)) {
			f.preview(lane, true); long request = f.open();
			assertFalse(((ClientInventory) get(f.serverFixture, "client")).preview(request, lane).completeScan());
			put(f.serverFixture, "refusePreview", true);
			f.settings().setPhysicalSlotsPerTick(IntLimit.finite(32)); f.tick(1);
			Object preview = call(f.serverFixture, "previewState", new Class<?>[] {long.class, InventoryResourceKind.class}, request, lane);
			var capture = (nx.pingwheel.common.presentation.source.CaptureResult) get(get(preview, "last"), "result");
			assertEquals(nx.pingwheel.common.presentation.source.CaptureResult.Completeness.COMPLETE, capture.completeness());
			assertFalse(f.publisher().previewCutComplete((nx.pingwheel.common.presentation.source.SyncPublisher.Context) get(preview, "context"), (long) get(preview, "revision")));
			MarkerId marker = f.select(request, lane);
			f.assertImmediate(marker, true);
		}
	}

	@Test void refusedInventoryInitialDoesNotBlockOrdinaryOrPropertyReceipt() throws Exception {
		try (var f = new Combined(Map.of(), OWNER)) {
			f.preview(InventoryResourceKind.ITEM, false); long request = f.open(); put(f.serverFixture, "refuseCreated", true);
			MarkerId blocked = f.select(request, InventoryResourceKind.ITEM);
			assertTrue(f.client.feedback.content.isEmpty());
			for (boolean property : new boolean[] {false, true}) {
				var type = TargetTypeCatalog.builtIn().findById(TYPE).orElseThrow();
				var ref = PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.CUSTOM_NAME);
				var marker = f.store().create(OWNER, (Target) get(f.serverFixture, "requestedTarget"), type, type.defaultPingType(),
					new MarkerAnchor(1,64,3), 0, 10000, List.of(OWNER), property ? List.of(PresentationPropertySelection.of(ref, "request")) : List.of()).marker();
				PresentationServer.created(f.server(), marker, nx.pingwheel.common.name.TargetNameJsonCodec.UNKNOWN, "owner", Map.of(PresentationBasic.ID,
					new PresentationSection(PresentationBasic.ID, 1, Map.of(PresentationBasic.NAME, new PresentationValue.Text("\"chest\""),
						PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("selected custom")), false)));
			}
			assertEquals(1, f.client.feedback.ordinary.size()); assertEquals(1, f.client.feedback.content.size());
			assertTrue(hasArgument(f.client.feedback.content.getFirst(), "selected custom"));
			assertTrue(f.client.runtime.store().marker(blocked).isEmpty());
		}
	}

	@Test void soundCallbackRevocationCannotBeUndoneBySeedInstallationOrServerReplay() throws Exception {
		try (var f = new Combined(Map.of(), OWNER)) {
			f.preview(InventoryResourceKind.ITEM, false); long request = f.open();
			f.client.feedback.onPlay = () -> f.client.inventoryPacket(wire(InventoryS2CPacket.policy(f.client.inventory.epoch(),
				f.client.presentation.epoch(), f.client.presentation.sessionView(), java.util.Set.of())));
			MarkerId marker = f.select(request, InventoryResourceKind.ITEM);
			assertTrue(f.client.runtime.store().marker(marker).isPresent());
			assertNull(f.client.inventory.creationSnapshot(marker)); assertTrue(f.client.inventory.tracking(marker).entries().isEmpty());
			assertTrue(f.client.feedback.content.isEmpty());
			f.client.inventoryPacket(wire(InventoryS2CPacket.policy(f.client.inventory.epoch(), f.client.presentation.epoch(),
				f.client.presentation.sessionView(), java.util.Set.of(TYPE))));
			f.replayAndRefresh(marker); assertNull(f.client.inventory.creationSnapshot(marker)); assertTrue(f.client.feedback.content.isEmpty());
		}
	}

	@ParameterizedTest @CsvSource({"EXPIRED", "CANCELLED"})
	void deferredCreationRemovedBeforeHandoffCannotEmitLateChat(MarkerRemovalReason reason) throws Exception {
		try (var f = new Combined(Map.of(), OWNER)) {
			f.preview(InventoryResourceKind.ITEM, false); long request = f.open(); put(f.serverFixture, "refuseCreated", true);
			MarkerId marker = f.select(request, InventoryResourceKind.ITEM);
			assertTrue(f.client.feedback.content.isEmpty()); assertTrue(f.client.runtime.store().marker(marker).isEmpty());
			put(f.serverFixture, "refuseCreated", false);
			if (reason == MarkerRemovalReason.EXPIRED) f.tick(10001);
			else { f.backend().remove(marker); PresentationServer.removed((ServerPlayer) get(f.serverFixture, "player"), marker, reason); f.store().removeByServer(marker, reason); f.tick(1); }
			assertTrue(f.client.feedback.content.isEmpty()); assertTrue(f.client.runtime.store().marker(marker).isEmpty());
			assertTrue(f.events.stream().noneMatch(e -> e.packet() instanceof PresentationS2CPacket p && p.kind() == PresentationS2CPacket.Kind.CREATED));
		}
	}

	@NotNullByDefault
	private record Event(int order, UUID recipient, IPacket packet) {}

	/** Reflection touches only named, bounded fixture ports. No arbitrary production graph walk. */
	@NotNullByDefault
	private static final class Combined implements AutoCloseable {
		final Object serverFixture;
		final UUID recipient;
		final Client client = new Client();
		final List<Event> events = new ArrayList<>();
		int clientRequests;
		Combined(@Unmodifiable Map<String, Integer> permissions, UUID recipient) throws Exception {
			this.recipient = recipient;
			Class<?> fixture = Class.forName("nx.pingwheel.common.presentation.minecraft.InventoryProductionPolicyTest$Fixture");
			Constructor<?> constructor = fixture.getDeclaredConstructor(Map.class); constructor.setAccessible(true);
			serverFixture = constructor.newInstance(permissions);
			// The existing fixture's server encoder decodes the exact staged CREATED bytes before receiver.
			Field receiverField = field(Class.forName("nx.pingwheel.common.presentation.minecraft.InventoryProductionPolicyTest$Network"), "receiver");
			@SuppressWarnings("unchecked") var original = (BiConsumer<IPacket, ServerPlayer>) Objects.requireNonNull(receiverField.get(null));
			receiverField.set(null, (BiConsumer<IPacket, ServerPlayer>) (packet, player) -> {
				original.accept(packet, player);
				IPacket decoded = packet instanceof PresentationS2CPacket p && (p.kind() != PresentationS2CPacket.Kind.CREATED
					|| p.content().kind() != PresentationReceiptContent.Kind.INVENTORY) ? wire(p) : packet;
				receive(player.getUUID(), decoded);
			});
			InventorySyncPublisher.Transport originalTransport = (InventorySyncPublisher.Transport) get(publisher(), "transport");
			InventorySyncPublisher.Transport transport = (InventorySyncPublisher.Transport) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {InventorySyncPublisher.Transport.class}, (proxy, method, args) -> {
				try {
					@Nullable Object result = method.invoke(originalTransport, args);
					if (method.getName().equals("send")) receive((UUID) args[0], wire((InventoryS2CPacket) args[1]));
					return result;
				} catch (InvocationTargetException failure) { throw failure.getCause(); }
			});
			put(publisher(), "transport", transport);
			// Owner negotiation precedes port wrapping; replay only its genuine recorded OFFER/RESET/controls.
			if (recipient.equals(OWNER)) {
				for (Object packet : (List<?>) get(serverFixture, "presentation")) receive(OWNER, wire((PresentationS2CPacket) packet));
				for (Object packet : (List<?>) get(serverFixture, "packets")) receive(OWNER, wire((InventoryS2CPacket) packet));
			}
		}
		void receive(UUID id, IPacket packet) {
			events.add(new Event(events.size(), id, packet));
			if (!id.equals(recipient)) return;
			if (packet instanceof PresentationS2CPacket p) client.runtime.onPresentationPacket(p);
			if (packet instanceof InventoryS2CPacket p) client.inventoryPacket(p);
		}
		void preview(InventoryResourceKind lane, boolean partial) throws Exception {
			state(lane, "amount", 5L); state(lane, "verified", true); state(lane, "oneValue", true);
			if (partial) { state(lane, "slots", 8); settings().setPhysicalSlotsPerTick(IntLimit.finite(lane == InventoryResourceKind.ITEM ? 1 : 2)); }
		}
		void state(InventoryResourceKind lane, String name, Object value) throws Exception { put(Objects.requireNonNull(((Map<?, ?>) get(serverFixture, "states")).get(lane)), name, value); }
		int reads(InventoryResourceKind lane) throws Exception { return (int) get(Objects.requireNonNull(((Map<?, ?>) get(serverFixture, "states")).get(lane)), "reads"); }
		long open() throws Exception { return (long) call(serverFixture, "open", new Class<?>[0]); }
		MarkerId select(long request, InventoryResourceKind lane) throws Exception { return (MarkerId) call(serverFixture, "select", new Class<?>[] {long.class, InventoryResourceKind.class}, request, lane); }
		void tick(int tick) throws Exception {
			call(serverFixture, "tick", new Class<?>[] {int.class}, tick);
			client.inventory.tick(true);
			while (clientRequests < client.sent.size()) {
				IPacket packet = client.sent.get(clientRequests++);
				if (packet instanceof InventoryC2SPacket p) backend().handle(recipient, p, tick, settings());
			}
		}
		void join(UUID id) throws Exception { call(serverFixture, "join", new Class<?>[] {UUID.class}, id); }
		Map<?, ?> joined() throws Exception { return (Map<?, ?>) get(get(serverFixture, "players"), "others"); }
		InventorySettings settings() throws Exception { return (InventorySettings) get(serverFixture, "settings"); }
		InventoryBackend backend() throws Exception { return (InventoryBackend) get(serverFixture, "backend"); }
		MinecraftServer server() throws Exception { return (MinecraftServer) get(serverFixture, "server"); }
		ServerMarkerStore store() throws Exception { return (ServerMarkerStore) get(serverFixture, "store"); }
		InventorySyncPublisher publisher() throws Exception { return (InventorySyncPublisher) get(backend(), "publisher"); }
		InventoryS2CPacket pending(UUID id, InventoryResourceKind lane) throws Exception { return (InventoryS2CPacket) call(serverFixture, "pendingPolicy", new Class<?>[] {UUID.class, InventoryResourceKind.class}, id, lane); }
		Event created(MarkerId marker) { return events.stream().filter(e -> e.recipient().equals(recipient) && e.packet() instanceof PresentationS2CPacket p
			&& p.kind() == PresentationS2CPacket.Kind.CREATED && p.snapshot().id().equals(marker)).findFirst().orElseThrow(); }
		Component assertImmediate(MarkerId marker, boolean partial) {
			var packet = (PresentationS2CPacket) created(marker).packet();
			var snapshot = Objects.requireNonNull(packet.content().inventorySnapshot());
			assertEquals(5, snapshot.amount()); assertEquals(!partial, snapshot.previewComplete()); assertEquals(partial, snapshot.quantityUncertain());
			assertEquals(1, client.feedback.content.size(), "CREATED must synchronously chat retained Preview5 without POLICY/Tracking completion");
			assertTrue(client.feedback.ordinary.isEmpty());
			Component chat = client.feedback.content.getFirst();
			assertEquals(partial ? 1 : 0, translations(chat, "pingforit.spatial.inventory.uncertain"));
			assertEquals(partial, chat.getString().contains(Objects.requireNonNull(locale.get("pingforit.spatial.inventory.uncertain"))),
				"actual locale uncertainty is visible for incomplete Preview, not merely pending Tracking");
			var tracking = client.inventory.tracking(marker);
			assertFalse(tracking.complete()); assertEquals(InventoryClientStore.NO_BASELINE, tracking.baselineId());
			assertEquals(0, client.inventory.stats().trackedChannels());
			var line = InventoryTrackingRenderer.lines(tracking).getFirst();
			assertEquals(5L, line.count()); assertEquals("pingforit.spatial.inventory.updating", line.statusKey()); assertFalse(line.grey());
			if (snapshot.resourceKind() == InventoryResourceKind.ITEM) assertTrue(hasArgument(chat, "5"), "chat uses exact selected amount5");
			else { assertTrue(hasArgument(chat, "<1")); assertEquals(1, translations(chat, "presentation.pingforit.format.fluid_amount")); }
			return chat;
		}
		void replayAndRefresh(MarkerId marker) throws Exception {
			client.runtime.onPresentationPacket((PresentationS2CPacket) created(marker).packet());
			PresentationServer.updated(server(), store().find(marker).orElseThrow());
			var initial = (PresentationS2CPacket) created(marker).packet();
			client.runtime.onPresentationPacket(wire(PresentationS2CPacket.winner(initial.epoch(), initial.view(), initial.snapshot().targetKey(), Optional.of(marker))));
			for (Event event : List.copyOf(events)) if (event.recipient().equals(recipient) && event.packet() instanceof InventoryS2CPacket p
				&& marker.equals(p.markerId())) client.inventoryPacket(wire(p));
			client.runtime.onTick();
		}
		@Override public void close() {
			try { client.runtime.close(); client.inventory.reset(); ((AutoCloseable) serverFixture).close(); }
			catch (Exception failure) { throw new AssertionError("combined fixture cleanup", failure); }
		}
	}

	@NotNullByDefault
	private static final class Client {
		final List<IPacket> sent = new ArrayList<>();
		final Feedback feedback = new Feedback();
		final ClientPresentation presentation = new ClientPresentation(sent::add, List.of());
		final ClientInventory inventory = new ClientInventory(sent::add);
		final ClientPingRuntime runtime = ClientPingRuntime.createForInteraction((key, color) -> fail("unexpected local error"), sent::add,
			ClientRateLimitPolicy.DEFAULT, () -> 0, target -> TargetValidation.valid(), new Access(), presentation, inventory, null, marker -> 200, feedback);
		Client() { presentation.tick(true); }
		void inventoryPacket(InventoryS2CPacket packet) { runtime.onInventoryPacket(packet); }
		Optional<Boolean> authorization(InventoryResourceKind lane) { return inventory.authorization(lane, presentation.epoch(), presentation.sessionView(), TYPE); }
	}

	@NotNullByDefault
	private static final class Feedback implements ClientPingRuntime.PresentationReceiptFeedback {
		final List<Component> content = new ArrayList<>();
		final List<MarkerSnapshot> sounds = new ArrayList<>(), ordinary = new ArrayList<>();
		Runnable onPlay = () -> {};
		@Override public void play(MarkerSnapshot marker) { sounds.add(marker); onPlay.run(); }
		@Override public void chat(String author, MarkerSnapshot marker, Component name) { ordinary.add(marker); }
		@Override public boolean ready() { return true; }
		@Override public Optional<Component> name(String json) { return ContentChatComposer.nameJsonDecoder(RegistryAccess.EMPTY).apply(json); }
		@Override public Optional<Component> item(String json) { return ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(json); }
		@Override public String template(PingType type) { return ContentChatTemplate.DEFAULT_TEMPLATE; }
		@Override public void content(Component message) { content.add(message); }
	}

	@NotNullByDefault
	private static final class Access implements ClientPingRuntime.InteractionAccess {
		final Object world = new Object();
		@Override public Lifecycle lifecycle() { return new Lifecycle(world, "minecraft:overworld", null, true, true, false); }
		@Override public Optional<CapturedRay> capturePressRay() { return Optional.empty(); }
		@Override public void capture(InteractionToken token, CapturedRay ray, ClientPingRuntime.CaptureCompletion completion) { fail("receipt recaptured source"); }
		@Override public CancellationContext cancellation(CapturedRay ray) { throw new AssertionError("receipt cancellation read"); }
		@Override public void syncMouse(PingInteractionPhase phase) {}
		@Override public void disposeMouse(boolean screenTransition) {}
		@Override public Optional<NativeSelectorInput.Frame> inputFrame() { return Optional.empty(); }
		@Override public SpatialSelectorSession.ListGeometry listGeometry(SpatialOverlayRenderer.Style style) { throw new AssertionError("receipt selector read"); }
		@Override public void resetInput() {}
		@Override public void applyToggle(SelectorIntent.CaptureToggle toggle, long timeMillis) { fail("receipt toggle"); }
	}

	private static PresentationS2CPacket wire(PresentationS2CPacket packet) {
		var buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		try { packet.write(buffer); var decoded = PresentationS2CPacket.readSafe(buffer); assertFalse(decoded.isCorrupt()); assertFalse(buffer.isReadable()); return decoded; }
		finally { buffer.release(); }
	}
	private static InventoryS2CPacket wire(InventoryS2CPacket packet) {
		var buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		try { packet.write(buffer); var decoded = InventoryS2CPacket.readSafe(buffer); assertFalse(decoded.isCorrupt()); assertFalse(buffer.isReadable()); return decoded; }
		finally { buffer.release(); }
	}
	private static int translations(Component text, String key) {
		int count = 0;
		if (text.getContents() instanceof TranslatableContents contents) {
			if (key.equals(contents.getKey())) count++;
			for (Object arg : contents.getArgs()) if (arg instanceof Component child) count += translations(child, key);
		}
		for (Component child : text.getSiblings()) count += translations(child, key);
		return count;
	}
	private static boolean hasArgument(Component text, String expected) {
		if (text.getContents() instanceof TranslatableContents contents) for (Object arg : contents.getArgs())
			if (arg instanceof Component child ? expected.equals(child.getString()) || hasArgument(child, expected) : expected.equals(String.valueOf(arg))) return true;
		return text.getSiblings().stream().anyMatch(child -> hasArgument(child, expected));
	}
	@NotNullByDefault
	private static final class ReceiptLanguage extends Language {
		private final @Unmodifiable Map<String, String> translations;
		private final Language fallback;
		ReceiptLanguage(@Unmodifiable Map<String, String> translations, Language fallback) { this.translations = translations; this.fallback = fallback; }
		@Override public String getOrDefault(String key, String defaultValue) { return translations.getOrDefault(key, fallback.getOrDefault(key, defaultValue)); }
		@Override public boolean has(String key) { return translations.containsKey(key) || fallback.has(key); }
		@Override public boolean isDefaultRightToLeft() { return false; }
		@Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
	}
	private static Field field(Class<?> owner, String name) throws NoSuchFieldException { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
	private static Object get(Object object, String name) throws Exception { return Objects.requireNonNull(field(object.getClass(), name).get(object)); }
	private static void put(Object object, String name, Object value) throws Exception { field(object.getClass(), name).set(object, value); }
	private static Object call(Object object, String name, Class<?> @Unmodifiable [] types, Object... args) throws Exception {
		Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
		try { return Objects.requireNonNullElse(method.invoke(object, args), Boolean.TRUE); }
		catch (InvocationTargetException failure) { if (failure.getCause() instanceof Exception cause) throw cause; throw failure; }
	}
}
