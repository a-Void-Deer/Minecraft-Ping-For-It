package nx.pingwheel.common.presentation.inventory.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.domain.*;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.marker.*;
import nx.pingwheel.common.network.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientInventoryV2Test {
	static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	static InventoryS2CPacket data(InventoryS2CPacket.Kind kind, MarkerId marker, long watermark, InventoryS2CPacket.Entry entry) {
		return InventoryS2CPacket.data(kind, 7, marker == null ? 1 : 0, marker, 4, 1, watermark, 0, 1, true, InventoryS2CPacket.Status.UNCERTAIN, 0,
			List.of(entry)).stamp(100, 1);
	}
	static void ready(ClientInventory client) {
		client.presentationReset(100, 1);
		client.accept(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(5, 3, 5, 0)).stamp(100, 1));
		client.accept(InventoryS2CPacket.policy(7, 100, 1, Set.of("entity_block")));
	}
	static CapturedPingContext capture(Target target, String targetTypeId, BlockFace face) {
		var resolved = new ResolvedTarget(target, TargetTypeCatalog.builtIn().findById(targetTypeId).orElseThrow());
		return new CapturedPingContext(new ActiveInteraction().begin(), resolved, CapturedRay.defaultRay(),
			Optional.empty(), Optional.ofNullable(face));
	}
	static Target.ExternalBlockTarget externalCandidate(String locator) {
		return Target.ExternalBlockTarget.candidate("minecraft:overworld", "provider:test", "minecraft:chest", locator, true);
	}
	@Test void frozenFaceRequiredAndSingleReleaseReportsActualDispatchOutcome() {
		List<IPacket> sent = new ArrayList<>(); ClientInventory client = new ClientInventory(sent::add); ready(client);
		assertEquals(ClientInventory.NO_REQUEST, client.open(TARGET));
		long request = client.open(TARGET, BlockFace.SOUTH, "entity_block");
		assertEquals(BlockFace.SOUTH, ((InventoryC2SPacket) sent.getFirst()).face());
		client.accept(data(InventoryS2CPacket.Kind.PREVIEW, null, 1, new InventoryS2CPacket.Entry("opaque", "minecraft:stone", "stone", null, 7, 1, false, null)));
		var ref = client.selectable(request).getFirst();
		assertEquals(ClientInventory.DispatchOutcome.THROTTLED, client.select(ref, "attention", p -> ClientInventory.DispatchOutcome.THROTTLED));
		assertEquals(ClientInventory.DispatchOutcome.ALREADY_RELEASED, client.select(ref, "attention", p -> { throw new AssertionError("single use"); }));
		client.close(request); assertTrue(client.selectable(request).isEmpty());
	}
	@Test void policyAndAcceptedMarkerFenceTrackingAndResetCannotResurrectOldView() {
		ClientInventory client = new ClientInventory(p -> {}); ready(client); MarkerId marker = new MarkerId(3);
		var entry = new InventoryS2CPacket.Entry("selected", "minecraft:stone", "stone", null, 2, 1, false, null);
		client.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, marker, 1, entry)); assertTrue(client.tracking(marker).entries().isEmpty());
		client.markerCreated(new MarkerSnapshot(marker, new UUID(1, 1), TARGET, "entity_block", "attention", new MarkerAnchor(1, 2, 3), 0, 30));
		client.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, marker, 1, entry)); assertEquals(2, client.tracking(marker).entries().getFirst().count());
		client.presentationReset(100, 2); assertFalse(client.ready()); assertTrue(client.tracking(marker).entries().isEmpty());
		client.accept(InventoryS2CPacket.policy(7, 100, 2, Set.of()));
		client.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, marker, 1, entry)); assertTrue(client.tracking(marker).entries().isEmpty());
	}
	@Test void atomicGroupReplacementRetiresExactVariantAndLateExactCannotResurrect() {
		ClientInventory client = new ClientInventory(p -> {}); ready(client); MarkerId marker = new MarkerId(3);
		client.markerCreated(new MarkerSnapshot(marker, new UUID(1, 1), TARGET, "entity_block", "attention", new MarkerAnchor(1, 2, 3), 0, 30));
		client.accept(data(InventoryS2CPacket.Kind.SNAPSHOT, marker, 1, new InventoryS2CPacket.Entry("exact", "minecraft:stone", "stone", null, 2, 1, false, null)));
		client.accept(data(InventoryS2CPacket.Kind.STREAM, marker, 2, new InventoryS2CPacket.Entry("aggregate", "minecraft:stone", "stone", null, 9, 2, true,
			InventoryS2CPacket.Status.COMPONENT_TOO_LONG, 2, true, "danger")));
		assertEquals(List.of("aggregate"), client.tracking(marker).entries().stream().map(ClientInventory.EntryView::key).toList());
		client.accept(data(InventoryS2CPacket.Kind.STREAM, marker, 3, new InventoryS2CPacket.Entry("exact", "minecraft:stone", "stone", null, 50, 50, false, null)));
		assertEquals(1, client.tracking(marker).entries().size()); assertEquals(9, client.tracking(marker).entries().getFirst().count());
		assertEquals("danger", client.tracking(marker).entries().getFirst().itemPingType());
	}
	@Test void externalCandidateCaptureOpensTheOriginalWireIdentityWithoutInventingAPhysicalBlock() {
		List<IPacket> sent = new ArrayList<>(); ClientInventory client = new ClientInventory(sent::add); ready(client);
		Target.ExternalBlockTarget candidate = externalCandidate("locator:7");
		long request = client.open(capture(candidate, "entity_block", BlockFace.EAST));
		assertTrue(request >= 0);
		InventoryC2SPacket open = assertInstanceOf(InventoryC2SPacket.class, sent.getFirst());
		assertEquals(InventoryC2SPacket.Kind.OPEN, open.kind());
		assertSame(candidate, open.target(), "the captured candidate is retained verbatim");
		Target.ExternalBlockTarget written = assertInstanceOf(Target.ExternalBlockTarget.class, open.target());
		assertFalse(open.target() instanceof Target.BlockTarget, "no physical block target is invented");
		assertEquals("provider:test", written.providerId());
		assertEquals("minecraft:chest", written.expectedBlockRegistryId());
		assertEquals("locator:7", written.providerLocator());
		assertTrue(written.hasBlockEntity());
		assertTrue(written.isCandidate());
		assertEquals(BlockFace.EAST, open.face());
		assertEquals(1, client.stats().previewChannels());
	}
	@Test void capturesWithoutARealFaceOrWithNonCandidateTargetsDoNotOpen() {
		List<IPacket> sent = new ArrayList<>(); ClientInventory client = new ClientInventory(sent::add); ready(client);
		assertEquals(ClientInventory.NO_REQUEST, client.open(capture(externalCandidate("locator"), "entity_block", null)));
		assertEquals(ClientInventory.NO_REQUEST, client.open(capture(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"), "block", null)));
		assertEquals(ClientInventory.NO_REQUEST, client.open(Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "provider:test", "stable-1", "minecraft:chest", "locator", false), BlockFace.UP, "entity_block"));
		assertEquals(ClientInventory.NO_REQUEST, client.open(new Target.EntityTarget("minecraft:overworld", UUID.randomUUID()), BlockFace.UP, "entity"));
		assertEquals(ClientInventory.NO_REQUEST, client.open(new Target.LocationTarget("minecraft:overworld", 1, 2, 3), BlockFace.UP, "location"));
		assertTrue(sent.isEmpty());
		assertEquals(0, client.stats().previewChannels());
	}
	@Test void sameRegistryDifferentLocatorsDoNotAliasPreviewRequests() {
		List<IPacket> sent = new ArrayList<>(); ClientInventory client = new ClientInventory(sent::add); ready(client);
		long first = client.open(capture(externalCandidate("locator:a"), "entity_block", BlockFace.NORTH));
		long second = client.open(capture(externalCandidate("locator:b"), "entity_block", BlockFace.NORTH));
		assertNotEquals(first, second);
		List<InventoryC2SPacket> opens = sent.stream().filter(InventoryC2SPacket.class::isInstance)
			.map(InventoryC2SPacket.class::cast).filter(packet -> packet.kind() == InventoryC2SPacket.Kind.OPEN).toList();
		assertEquals(2, opens.size());
		assertEquals("locator:a", ((Target.ExternalBlockTarget) opens.get(0).target()).providerLocator());
		assertEquals("locator:b", ((Target.ExternalBlockTarget) opens.get(1).target()).providerLocator());

		client.accept(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, first, null, 9, 1, 1, 0, 1, true,
			InventoryS2CPacket.Status.READY, 0, List.of(new InventoryS2CPacket.Entry("opaque", "minecraft:stone", "stone", null, 7, 1, false, null))).stamp(100, 1));
		assertEquals(1, client.preview(first).entries().size());
		assertTrue(client.preview(second).entries().isEmpty());
		assertEquals(2, client.stats().previewChannels());
	}
}
