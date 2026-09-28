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
}
