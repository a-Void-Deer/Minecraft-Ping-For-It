package nx.pingwheel.common.presentation.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.domain.Target;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientPresentationTest {
	private static final String BASIC = ClientPresentation.BASIC;
	private static final String NAME = ClientPresentation.NAME;
	private static final String HEALTH = "minecraft:entity.health";
	private static final MarkerId ID = new MarkerId(42);
	private static final PresentationPropertyRef DEFAULT = PresentationPropertyRef.root(BASIC, HEALTH);
	private final List<IPacket> sent = new ArrayList<>();
	private final ClientPresentation client = new ClientPresentation(sent::add);

	private void offer() {
		client.tick(true);
		assertEquals(PresentationC2SPacket.Kind.HELLO, ((PresentationC2SPacket) sent.get(0)).kind());
		assertTrue(client.offer(PresentationS2CPacket.offer(81, Map.of(BASIC, List.of(
			new PresentationField(NAME, PresentationField.Kind.TEXT, true, 0, "Name"),
			new PresentationField(HEALTH, PresentationField.Kind.NUMBER, true, 0, "Health"))), Map.of(BASIC, 1))));
		assertEquals(1, sent.size(), "offer does not prompt a subscription");
		assertFalse(client.ready());
	}

	private PresentationSection initial() {
		return new PresentationSection(BASIC, 1, Map.of(NAME, new PresentationValue.Text("{\"text\":\"Server\"}"),
			HEALTH, new PresentationValue.NumberValue(6)), false, Map.of(DEFAULT, "danger"));
	}

	@Test void helloOfferResetGuardsEpochAndViewWithoutClientPreference() {
		offer();
		assertFalse(client.reset(PresentationS2CPacket.reset(82, 1, Map.of())));
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 1,
			Map.of("entity", Map.of(BASIC, Set.of(NAME, HEALTH))))));
		assertTrue(client.ready());
		client.store().initial(81, 1, ID.value(), "entity", DEFAULT, initial());
		assertEquals(new PresentationValue.NumberValue(6), client.view(ID).property(DEFAULT));
		assertEquals(initial().fields().get(NAME), client.view(ID).field(BASIC, NAME),
			"the name is independent of the health default and its annotation");
		assertFalse(client.reset(PresentationS2CPacket.reset(81, 0, Map.of())));
		assertEquals(1, sent.size());
	}

	@Test void tighterMaskPrunesFrozenFieldsAndAnnotationsWithoutResurrection() {
		offer();
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 1,
			Map.of("entity", Map.of(BASIC, Set.of(NAME, HEALTH))))));
		client.store().initial(81, 1, ID.value(), "entity", DEFAULT, initial());
		client.removed(ID, 2, true);
		assertTrue(client.store().isFrozen(ID.value()));
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 2,
			Map.of("entity", Map.of(BASIC, Set.of(NAME))))));
		assertNull(client.view(ID).field(BASIC, HEALTH));
		assertEquals(initial().fields().get(NAME), client.view(ID).field(BASIC, NAME));
		assertTrue(client.view(ID).sections().get(BASIC).annotations().isEmpty());
		assertEquals(DEFAULT, client.view(ID).defaultRef(), "an unauthorized default is not replaced with an invented one");
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 3,
			Map.of("entity", Map.of(BASIC, Set.of(NAME, HEALTH))))));
		assertNull(client.view(ID).property(DEFAULT), "loosening cannot resurrect pruned frozen values");
	}

	@Test void acceptedMetadataIsPreservedButNotAnAuthorizationMask() {
		offer();
		assertTrue(client.catalogOffered());
		assertEquals(2, client.acceptedCatalog().entries().size());
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 1, Map.of())));
		client.store().initial(81, 1, ID.value(), "entity", DEFAULT, initial());
		assertTrue(client.view(ID).sections().isEmpty(), "missing server mask denies even offered fields");
		client.close();
		assertFalse(client.catalogOffered());
		assertTrue(client.acceptedCatalog().isEmpty());
	}

	@Test void createdPacketInitializesTargetTypeAndDefaultAndSectionsCarryNestedAnnotations() {
		offer();
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 1,
			Map.of("entity", Map.of(BASIC, Set.of(NAME, HEALTH))))));
		var snapshot = new MarkerSnapshot(ID, UUID.randomUUID(),
			new Target.LocationTarget("minecraft:overworld", 1, 2, 3), "entity", "attention",
			new MarkerAnchor(1, 2, 3), 1, 100);
		assertTrue(client.initial(PresentationS2CPacket.created(81, 1, 1, snapshot, "Owner", DEFAULT, initial())));
		assertEquals("entity", client.view(ID).targetTypeId());
		assertEquals(DEFAULT, client.view(ID).defaultRef());
		assertEquals("danger", client.view(ID).sections().get(BASIC).annotations().get(DEFAULT));
		assertTrue(client.section(PresentationS2CPacket.section(81, 1, 2, ID,
			new PresentationSection(BASIC, 1, Map.of(HEALTH, new PresentationValue.NumberValue(8)), false,
				Map.of(DEFAULT, "request")))));
		assertEquals(new PresentationValue.NumberValue(8), client.view(ID).property(DEFAULT));
		assertEquals("request", client.view(ID).sections().get(BASIC).annotations().get(DEFAULT));
		assertNull(client.view(ID).field(BASIC, NAME), "a section replaces rather than merges fields");
	}

	@Test void masksAuthorizeEachMarkerByItsStoredTypeRatherThanItsAdapterOrName() {
		offer();
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 1,
			Map.of("entity", Map.of(BASIC, Set.of(HEALTH)), "block", Map.of(BASIC, Set.of(NAME))))));
		MarkerId blockId = new MarkerId(43);
		client.store().initial(81, 1, ID.value(), "entity", DEFAULT, initial());
		client.store().initial(81, 1, blockId.value(), "block",
			PresentationPropertyRef.root(BASIC, NAME), initial());
		assertNull(client.view(ID).field(BASIC, NAME));
		assertNotNull(client.view(ID).field(BASIC, HEALTH));
		assertNotNull(client.view(blockId).field(BASIC, NAME));
		assertNull(client.view(blockId).field(BASIC, HEALTH));
		assertEquals(Map.of(), client.view(blockId).sections().get(BASIC).annotations());
	}

	@Test void childOnlyResetPrunesFrozenAnnotationsAndReferenceWithoutDestroyingRootOrResurrection() {
		String state = "minecraft:block.state";
		var denied = new PresentationPropertyRef(BASIC, state, List.of("denied"));
		var descendant = new PresentationPropertyRef(BASIC, state, List.of("denied", "allowed"));
		var sibling = new PresentationPropertyRef(BASIC, state, List.of("sibling"));
		client.tick(true);
		assertTrue(client.offer(PresentationS2CPacket.offer(81, Map.of(BASIC, List.of(
			new PresentationField(state, PresentationField.Kind.RECORD, true, 0, "State"))), Map.of(BASIC, 1))));
		var mask = Map.of("block", Map.of(BASIC, Set.of(state)));
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 1, mask)));
		var value = new PresentationValue.RecordValue(Map.of("denied", new PresentationValue.RecordValue(
			Map.of("allowed", new PresentationValue.Flag(true))), "sibling", new PresentationValue.NumberValue(2)));
		var section = new PresentationSection(BASIC, 1, Map.of(state, value), false,
			Map.of(denied, "danger", descendant, "attention", sibling, "attention"));
		client.store().initial(81, 1, ID.value(), "block", denied, section);
		client.removed(ID, 2, true);
		var children = new java.util.LinkedHashMap<>(PresentationS2CPacket.emptyChildBlack());
		children.put("block", List.of(denied));
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 2, mask, children)));
		var shown = client.view(ID);
		assertNull(shown.defaultRef()); assertNull(shown.property(denied));
		assertEquals(value, shown.field(BASIC, state), "authorization never strips nested keys from a root record");
		assertEquals(new PresentationValue.Flag(true), shown.property(descendant), "exact denial does not cascade");
		assertEquals(Set.of(descendant, sibling), shown.sections().get(BASIC).annotations().keySet());
		assertFalse(client.propertyAllowed("block", denied));
		assertTrue(client.previewAccess("block").orElseThrow().allows(descendant));
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 3, mask)));
		assertNull(client.view(ID).defaultRef(), "loosening cannot restore a removed frozen reference");
		assertFalse(client.view(ID).sections().get(BASIC).annotations().containsKey(denied));
		assertFalse(client.section(PresentationS2CPacket.section(81, 2, 4, ID, section)));
		assertFalse(client.section(PresentationS2CPacket.section(81, 3, 4, ID, section)), "frozen data cannot resurrect annotations");
	}

	@Test void incomingSectionDropsDeniedAnnotationsAndReceiptCannotSelectDeniedChild() {
		String state = "minecraft:block.state";
		var denied = new PresentationPropertyRef(BASIC, state, List.of("lit"));
		var allowed = new PresentationPropertyRef(BASIC, state, List.of("facing"));
		client.tick(true);
		assertTrue(client.offer(PresentationS2CPacket.offer(81, Map.of(BASIC, List.of(
			new PresentationField(state, PresentationField.Kind.RECORD, true, 0, "State"))), Map.of(BASIC, 1))));
		var children = new java.util.LinkedHashMap<>(PresentationS2CPacket.emptyChildBlack()); children.put("block", List.of(denied));
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 1, Map.of("block", Map.of(BASIC, Set.of(state))), children)));
		var section = new PresentationSection(BASIC, 1, Map.of(state, new PresentationValue.RecordValue(
			Map.of("lit", new PresentationValue.Flag(true), "facing", new PresentationValue.Text("north")))), false,
			Map.of(denied, "danger", allowed, "attention"));
		var snapshot = new MarkerSnapshot(ID, UUID.randomUUID(), new Target.LocationTarget("minecraft:overworld", 1, 2, 3),
			"block", "attention", new MarkerAnchor(1, 2, 3), 1, 100);
		assertFalse(client.initial(PresentationS2CPacket.created(81, 1, 1, snapshot, "Owner", PresentationPropertyRef.root(BASIC, state),
			nx.pingwheel.common.presentation.PresentationReceiptContent.properties(List.of(denied)), section)));
		assertFalse(client.store().isKnown(ID.value()), "receipt validation is atomic with marker acceptance");
		assertTrue(client.initial(PresentationS2CPacket.created(81, 1, 1, snapshot, "Owner", PresentationPropertyRef.root(BASIC, state), section)));
		assertEquals(Set.of(allowed), client.view(ID).sections().get(BASIC).annotations().keySet());
		assertTrue(client.section(PresentationS2CPacket.section(81, 1, 2, ID, section)));
		assertEquals(Set.of(allowed), client.view(ID).sections().get(BASIC).annotations().keySet());
		assertEquals(section.fields().get(state), client.view(ID).field(BASIC, state));
	}

	@Test void resetZeroCannotPublishAuthorityAndTombstonesHaveNoProjection() {
		offer();
		assertFalse(client.reset(PresentationS2CPacket.reset(81, 0, Map.of())));
		assertFalse(client.ready()); assertTrue(client.previewAccess("block").isEmpty());
		assertTrue(client.reset(PresentationS2CPacket.reset(81, 1, Map.of("entity", Map.of(BASIC, Set.of(NAME, HEALTH))))));
		client.store().initial(81, 1, ID.value(), "entity", DEFAULT, initial());
		client.removed(ID, 2, false);
		assertTrue(client.store().isKnown(ID.value()));
		assertEquals(PresentationView.empty(), client.view(ID));
		client.close(); assertTrue(client.previewAccess("entity").isEmpty());
	}
}
