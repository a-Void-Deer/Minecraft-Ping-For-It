package nx.pingwheel.common.client;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import nx.pingwheel.common.chat.ContentChatComposer;
import nx.pingwheel.common.chat.ContentChatTemplate;
import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.client.spatial.NativeSelectorInput;
import nx.pingwheel.common.client.spatial.SelectorIntent;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.cancel.CancellationContext;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import nx.pingwheel.common.interaction.state.TargetValidation;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.MarkerContentGrant;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationReceiptContent;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.TestContentGrants;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryCreationSnapshot;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.render.SpatialOverlayRenderer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Wire-decoded CREATED admission through the real runtime; no world or source reads. */
@NotNullByDefault
class ClientInventoryCreationCapacityTest {
	private static final long EPOCH = 81;
	private static final MarkerId OLD = new MarkerId(1);
	private static final MarkerId NEXT = new MarkerId(1000);
	private static final PresentationPropertyRef NAME = PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME);
	private static final @Unmodifiable Map<String, @Unmodifiable Set<String>> ALLOWED = Map.of(
		PresentationBasic.ID, Set.of(PresentationBasic.NAME), InventoryPresentation.ADAPTER_ID, Set.of(InventoryPresentation.ITEMS));
	private static final MarkerContentGrant GRANT = new MarkerContentGrant(TestContentGrants.fromAllowed(ALLOWED), InventoryResourceKind.ITEM);
	private static final InventoryCreationSnapshot SEED = seed(5);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void fullMetadataAdmitsSameLogicalTargetReplacementWithOrWithoutTimelyServerExpiry(boolean timelyExpiry) {
		Fixture f = new Fixture();
		Target.BlockTarget hit = target(0);
		TargetKey key = new TargetKey.DoubleChestKey(hit.dimensionId(), 0, 2, 3, 1, 2, 3, hit.blockRegistryId());
		MarkerSnapshot old = snapshot(OLD, hit, key, 0, 100);
		f.fill(old);
		if (timelyExpiry) {
			f.receive(PresentationS2CPacket.removed(EPOCH, 1, OLD, MarkerRemovalReason.EXPIRED));
			assertNull(f.inventory.creationSnapshot(OLD));
		} else {
			// The client reaches its fallback deadline while the slower server is
			// still at tick 50. No reliable REMOVE was lost: none is due yet.
			f.reachFallback(OLD);
			assertEquals(SEED, f.inventory.creationSnapshot(OLD));
			assertFalse(f.runtime.store().isAuthoritativelyRemoved(OLD));
		}
		assertTrue(f.runtime.store().marker(OLD).orElseThrow().isStale());
		MarkerSnapshot replacement = snapshot(NEXT, target(1), key, timelyExpiry ? 100 : 50, 200);
		if (!timelyExpiry) assertTrue(replacement.arrivalTick() < old.expiresAtTick());
		assertNotEquals(old.owner(), replacement.owner(), "owner UUID is not part of same-target supersession");
		InventoryCreationSnapshot frozen = seed(9_007_199_254_740_993L);
		f.create(replacement, 1, frozen, basic());

		assertTrue(f.runtime.store().marker(NEXT).isPresent(), "a net-zero metadata replacement must not drop the whole marker");
		assertTrue(f.runtime.store().marker(OLD).isEmpty());
		assertEquals(ClientInventory.MAX_TRACKED_CHANNELS, f.runtime.store().allMarkers().size());
		assertNull(f.inventory.creationSnapshot(OLD));
		assertTrue(f.inventory.trackingKind(OLD).isEmpty());
		assertTrue(f.runtime.nameStore().find(OLD).isEmpty());
		assertTrue(f.presentation.view(OLD).sections().isEmpty());
		assertEquals(frozen, f.inventory.creationSnapshot(NEXT));
		assertEquals(frozen.amount(), f.inventory.tracking(NEXT).entries().getFirst().count());
		assertFalse(f.inventory.tracking(NEXT).complete(), "creation is not a completed Tracking baseline");
		assertEquals(0, f.inventory.stats().trackedChannels(), "metadata capacity is independent of active channels/assemblers");
		assertTrue(f.runtime.nameStore().find(NEXT).isPresent());
		assertEquals(ClientInventory.MAX_TRACKED_CHANNELS + 1, f.feedback.sounds.size());
		assertEquals(ClientInventory.MAX_TRACKED_CHANNELS + 1, f.feedback.content.size());
		// Actual cleanup leaves exactly one replacement slot, not spare capacity.
		MarkerSnapshot unrelated = snapshot(new MarkerId(1001), target(900), 50, 200);
		f.create(unrelated, 1, SEED, basic());
		assertTrue(f.runtime.store().marker(unrelated.id()).isEmpty());
		f.receive(PresentationS2CPacket.removed(EPOCH, 1, new MarkerId(2), MarkerRemovalReason.CANCELLED));
		f.create(old, 2, SEED, basic());
		assertTrue(f.runtime.store().marker(OLD).isEmpty(), "even with spare capacity, supersession keeps the eviction tombstone");
		f.assertNoCommands();
	}

	@ParameterizedTest
	@ValueSource(strings = {"different-target", "synchronized", "different-logical-key", "metadata-free-stale"})
	void fullMetadataWithoutAnEligibleRetainedEntryRejectsWithoutEffects(String scenario) {
		Fixture f = new Fixture();
		MarkerSnapshot old = snapshot(OLD, target(0), 0, scenario.equals("metadata-free-stale") ? 10000 : 100);
		f.fill(old);
		MarkerSnapshot incoming = snapshot(NEXT, target(0), 50, 200);
		if (scenario.equals("metadata-free-stale")) {
			MarkerSnapshot visualOnly = snapshot(new MarkerId(900), target(800), 0, 100);
			f.receive(PresentationS2CPacket.created(EPOCH, 1, 1, visualOnly, "Owner", NAME,
				new MarkerContentGrant(TestContentGrants.basic(Set.of(PresentationBasic.NAME)), null),
				PresentationReceiptContent.whole(), basic()));
			f.reachFallback(visualOnly.id());
			assertTrue(f.inventory.receiptAuthorization(visualOnly.id(), EPOCH, 1, "entity_block").isEmpty());
			incoming = snapshot(NEXT, target(800), 50, 200);
		} else if (!scenario.equals("synchronized")) {
			f.reachFallback(OLD);
			if (scenario.equals("different-target")) incoming = snapshot(NEXT, target(800), 50, 200);
			else incoming = snapshot(NEXT, target(0), new TargetKey.DoubleChestKey("minecraft:overworld", 0, 2, 3, 1, 2, 3,
				"minecraft:chest"), 50, 200);
		}
		@Unmodifiable List<ClientMarker> before = f.runtime.store().allMarkers();
		var presentationBefore = f.presentation.view(OLD);
		int sounds = f.feedback.sounds.size();
		int content = f.feedback.content.size();
		f.create(incoming, 1, SEED, basic());
		assertEquals(before, f.runtime.store().allMarkers());
		assertEquals(presentationBefore, f.presentation.view(OLD));
		assertEquals(SEED, f.inventory.creationSnapshot(OLD));
		assertNull(f.inventory.creationSnapshot(NEXT));
		assertTrue(f.presentation.view(NEXT).sections().isEmpty());
		assertTrue(f.runtime.nameStore().find(NEXT).isEmpty());
		assertEquals(sounds, f.feedback.sounds.size());
		assertEquals(content, f.feedback.content.size());
		// Rejection must not consume known membership or revision 1.
		f.receive(PresentationS2CPacket.removed(EPOCH, 1, new MarkerId(2), MarkerRemovalReason.CANCELLED));
		f.create(incoming, 1, SEED, basic());
		assertTrue(f.runtime.store().marker(NEXT).isPresent());
		assertEquals(SEED, f.inventory.creationSnapshot(NEXT));
		f.assertNoCommands();
	}

	@ParameterizedTest
	@ValueSource(strings = {"epoch", "view", "missing-name", "stale-name", "zero-revision"})
	void rejectedReplacementCannotSpendTheProspectiveSlotOrAdvanceKnownRevision(String fault) {
		Fixture f = new Fixture();
		MarkerSnapshot old = snapshot(OLD, target(0), 0, 100);
		f.fill(old);
		f.reachFallback(OLD);
		MarkerSnapshot incoming = snapshot(NEXT, target(0), 50, 200);
		PresentationSection section = fault.equals("missing-name")
			? new PresentationSection(PresentationBasic.ID, 1, Map.of(), false)
			: fault.equals("stale-name") ? new PresentationSection(PresentationBasic.ID, 1, basic().fields(), true) : basic();
		@Unmodifiable List<ClientMarker> before = f.runtime.store().allMarkers();
		var presentationBefore = f.presentation.view(OLD);
		var nameBefore = f.runtime.nameStore().find(OLD);
		f.receive(PresentationS2CPacket.created(fault.equals("epoch") ? EPOCH + 1 : EPOCH,
			fault.equals("view") ? 2 : 1, fault.equals("zero-revision") ? 0 : 1, incoming, "Owner", NAME, GRANT,
			PresentationReceiptContent.inventory(seed(99)), section));
		assertEquals(before, f.runtime.store().allMarkers());
		assertEquals(presentationBefore, f.presentation.view(OLD));
		assertEquals(nameBefore, f.runtime.nameStore().find(OLD));
		assertEquals(SEED, f.inventory.creationSnapshot(OLD));
		assertEquals(Optional.of(InventoryResourceKind.ITEM), f.inventory.trackingKind(OLD));
		assertNull(f.inventory.creationSnapshot(NEXT));
		assertTrue(f.presentation.view(NEXT).sections().isEmpty());
		assertEquals(ClientInventory.MAX_TRACKED_CHANNELS, f.feedback.sounds.size());
		assertEquals(ClientInventory.MAX_TRACKED_CHANNELS, f.feedback.content.size());
		f.create(incoming, 1, SEED, basic());
		assertTrue(f.runtime.store().marker(NEXT).isPresent(), "valid retry at the same revision must remain first-receipt eligible");
		assertEquals(SEED, f.inventory.creationSnapshot(NEXT));
		assertTrue(f.runtime.store().marker(OLD).isEmpty());
		f.assertNoCommands();
	}

	@Test
	void staleSameIdRevisionKeepsOldMetadataAndANewerRefreshNeverCountsItsOwnSlotAsFreed() {
		Fixture f = new Fixture();
		MarkerSnapshot old = snapshot(OLD, target(0), 0, 100);
		f.fill(old);
		f.reachFallback(OLD);
		ClientMarker before = f.runtime.store().marker(OLD).orElseThrow();
		f.create(old, 1, seed(99), basic());
		assertEquals(before, f.runtime.store().marker(OLD).orElseThrow());
		assertEquals(SEED, f.inventory.creationSnapshot(OLD));
		f.create(old, 2, seed(99), basic());
		assertTrue(f.runtime.store().marker(OLD).orElseThrow().isSynchronized());
		assertEquals(SEED, f.inventory.creationSnapshot(OLD), "refresh cannot replace the frozen creation content");
		assertEquals(before.displayExpiresAtLocalTick(), f.runtime.store().marker(OLD).orElseThrow().displayExpiresAtLocalTick());
		f.create(snapshot(NEXT, target(800), 50, 200), 1, SEED, basic());
		assertTrue(f.runtime.store().marker(NEXT).isEmpty());
		assertEquals(ClientInventory.MAX_TRACKED_CHANNELS, f.feedback.content.size());
		f.assertNoCommands();
	}

	@Test
	void prospectiveAdmissionVisitsOnlyRealUniqueMetadataEntriesAndDoesNotEvictThem() {
		Fixture f = new Fixture();
		f.fill(snapshot(OLD, target(0), 0, 100));
		MarkerSnapshot incoming = snapshot(NEXT, target(0), 50, 200);
		List<MarkerId> visited = new ArrayList<>();
		assertTrue(f.inventory.admitsCreation(incoming, GRANT, SEED, id -> {
			visited.add(id);
			return id.equals(OLD) || id.equals(new MarkerId(2));
		}));
		assertEquals(ClientInventory.MAX_TRACKED_CHANNELS, visited.size());
		assertEquals(visited.size(), Set.copyOf(visited).size());
		assertFalse(visited.contains(NEXT));
		assertEquals(SEED, f.inventory.creationSnapshot(OLD));
		assertEquals(SEED, f.inventory.creationSnapshot(new MarkerId(2)));
		assertFalse(f.inventory.admitsCreation(incoming, GRANT, SEED, NEXT::equals));
		assertFalse(f.inventory.admitsCreation(incoming, GRANT, SEED), "a prospective check cannot actually release slots");
		f.assertNoCommands();
	}

	private static Target.BlockTarget target(int x) {
		return new Target.BlockTarget("minecraft:overworld", x, 2, 3, "minecraft:chest");
	}
	private static MarkerSnapshot snapshot(MarkerId id, Target.BlockTarget target, long arrival, long expiry) {
		return snapshot(id, target, TargetKey.from(target), arrival, expiry);
	}
	private static MarkerSnapshot snapshot(MarkerId id, Target.BlockTarget target, TargetKey key, long arrival, long expiry) {
		return new MarkerSnapshot(id, new UUID(0, id.value()), target, key, "entity_block", "attention",
			new MarkerAnchor(target.x(), target.y(), target.z()), arrival, expiry);
	}
	private static InventoryCreationSnapshot seed(long amount) {
		return new InventoryCreationSnapshot(InventoryResourceKind.ITEM, "selected", "minecraft:gunpowder",
			"item.minecraft.gunpowder", null, amount, false, "attention", false, true);
	}
	private static PresentationSection basic() {
		return new PresentationSection(PresentationBasic.ID, 1,
			Map.of(PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"Chest\"}")), false);
	}

	@NotNullByDefault
	private static final class Fixture {
		final List<IPacket> sent = new ArrayList<>();
		final Feedback feedback = new Feedback();
		final ClientPresentation presentation = new ClientPresentation(sent::add);
		final ClientInventory inventory = new ClientInventory(sent::add);
		final ClientPingRuntime runtime = ClientPingRuntime.createForInteraction((key, color) -> fail("unexpected local error"),
			sent::add, ClientRateLimitPolicy.DEFAULT, () -> 0, target -> TargetValidation.valid(), new Access(), presentation,
			inventory, null, snapshot -> 1000, feedback);

		Fixture() {
			presentation.tick(true);
			receive(PresentationS2CPacket.offer(EPOCH,
				Map.of(PresentationBasic.ID, PresentationBasic.fields(), InventoryPresentation.ADAPTER_ID, InventoryPresentation.INSTANCE.fields()),
				Map.of(PresentationBasic.ID, 1, InventoryPresentation.ADAPTER_ID, InventoryPresentation.SCHEMA)));
			receive(PresentationS2CPacket.reset(EPOCH, 1, Map.of("entity_block", ALLOWED)));
			runtime.onInventoryPacket(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(10, 10, 3, 0)).stamp(EPOCH, 1));
			runtime.onInventoryPacket(InventoryS2CPacket.policy(7, EPOCH, 1, Set.of("entity_block")));
			assertTrue(presentation.ready());
			assertTrue(inventory.ready(InventoryResourceKind.ITEM));
			sent.clear();
		}
		void fill(MarkerSnapshot first) {
			create(first, 1, SEED, basic());
			// Exercise the finite admission boundary, not a snapshot of a tuning default.
			for (int i = 2; i <= ClientInventory.MAX_TRACKED_CHANNELS; i++) {
				MarkerSnapshot marker = snapshot(new MarkerId(i), target(i + 10), 0, 10000);
				create(marker, 1, SEED, basic());
				assertEquals(SEED, inventory.creationSnapshot(marker.id()), "every capacity slot is a legitimate wire initial");
			}
			assertEquals(SEED, inventory.creationSnapshot(first.id()));
			assertEquals(ClientInventory.MAX_TRACKED_CHANNELS, runtime.store().allMarkers().size());
			assertEquals(0, inventory.stats().trackedChannels());
		}
		void reachFallback(MarkerId id) {
			long deadline = runtime.store().marker(id).orElseThrow().fallbackExpiresAtLocalTick();
			for (long tick = 0; tick < deadline; tick++) runtime.onTick();
			assertTrue(runtime.store().marker(id).orElseThrow().isStale());
		}
		void create(MarkerSnapshot marker, long revision, InventoryCreationSnapshot seed, PresentationSection section) {
			receive(PresentationS2CPacket.created(EPOCH, 1, revision, marker, "Owner", NAME, GRANT,
				PresentationReceiptContent.inventory(seed), section));
		}
		void receive(PresentationS2CPacket packet) {
			FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
			try {
				packet.write(buffer);
				PresentationS2CPacket decoded = PresentationS2CPacket.readSafe(buffer);
				assertFalse(decoded.isCorrupt(), "the wire envelope must be valid before runtime validation");
				assertFalse(buffer.isReadable());
				runtime.onPresentationPacket(decoded);
			} finally { buffer.release(); }
		}
		void assertNoCommands() { assertTrue(sent.isEmpty(), "receipt admission must not send commands or read sources"); }
	}

	@NotNullByDefault
	private static final class Feedback implements ClientPingRuntime.PresentationReceiptFeedback {
		final List<MarkerSnapshot> sounds = new ArrayList<>();
		final List<Component> content = new ArrayList<>();
		public boolean ready() { return true; }
		public void play(MarkerSnapshot snapshot) { sounds.add(snapshot); }
		public void chat(String owner, MarkerSnapshot snapshot, Component name) {}
		public Optional<Component> name(String json) { return ContentChatComposer.nameJsonDecoder(RegistryAccess.EMPTY).apply(json); }
		public String template(PingType type) { return ContentChatTemplate.DEFAULT_TEMPLATE; }
		public void content(Component message) { content.add(message); }
	}

	@NotNullByDefault
	private static final class Access implements ClientPingRuntime.InteractionAccess {
		final Object world = new Object();
		public Lifecycle lifecycle() { return new Lifecycle(world, "minecraft:overworld", null, true, true, false); }
		public Optional<CapturedRay> capturePressRay() { return Optional.empty(); }
		public void capture(InteractionToken token, CapturedRay ray, ClientPingRuntime.CaptureCompletion completion) { fail("unexpected capture"); }
		public CancellationContext cancellation(CapturedRay ray) { throw new AssertionError("unexpected cancellation"); }
		public void syncMouse(PingInteractionPhase phase) {}
		public void disposeMouse(boolean screenTransition) {}
		public Optional<NativeSelectorInput.Frame> inputFrame() { return Optional.empty(); }
		public SpatialSelectorSession.ListGeometry listGeometry(SpatialOverlayRenderer.Style style) { throw new AssertionError("unexpected selector read"); }
		public void resetInput() {}
		public void applyToggle(SelectorIntent.CaptureToggle toggle, long timeMillis) { fail("unexpected toggle"); }
	}
}
