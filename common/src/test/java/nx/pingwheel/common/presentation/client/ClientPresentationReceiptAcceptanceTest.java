package nx.pingwheel.common.presentation.client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.InventoryCreationSnapshot;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ClientPresentationReceiptAcceptanceTest {

	private static final String BASIC = PresentationBasic.ID;
	private static final MarkerId ID = new MarkerId(42);
	private static final PresentationPropertyRef NAME = PresentationPropertyRef.root(BASIC, PresentationBasic.NAME);
	private static final PresentationPropertyRef HEALTH = PresentationPropertyRef.root(BASIC, PresentationBasic.HEALTH);
	private static final PresentationPropertyRef CUSTOM = PresentationPropertyRef.root(BASIC, PresentationBasic.CUSTOM_NAME);
	private static final MarkerSnapshot SNAPSHOT = new MarkerSnapshot(ID, new UUID(1, 2),
		new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"), "entity_block", "attention",
		new MarkerAnchor(1, 2, 3), 1, 100);

	@Test void deniedOrUnacceptedSelectionRejectsBeforeAtomicStoreMutation() {
		ClientPresentation denied = ready(PresentationBasic.fields(), Set.of(PresentationBasic.NAME));
		assertFalse(denied.initial(created(1, PresentationReceiptContent.properties(List.of(HEALTH)), basic(true))));
		assertFalse(denied.store().isKnown(ID.value()));
		assertTrue(denied.view(ID).sections().isEmpty());

		ClientPresentation unaccepted = ready(List.of(new PresentationField(PresentationBasic.NAME,
			PresentationField.Kind.TEXT, true, 0, "Name")), Set.of(PresentationBasic.NAME, PresentationBasic.HEALTH));
		assertFalse(unaccepted.initial(created(1, PresentationReceiptContent.properties(List.of(HEALTH)), basic(true))));
		assertFalse(unaccepted.store().isKnown(ID.value()), "an advertised mask cannot grant an unaccepted field");
		assertTrue(unaccepted.previewAccess("entity_block").orElseThrow().fields(BASIC).contains(PresentationBasic.NAME));
		assertFalse(unaccepted.previewAccess("entity_block").orElseThrow().fields(BASIC).contains(PresentationBasic.HEALTH));
	}

	@Test void nestedScalarSelectionAndPresentUnannotatedValueRejectWithoutRollingBackOldBasic() {
		var client = ready(PresentationBasic.fields(), Set.of(PresentationBasic.NAME, PresentationBasic.HEALTH));
		assertTrue(client.initial(created(1, PresentationReceiptContent.whole(), basic(true))));
		var before = client.view(ID);
		var nested = new PresentationPropertyRef(BASIC, PresentationBasic.HEALTH, List.of("not-a-record"));
		assertFalse(client.initial(created(2, PresentationReceiptContent.properties(List.of(nested)), basic(true))));
		var unannotated = new PresentationSection(BASIC, 1, Map.of(PresentationBasic.NAME,
			new PresentationValue.Text("{\"text\":\"Replacement\"}"), PresentationBasic.HEALTH,
			new PresentationValue.NumberValue(99)), false);
		assertFalse(client.initial(created(3, PresentationReceiptContent.properties(List.of(HEALTH)), unannotated)));
		assertEquals(before, client.view(ID), "neither malformed metadata candidate may replace the existing initial");
		assertEquals(1, client.store().sections(ID.value()).get(BASIC).revision());
	}

	@Test void missingAuthorizedValueWaitsAndSuppressedRetainsNoReceiptReferences() {
		var client = ready(PresentationBasic.fields(), Set.of(PresentationBasic.NAME, PresentationBasic.HEALTH));
		var pending = created(1, PresentationReceiptContent.properties(List.of(HEALTH)), basic(false));
		assertTrue(client.initial(pending), "missing authorized data is not malformed selection metadata");
		assertNull(client.view(ID).property(HEALTH));
		assertEquals(NAME, client.view(ID).defaultRef(), "receipt refs do not change the HUD default ref");
		var suppressed = created(2, PresentationReceiptContent.suppressed(), basic(false));
		assertTrue(client.initial(suppressed));
		assertTrue(suppressed.content().selectedRefs().isEmpty());
		assertEquals(NAME, client.view(ID).defaultRef());
	}

	@Test void customNameManifestFieldIsAcceptedAsRawTextBesideComposedName() {
		var client = ready(PresentationBasic.fields(), Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME));
		var basic = new PresentationSection(BASIC, 1, Map.of(PresentationBasic.NAME,
			new PresentationValue.Text("{\"text\":\"Custom (Chest)\"}"), PresentationBasic.CUSTOM_NAME,
			new PresentationValue.Text("Custom")), false, Map.of(CUSTOM, "request"));
		assertTrue(client.initial(created(1, PresentationReceiptContent.properties(List.of(CUSTOM)), basic)));
		assertEquals(new PresentationValue.Text("Custom"), client.view(ID).property(CUSTOM));
		assertEquals(new PresentationValue.Text("{\"text\":\"Custom (Chest)\"}"), client.view(ID).property(NAME));
		assertTrue(client.previewAccess("entity_block").orElseThrow().fields(BASIC).contains(PresentationBasic.CUSTOM_NAME));
	}

	@ParameterizedTest
	@ValueSource(strings = {"epoch", "view", "version", "marker-id", "target", "type", "external-type", "ping", "ref",
		"missing-name", "stale-name", "denied-name", "schema", "lane", "basic-frame"})
	void invalidInventoryInitialCannotRegisterOrReplaceAnyPresentationState(String fault) {
		ClientPresentation client = inventoryReady(fault);
		assertTrue(client.initial(created(1, PresentationReceiptContent.whole(), basic(false))));
		var before = client.view(ID);
		var content = inventoryReceipt();
		MarkerSnapshot marker = switch (fault) {
			case "target" -> new MarkerSnapshot(ID, SNAPSHOT.owner(), new Target.LocationTarget("minecraft:overworld", 1, 2, 3),
				"entity_block", "attention", SNAPSHOT.anchor(), 1, 100);
			case "type" -> new MarkerSnapshot(ID, SNAPSHOT.owner(), SNAPSHOT.target(), "location", "attention", SNAPSHOT.anchor(), 1, 100);
			case "external-type" -> new MarkerSnapshot(ID, SNAPSHOT.owner(), Target.ExternalBlockTarget.committed(
				"minecraft:overworld", "sable", "tracking", "minecraft:stone", "opaque", false),
				"entity_block", "attention", SNAPSHOT.anchor(), 1, 100);
			case "ping" -> new MarkerSnapshot(ID, SNAPSHOT.owner(), SNAPSHOT.target(), "entity_block", "not_registered", SNAPSHOT.anchor(), 1, 100);
			default -> SNAPSHOT;
		};
		PresentationSection section = switch (fault) {
			case "missing-name" -> new PresentationSection(BASIC, 1, Map.of(), false);
			case "stale-name" -> new PresentationSection(BASIC, 1, basic(false).fields(), true);
			default -> basic(false);
		};
		var base = PresentationS2CPacket.created(fault.equals("epoch") ? 82 : 81, fault.equals("view") ? 2 : 1,
			2, marker, "Replacement", fault.equals("ref") ? HEALTH : NAME, content, section);
		var candidate = new PresentationS2CPacket(base.kind(), fault.equals("version") ? base.protocol() + 1 : base.protocol(),
			base.epoch(), base.view(), base.revision(), base.manifest(), base.schemas(), base.mask(), base.childBlack(), base.snapshot(),
			base.ownerName(), base.defaultRef(), base.content(), fault.equals("basic-frame") ? new byte[] {(byte) 255} : base.sectionBytes(),
			fault.equals("marker-id") ? new MarkerId(99) : base.markerId(), base.removalReason(), base.targetKey(), base.winnerId(),
			base.requestId(), base.requestKind(), base.rejectReason());
		assertFalse(client.initial(candidate));
		assertEquals(before, client.view(ID));
		assertEquals(1, client.store().sections(ID.value()).get(BASIC).revision());
		assertFalse(client.store().isKnown(99));
	}

	@Test void inventoryCreationAcceptsNegotiatedLaneWithoutSectionFieldGrantOrLocalRegistryKnowledge() {
		ClientPresentation client = inventoryReady("valid");
		assertTrue(client.initial(created(1, inventoryReceipt(), basic(false))));
		assertEquals(NAME, client.view(ID).defaultRef());
		assertEquals(basic(false).fields().get(PresentationBasic.NAME), client.view(ID).property(NAME));
	}

	private static PresentationReceiptContent inventoryReceipt() {
		return PresentationReceiptContent.inventory(new InventoryCreationSnapshot(InventoryResourceKind.ITEM, "selected",
			"uninstalled:resource", "item.uninstalled.resource", null, 5, false, "take", false, true));
	}
	private static ClientPresentation inventoryReady(String fault) {
		ClientPresentation client = new ClientPresentation(packet -> {});
		client.tick(true);
		var inventoryFields = fault.equals("lane") ? InventoryPresentation.INSTANCE.fields().stream()
			.filter(field -> !field.id().equals(InventoryPresentation.ITEMS)).toList() : InventoryPresentation.INSTANCE.fields();
		assertTrue(client.offer(PresentationS2CPacket.offer(81, Map.of(BASIC, PresentationBasic.fields(),
			InventoryPresentation.ADAPTER_ID, inventoryFields), Map.of(BASIC, 1, InventoryPresentation.ADAPTER_ID,
			InventoryPresentation.SCHEMA + (fault.equals("schema") ? 1 : 0)))));
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 1, Map.of("entity_block", Map.of(BASIC,
			fault.equals("denied-name") ? Set.of(PresentationBasic.HEALTH) : Set.of(PresentationBasic.NAME))))));
		return client;
	}

	private static ClientPresentation ready(List<PresentationField> offeredFields, Set<String> allowed) {
		ClientPresentation client = new ClientPresentation(packet -> {});
		client.tick(true);
		assertTrue(client.offer(PresentationS2CPacket.offer(81, Map.of(BASIC, offeredFields), Map.of(BASIC, 1))));
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 1, Map.of("entity_block", Map.of(BASIC, allowed)))));
		return client;
	}
	private static PresentationS2CPacket created(long revision, PresentationReceiptContent content, PresentationSection basic) {
		return PresentationS2CPacket.created(81, 1, revision, SNAPSHOT, "Owner", NAME, content, basic);
	}
	private static PresentationSection basic(boolean health) {
		return new PresentationSection(BASIC, 1, health ? Map.of(PresentationBasic.NAME,
			new PresentationValue.Text("{\"text\":\"Chest\"}"), PresentationBasic.HEALTH,
			new PresentationValue.NumberValue(7)) : Map.of(PresentationBasic.NAME,
			new PresentationValue.Text("{\"text\":\"Chest\"}")), false, health ? Map.of(HEALTH, "danger") : Map.of());
	}
}
