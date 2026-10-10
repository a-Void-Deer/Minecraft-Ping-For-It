package nx.pingwheel.common.presentation.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationPropertySelection;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Atomic-initial receipt projection at the production initial-sending seam:
 * every initial exit computes the descriptor from the marker's explicit
 * selections against the recipient's accepted manifest, mask and fresh
 * authorization, and a denied input suppresses the whole content message.
 */
class PresentationServerReceiptTest {
	private static final UUID OWNER = new UUID(0, 1);
	private static final UUID VIEWER = new UUID(0, 2);
	private static final String BASIC = PresentationBasic.ID;
	private static final String INVENTORY_ADAPTER = InventoryPresentation.ADAPTER_ID;
	private static final String TYPE = "block";
	private static final PresentationPropertyRef NAME =
		PresentationPropertyRef.root(BASIC, PresentationBasic.NAME);
	private static final PresentationPropertyRef HEALTH =
		PresentationPropertyRef.root(BASIC, PresentationBasic.HEALTH);
	private static final PresentationPropertyRef MAX_HEALTH =
		PresentationPropertyRef.root(BASIC, PresentationBasic.MAX_HEALTH);
	private static final PresentationPropertyRef ITEM_COUNT =
		PresentationPropertyRef.root(BASIC, PresentationBasic.ITEM_COUNT);
	private static final PresentationPropertyRef CUSTOM =
		PresentationPropertyRef.root(BASIC, PresentationBasic.CUSTOM_NAME);

	private static ServerMarker marker(List<PresentationPropertySelection> properties) {
		var type = TargetTypeCatalog.builtIn().findById(TYPE).orElseThrow();
		return new ServerMarker(new MarkerId(1), OWNER,
			new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"),
			type, type.defaultPingType(), new MarkerAnchor(1, 2, 3), 2, 500, List.of(VIEWER), properties);
	}

	private static PresentationServer.Session session() {
		return session(PresentationBasic.fields());
	}

	private static PresentationServer.Session session(List<PresentationField> advertised) {
		var session = new PresentationServer.Session(37,
			Map.of(BASIC, 1), Map.of(BASIC, advertised));
		session.mask = Map.of(TYPE, Map.of(BASIC, Set.of(PresentationBasic.NAME, PresentationBasic.HEALTH,
			PresentationBasic.MAX_HEALTH, PresentationBasic.ITEM_COUNT)));
		session.ready = true;
		session.view = 1;
		return session;
	}

	private static PresentationServer.Lease lease(ServerMarker marker) {
		var lease = new PresentationServer.Lease(marker, "Owner");
		var source = new PresentationServer.Source(BASIC, 1);
		source.value = cachedBasic();
		lease.sources.put(BASIC, source);
		return lease;
	}

	private static PresentationSection cachedBasic() {
		return new PresentationSection(BASIC, 1,
			Map.of(PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"Chest\"}")), false);
	}

	/**
	 * A session that accepted the dedicated inventory route: the cached
	 * advertised inventory view contains the marker's target type, while the
	 * named Basic field stays authorized.
	 */
	private static PresentationServer.Session inventorySession() {
		var session = new PresentationServer.Session(37,
			Map.of(BASIC, 1, INVENTORY_ADAPTER, InventoryPresentation.SCHEMA),
			Map.of(BASIC, PresentationBasic.fields()));
		session.mask = Map.of(TYPE, Map.of(BASIC, Set.of(PresentationBasic.NAME)));
		session.inventoryTypes = Set.of(TYPE);
		session.ready = true;
		session.view = 1;
		return session;
	}

	private static PresentationReceiptContent contentOf(List<PresentationS2CPacket> packets) {
		assertEquals(1, packets.size());
		var packet = packets.get(0);
		assertEquals(PresentationS2CPacket.Kind.CREATED, packet.kind());
		return packet.content();
	}

	/** A negotiated client whose accepted Basic mask can decode the server-produced initial. */
	private static ClientPresentation acceptingClient(long epoch, long view, Set<String> allowed) {
		ClientPresentation client = new ClientPresentation(packet -> {});
		client.tick(true);
		assertTrue(client.offer(PresentationS2CPacket.offer(epoch,
			Map.of(BASIC, PresentationBasic.fields()), Map.of(BASIC, 1))));
		assertTrue(client.reset(PresentationS2CPacket.reset(epoch, view,
			Map.of(TYPE, Map.of(BASIC, allowed)))));
		return client;
	}

	private static List<PresentationS2CPacket> send(PresentationServer.Session session, ServerMarker marker,
		Set<String> basicAllowed, Set<String> freshAllowed) {
		var packets = new ArrayList<PresentationS2CPacket>();
		PresentationServer.sendInitial(session, lease(marker), new BasicAdapter(), basicAllowed, packets::add,
			adapter -> adapter.adapterId().equals(BASIC) ? freshAllowed : Set.of());
		return packets;
	}

	@Test void defaultOnlyMarkerProjectsWholeEvenWhenTheDefaultRefIsAName() {
		var session = session();
		var content = contentOf(send(session, marker(List.of()), Set.of(), Set.of()));
		assertEquals(PresentationReceiptContent.Kind.WHOLE, content.kind());
		assertTrue(content.selectedRefs().isEmpty());
	}

	@Test void explicitSelectionProjectsItsExactRefsIncludingANameTheHudSkips() {
		var session = session();
		var content = contentOf(send(session, marker(List.of(PresentationPropertySelection.of(NAME, "danger"))),
			Set.of(PresentationBasic.NAME), Set.of(PresentationBasic.NAME)));
		assertEquals(PresentationReceiptContent.Kind.PROPERTIES, content.kind());
		assertEquals(List.of(NAME), content.selectedRefs(),
			"the receipt carries the explicit selected ref even though the HUD never adds a name line");
	}

	@Test void nullableOnlySelectionSendsAWholeInitialTheClientAccepts() {
		var session = session();
		var packets = send(session, marker(List.of(PresentationPropertySelection.of(NAME))),
			Set.of(PresentationBasic.NAME), Set.of(PresentationBasic.NAME));
		var content = contentOf(packets);
		assertEquals(PresentationReceiptContent.Kind.WHOLE, content.kind());
		assertTrue(content.selectedRefs().isEmpty(), "a nullable selection is not content authority");
		var client = acceptingClient(37, 1, Set.of(PresentationBasic.NAME));
		assertTrue(client.initial(packets.get(0)),
			"the nullable selection's unannotated Basic value must not reject the marker");
		assertEquals(new PresentationValue.Text("{\"text\":\"Chest\"}"),
			client.view(new MarkerId(1)).property(NAME), "the nullable value stays server-projected");
	}

	@Test void nullableAndAnnotatedMixedSelectionSendsOnlyTheExplicitRefsTheClientAccepts() {
		var session = session();
		session.mask = Map.of(TYPE, Map.of(BASIC,
			Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME)));
		var packets = send(session, marker(List.of(
			PresentationPropertySelection.of(NAME),
			PresentationPropertySelection.of(CUSTOM, "request"))),
			Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME),
			Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME));
		var content = contentOf(packets);
		assertEquals(PresentationReceiptContent.Kind.PROPERTIES, content.kind());
		assertEquals(List.of(CUSTOM), content.selectedRefs(),
			"the nullable selection stays out while the complete explicit ref set is carried");
		var client = acceptingClient(37, 1, Set.of(PresentationBasic.NAME, PresentationBasic.CUSTOM_NAME));
		assertTrue(client.initial(packets.get(0)),
			"the unannotated nullable value beside the annotated ref is valid metadata");
		assertEquals(new PresentationValue.Text("{\"text\":\"Chest\"}"),
			client.view(new MarkerId(1)).property(NAME), "the nullable value stays server-projected");
		assertNull(client.view(new MarkerId(1)).property(CUSTOM),
			"the annotated value still waits for its authorized section");
	}

	@Test void multiRefSelectionWithOneFreshlyDeniedRefSuppressesWithoutRefs() {
		var session = session();
		var content = contentOf(send(session, marker(List.of(
			PresentationPropertySelection.of(ITEM_COUNT, "attention"),
			PresentationPropertySelection.of(HEALTH, "danger"))),
			Set.of(PresentationBasic.NAME, PresentationBasic.ITEM_COUNT), Set.of(PresentationBasic.NAME, PresentationBasic.ITEM_COUNT)));
		assertEquals(PresentationReceiptContent.Kind.SUPPRESSED, content.kind());
		assertTrue(content.selectedRefs().isEmpty(), "no selected ref may leak through a suppressed receipt");
	}

	@Test void deniedChildSuppressesReceiptAndAnnotationButRetainsCompleteRoot() {
		var child = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("lit"));
		var root = PresentationPropertyRef.root(BASIC, PresentationBasic.BLOCK_STATE);
		var session = session();
		var allowed = Set.of(PresentationBasic.NAME, PresentationBasic.BLOCK_STATE);
		session.mask = Map.of(TYPE, Map.of(BASIC, allowed)); session.childBlack = Map.of(TYPE, List.of(child));
		var record = new PresentationValue.RecordValue(Map.of("lit", new PresentationValue.Flag(true), "facing", new PresentationValue.Text("north")));
		var lease = lease(marker(List.of(PresentationPropertySelection.of(child, "attention"))));
		lease.sources.get(BASIC).value = new PresentationSection(BASIC, 1, Map.of(
			PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"Chest\"}"), PresentationBasic.BLOCK_STATE, record), false);
		var packets = new ArrayList<PresentationS2CPacket>();
		PresentationServer.sendInitial(session, lease, new BasicAdapter(), allowed, packets::add, adapter -> allowed);
		assertEquals(PresentationReceiptContent.Kind.SUPPRESSED, contentOf(packets).kind());
		var bytes = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(packets.getFirst().sectionBytes()));
		try { var section = nx.pingwheel.common.presentation.PresentationCodec.read(bytes, field -> true);
			assertEquals(record, root.resolve(section)); assertFalse(section.annotations().containsKey(child));
		} finally { bytes.release(); }
		var rootContent = contentOf(send(session, marker(List.of(PresentationPropertySelection.of(root, "attention"))), allowed, allowed));
		assertEquals(PresentationReceiptContent.properties(List.of(root)), rootContent);
	}

	@Test void deniedTargetNameSuppressesAContentMarkerButKeepsTheWholeReceipt() {
		var session = session();
		var content = contentOf(send(session, marker(List.of(PresentationPropertySelection.of(ITEM_COUNT, "attention"))),
			Set.of(PresentationBasic.ITEM_COUNT), Set.of(PresentationBasic.ITEM_COUNT)));
		assertEquals(PresentationReceiptContent.Kind.SUPPRESSED, content.kind(),
			"the content message needs the composed target name");
		var whole = contentOf(send(session, marker(List.of()), Set.of(), Set.of()));
		assertEquals(PresentationReceiptContent.Kind.WHOLE, whole.kind(),
			"the ordinary receipt keeps its unknown-name behavior");
	}

	@Test void maskManifestAndKindIncompatibilitySuppressTheContentReceipt() {
		var missingMask = session();
		missingMask.mask = Map.of(TYPE, Map.of(BASIC, Set.of(PresentationBasic.NAME)));
		var content = contentOf(send(missingMask, marker(List.of(PresentationPropertySelection.of(ITEM_COUNT, "attention"))),
			Set.of(PresentationBasic.NAME, PresentationBasic.ITEM_COUNT), Set.of(PresentationBasic.NAME, PresentationBasic.ITEM_COUNT)));
		assertEquals(PresentationReceiptContent.Kind.SUPPRESSED, content.kind());

		var missingManifest = session(List.of());
		var manifestContent = contentOf(send(missingManifest, marker(List.of(PresentationPropertySelection.of(ITEM_COUNT, "attention"))),
			Set.of(PresentationBasic.NAME, PresentationBasic.ITEM_COUNT), Set.of(PresentationBasic.NAME, PresentationBasic.ITEM_COUNT)));
		assertEquals(PresentationReceiptContent.Kind.SUPPRESSED, manifestContent.kind(),
			"a field absent from the accepted manifest is incompatible");
	}

	@Test void healthSelectionNeedsTheFreshlyAuthorizedMaximumHealthDependency() {
		var session = session();
		var withoutMax = contentOf(send(session, marker(List.of(PresentationPropertySelection.of(HEALTH, "danger"))),
			Set.of(PresentationBasic.NAME, PresentationBasic.HEALTH), Set.of(PresentationBasic.NAME, PresentationBasic.HEALTH)));
		assertEquals(PresentationReceiptContent.Kind.SUPPRESSED, withoutMax.kind());
		var withMax = contentOf(send(session, marker(List.of(PresentationPropertySelection.of(HEALTH, "danger"))),
			Set.of(PresentationBasic.NAME, PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH),
			Set.of(PresentationBasic.NAME, PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH)));
		assertEquals(PresentationReceiptContent.Kind.PROPERTIES, withMax.kind());
		assertEquals(List.of(HEALTH), withMax.selectedRefs());
	}

	@Test void productionSendInitialUsesTheFreshInventoryGateRatherThanTheCachedView() {
		var session = inventorySession();
		var lease = lease(marker(List.of()));
		var allowedPackets = new ArrayList<PresentationS2CPacket>();
		PresentationServer.sendInitial(session, lease, new BasicAdapter(), Set.of(PresentationBasic.NAME),
			allowedPackets::add, adapter -> Set.of(), true, Set.of(TYPE));
		assertEquals(PresentationReceiptContent.Kind.INVENTORY, contentOf(allowedPackets).kind(),
			"the tracked sidecar plus the fresh and cached view agree on the target type");

		// Inventory permission is revoked after the last RESET; the session's
		// cached inventoryTypes still advertises the type for this recipient.
		var deniedPackets = new ArrayList<PresentationS2CPacket>();
		PresentationServer.sendInitial(session, lease, new BasicAdapter(), Set.of(PresentationBasic.NAME),
			deniedPackets::add, adapter -> Set.of(), true, Set.of());
		var denied = contentOf(deniedPackets);
		assertEquals(PresentationReceiptContent.Kind.SUPPRESSED, denied.kind(),
			"a fresh inventory denial must suppress the content message");
		assertNotEquals(PresentationReceiptContent.Kind.INVENTORY, denied.kind());
		assertNotEquals(PresentationReceiptContent.Kind.WHOLE, denied.kind());
		assertTrue(denied.selectedRefs().isEmpty(), "a suppressed inventory receipt leaks no refs");
		assertEquals(Set.of(TYPE), session.inventoryTypes,
			"the cached advertised view is not rewritten by the fresh denial");
		assertEquals(0, lease.sources.get(BASIC).nextSample, "the atomic initial never samples a source");
	}

	@Test void productionCachedInitialUsesTheFreshInventoryGateRatherThanTheCachedView() {
		var session = inventorySession();
		var marker = marker(List.of());
		var allowedPackets = new ArrayList<PresentationS2CPacket>();
		PresentationServer.sendCachedInitial(session, marker, "Owner", new BasicAdapter(), cachedBasic(),
			Set.of(PresentationBasic.NAME), allowedPackets::add, adapter -> Set.of(), true, Set.of(TYPE));
		assertEquals(PresentationReceiptContent.Kind.INVENTORY, contentOf(allowedPackets).kind(),
			"the cached re-baseline projects the inventory kind while the fresh gate allows it");

		var deniedPackets = new ArrayList<PresentationS2CPacket>();
		PresentationServer.sendCachedInitial(session, marker, "Owner", new BasicAdapter(), cachedBasic(),
			Set.of(PresentationBasic.NAME), deniedPackets::add, adapter -> Set.of(), true, Set.of());
		var denied = contentOf(deniedPackets);
		assertEquals(PresentationReceiptContent.Kind.SUPPRESSED, denied.kind(),
			"a fresh inventory denial must suppress the cached re-baseline too");
		assertNotEquals(PresentationReceiptContent.Kind.INVENTORY, denied.kind());
		assertNotEquals(PresentationReceiptContent.Kind.WHOLE, denied.kind());
		assertTrue(denied.selectedRefs().isEmpty(), "a suppressed inventory receipt leaks no refs");
		assertEquals(Set.of(TYPE), session.inventoryTypes,
			"the cached advertised view is not rewritten by the fresh denial");
	}

	private static final class BasicAdapter implements PresentationAdapter {
		@Override public String adapterId() { return BASIC; }
		@Override public String modId() { return "minecraft"; }
		@Override public int schema() { return 1; }
		@Override public int minUpdateIntervalTicks() { return 5; }
		@Override public List<PresentationField> fields() { return PresentationBasic.fields(); }
		@Override public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
			throw new AssertionError("server Basic uses its world reader, not the adapter collector");
		}
	}
}
