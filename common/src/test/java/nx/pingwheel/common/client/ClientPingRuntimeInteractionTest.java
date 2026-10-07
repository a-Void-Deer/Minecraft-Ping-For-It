package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.client.spatial.NativeSelectorInput;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.client.spatial.SelectorIntent;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.CancelMarkerCandidate;
import nx.pingwheel.common.interaction.cancel.CancellationContext;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.CandidateEvidence;
import nx.pingwheel.common.interaction.candidate.CandidateHit;
import nx.pingwheel.common.interaction.candidate.CaptureEquivalenceKey;
import nx.pingwheel.common.interaction.candidate.FrozenCandidateAcquisition;
import nx.pingwheel.common.interaction.candidate.PreciseTargetType;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import nx.pingwheel.common.interaction.state.TargetGoneReason;
import nx.pingwheel.common.interaction.state.TargetValidation;
import nx.pingwheel.common.network.*;
import nx.pingwheel.common.presentation.*;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.preview.ClientPresentationPreview;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;
import nx.pingwheel.common.presentation.preview.PreviewObservation;
import nx.pingwheel.common.render.SpatialOverlayRenderer;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end runtime interaction, with only the live world, native cursor and clock recorded. */
class ClientPingRuntimeInteractionTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final UUID OWNER = new UUID(1, 2);
	private static final TargetSnapshot CHEST = TargetSnapshotFactory.block(DIMENSION, 1, 2, 3, "minecraft:chest", true, BlockFace.WEST);
	private static final TargetSnapshot STONE = TargetSnapshotFactory.block(DIMENSION, 4, 5, 6, "minecraft:stone", false, BlockFace.NORTH);
	private static final TargetSnapshot BEHIND_CHEST = TargetSnapshotFactory.block(DIMENSION, 7, 2, 3, "minecraft:chest", true, BlockFace.EAST);
	private static final TargetSnapshot ITEM = TargetSnapshotFactory.entity(DIMENSION, new UUID(3, 4), "minecraft:item");
	private static final TargetSnapshot BEHIND_ITEM = TargetSnapshotFactory.entity(DIMENSION, new UUID(5, 6), "minecraft:item");
	private int oldHold, oldSlice;
	private boolean oldCompatibility;

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@BeforeEach void settings() {
		var config = ClientConfig.HANDLER.getConfig();
		oldHold = config.getWheelHoldMillis();
		oldSlice = config.getLongPressCompatibilitySliceMillis(); oldCompatibility = config.isLongPressCompatibilityMode();
		config.setWheelHoldMillis(250);
		config.setLongPressCompatibilityMode(false); config.setLongPressCompatibilitySliceMillis(125);
	}
	@AfterEach void restoreSettings() {
		var config = ClientConfig.HANDLER.getConfig();
		config.setWheelHoldMillis(oldHold);
		config.setLongPressCompatibilitySliceMillis(oldSlice); config.setLongPressCompatibilityMode(oldCompatibility);
	}

	private static final class Access implements ClientPingRuntime.InteractionAccess {
		Object world = new Object(), screen;
		boolean player = true, focus = true, overlay, grabbed = true, pending, validFrame = true;
		String dimension = DIMENSION;
		int captures, cancellations, resets, disposals;
		boolean screenDisposal;
		CapturedRay ray = CapturedRay.defaultRay();
		TargetSnapshot snapshot = CHEST;
		List<TargetSnapshot> supplements = List.of();
		final List<Runnable> completions = new ArrayList<>();
		CapturedRay capturedRay, cancellationRay;
		final List<SelectorIntent.CaptureToggle> toggles = new ArrayList<>();
		NativeSelectorInput.Frame frame = new NativeSelectorInput.Frame(1, 2000, 1400, 1000, 700, true, true);
		public Lifecycle lifecycle() { return new Lifecycle(world, dimension, screen, player, focus, overlay); }
		public Optional<CapturedRay> capturePressRay() { return Optional.of(ray); }
		public void capture(InteractionToken token, CapturedRay pressRay, ClientPingRuntime.CaptureCompletion completion) {
			captures++; capturedRay = pressRay;
			TargetSnapshot frozen = surface(snapshot, new WorldVector(1, 2, 3));
			var evidence = supplements.stream().map(value -> surface(value, new WorldVector(5, 5, 5)))
				.map(value -> new CandidateEvidence(value, FrozenCandidateAcquisition.distance(pressRay.origin(), value.candidateHit().orElseThrow().worldHit()))).toList();
			var acquisition = new FrozenCandidateAcquisition(token, pressRay, 100, new WorldVector(1, 2, 3), evidence,
				Set.of(PreciseTargetType.values()));
			Runnable apply = () -> completion.complete(frozen, Optional.of(acquisition));
			completions.add(apply);
			if (!pending) apply.run();
		}
		public CancellationContext cancellation(CapturedRay frozen) {
			cancellations++; cancellationRay = frozen;
			return new CancellationContext(OWNER, DIMENSION, frozen.origin(), frozen.direction(), List.of(
				new CancelMarkerCandidate(new MarkerId(42), OWNER, DIMENSION, new WorldVector(
					frozen.origin().x() + frozen.direction().x() * 2, frozen.origin().y() + frozen.direction().y() * 2,
					frozen.origin().z() + frozen.direction().z() * 2))));
		}
		public void syncMouse(PingInteractionPhase phase) { grabbed = phase != PingInteractionPhase.WHEEL_OPEN; }
		public void disposeMouse(boolean screenTransition) { disposals++; screenDisposal = screenTransition; grabbed = !screenTransition; }
		public Optional<NativeSelectorInput.Frame> inputFrame() {
			return validFrame ? Optional.of(new NativeSelectorInput.Frame(frame.window(), frame.windowWidth(), frame.windowHeight(),
				frame.guiWidth(), frame.guiHeight(), focus, grabbed)) : Optional.empty();
		}
		public SpatialSelectorSession.ListGeometry listGeometry(SpatialOverlayRenderer.Style style) {
			return new SpatialSelectorSession.ListGeometry(5, 220, 18, 24, 20, 40);
		}
		public void resetInput() { resets++; }
		public void applyToggle(SelectorIntent.CaptureToggle toggle, long timeMillis) { toggles.add(toggle); }
	}
	private static TargetSnapshot surface(TargetSnapshot value, WorldVector hit) {
		return value.withCandidateHit(new CandidateHit(hit, CaptureEquivalenceKey.nativeTarget(value.target())));
	}
	private static boolean disabled(Fixture f, String choiceId) {
		return f.menu().choices().stream().filter(choice -> choice.id().equals(choiceId)).findFirst().orElseThrow().disabled();
	}
	private static final class Fixture {
		long now;
		boolean fail, gone;
		Runnable validationHook = () -> {};
		final Access access = new Access();
		final List<IPacket> sent = new ArrayList<>();
		final List<String> errors = new ArrayList<>();
		final List<ResolvedTarget> validations = new ArrayList<>();
		final ClientPresentation presentation = new ClientPresentation(this::send);
		final ClientInventory inventory = new ClientInventory(this::send);
		final ClientPresentationPreview preview;
		final ClientPingRuntime runtime;
		double propertyValue = 7;
		Fixture() { this(new ClientRateLimitPolicy(20, 1000), true); }
		Fixture(ClientRateLimitPolicy policy, boolean ready) {
			PreviewFieldAccess reader = new PreviewFieldAccess() {
				public String adapterId() { return PresentationBasic.ID; }
				public Map<String, Outcome> observe(nx.pingwheel.common.domain.Target target, Set<String> fields, ReadContext context) {
					Map<String, Outcome> result = new java.util.LinkedHashMap<>();
					for (String field : fields) if (field.equals(PresentationBasic.HEALTH) || field.equals(PresentationBasic.MAX_HEALTH))
						result.put(field, new Observed(new PreviewObservation(new PresentationValue.NumberValue(
							field.equals(PresentationBasic.HEALTH) ? propertyValue : 20), PreviewObservation.Origin.CLIENT_SYNCED, context.tick(), false)));
					return result;
				}
			};
			preview = new ClientPresentationPreview(presentation::previewAccess, () -> new PreviewFieldAccess.ReadContext() {
				public Object levelIdentity() { return access.world; }
				public String dimensionId() { return access.dimension; }
				public long tick() { return now / 50; }
			}, List.of(reader), (target, type) -> Optional.empty(), this::send);
			runtime = ClientPingRuntime.createForInteraction((key, color) -> errors.add(key), this::send, policy, () -> now,
				target -> { validations.add(target); validationHook.run(); return gone ? TargetValidation.gone(TargetGoneReason.BLOCK_REPLACED) : TargetValidation.valid(); },
				access, presentation, inventory, preview);
			if (ready) negotiate(Set.of(PresentationBasic.NAME));
		}
		void send(IPacket packet) {
			if (fail && packet instanceof PresentationC2SPacket p && p.kind() == PresentationC2SPacket.Kind.CREATE) throw new IllegalStateException("transport");
			sent.add(packet);
		}
		void negotiate(Set<String> fields) {
			presentation.tick(true);
			runtime.onPresentationPacket(PresentationS2CPacket.offer(100, Map.of(PresentationBasic.ID, List.of(
				new PresentationField(PresentationBasic.NAME, PresentationField.Kind.TEXT, true, 0, "Name"),
				new PresentationField(PresentationBasic.HEALTH, PresentationField.Kind.NUMBER, true, 0, "Health"),
				new PresentationField(PresentationBasic.MAX_HEALTH, PresentationField.Kind.NUMBER, true, 0, "Maximum health"))), Map.of(PresentationBasic.ID, 1)));
			reset(1, fields);
			runtime.onInventoryPacket(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(5, 3, 5, 0)).stamp(100, 1));
			runtime.onInventoryPacket(InventoryS2CPacket.policy(7, 100, 1, Set.of("entity_block", "block", "entity")));
		}
		void reset(long view, Set<String> fields) {
			runtime.onPresentationPacket(PresentationS2CPacket.reset(100, view, Map.of(
				"entity_block", Map.of(PresentationBasic.ID, fields), "block", Map.of(PresentationBasic.ID, fields), "entity", Map.of(PresentationBasic.ID, fields))));
		}
		void press(long time) { now = time; runtime.onPress(time); }
		void release(long time) { now = time; runtime.onRelease(); }
		void frame(long time, boolean held) { now = time; runtime.onRenderFrame(held); }
		SpatialSelectorSession.Snapshot snapshot() { return runtime.selectorSnapshot().orElseThrow(); }
		SpatialController.MenuView menu() { return snapshot().radial().menus().getLast(); }
		double rawX = 1000, rawY = 700;
		void prime() { runtime.onMouseMove(1, rawX, rawY); }
		void move(double dx, double dy, long time) {
			now = time; rawX += dx * access.frame.windowWidth() / access.frame.guiWidth();
			rawY += dy * access.frame.windowHeight() / access.frame.guiHeight(); runtime.onMouseMove(1, rawX, rawY);
		}
		void focus(String choiceId, long time) {
			var menu = menu(); var choice = menu.choices().stream().filter(value -> value.id().equals(choiceId)).findFirst().orElseThrow();
			double radians = Math.toRadians(choice.startDegrees() + choice.spanDegrees() / 2);
			var pointer = snapshot().radial().pointer(); double distance = snapshot().settings().stroke() * 2;
			move(menu.origin().x() + Math.sin(radians) * distance - pointer.x(), menu.origin().y() - Math.cos(radians) * distance - pointer.y(), time);
		}
		void enter(String choiceId, long time) {
			int depth = snapshot().radial().menus().size(); focus(choiceId, time); frame(time + snapshot().settings().dwellMillis(), true);
			assertEquals(depth + 1, snapshot().radial().menus().size());
		}
		long request() { return sent.stream().filter(p -> p instanceof InventoryC2SPacket i && i.kind() == InventoryC2SPacket.Kind.OPEN)
			.map(p -> ((InventoryC2SPacket) p).requestId()).reduce((a, b) -> b).orElseThrow(); }
		void inventoryRows() {
			runtime.onInventoryPacket(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, request(), null, 9, 1, 1, 0, 1, true,
				InventoryS2CPacket.Status.READY, 0, List.of(new InventoryS2CPacket.Entry("opaque", "minecraft:stone", "Stone", null, 7, 1, false, null))).stamp(100, 1));
		}
		void openList() {
			press(0); inventoryRows(); frame(250, true); prime(); enter("content", 260);
			String list = menu().choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow().id();
			enter(list, 460); assertTrue(snapshot().inventory().open()); assertEquals("opaque", snapshot().inventory().selectedKey());
		}
		List<PresentationC2SPacket> creates() { return sent.stream().filter(p -> p instanceof PresentationC2SPacket c && c.kind() == PresentationC2SPacket.Kind.CREATE).map(p -> (PresentationC2SPacket) p).toList(); }
		List<InventoryC2SPacket> selects() { return sent.stream().filter(p -> p instanceof InventoryC2SPacket c && c.kind() == InventoryC2SPacket.Kind.SELECT).map(p -> (InventoryC2SPacket) p).toList(); }
	}

	@Test void shortPressKeepsPressTargetAndRayDespiteCameraMoveAndOpensHeldPreviewOnlyOnce() {
		Fixture f = new Fixture(); f.press(0); CapturedRay pressRay = f.access.capturedRay;
		assertEquals(1, f.inventory.stats().previewChannels()); assertTrue(f.runtime.selectorSnapshot().isEmpty());
		f.access.ray = new CapturedRay(new WorldVector(30, 30, 30), new WorldVector(1, 0, 0)); f.access.snapshot = STONE;
		f.release(10); f.release(11); f.frame(500, false);
		assertEquals(CHEST.target(), f.creates().getFirst().target()); assertEquals(1, f.creates().size());
		assertEquals(pressRay, f.access.capturedRay); assertEquals(1, f.access.captures); assertEquals(0, f.access.cancellations);
		assertEquals(0, f.inventory.stats().previewChannels()); assertFalse(f.runtime.hasCompatibilityState());
	}
	@Test void pendingReleaseCompletionCannotOpenMenuOrStartHeldPreview() {
		Fixture f = new Fixture(); f.access.pending = true; f.press(0); f.release(300); f.frame(400, false);
		assertTrue(f.creates().isEmpty()); assertEquals(0, f.inventory.stats().previewChannels());
		f.access.completions.getFirst().run(); f.frame(500, false); f.frame(600, false);
		assertEquals(1, f.creates().size()); assertTrue(f.runtime.selectorSnapshot().isEmpty()); assertEquals(0, f.inventory.stats().previewChannels());
	}
	@Test void elapsedThresholdWithoutPresentIsStillExactlyOneDefaultCreate() {
		Fixture f = new Fixture(); f.press(0); f.release(800);
		assertEquals(1, f.creates().size()); assertEquals("attention", f.creates().getFirst().pingType());
		assertTrue(f.runtime.selectorSnapshot().isEmpty()); assertEquals(1, f.access.captures);
	}
	@ParameterizedTest @ValueSource(strings = {"reset", "screen", "focus", "level", "dimension", "disconnect"})
	void abortInvalidatesLateCaptureAndSyntheticRelease(String reason) {
		Fixture f = new Fixture(); f.access.pending = true; f.press(0);
		switch (reason) {
			case "reset" -> f.runtime.abort();
			case "screen" -> { f.runtime.abortForScreenTransition(); f.access.screen = new Object(); }
			case "focus" -> { f.access.focus = false; f.frame(20, true); }
			case "level" -> { f.access.world = new Object(); f.frame(20, true); }
			case "dimension" -> { f.access.dimension = "minecraft:the_nether"; f.frame(20, true); }
			case "disconnect" -> f.runtime.close();
			default -> throw new AssertionError();
		}
		f.release(21); f.access.completions.getFirst().run(); f.frame(400, false);
		assertTrue(f.creates().isEmpty()); assertTrue(f.selects().isEmpty()); assertTrue(f.runtime.selectorSnapshot().isEmpty());
		assertEquals(PingInteractionPhase.IDLE, f.runtime.phase()); assertEquals(0, f.inventory.stats().previewChannels());
		assertTrue(f.access.resets > 0);
	}
	@Test void centerAbandonsAndOnlyDownCancelLazilyBuildsFrozenCone() {
		Fixture center = new Fixture(); center.press(0); center.frame(250, true); center.release(260);
		assertTrue(center.creates().isEmpty()); assertEquals(0, center.access.cancellations);
		Fixture cancel = new Fixture(); cancel.press(0); cancel.frame(250, true); cancel.prime(); cancel.focus("cancel-marker", 260);
		cancel.access.ray = new CapturedRay(new WorldVector(10, 10, 10), new WorldVector(1, 0, 0)); cancel.release(270);
		assertEquals(1, cancel.access.cancellations); assertEquals(cancel.access.capturedRay, cancel.access.cancellationRay);
		assertEquals(1, cancel.sent.stream().filter(p -> p instanceof PresentationC2SPacket c && c.kind() == PresentationC2SPacket.Kind.REMOVE).count());
		assertTrue(cancel.creates().isEmpty()); assertEquals(0, cancel.inventory.stats().previewChannels());
	}
	@Test void listReleaseSendsSelectBeforeExactCloseAndNeverOrdinaryCreate() {
		Fixture f = new Fixture(); f.openList(); long request = f.request(); f.release(700); f.release(701); f.frame(800, false);
		assertEquals(1, f.selects().size()); assertEquals("opaque", f.selects().getFirst().entryKey()); assertEquals(request, f.selects().getFirst().requestId());
		assertTrue(f.creates().isEmpty()); assertEquals(0, f.inventory.stats().previewChannels());
		int selected = f.sent.indexOf(f.selects().getFirst());
		int closed = java.util.stream.IntStream.range(0, f.sent.size()).filter(i -> f.sent.get(i) instanceof InventoryC2SPacket c
			&& c.kind() == InventoryC2SPacket.Kind.CLOSE && c.requestId() == request).findFirst().orElseThrow();
		assertTrue(selected < closed); assertEquals(1, f.validations.size()); assertEquals(0, f.access.cancellations);
	}
	@Test void longHeldSelectorDoesNotAutoCloseAndReleaseStillSelectsRow() {
		Fixture f = new Fixture(); f.openList(); long request = f.request(); f.release(10250);
		assertEquals(1, f.selects().size()); assertEquals("opaque", f.selects().getFirst().entryKey()); assertEquals(request, f.selects().getFirst().requestId());
		assertTrue(f.creates().isEmpty()); assertEquals(1, f.validations.size());
		assertEquals(PingInteractionPhase.IDLE, f.runtime.phase()); assertEquals(0, f.inventory.stats().previewChannels());
	}
	@Test void resetImmediatelyBeforeReleaseCannotSelectAndLatePreviewCannotRevive() {
		Fixture f = new Fixture(); f.openList(); long request = f.request(); f.runtime.abort(); f.release(700);
		f.runtime.onInventoryPacket(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, request, null, 9, 1, 2, 0, 1, true,
			InventoryS2CPacket.Status.READY, 0, List.of(new InventoryS2CPacket.Entry("late", "minecraft:stone", "Stone", null, 8, 2, false, null))).stamp(100, 1));
		assertTrue(f.selects().isEmpty()); assertTrue(f.creates().isEmpty()); assertEquals(0, f.inventory.stats().previewChannels());
	}
	@Test void policyResetImmediatelyBeforeListReleaseBlocksOldOpaqueReferenceWithoutPlainRetry() {
		Fixture f = new Fixture(); f.openList(); f.reset(2, Set.of()); f.release(700);
		assertTrue(f.selects().isEmpty()); assertTrue(f.creates().isEmpty()); assertTrue(f.runtime.selectorSnapshot().isEmpty());
	}
	@Test void preciseReleaseConsumesFrozenCandidateIdRatherThanCurrentCamera() {
		Fixture f = new Fixture(); f.access.supplements = List.of(STONE); f.press(0); f.frame(250, true); f.prime(); f.enter("precise", 260);
		f.focus("precise:block", 460); f.access.snapshot = CHEST; f.release(470);
		assertEquals(STONE.target(), f.creates().getFirst().target()); assertEquals(1, f.access.captures); assertEquals(1, f.validations.size());
	}
	@Test void broadBlockSlotKeepsBehindChestSeparateFromOrdinaryEntityBlock() {
		Fixture front = new Fixture(); front.access.supplements = List.of(BEHIND_CHEST);
		front.press(0); front.frame(250, true); front.prime(); front.enter("precise", 260);
		assertFalse(disabled(front, "precise:entity_block")); assertFalse(disabled(front, "precise:block"));
		front.focus("precise:entity_block", 460); front.release(470);
		assertEquals(CHEST.target(), front.creates().getFirst().target()); assertEquals("attention", front.creates().getFirst().pingType());

		Fixture behind = new Fixture(); behind.access.supplements = List.of(BEHIND_CHEST);
		behind.press(0); behind.frame(250, true); behind.prime(); behind.enter("precise", 260);
		behind.focus("precise:block", 460); behind.release(470);
		assertEquals(BEHIND_CHEST.target(), behind.creates().getFirst().target());
		assertEquals("attention", behind.creates().getFirst().pingType());
	}
	@Test void broadEntitySlotKeepsBehindDroppedItemSeparateFromOrdinaryDroppedItem() {
		Fixture front = new Fixture(); front.access.snapshot = ITEM; front.access.supplements = List.of(BEHIND_ITEM);
		front.press(0); front.frame(250, true); front.prime(); front.enter("precise", 260);
		assertFalse(disabled(front, "precise:dropped_item")); assertFalse(disabled(front, "precise:entity"));
		front.focus("precise:dropped_item", 460); front.release(470);
		assertEquals(ITEM.target(), front.creates().getFirst().target()); assertEquals("loot", front.creates().getFirst().pingType());

		Fixture behind = new Fixture(); behind.access.snapshot = ITEM; behind.access.supplements = List.of(BEHIND_ITEM);
		behind.press(0); behind.frame(250, true); behind.prime(); behind.enter("precise", 260);
		behind.focus("precise:entity", 460); behind.release(470);
		assertEquals(BEHIND_ITEM.target(), behind.creates().getFirst().target());
		assertEquals("loot", behind.creates().getFirst().pingType());
	}
	@Test void targetGoneIsFeedbackOnlyAndClosesPreview() {
		Fixture f = new Fixture(); f.press(0); f.frame(250, true); f.prime(); f.enter("intent", 260); f.focus("intent:attention", 460);
		f.gone = true; f.release(470); assertTrue(f.creates().isEmpty()); assertEquals(1, f.errors.size()); assertEquals(0, f.inventory.stats().previewChannels());
	}
	@ParameterizedTest @ValueSource(strings = {"throttle", "unready", "transport", "gone"})
	void declinedDefaultReceiptCannotLaunchDeferredInteractionOrQueueCreate(String decline) {
		ClientConfig.HANDLER.getConfig().setLongPressCompatibilityMode(true);
		Fixture f = new Fixture(new ClientRateLimitPolicy(1, 1000), !decline.equals("unready"));
		if (decline.equals("throttle")) { f.press(0); f.release(1); f.runtime.abort(); f.sent.clear(); }
		else f.runtime.applyRateLimitPolicy(new ClientRateLimitPolicy(10, 1000));
		int capturesBefore = f.access.captures;
		f.fail = decline.equals("transport"); f.gone = decline.equals("gone"); f.access.pending = true;
		f.press(20); f.release(30); f.press(300); f.release(310); f.access.completions.getLast().run(); f.frame(320, false); f.frame(3000, false);
		assertEquals(capturesBefore + 1, f.access.captures); assertTrue(f.creates().isEmpty()); assertFalse(f.runtime.hasCompatibilityState()); assertTrue(f.runtime.selectorSnapshot().isEmpty());
	}
	@Test void successfulDefaultReceiptAloneLaunchesDeferredFrozenRay() {
		ClientConfig.HANDLER.getConfig().setLongPressCompatibilityMode(true);
		Fixture f = new Fixture(); f.access.pending = true; f.press(0); f.release(10);
		CapturedRay secondRay = new CapturedRay(new WorldVector(1, 1, 1), new WorldVector(1, 0, 0)); f.access.ray = secondRay;
		f.press(300); f.release(310); f.access.ray = CapturedRay.defaultRay(); f.access.completions.getFirst().run(); f.frame(320, false);
		assertEquals(2, f.access.captures); assertEquals(secondRay, f.access.capturedRay); assertEquals(1, f.creates().size());
		f.access.completions.getLast().run(); f.frame(330, false); assertEquals(2, f.creates().size());
	}
	@Test void resizeCaptureAndInvalidDimensionsReprimeWithoutSyntheticTravelAndScrollIsOwned() {
		Fixture f = new Fixture(); assertFalse(f.runtime.onMouseScroll(1, 0, 1)); f.press(0); f.frame(250, true); f.prime();
		f.move(10, 0, 260); var before = f.snapshot().radial().pointer();
		f.access.validFrame = false; f.move(500, 500, 270); assertFalse(f.runtime.onMouseScroll(1, 0, 1));
		f.access.validFrame = true; f.move(500, 500, 280); assertEquals(before, f.snapshot().radial().pointer());
		f.access.frame = new NativeSelectorInput.Frame(1, 1000, 700, 1000, 700, true, false); f.move(500, 500, 290);
		assertEquals(before, f.snapshot().radial().pointer()); assertTrue(f.runtime.onMouseScroll(1, 0, 1)); assertFalse(f.runtime.onMouseScroll(2, 0, 1));
		f.runtime.abortForScreenTransition(); assertTrue(f.access.screenDisposal); assertFalse(f.runtime.onMouseScroll(1, 0, 1));
	}
	@Test void propertyCreatePreservesAddressObservedValueAnnotationAndWholeMarkerType() {
		Fixture f = new Fixture(); f.access.snapshot = TargetSnapshotFactory.entity(DIMENSION, new UUID(5, 6), "minecraft:pig");
		f.reset(2, Set.of(PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH));
		f.press(0); f.frame(250, true); f.prime(); f.enter("content", 260);
		String property = f.menu().choices().stream().filter(choice -> choice.label().contains(PresentationBasic.HEALTH))
			.findFirst().orElseThrow().id(); f.focus(property, 460);
		f.propertyValue = 9; f.release(470);
		var create = f.creates().getFirst(); var selection = create.properties().getFirst();
		assertEquals(PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.HEALTH), selection.ref());
		assertEquals(new PresentationValue.NumberValue(7), selection.observedValue(), "release authorizes but never substitutes a fresh observation");
		assertEquals("attention", selection.pingTypeId()); assertEquals("attention", create.pingType()); assertEquals(1, f.creates().size());
	}
	@Test void deniedPropertyAfterResetCannotFallBackToPlainCreate() {
		Fixture f = new Fixture(); f.access.snapshot = TargetSnapshotFactory.entity(DIMENSION, new UUID(5, 6), "minecraft:pig");
		f.reset(2, Set.of(PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH));
		f.press(0); f.frame(250, true); f.prime(); f.enter("content", 260);
		String property = f.menu().choices().stream().filter(choice -> choice.label().contains(PresentationBasic.HEALTH))
			.findFirst().orElseThrow().id(); f.focus(property, 460); f.reset(3, Set.of()); f.release(470);
		assertTrue(f.creates().isEmpty()); assertTrue(f.runtime.selectorSnapshot().isEmpty());
	}
	@Test void localToggleCommitsOnceWithoutValidationPacketOrCancellation() {
		Fixture f = new Fixture(); f.press(0); f.frame(250, true); f.prime(); f.enter("settings", 260);
		f.focus("settings:FLUIDS", 460); f.release(470); f.release(480);
		assertEquals(List.of(SelectorIntent.CaptureToggle.FLUIDS), f.access.toggles); assertTrue(f.creates().isEmpty());
		assertTrue(f.validations.isEmpty()); assertEquals(0, f.access.cancellations); assertEquals(0, f.inventory.stats().previewChannels());
	}
	@Test void actuallyOpenedMenuDoesNotSeedCompatibilityAfterClose() {
		ClientConfig.HANDLER.getConfig().setLongPressCompatibilityMode(true);
		Fixture f = new Fixture(); f.press(0); f.frame(250, true); f.prime(); f.enter("intent", 260);
		f.focus("intent:attention", 460); f.release(470); assertFalse(f.runtime.hasCompatibilityState());
		f.press(480); f.release(490); assertEquals(2, f.creates().size()); assertEquals(2, f.access.captures);
	}
	@Test void throttleOnSelectedListIsSingleUseAndNeverQueuedOrRetriedAsCreate() {
		Fixture f = new Fixture(new ClientRateLimitPolicy(1, 1000), true);
		f.openList();
		long other = f.inventory.open((nx.pingwheel.common.domain.Target.BlockTarget) CHEST.target(), BlockFace.NORTH, "entity_block");
		f.runtime.onInventoryPacket(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, other, null, 8, 1, 1, 0, 1, true,
			InventoryS2CPacket.Status.READY, 0, List.of(new InventoryS2CPacket.Entry("other", "minecraft:stone", "Stone", null, 4, 1, false, null))).stamp(100, 1));
		assertEquals(ClientInventory.DispatchOutcome.SENT, f.runtime.dispatchInventory(f.inventory.selectable(other).getFirst(), "attention"));
		f.release(700); f.frame(2000, false);
		assertEquals(1, f.selects().size()); assertEquals(other, f.selects().getFirst().requestId()); assertTrue(f.creates().isEmpty());
		assertEquals(1, f.inventory.stats().previewChannels(), "cleanup closes only the selector-owned request");
	}
	@Test void heldPreviewResponseUsesRuntimeForwarderAndLateResponseAfterAbortCannotRevive() {
		Fixture f = new Fixture(); f.press(0);
		var request = f.sent.stream().filter(p -> p instanceof PresentationPreviewC2SPacket q
			&& q.kind() == PresentationPreviewC2SPacket.Kind.READ).map(p -> (PresentationPreviewC2SPacket) p).findFirst().orElseThrow();
		var reply = PresentationPreviewS2CPacket.result(request, new PresentationSection(PresentationBasic.ID, 1,
			Map.of(PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"Actual chest\"}")), false));
		f.runtime.onPresentationPreview(reply);
		assertEquals(new PresentationValue.Text("{\"text\":\"Actual chest\"}"), f.preview.projection().orElseThrow()
			.property(PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME)).orElseThrow().value());
		f.runtime.abort(); f.runtime.onPresentationPreview(reply); f.release(20); f.frame(400, false);
		assertTrue(f.preview.projection().isEmpty()); assertTrue(f.runtime.selectorSnapshot().isEmpty()); assertTrue(f.creates().isEmpty());
	}
	@Test void lateOldCaptureCannotClearNewSelectorOrItsExactRequest() {
		Fixture f = new Fixture(); f.access.pending = true; f.press(0); Runnable old = f.access.completions.getFirst(); f.runtime.abort();
		f.access.pending = false; f.press(100); long current = f.request(); f.frame(350, true);
		old.run(); assertTrue(f.runtime.selectorSnapshot().isPresent()); assertEquals(1, f.inventory.stats().previewChannels());
		f.release(360); assertEquals(0, f.inventory.stats().previewChannels());
		assertEquals(1, f.sent.stream().filter(p -> p instanceof InventoryC2SPacket c && c.kind() == InventoryC2SPacket.Kind.CLOSE && c.requestId() == current).count());
	}
	@Test void rapidReceiptBackdatesOnlyTimeAndKeepsSecondPhysicalRay() {
		ClientConfig.HANDLER.getConfig().setLongPressCompatibilityMode(true);
		Fixture f = new Fixture(); f.press(0); f.release(10); assertEquals(1, f.creates().size());
		CapturedRay second = new CapturedRay(new WorldVector(5, 5, 5), new WorldVector(1, 0, 0)); f.access.ray = second;
		f.press(120); f.release(121); f.frame(240, false); assertTrue(f.runtime.selectorSnapshot().isEmpty());
		f.press(245); f.release(246); f.frame(250, false); assertTrue(f.runtime.selectorSnapshot().isPresent());
		assertEquals(second, f.access.capturedRay); assertEquals(2, f.access.captures);
		f.prime(); f.focus("intent", 260); f.press(350); f.release(351); f.frame(440, false);
		f.focus("intent:attention", 460);
		f.frame(480, false); assertEquals(2, f.creates().size()); assertFalse(f.runtime.hasCompatibilityState());
	}
	@Test void spatialSettingsAreFrozenAtPressAndOpenWheelIgnoresElapsedTime() {
		var settings = ClientConfig.HANDLER.getConfig().getSpatialSelector(); int oldStroke = settings.getStroke();
		try {
			settings.setStroke(70); Fixture f = new Fixture(); f.press(0); settings.setStroke(85); f.frame(250, true);
			assertEquals(70, f.snapshot().settings().stroke());
			f.frame(10250, true);
			assertEquals(PingInteractionPhase.WHEEL_OPEN, f.runtime.phase());
			assertTrue(f.runtime.selectorSnapshot().isPresent());
			assertTrue(f.selects().isEmpty()); assertTrue(f.creates().isEmpty());
		} finally { settings.setStroke(oldStroke); }
	}
	@Test void invalidViewportCannotActuallyOpenAndReleaseStillUsesDefaultPath() {
		Fixture f = new Fixture(); f.access.validFrame = false; f.press(0); f.frame(500, true);
		assertEquals(PingInteractionPhase.PRESSED, f.runtime.phase()); assertTrue(f.runtime.selectorSnapshot().isEmpty());
		f.release(600); assertEquals(1, f.creates().size()); assertEquals("attention", f.creates().getFirst().pingType());
	}
	@ParameterizedTest @ValueSource(booleans = {false, true})
	void reentrantAbortInValidatorBlocksEveryReleasePacket(boolean menu) {
		Fixture f = new Fixture(); f.press(0);
		if (menu) { f.frame(250, true); f.prime(); f.enter("intent", 260); f.focus("intent:attention", 460); }
		f.validationHook = f.runtime::abort; f.release(menu ? 470 : 20);
		assertTrue(f.creates().isEmpty()); assertTrue(f.selects().isEmpty()); assertEquals(PingInteractionPhase.IDLE, f.runtime.phase());
		assertEquals(0, f.inventory.stats().previewChannels());
	}
}
