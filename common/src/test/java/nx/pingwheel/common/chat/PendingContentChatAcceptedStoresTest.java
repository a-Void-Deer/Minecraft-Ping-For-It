package nx.pingwheel.common.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real accepted stores and composer; this fixture is not production runtime wiring. */
class PendingContentChatAcceptedStoresTest {

	private static final String BASIC = PresentationBasic.ID;
	private static final MarkerId ID = new MarkerId(42);
	private static final PresentationPropertyRef NAME = PresentationPropertyRef.root(BASIC, PresentationBasic.NAME);
	private static final PresentationPropertyRef HEALTH = PresentationPropertyRef.root(BASIC, PresentationBasic.HEALTH);
	private static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	private static final MarkerSnapshot SNAPSHOT = new MarkerSnapshot(ID, new UUID(1, 2), TARGET, "entity_block", "attention",
		new MarkerAnchor(1, 2, 3), 1, 100);

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void acceptedBasicCompletesExactlyOnceAndKnownCreatedNeverRepeatsEvenAfterVisualDeadline() {
		Fixture f = new Fixture();
		var created = f.created(1, PresentationReceiptContent.properties(List.of(HEALTH)), basic(true));
		assertTrue(f.createdReceipt(created));
		assertFalse(f.markers.marker(ID).orElseThrow().isVisuallyActiveAt(1), "zero local display deadline is intentional");
		assertTrue(f.controller.attempt(ID), "local visual expiry is not authoritative expiry");
		assertEquals(1, f.sent.size());
		assertTrue(f.createdReceipt(f.created(2, created.content(), basic(true))));
		assertFalse(f.controller.attempt(ID));
		assertEquals(1, f.sent.size());
		assertFalse(f.createdReceipt(created), "old initial is not accepted");
	}

	@Test void acceptedMixedAnnotationsCompleteOneMessageWithEachEntrysOwnColoredPhrase() {
		Fixture f = new Fixture();
		var section = new PresentationSection(BASIC, 1, basic(true).fields(), false,
			Map.of(NAME, "danger", HEALTH, "attention"));
		var content = PresentationReceiptContent.properties(List.of(NAME, HEALTH));
		assertTrue(f.createdReceipt(f.created(1, content, section)));
		assertTrue(f.controller.attempt(ID));
		assertEquals(1, f.sent.size());
		List<Component> phrases = new ArrayList<>();
		collectPhrases(f.sent.getFirst(), phrases);
		assertEquals(content.selectedRefs().stream().map(ref -> "presentation.pingforit.type."
			+ section.annotations().get(ref) + ".display").toList(),
			phrases.stream().map(component -> ((TranslatableContents) component.getContents()).getKey()).toList());
		assertEquals(2, phrases.size(), "there is no extra main/default phrase");
		assertEquals(PingTypeCatalog.builtIn().findById("attention").orElseThrow().textColor(),
			phrases.getFirst().getStyle().getColor().getValue());
		assertEquals(PingTypeCatalog.builtIn().findById("danger").orElseThrow().textColor(),
			phrases.getLast().getStyle().getColor().getValue());
		assertFalse(f.controller.attempt(ID));
		assertEquals(1, f.sent.size());
	}

	@Test void acceptedMissingThenUnannotatedSecondaryValueWaitsUntilItsOwnAnnotationArrives() {
		Fixture f = new Fixture();
		var content = PresentationReceiptContent.properties(List.of(HEALTH,
			PresentationPropertyRef.root(BASIC, PresentationBasic.MAX_HEALTH)));
		var initial = new PresentationSection(BASIC, 1, Map.of(
			PresentationBasic.NAME, basic(false).fields().get(PresentationBasic.NAME),
			PresentationBasic.HEALTH, new PresentationValue.NumberValue(7)), false, Map.of(HEALTH, "attention"));
		assertTrue(f.createdReceipt(f.created(1, content, initial)));
		assertFalse(f.controller.attempt(ID));
		assertTrue(f.presentation.section(PresentationS2CPacket.section(81, 1, 2, ID, basic(true))));
		assertFalse(f.controller.attempt(ID), "present max-health context is not its own explicit annotation");
		assertTrue(f.sent.isEmpty());
		var complete = new PresentationSection(BASIC, 1, basic(true).fields(), false,
			Map.of(HEALTH, "attention", PresentationPropertyRef.root(BASIC, PresentationBasic.MAX_HEALTH), "danger"));
		assertTrue(f.presentation.section(PresentationS2CPacket.section(81, 1, 3, ID, complete)));
		assertTrue(f.controller.attempt(ID));
		assertEquals(1, f.sent.size());
	}

	@Test void fragmentedTrackingWaitsForCommitted96AndPreviewStatusAndLaterStreamNeverProduceAnotherLine() {
		Fixture f = new Fixture();
		assertTrue(f.createdReceipt(f.created(1, PresentationReceiptContent.inventory(), basic(false))));
		long preview = f.inventory.open(TARGET, BlockFace.NORTH, "entity_block");
		assertTrue(preview > 0);
		f.inventory.accept(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, preview, null, 2, 1, 1,
			0, 1, true, InventoryS2CPacket.Status.READY, 0, List.of(item(123, 1))).stamp(81, 1));
		assertEquals(123, f.inventory.preview(preview).entries().getFirst().count());
		assertFalse(f.controller.attempt(ID), "preview completeness is not tracking authority");
		f.inventory.accept(InventoryS2CPacket.status(7, 0, ID, 4, 1, 1, InventoryS2CPacket.Status.READY, 0).stamp(81, 1));
		assertFalse(f.inventory.tracking(ID).complete());
		assertFalse(f.controller.attempt(ID), "status-only readiness does not commit a baseline");
		f.inventory.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, 4, 1, 1, 0, 2,
			InventoryS2CPacket.Status.UPDATING, List.of(item(96, 1))));
		assertFalse(f.inventory.tracking(ID).complete());
		assertTrue(f.inventory.tracking(ID).entries().isEmpty(), "partial assembly stays invisible");
		assertFalse(f.controller.attempt(ID));
		f.inventory.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, 4, 1, 1, 1, 2,
			InventoryS2CPacket.Status.READY, List.of()));
		assertTrue(f.inventory.tracking(ID).complete());
		assertEquals(96, f.inventory.tracking(ID).entries().getFirst().count());
		assertTrue(f.controller.attempt(ID));
		assertEquals("96", countArgument(f.sent.getFirst()));
		f.inventory.accept(data(InventoryS2CPacket.Kind.STREAM, 4, 1, 2, 0, 1,
			InventoryS2CPacket.Status.READY, List.of(item(97, 2))));
		assertEquals(97, f.inventory.tracking(ID).entries().getFirst().count());
		assertFalse(f.controller.attempt(ID));
		assertEquals(1, f.sent.size());
	}

	@Test void invalidatedCountIsGreyAndNewStatusCannotResurrectItButCompleteZeroIsAuthoritative() {
		Fixture f = new Fixture();
		assertTrue(f.createdReceipt(f.created(1, PresentationReceiptContent.inventory(), basic(false))));
		f.inventory.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, 4, 1, 1, 0, 1,
			InventoryS2CPacket.Status.READY, List.of(item(96, 1))));
		f.inventory.accept(InventoryS2CPacket.status(7, 0, ID, 4, 2, 1, InventoryS2CPacket.Status.INVALID, 0).stamp(81, 1));
		assertTrue(f.inventory.tracking(ID).grey());
		assertFalse(f.controller.attempt(ID));
		f.inventory.accept(InventoryS2CPacket.status(7, 0, ID, 5, 3, 2, InventoryS2CPacket.Status.READY, 0).stamp(81, 1));
		assertFalse(f.inventory.tracking(ID).complete());
		assertFalse(f.controller.attempt(ID));
		f.inventory.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, 5, 3, 2, 0, 1,
			InventoryS2CPacket.Status.READY, List.of(item(0, 2))));
		assertTrue(f.controller.attempt(ID));
		assertEquals("0", countArgument(f.sent.getFirst()));
	}

	@Test void acceptedResetCancelsPendingAndOldViewOrKnownNewViewCannotRestartIt() {
		Fixture f = new Fixture();
		assertTrue(f.createdReceipt(f.created(1, PresentationReceiptContent.properties(List.of(HEALTH)), basic(false))));
		assertFalse(f.controller.attempt(ID));
		assertTrue(f.presentation.reset(PresentationS2CPacket.reset(81, 2, mask())));
		f.controller.reset(); f.inventory.presentationReset(81, 2);
		assertFalse(f.presentation.section(PresentationS2CPacket.section(81, 1, 2, ID, basic(true))));
		assertFalse(f.controller.attempt(ID));
		var replay = PresentationS2CPacket.created(81, 2, 2, SNAPSHOT, "Owner", NAME,
			PresentationReceiptContent.properties(List.of(HEALTH)), basic(true));
		assertTrue(f.createdReceipt(replay));
		assertFalse(f.controller.attempt(ID));
		assertEquals(0, f.controller.pendingCount());
		assertTrue(f.sent.isEmpty());
	}

	@Test void authoritativeExpiryCancelsAlthoughTheStoreRetainsAVisualRecord() {
		Fixture f = new Fixture(200);
		assertTrue(f.createdReceipt(f.created(1, PresentationReceiptContent.properties(List.of(HEALTH)), basic(false))));
		f.markers.onRemoved(ID, MarkerRemovalReason.EXPIRED, 2);
		f.presentation.removed(ID, 2, true); f.inventory.markerRemoved(ID); f.controller.cancel(ID);
		assertTrue(f.markers.marker(ID).isPresent(), "expiry may retain a visual record");
		assertFalse(f.presentation.section(PresentationS2CPacket.section(81, 1, 3, ID, basic(true))));
		assertFalse(f.controller.attempt(ID));
		assertTrue(f.sent.isEmpty());
	}

	private static final class Fixture {
		final ClientPresentation presentation = new ClientPresentation(packet -> {});
		final ClientInventory inventory = new ClientInventory(packet -> {});
		final ClientMarkerStore markers;
		final List<Component> sent = new ArrayList<>();
		final PendingContentChatController controller;
		Fixture() { this(0); }
		Fixture(long displayDuration) {
			markers = new ClientMarkerStore(0, displayDuration);
			presentation.tick(true);
			assertTrue(presentation.offer(PresentationS2CPacket.offer(81, Map.of(BASIC, List.of(
				new PresentationField(PresentationBasic.NAME, PresentationField.Kind.TEXT, true, 0, "Name"),
				new PresentationField(PresentationBasic.HEALTH, PresentationField.Kind.NUMBER, true, 0, "Health"),
				new PresentationField(PresentationBasic.MAX_HEALTH, PresentationField.Kind.NUMBER, true, 0, "Max"))), Map.of(BASIC, 1))));
			assertTrue(presentation.reset(PresentationS2CPacket.reset(81, 1, mask())));
			inventory.presentationReset(81, 1);
			inventory.accept(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(10, 20, 3, 0)).stamp(81, 1));
			inventory.accept(InventoryS2CPacket.policy(7, 81, 1, Set.of("entity_block")));
			controller = new PendingContentChatController(4, id -> {
				ClientMarker marker = markers.marker(id).orElse(null);
				Map<String, Set<String>> allowed = new LinkedHashMap<>();
				presentation.previewAccess("entity_block").ifPresent(access ->
					access.adapters().forEach((adapter, descriptor) -> allowed.put(adapter, descriptor.fields().keySet())));
				return new PendingContentChatController.Current(presentation.epoch(), presentation.sessionView(), presentation.ready(),
					marker != null && marker.isSynchronized() && !markers.isAuthoritativelyRemoved(id),
					marker == null ? null : PendingContentChatController.MarkerIdentity.of(marker), allowed,
					inventory.ready() ? PendingContentChatController.Authorization.ALLOWED : PendingContentChatController.Authorization.UNKNOWN,
					presentation.view(id), inventory.tracking(id));
			}, ContentChatComposer.nameJsonDecoder(RegistryAccess.EMPTY), ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY),
				ignored -> ContentChatTemplate.DEFAULT_TEMPLATE, sent::add);
		}
		PresentationS2CPacket created(long revision, PresentationReceiptContent receipt, PresentationSection basic) {
			return PresentationS2CPacket.created(81, 1, revision, SNAPSHOT, "Owner", NAME, receipt, basic);
		}
		boolean createdReceipt(PresentationS2CPacket packet) {
			if (!presentation.initial(packet)) return false;
			boolean newlySeen = markers.marker(packet.markerId()).isEmpty();
			markers.onCreated(packet.snapshot(), 1);
			inventory.markerCreated(packet.snapshot());
			controller.begin(packet, newlySeen);
			return true;
		}
	}

	private static Map<String, Map<String, Set<String>>> mask() {
		return Map.of("entity_block", Map.of(BASIC, Set.of(PresentationBasic.NAME, PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH)));
	}
	private static PresentationSection basic(boolean health) {
		Map<String, PresentationValue> fields = new LinkedHashMap<>();
		fields.put(PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"Chest\"}"));
		if (health) {
			fields.put(PresentationBasic.HEALTH, new PresentationValue.NumberValue(7));
			fields.put(PresentationBasic.MAX_HEALTH, new PresentationValue.NumberValue(20));
		}
		return new PresentationSection(BASIC, 1, fields, false, health ? Map.of(HEALTH, "attention") : Map.of());
	}
	private static InventoryS2CPacket.Entry item(long count, long revision) {
		return new InventoryS2CPacket.Entry("selected", "minecraft:gunpowder", "item.minecraft.gunpowder", null,
			count, revision, false, InventoryS2CPacket.Status.READY, 0, false, "take");
	}
	private static InventoryS2CPacket data(InventoryS2CPacket.Kind kind, long baseline, long revision, long watermark,
		int part, int total, InventoryS2CPacket.Status status, List<InventoryS2CPacket.Entry> entries) {
		return InventoryS2CPacket.data(kind, 7, 0, ID, baseline, revision, watermark, part, total, true, status, 0, entries).stamp(81, 1);
	}
	private static String countArgument(Component component) {
		if (component.getContents() instanceof TranslatableContents translated) {
			if (ContentChatTemplate.ITEM_COUNT_KEY.equals(translated.getKey())) return String.valueOf(translated.getArgs()[1]);
			for (Object arg : translated.getArgs()) if (arg instanceof Component child) {
				String found = countArgument(child); if (found != null) return found;
			}
		}
		for (Component sibling : component.getSiblings()) {
			String found = countArgument(sibling); if (found != null) return found;
		}
		return null;
	}

	private static void collectPhrases(Component component, List<Component> result) {
		if (component.getContents() instanceof TranslatableContents translated) {
			if (translated.getKey().startsWith("presentation.pingforit.type.") && translated.getKey().endsWith(".display"))
				result.add(component);
			for (Object arg : translated.getArgs())
				if (arg instanceof Component child) collectPhrases(child, result);
		}
		for (Component sibling : component.getSiblings()) collectPhrases(sibling, result);
	}
}
