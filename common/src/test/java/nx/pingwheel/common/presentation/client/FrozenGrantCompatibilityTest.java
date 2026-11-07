package nx.pingwheel.common.presentation.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.MarkerContentGrant;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationGrant;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A legal grant ordinal is not enough: the client typed gate must require the
 * frozen adapter schema and field kind to match both the accepted offer and the
 * local adapter before it accepts CREATED or projects a receipt selection.
 */
@NotNullByDefault
class FrozenGrantCompatibilityTest {
	private static final long EPOCH = 81L;
	private static final long VIEW = 1L;
	private static final String BASIC = PresentationBasic.ID;
	private static final String NAME = PresentationBasic.NAME;
	private static final MarkerId ID = new MarkerId(71);
	private static final PresentationPropertyRef NAME_REF = PresentationPropertyRef.root(BASIC, NAME);
	private static final PresentationPropertyRef ITEMS_REF =
		PresentationPropertyRef.root(InventoryPresentation.ADAPTER_ID, InventoryPresentation.ITEMS);

	@Test
	void grantSchemaMismatchRejectsCreatedEvenWhenTheSectionSchemaMatches() {
		var client = ready(false);
		var packet = created(grant(BASIC, 2, NAME, PresentationField.Kind.TEXT),
			PresentationReceiptContent.properties(List.of(NAME_REF)), basicSection());

		assertFalse(client.initial(packet), "grant schema 2 cannot authorize an accepted Basic schema 1");
		assertTrue(client.markerGrant(ID).isEmpty());
		assertTrue(client.view(ID).sections().isEmpty());
	}

	@Test
	void grantKindMismatchRejectsCreatedEvenWhenGrantAndSectionSchemasMatch() {
		var client = ready(false);
		var packet = created(grant(BASIC, 1, NAME, PresentationField.Kind.NUMBER),
			PresentationReceiptContent.properties(List.of(NAME_REF)), basicSection());

		assertFalse(client.initial(packet), "a NUMBER grant cannot authorize a TEXT section field or its receipt ref");
		assertTrue(client.markerGrant(ID).isEmpty());
		assertTrue(client.view(ID).sections().isEmpty());
	}

	@Test
	void matchingGrantSchemaAndKindAcceptCreated() {
		var client = ready(false);
		var packet = created(grant(BASIC, 1, NAME, PresentationField.Kind.TEXT),
			PresentationReceiptContent.properties(List.of(NAME_REF)), basicSection());

		assertTrue(client.initial(packet));
		assertTrue(client.markerGrant(ID).isPresent());
		assertEquals(new PresentationValue.Text("A"), client.view(ID).field(BASIC, NAME));
	}

	@Test
	void propertyReceiptRejectsAMismatchedDedicatedInventoryAdapterSchema() {
		var client = ready(true);
		var packet = created(inventoryGrant(InventoryPresentation.SCHEMA + 1),
			PresentationReceiptContent.properties(List.of(ITEMS_REF)), basicSection());

		assertFalse(client.initial(packet),
			"a property receipt ref cannot be authorized by a mismatched inventory adapter schema");
		assertTrue(client.markerGrant(ID).isEmpty());
		assertTrue(client.view(ID).sections().isEmpty());
	}

	private static ClientPresentation ready(boolean inventory) {
		ClientPresentation client = new ClientPresentation(packet -> {});
		client.tick(true);
		Map<String, List<PresentationField>> manifest = inventory
			? Map.of(BASIC, PresentationBasic.fields(), InventoryPresentation.ADAPTER_ID,
				InventoryPresentation.INSTANCE.fields())
			: Map.of(BASIC, PresentationBasic.fields());
		Map<String, Integer> schemas = inventory
			? Map.of(BASIC, 1, InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA)
			: Map.of(BASIC, 1);
		assertTrue(client.offer(PresentationS2CPacket.offer(EPOCH, manifest, schemas)));
		assertTrue(client.reset(PresentationS2CPacket.reset(EPOCH, VIEW,
			Map.of("block", Map.of(BASIC, Set.of(NAME))), PresentationS2CPacket.emptyChildBlack())));
		return client;
	}

	private static MarkerContentGrant grant(String adapter, int schema, String field, PresentationField.Kind kind) {
		var fields = new LinkedHashMap<String, PresentationField.Kind>();
		fields.put(field, kind);
		return new MarkerContentGrant(new PresentationGrant(Map.of(adapter,
			new PresentationGrant.AdapterGrant(schema, fields)), Set.of()), null);
	}

	private static MarkerContentGrant inventoryGrant(int inventorySchema) {
		var basic = new LinkedHashMap<String, PresentationField.Kind>();
		basic.put(NAME, PresentationField.Kind.TEXT);
		var inventory = new LinkedHashMap<String, PresentationField.Kind>();
		inventory.put(InventoryPresentation.ITEMS, PresentationField.Kind.RECORD);
		return new MarkerContentGrant(new PresentationGrant(Map.of(
			BASIC, new PresentationGrant.AdapterGrant(1, basic),
			InventoryPresentation.ADAPTER_ID, new PresentationGrant.AdapterGrant(inventorySchema, inventory)), Set.of()), null);
	}

	private static PresentationSection basicSection() {
		return new PresentationSection(BASIC, 1,
			Map.of(NAME, new PresentationValue.Text("A")), false, Map.of(NAME_REF, "danger"));
	}

	private static PresentationS2CPacket created(MarkerContentGrant grant, PresentationReceiptContent content,
		PresentationSection basic) {
		var packet = PresentationS2CPacket.created(EPOCH, VIEW, 1, snapshot(), "Owner", NAME_REF,
			grant, content, basic);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(buffer);
			var decoded = new PresentationS2CPacket(buffer);
			assertFalse(decoded.isCorrupt(), "the legal grant structure must survive the production wire codec");
			return decoded;
		} finally {
			buffer.release();
		}
	}

	private static MarkerSnapshot snapshot() {
		return new MarkerSnapshot(ID, new UUID(0L, ID.value()),
			new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"),
			"block", "attention", new MarkerAnchor(1, 2, 3), 1, 100);
	}
}
