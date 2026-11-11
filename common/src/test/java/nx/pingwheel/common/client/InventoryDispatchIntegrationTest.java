package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerRequestKind;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.network.*;
import nx.pingwheel.common.presentation.*;
import nx.pingwheel.common.presentation.inventory.InventoryCreationSnapshot;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNullByDefault;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryDispatchIntegrationTest {
	static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	@NotNullByDefault
	static class Fixture {
		final List<IPacket> sent = new ArrayList<>(); final List<String> errors = new ArrayList<>(); final List<String> cooldowns = new ArrayList<>(); boolean fail;
		boolean failAfterSelection;
		Consumer<InventoryC2SPacket> selectionHandoff = ignored -> {};
		final ClientInventory inventory = new ClientInventory(sent::add);
		final ClientPingRuntime runtime = ClientPingRuntime.create(new ClientPingActionDispatcher.LocalErrorSink() {
			@Override public void showLocalError(String key, int color) { errors.add(key); }
			@Override public void showCreateCooldown(String key, int color) { cooldowns.add(key); }
		}, packet -> {
			if (fail && packet instanceof InventoryC2SPacket) throw new IllegalStateException("transport"); sent.add(packet);
			if (packet instanceof InventoryC2SPacket selection && selection.kind() == InventoryC2SPacket.Kind.SELECT) {
				selectionHandoff.accept(selection);
				if (failAfterSelection) throw new IllegalStateException("after synchronous terminal response");
			}
		}, new ClientRateLimitPolicy(1, 1000), () -> 0, marker -> 100, new ClientPingRuntime.PresentationReceiptFeedback() {
			@Override public void play(MarkerSnapshot snapshot) {}
			@Override public void chat(String owner, MarkerSnapshot snapshot, net.minecraft.network.chat.Component name) {}
		});
		Fixture() {
			runtime.presentation().tick(true);
			runtime.onPresentationPacket(PresentationS2CPacket.offer(100, Map.of(PresentationBasic.ID,
				List.of(new PresentationField(PresentationBasic.NAME, PresentationField.Kind.TEXT, true, 0, "Name")),
				nx.pingwheel.common.presentation.inventory.InventoryPresentation.ADAPTER_ID, nx.pingwheel.common.presentation.inventory.InventoryPresentation.INSTANCE.fields()),
				Map.of(PresentationBasic.ID, 1, nx.pingwheel.common.presentation.inventory.InventoryPresentation.ADAPTER_ID,
					nx.pingwheel.common.presentation.inventory.InventoryPresentation.INSTANCE.schema())));
			runtime.inventory(inventory);
			runtime.onPresentationPacket(PresentationS2CPacket.reset(100, 1, Map.of("entity_block", Map.of(PresentationBasic.ID, Set.of(PresentationBasic.NAME)))));
			runtime.onInventoryPacket(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(5, 3, 5, 0)).stamp(100, 1));
			runtime.onInventoryPacket(InventoryS2CPacket.policy(7, 100, 1, Set.of("entity_block")));
		}
		ClientInventory.PreviewEntryReference preview() {
			long request = inventory.open(TARGET, BlockFace.WEST, "entity_block");
			runtime.onInventoryPacket(InventoryS2CPacket.opened(7, 100, 1, request, 1));
			runtime.onInventoryPacket(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, request, null, request, 1, 1,
				0, 1, true, InventoryS2CPacket.Status.UNCERTAIN, 0, List.of(entry())).stamp(100, 1));
			return inventory.selectable(request).getFirst();
		}
		static InventoryS2CPacket.Entry entry() { return new InventoryS2CPacket.Entry("opaque", "minecraft:stone", "Stone", null, 7, 1, false, null); }
	}
	private static PingInteractionAction.CreatePing ordinaryCreate() {
		var snapshot = TargetSnapshotFactory.block(TARGET.dimensionId(), TARGET.x(), TARGET.y(), TARGET.z(), TARGET.blockRegistryId(), true, BlockFace.WEST);
		var resolved = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop()).resolve(snapshot.target(), snapshot.matchContext());
		return new PingInteractionAction.CreatePing(new CapturedPingContext(new ActiveInteraction().begin(), resolved), resolved.targetType().defaultPingType());
	}
	@Test void ordinaryCreateConsumesTheSameSingleAllowanceAsInventorySelect() {
		Fixture f = new Fixture(); var reference = f.preview();
		f.runtime.dispatchPropertyPing(ordinaryCreate(), List.of());
		assertEquals(1, f.sent.stream().filter(p -> p instanceof PresentationC2SPacket c && c.kind() == PresentationC2SPacket.Kind.CREATE).count());
		assertEquals(ClientInventory.DispatchOutcome.THROTTLED, f.runtime.dispatchInventory(reference, "attention"));
		assertEquals(0, f.sent.stream().filter(p -> p instanceof InventoryC2SPacket i && i.kind() == InventoryC2SPacket.Kind.SELECT).count());
	}
	@Test void inventorySelectConsumesTheSameSingleAllowanceAsOrdinaryCreate() {
		Fixture f = new Fixture(); var reference = f.preview(); var create = ordinaryCreate();
		assertEquals(ClientInventory.DispatchOutcome.SENT, f.runtime.dispatchInventory(reference, "attention"));
		f.runtime.dispatchPropertyPing(create, List.of());
		assertEquals(1, f.sent.stream().filter(p -> p instanceof InventoryC2SPacket i && i.kind() == InventoryC2SPacket.Kind.SELECT).count());
		assertEquals(0, f.sent.stream().filter(p -> p instanceof PresentationC2SPacket c && c.kind() == PresentationC2SPacket.Kind.CREATE).count(),
			"at a fixed clock the sole allowance belongs to SELECT, so the later ordinary create must be dropped");
		f.runtime.handleRejected(create.context().token().sequence(), MarkerRequestKind.CREATE, MarkerRejectReason.TARGET_GONE);
		assertTrue(f.errors.isEmpty(), "the throttled ordinary create must not overwrite the inventory receipt");
		assertEquals(List.of(ClientPingActionDispatcher.CREATE_COOLDOWN_MESSAGE_KEY), f.cooldowns);
	}
	@Test void actualSendAndCourtesyLimiterAreSingleUseAndCorrelatedRejectionOnlySurfacesOnce() {
		Fixture f = new Fixture(); var first = f.preview(); assertEquals(ClientInventory.DispatchOutcome.SENT, f.runtime.dispatchInventory(first, "danger"));
		var selected = (InventoryC2SPacket) f.sent.stream().filter(p -> p instanceof InventoryC2SPacket i && i.kind() == InventoryC2SPacket.Kind.SELECT).findFirst().orElseThrow();
		var second = f.preview(); assertEquals(ClientInventory.DispatchOutcome.THROTTLED, f.runtime.dispatchInventory(second, "danger"));
		assertEquals(ClientInventory.DispatchOutcome.ALREADY_RELEASED, f.runtime.dispatchInventory(second, "danger"));
		var reject = InventoryS2CPacket.selected(7, selected.requestId(), selected.commitId(), null, MarkerRejectReason.TARGET_GONE).stamp(100, 1);
		f.runtime.onInventoryPacket(reject); f.runtime.onInventoryPacket(reject);
		assertEquals(List.of(InventoryFailureReason.RATE_LIMITED.translationKey(), InventoryFailureReason.TARGET_GONE.translationKey()), f.errors);
		assertEquals(1, f.sent.stream().filter(p -> p instanceof InventoryC2SPacket i && i.kind() == InventoryC2SPacket.Kind.SELECT).count());
	}
	@Test void transportFailureIsNotARequestAndCannotRetrySameRelease() {
		Fixture f = new Fixture(); var reference = f.preview(); f.fail = true;
		assertEquals(ClientInventory.DispatchOutcome.TRANSPORT_FAILED, f.runtime.dispatchInventory(reference, "attention"));
		assertEquals(ClientInventory.DispatchOutcome.ALREADY_RELEASED, f.runtime.dispatchInventory(reference, "attention"));
		f.runtime.onInventoryPacket(InventoryS2CPacket.selected(7, reference.requestId(), 1, null, MarkerRejectReason.TARGET_GONE).stamp(100, 1));
		assertEquals(List.of(InventoryFailureReason.TRANSPORT_FAILURE.translationKey()), f.errors);
	}
	@Test void synchronousMatchingRejectThenSenderThrowKeepsOnlyTheAuthoritativeReason() {
		Fixture f = new Fixture(); var reference = f.preview(); f.failAfterSelection = true;
		f.selectionHandoff = selection -> f.runtime.onInventoryPacket(InventoryS2CPacket.selected(7,
			selection.requestId(), selection.commitId(), null, MarkerRejectReason.INVALID_REQUEST,
			InventoryFailureReason.SOURCE_AUTHORITY_DENIED).stamp(100, 1));
		assertEquals(ClientInventory.DispatchOutcome.SENT, f.runtime.dispatchInventory(reference, "attention"));
		assertEquals(List.of(InventoryFailureReason.SOURCE_AUTHORITY_DENIED.translationKey()), f.errors);
		var selection = (InventoryC2SPacket) f.sent.stream().filter(packet -> packet instanceof InventoryC2SPacket p
			&& p.kind() == InventoryC2SPacket.Kind.SELECT).findFirst().orElseThrow();
		assertEquals(InventoryFailureReason.SOURCE_AUTHORITY_DENIED, f.inventory.selectionResult(selection.commitId()).failureReason());
		f.selectionHandoff.accept(selection);
		assertEquals(1, f.errors.size(), "terminal replay cannot add another line");
		assertEquals(ClientInventory.DispatchOutcome.ALREADY_RELEASED, f.runtime.dispatchInventory(reference, "attention"));
		assertEquals(1, f.sent.stream().filter(packet -> packet instanceof InventoryC2SPacket p && p.kind() == InventoryC2SPacket.Kind.SELECT).count());
	}
	@Test void synchronousMatchingSelectedThenSenderThrowDoesNotReportCreationFailure() {
		Fixture f = new Fixture(); var reference = f.preview(); f.failAfterSelection = true; MarkerId marker = new MarkerId(91);
		f.selectionHandoff = selection -> f.runtime.onInventoryPacket(InventoryS2CPacket.selected(7,
			selection.requestId(), selection.commitId(), marker, null).stamp(100, 1));
		assertEquals(ClientInventory.DispatchOutcome.SENT, f.runtime.dispatchInventory(reference, "attention"));
		assertTrue(f.errors.isEmpty());
		var selection = (InventoryC2SPacket) f.sent.stream().filter(packet -> packet instanceof InventoryC2SPacket p
			&& p.kind() == InventoryC2SPacket.Kind.SELECT).findFirst().orElseThrow();
		assertEquals(marker, f.inventory.selectionResult(selection.commitId()).markerId());
		assertNull(f.inventory.selectionResult(selection.commitId()).rejection());
		f.runtime.onInventoryPacket(InventoryS2CPacket.selected(7, selection.requestId(), selection.commitId(), null,
			MarkerRejectReason.TARGET_GONE).stamp(100, 1));
		assertTrue(f.errors.isEmpty(), "a later contradictory result cannot replace the consumed success");
	}
	@Test void staleSynchronousResponseDoesNotHideActualTransportFailure() {
		Fixture f = new Fixture(); var reference = f.preview(); f.failAfterSelection = true;
		f.selectionHandoff = selection -> f.runtime.onInventoryPacket(InventoryS2CPacket.selected(7,
			selection.requestId(), selection.commitId(), new MarkerId(91), null).stamp(100, 0));
		assertEquals(ClientInventory.DispatchOutcome.TRANSPORT_FAILED, f.runtime.dispatchInventory(reference, "attention"));
		assertEquals(List.of(InventoryFailureReason.TRANSPORT_FAILURE.translationKey()), f.errors);
	}
	@Test void synchronousSuccessThenSessionResetAndSenderThrowKeepsTheAcceptedTerminalLatch() {
		Fixture f = new Fixture(); var reference = f.preview(); f.failAfterSelection = true;
		f.selectionHandoff = selection -> {
			f.runtime.onInventoryPacket(InventoryS2CPacket.selected(7, selection.requestId(), selection.commitId(), new MarkerId(91), null).stamp(100, 1));
			f.inventory.reset();
		};
		assertEquals(ClientInventory.DispatchOutcome.SENT, f.runtime.dispatchInventory(reference, "attention"));
		assertTrue(f.errors.isEmpty(), "an accepted terminal cannot become a transport failure when its session cache is cleared reentrantly");
	}
	@Test void acceptedBasicDeliveryAuthorizesTrackingAndMarkerExpiryEndsInventoryEvenIfBasicIsRetainedStale() {
		Fixture f = new Fixture(); var marker = new MarkerId(3);
		var data = InventoryS2CPacket.data(InventoryS2CPacket.Kind.SNAPSHOT, 7, 0, marker, 4, 1, 1, 0, 1, true,
			InventoryS2CPacket.Status.UNCERTAIN, 0, List.of(Fixture.entry())).stamp(100, 1);
		f.runtime.onInventoryPacket(data); assertTrue(f.inventory.tracking(marker).entries().isEmpty());
		var snapshot = new MarkerSnapshot(marker, new UUID(1, 1), TARGET, "entity_block", "attention", new MarkerAnchor(1, 2, 3), 0, 30);
		var grant = new MarkerContentGrant(TestContentGrants.fromAllowed(Map.of(
			PresentationBasic.ID, Set.of(PresentationBasic.NAME),
			InventoryPresentation.ADAPTER_ID, Set.of(InventoryResourceKind.ITEM.fieldId()))),
			InventoryResourceKind.ITEM);
		var content = PresentationReceiptContent.inventory(new InventoryCreationSnapshot(InventoryResourceKind.ITEM,
			"selected", "minecraft:stone", "Stone", null, 7, false, "attention", true, false));
		f.runtime.onPresentationPacket(PresentationS2CPacket.created(100, 1, 1, snapshot, "Owner",
			PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME), grant, content,
			new PresentationSection(PresentationBasic.ID, 1,
			Map.of(PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"Chest\"}")), false)));
		f.runtime.onInventoryPacket(data); assertEquals(7, f.inventory.tracking(marker).entries().getFirst().count());
		f.runtime.onPresentationPacket(PresentationS2CPacket.removed(100, 1, marker, MarkerRemovalReason.EXPIRED));
		assertTrue(f.runtime.store().marker(marker).orElseThrow().isStale()); assertTrue(f.inventory.tracking(marker).entries().isEmpty());
		f.runtime.onInventoryPacket(data); assertTrue(f.inventory.tracking(marker).entries().isEmpty());
	}
}
