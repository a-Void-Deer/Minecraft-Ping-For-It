package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPingRuntimeTest {

	@Test
	void isNewMarkerReceiptReflectsStoreAbsenceBeforeUpsert() {
		// The predicate reports only whether the marker id is currently absent
		// from the store; it does not exercise sound or chat side effects.
		ClientMarkerStore store = new ClientMarkerStore(10L);
		MarkerId markerId = new MarkerId(7L);
		Target.ExternalBlockTarget first = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", "tracking-id", "minecraft:chest", "locator-a", true);
		Target.ExternalBlockTarget updated = Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", "tracking-id", "minecraft:chest", "locator-b", true);

		assertTrue(ClientPingRuntime.isNewMarkerReceipt(store, markerId));
		store.onCreated(snapshot(markerId, first), 0L);

		assertFalse(ClientPingRuntime.isNewMarkerReceipt(store, markerId));
		store.onCreated(snapshot(markerId, updated), 1L);
		assertFalse(ClientPingRuntime.isNewMarkerReceipt(store, markerId));
	}

	@Test
	void presentationCreatedUpsertsLocatorAndAnchorWithoutRenewingVisualDeadlineOrReplayingFeedback() {
		var feedback = new RecordingFeedback();
		var runtime = negotiated(feedback, 0);
		var id = new MarkerId(7);
		var firstTarget = external("locator-a");
		var movedTarget = external("locator-b");
		var first = snapshot(id, firstTarget);
		var moved = new MarkerSnapshot(id, first.owner(), movedTarget, first.targetTypeId(), first.pingTypeId(),
			new MarkerAnchor(6, 7, 8), first.arrivalTick(), first.expiresAtTick());
		assertEquals(firstTarget, movedTarget, "the change is invisible to stable-identity equality");
		var key = TargetKey.from(firstTarget);
		runtime.onPresentationPacket(created(1, first));
		runtime.onPresentationPacket(PresentationS2CPacket.winner(runtime.presentation().epoch(), 1, key, Optional.of(id)));
		var before = runtime.store().marker(id).orElseThrow();
		assertFalse(before.isVisuallyActiveAt(0), "this fixture deliberately has an elapsed visual deadline");
		runtime.onPresentationPacket(created(2, moved));
		var after = runtime.store().marker(id).orElseThrow();
		assertEquals("locator-b", ((Target.ExternalBlockTarget) after.target()).providerLocator());
		assertEquals(moved.anchor(), after.anchor());
		assertEquals(before.id(), after.id());
		assertEquals(before.arrivalTick(), after.arrivalTick());
		assertEquals(before.expiresAtTick(), after.expiresAtTick());
		assertEquals(before.displayExpiresAtLocalTick(), after.displayExpiresAtLocalTick());
		assertEquals(before.targetTypeId(), after.targetTypeId());
		assertEquals(before.pingTypeId(), after.pingTypeId());
		assertTrue(runtime.store().renderMarkers().isEmpty(), "refresh must not re-show an elapsed visual");
		assertTrue(runtime.store().winnerId(key).isEmpty(), "the preserved slot cannot expose an expired visual");
		runtime.onPresentationPacket(created(1, first));
		runtime.onPresentationPacket(created(2, first));
		assertEquals("locator-b", ((Target.ExternalBlockTarget) runtime.store().marker(id).orElseThrow().target()).providerLocator(),
			"older and equal-revision Basic initials cannot roll back the authoritative payload");
		assertEquals(1, feedback.sounds.size());
		assertEquals(1, feedback.chats.size());
		assertEquals(1, runtime.store().allMarkers().size());
	}

	@Test
	void presentationCreatedNeverResurrectsExpiredOrHardRemovedMarkers() {
		var runtime = negotiated(new RecordingFeedback(), 100);
		var id = new MarkerId(8);
		var original = snapshot(id, external("original"));
		var moved = snapshot(id, external("moved"));
		runtime.onPresentationPacket(created(1, original));
		runtime.onPresentationPacket(PresentationS2CPacket.removed(runtime.presentation().epoch(), 1, id,
			MarkerRemovalReason.EXPIRED));
		assertTrue(runtime.store().marker(id).orElseThrow().isStale());
		runtime.onPresentationPacket(created(2, moved));
		assertEquals("original", ((Target.ExternalBlockTarget) runtime.store().marker(id).orElseThrow().target()).providerLocator());
		assertTrue(runtime.store().marker(id).orElseThrow().isStale());
		runtime.onPresentationPacket(PresentationS2CPacket.removed(runtime.presentation().epoch(), 1, id,
			MarkerRemovalReason.CANCELLED));
		runtime.onPresentationPacket(created(3, moved));
		assertTrue(runtime.store().marker(id).isEmpty());
	}

	@Test
	void refreshedActiveWinnerKeepsItsSlotAndVisualDeadline() {
		var feedback = new RecordingFeedback();
		var runtime = negotiated(feedback, 100);
		var id = new MarkerId(9);
		var first = snapshot(id, external("before"));
		var key = TargetKey.from(first.target());
		runtime.onPresentationPacket(created(1, first));
		runtime.onPresentationPacket(PresentationS2CPacket.winner(81, 1, key, Optional.of(id)));
		var deadline = runtime.store().marker(id).orElseThrow().displayExpiresAtLocalTick();
		var moved = snapshot(id, external("after"));
		runtime.onPresentationPacket(created(2, moved));
		assertEquals(Optional.of(id), runtime.store().winnerId(key));
		assertEquals(deadline, runtime.store().marker(id).orElseThrow().displayExpiresAtLocalTick());
		assertEquals("after", ((Target.ExternalBlockTarget) runtime.store().winnerMarker(key).orElseThrow()
			.target()).providerLocator());
		assertEquals(1, feedback.sounds.size());
		assertEquals(1, feedback.chats.size());
	}

	private static ClientPingRuntime negotiated(RecordingFeedback feedback, long displayDuration) {
		var runtime = ClientPingRuntime.create((key, color) -> {}, packet -> {}, ClientRateLimitPolicy.DEFAULT,
			InteractionTimeSource.system(), snapshot -> displayDuration, true, feedback);
		runtime.presentation().tick(true);
		var fields = List.of(new PresentationField(PresentationBasic.NAME, PresentationField.Kind.TEXT,
			true, 0, "Name"));
		assertTrue(runtime.presentation().offer(PresentationS2CPacket.offer(81,
			Map.of(PresentationBasic.ID, fields), Map.of(PresentationBasic.ID, 1))));
		runtime.onPresentationPacket(PresentationS2CPacket.reset(81, 1,
			Map.of("entity_block", Map.of(PresentationBasic.ID, Set.of(PresentationBasic.NAME)))));
		assertTrue(runtime.presentation().ready());
		return runtime;
	}

	private static Target.ExternalBlockTarget external(String locator) {
		return Target.ExternalBlockTarget.committed(
			"minecraft:overworld", "sable", "tracking-id", "minecraft:chest", locator, true);
	}

	private static PresentationS2CPacket created(long revision, MarkerSnapshot snapshot) {
		return PresentationS2CPacket.created(81, 1, revision, snapshot, "Owner",
			PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME),
			new PresentationSection(PresentationBasic.ID, 1, Map.of(PresentationBasic.NAME,
				new PresentationValue.Text("{\"text\":\"Cached name\"}")), false));
	}

	private static final class RecordingFeedback implements ClientPingRuntime.PresentationReceiptFeedback {
		final List<MarkerSnapshot> sounds = new ArrayList<>();
		final List<MarkerSnapshot> chats = new ArrayList<>();
		@Override public void play(MarkerSnapshot snapshot) { sounds.add(snapshot); }
		@Override public void chat(String owner, MarkerSnapshot snapshot, net.minecraft.network.chat.Component targetName) {
			chats.add(snapshot);
		}
	}

	private static MarkerSnapshot snapshot(MarkerId id, Target target) {
		return new MarkerSnapshot(
			id,
			new UUID(0L, 1L),
			target,
			"entity_block",
			"attention",
			new MarkerAnchor(1.0, 2.0, 3.0),
			1L,
			20L);
	}
}
