package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.*;
import nx.pingwheel.common.presentation.*;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryDispatchIntegrationTest {
	static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	static class Fixture {
		final List<IPacket> sent = new ArrayList<>(); final List<String> errors = new ArrayList<>(); boolean fail;
		final ClientInventory inventory = new ClientInventory(sent::add);
		final ClientPingRuntime runtime = ClientPingRuntime.create((key, color) -> errors.add(key), packet -> {
			if (fail && packet instanceof InventoryC2SPacket) throw new IllegalStateException("transport"); sent.add(packet);
		}, new ClientRateLimitPolicy(1, 1000), () -> 0, marker -> 100, true, new ClientPingRuntime.PresentationReceiptFeedback() {
			@Override public void play(MarkerSnapshot snapshot) {}
			@Override public void chat(String owner, MarkerSnapshot snapshot, net.minecraft.network.chat.Component name) {}
		});
		Fixture() {
			runtime.presentation().tick(true);
			runtime.onPresentationPacket(PresentationS2CPacket.offer(100, Map.of(PresentationBasic.ID,
				List.of(new PresentationField(PresentationBasic.NAME, PresentationField.Kind.TEXT, true, 0, "Name"))), Map.of(PresentationBasic.ID, 1)));
			runtime.inventory(inventory);
			runtime.onPresentationPacket(PresentationS2CPacket.reset(100, 1, Map.of("entity_block", Map.of(PresentationBasic.ID, Set.of(PresentationBasic.NAME)))));
			runtime.onInventoryPacket(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(5, 3, 5, 0)).stamp(100, 1));
			runtime.onInventoryPacket(InventoryS2CPacket.policy(7, 100, 1, Set.of("entity_block")));
		}
		ClientInventory.PreviewEntryReference preview() {
			long request = inventory.open(TARGET, BlockFace.WEST, "entity_block");
			runtime.onInventoryPacket(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, request, null, request, 1, 1,
				0, 1, true, InventoryS2CPacket.Status.UNCERTAIN, 0, List.of(entry())).stamp(100, 1));
			return inventory.selectable(request).getFirst();
		}
		static InventoryS2CPacket.Entry entry() { return new InventoryS2CPacket.Entry("opaque", "minecraft:stone", "Stone", null, 7, 1, false, null); }
	}
	@Test void actualSendAndCourtesyLimiterAreSingleUseAndCorrelatedRejectionOnlySurfacesOnce() {
		Fixture f = new Fixture(); var first = f.preview(); assertEquals(ClientInventory.DispatchOutcome.SENT, f.runtime.dispatchInventory(first, "danger"));
		var selected = (InventoryC2SPacket) f.sent.stream().filter(p -> p instanceof InventoryC2SPacket i && i.kind() == InventoryC2SPacket.Kind.SELECT).findFirst().orElseThrow();
		var second = f.preview(); assertEquals(ClientInventory.DispatchOutcome.THROTTLED, f.runtime.dispatchInventory(second, "danger"));
		assertEquals(ClientInventory.DispatchOutcome.ALREADY_RELEASED, f.runtime.dispatchInventory(second, "danger"));
		var reject = InventoryS2CPacket.selected(7, selected.requestId(), selected.commitId(), null, MarkerRejectReason.TARGET_GONE).stamp(100, 1);
		f.runtime.onInventoryPacket(reject); f.runtime.onInventoryPacket(reject); assertEquals(1, f.errors.size());
		assertEquals(1, f.sent.stream().filter(p -> p instanceof InventoryC2SPacket i && i.kind() == InventoryC2SPacket.Kind.SELECT).count());
	}
	@Test void transportFailureIsNotARequestAndCannotRetrySameRelease() {
		Fixture f = new Fixture(); var reference = f.preview(); f.fail = true;
		assertEquals(ClientInventory.DispatchOutcome.TRANSPORT_FAILED, f.runtime.dispatchInventory(reference, "attention"));
		assertEquals(ClientInventory.DispatchOutcome.ALREADY_RELEASED, f.runtime.dispatchInventory(reference, "attention"));
		f.runtime.onInventoryPacket(InventoryS2CPacket.selected(7, reference.requestId(), 1, null, MarkerRejectReason.TARGET_GONE).stamp(100, 1));
		assertTrue(f.errors.isEmpty());
	}
	@Test void acceptedBasicDeliveryAuthorizesTrackingAndMarkerExpiryEndsInventoryEvenIfBasicIsRetainedStale() {
		Fixture f = new Fixture(); var marker = new MarkerId(3);
		var data = InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT, 7, 0, marker, 4, 1, 1, 0, 1, true,
			InventoryS2CPacket.Status.UNCERTAIN, 0, List.of(Fixture.entry())).stamp(100, 1);
		f.runtime.onInventoryPacket(data); assertTrue(f.inventory.tracking(marker).entries().isEmpty());
		var snapshot = new MarkerSnapshot(marker, new UUID(1, 1), TARGET, "entity_block", "attention", new MarkerAnchor(1, 2, 3), 0, 30);
		f.runtime.onPresentationPacket(PresentationS2CPacket.created(100, 1, 1, snapshot, "Owner",
			PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME), new PresentationSection(PresentationBasic.ID, 1,
			Map.of(PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"Chest\"}")), false)));
		f.runtime.onInventoryPacket(data); assertEquals(7, f.inventory.tracking(marker).entries().getFirst().count());
		f.runtime.onPresentationPacket(PresentationS2CPacket.removed(100, 1, marker, MarkerRemovalReason.EXPIRED));
		assertTrue(f.runtime.store().marker(marker).orElseThrow().isStale()); assertTrue(f.inventory.tracking(marker).entries().isEmpty());
		f.runtime.onInventoryPacket(data); assertTrue(f.inventory.tracking(marker).entries().isEmpty());
	}
}
