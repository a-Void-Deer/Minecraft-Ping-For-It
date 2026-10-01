package nx.pingwheel.common.presentation.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientDedicatedDeliveryTest {
	private static final String BASIC = ClientPresentation.BASIC;
	private static final String NAME = ClientPresentation.NAME;
	private static final long EPOCH = 77;
	private static final MarkerId ID = new MarkerId(9);
	private static final PresentationPropertyRef DEFAULT = PresentationPropertyRef.root(BASIC, NAME);

	private final List<IPacket> sent = new ArrayList<>();
	private final ClientPresentation client = new ClientPresentation(sent::add);

	private void offer() {
		client.tick(true);
		var hello = (PresentationC2SPacket) sent.get(0);
		assertEquals(PresentationC2SPacket.Kind.HELLO, hello.kind());
		assertEquals(InventoryPresentation.SCHEMA, hello.schemas().get(InventoryPresentation.ADAPTER_ID),
			"the client must advertise the dedicated adapter schema");
		assertTrue(client.offer(PresentationS2CPacket.offer(EPOCH,
			Map.of(BASIC, PresentationBasic.fields(),
				InventoryPresentation.ADAPTER_ID, InventoryPresentation.INSTANCE.fields()),
			Map.of(BASIC, 1, InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA))));
	}

	@Test
	void dedicatedAdapterIsAdvertisedAndEntersTheAcceptedCatalogue() {
		offer();
		assertTrue(client.manifest().stream()
			.anyMatch(adapter -> adapter.adapterId().equals(InventoryPresentation.ADAPTER_ID)));
		assertTrue(client.acceptedCatalog().contains(InventoryPresentation.ITEMS),
			"the policy catalogue sees the negotiated dedicated field");
	}

	@Test
	void dedicatedSectionsCannotEnterTheLegacyStoreAndNormalSectionsStillApply() {
		offer();
		assertTrue(client.reset(PresentationS2CPacket.reset(EPOCH, 1, Map.of("entity",
			Map.of(BASIC, Set.of(NAME),
				InventoryPresentation.ADAPTER_ID, Set.of(InventoryPresentation.ITEMS))))));
		var snapshot = new MarkerSnapshot(ID, UUID.randomUUID(),
			new Target.LocationTarget("minecraft:overworld", 1, 2, 3), "entity", "attention",
			new MarkerAnchor(1, 2, 3), 1, 100);
		var basic = new PresentationSection(BASIC, 1,
			Map.of(NAME, new PresentationValue.Text("{\"text\":\"Chest\"}")), false);
		assertTrue(client.initial(PresentationS2CPacket.created(EPOCH, 1, 1, snapshot, "Owner", DEFAULT, basic)));

		assertFalse(client.section(PresentationS2CPacket.section(EPOCH, 1, 2, ID,
			new PresentationSection(InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA,
				Map.of(InventoryPresentation.ITEMS, new PresentationValue.RecordValue(
					Map.of("minecraft:stone", new PresentationValue.NumberValue(5)))), false))),
			"a dedicated adapter must not be accepted through the framed SECTION route");
		assertTrue(client.view(ID).sections().keySet().stream()
			.noneMatch(adapter -> adapter.equals(InventoryPresentation.ADAPTER_ID)));

		assertTrue(client.section(PresentationS2CPacket.section(EPOCH, 1, 3, ID,
			new PresentationSection(BASIC, 1,
				Map.of(NAME, new PresentationValue.Text("{\"text\":\"Still\"}")), false))),
			"normal SECTION adapters keep working");
		assertEquals(new PresentationValue.Text("{\"text\":\"Still\"}"), client.view(ID).field(BASIC, NAME));
	}
}
