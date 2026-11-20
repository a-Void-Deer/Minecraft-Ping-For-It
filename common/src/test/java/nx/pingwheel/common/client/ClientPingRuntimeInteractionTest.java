package nx.pingwheel.common.client;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import nx.pingwheel.common.client.duration.ClientMarkerDisplayDuration;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.client.marker.ObservedServerClock;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.client.spatial.NativeSelectorInput;
import nx.pingwheel.common.client.spatial.GestureSkipHold;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.client.spatial.SelectorIntent;
import nx.pingwheel.common.client.spatial.PreciseCaptureRefresh;
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
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import nx.pingwheel.common.interaction.state.TargetGoneReason;
import nx.pingwheel.common.interaction.state.TargetValidation;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerRequestKind;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.network.*;
import nx.pingwheel.common.presentation.*;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.preview.ClientPresentationPreview;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;
import nx.pingwheel.common.presentation.preview.PreviewObservation;
import nx.pingwheel.common.render.SpatialOverlayRenderer;
import org.jetbrains.annotations.Unmodifiable;

import static nx.pingwheel.common.client.marker.ClientMarkerFixtures.localStore;
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
	private boolean oldGesture;
	private int oldGestureMillis, oldDeadzone;
	private int oldDangerWindow;
	private double oldDangerAngle;
	private boolean oldInventoryStay;

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@BeforeEach void settings() {
		var config = ClientConfig.HANDLER.getConfig();
		oldHold = config.getWheelHoldMillis();
		oldSlice = config.getLongPressCompatibilitySliceMillis(); oldCompatibility = config.isLongPressCompatibilityMode();
		oldGesture = config.getSpatialSelector().isGestureSkipHoldEnabled();
		oldGestureMillis = config.getSpatialSelector().getGestureSkipHoldMillis();
		oldDeadzone = config.getSpatialSelector().getDeadzone();
		oldDangerWindow = config.getDoubleClickDangerWindowMs();
		oldDangerAngle = config.getDoubleClickDangerAngleDegrees();
		oldInventoryStay = config.getSpatialSelector().isInventorySelectionStayOpenEnabled();
		config.setWheelHoldMillis(250);
		config.setLongPressCompatibilityMode(false); config.setLongPressCompatibilitySliceMillis(125);
		config.setGestureSkipHoldEnabled(false); config.getSpatialSelector().setGestureSkipHoldMillis(75);
		config.setDoubleClickDangerWindowMs(100); config.setDoubleClickDangerAngleDegrees(3.5);
		config.getSpatialSelector().setInventorySelectionStayOpenEnabled(false);
	}
	@AfterEach void restoreSettings() {
		var config = ClientConfig.HANDLER.getConfig();
		config.setWheelHoldMillis(oldHold);
		config.setLongPressCompatibilitySliceMillis(oldSlice); config.setLongPressCompatibilityMode(oldCompatibility);
		config.getSpatialSelector().setGestureSkipHoldEnabled(oldGesture);
		config.getSpatialSelector().setGestureSkipHoldMillis(oldGestureMillis);
		config.getSpatialSelector().setDeadzone(oldDeadzone);
		config.setDoubleClickDangerWindowMs(oldDangerWindow);
		config.setDoubleClickDangerAngleDegrees(oldDangerAngle);
		config.getSpatialSelector().setInventorySelectionStayOpenEnabled(oldInventoryStay);
	}

	private static final class Access implements ClientPingRuntime.InteractionAccess {
		Object world = new Object(), screen;
		boolean player = true, focus = true, overlay, grabbed = true, pending, validFrame = true, distantPending, preciseIncomplete, nativeOwned = true;
		String dimension = DIMENSION;
		int captures, cancellations, resets, disposals;
		boolean screenDisposal;
		CapturedRay ray = CapturedRay.defaultRay();
		TargetSnapshot snapshot = CHEST;
		List<TargetSnapshot> supplements = List.of();
		WorldVector supplementHit = new WorldVector(5, 5, 5);
		final List<Runnable> completions = new ArrayList<>();
		CapturedRay capturedRay, cancellationRay;
		InteractionToken capturedToken;
		final List<PreciseCaptureRefresh.Request> liveRequests = new ArrayList<>();
		final List<java.util.function.Consumer<PreciseCaptureRefresh.Outcome>> distantCompletions = new ArrayList<>();
		final List<SelectorIntent.CaptureToggle> toggles = new ArrayList<>();
		SpatialOverlayRenderer.Style selectorStyle;
		NativeSelectorInput.Frame frame = new NativeSelectorInput.Frame(1, 2000, 1400, 1000, 700, true, true);
		double mouseX = 1000, mouseY = 700;
		public Optional<GestureSkipHold.Position> mousePosition() { return Optional.of(new GestureSkipHold.Position(mouseX, mouseY)); }
		public Lifecycle lifecycle() { return new Lifecycle(world, dimension, screen, player, focus, overlay); }
		public Optional<CapturedRay> capturePressRay() { return Optional.of(ray); }
		public void capture(InteractionToken token, CapturedRay pressRay, ClientPingRuntime.CaptureCompletion completion) {
			captures++; capturedRay = pressRay; capturedToken = token;
			TargetSnapshot frozen = surface(snapshot, new WorldVector(1, 2, 3));
			var evidence = supplements.stream().map(value -> surface(value, supplementHit))
				.map(value -> new CandidateEvidence(value, FrozenCandidateAcquisition.distance(pressRay.origin(), value.candidateHit().orElseThrow().worldHit()))).toList();
			var acquisition = new FrozenCandidateAcquisition(token, pressRay, 100, new WorldVector(1, 2, 3), evidence,
				Set.of(PreciseTargetType.values()));
			Runnable apply = () -> completion.complete(frozen, Optional.of(acquisition));
			completions.add(apply);
			if (!pending) apply.run();
		}
		public PreciseCaptureRefresh.Scan capturePrecise(PreciseCaptureRefresh.Request request) {
			liveRequests.add(request);
			if (preciseIncomplete) return PreciseCaptureRefresh.Scan.incomplete();
			var inputs = request.inputs();
			TargetSnapshot reference = surface(snapshot, new WorldVector(1, 2, 3));
			var evidence = supplements.stream().map(value -> surface(value, supplementHit))
				.map(value -> new CandidateEvidence(value, FrozenCandidateAcquisition.distance(inputs.ray().origin(), value.candidateHit().orElseThrow().worldHit()))).toList();
			var acquisition = new FrozenCandidateAcquisition(inputs.token(), inputs.ray(), inputs.pingDistance(), new WorldVector(1, 2, 3), evidence,
				Set.of(PreciseTargetType.values()));
			var resolver = nx.pingwheel.common.resolve.DefaultTargetResolver.builtIn(nx.pingwheel.common.resolve.TargetResolutionLogger.noop());
			var allocated = acquisition.finish(reference, resolver.resolve(reference.target(), reference.matchContext()), resolver);
			var slots = new java.util.EnumMap<PreciseTargetType, PreciseCaptureRefresh.Outcome>(PreciseTargetType.class);
			for (var type : PreciseTargetType.values()) if (type != PreciseTargetType.LOCATION) slots.put(type,
				allocated.slot(type).candidateId().map(id -> PreciseCaptureRefresh.Outcome.available(allocated.candidate(id).orElseThrow()))
					.orElseGet(PreciseCaptureRefresh.Outcome::missing));
			var location = allocated.candidate(allocated.slot(PreciseTargetType.LOCATION).candidateId().orElseThrow()).orElseThrow();
			return new PreciseCaptureRefresh.Scan(slots, PreciseCaptureRefresh.Outcome.available(location), distantPending);
		}
		public boolean capturePreciseDistant(PreciseCaptureRefresh.Request request, java.util.function.Consumer<PreciseCaptureRefresh.Outcome> completion) {
			distantCompletions.add(completion); return true;
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
				frame.guiWidth(), frame.guiHeight(), focus, grabbed, nativeOwned)) : Optional.empty();
		}
		public SpatialSelectorSession.ListGeometry listGeometry(SpatialOverlayRenderer.Style style) {
			selectorStyle = style;
			return new SpatialSelectorSession.ListGeometry(5, 220, 18, 24, 20, 40);
		}
		public void resetInput() { resets++; }
		public void applyToggle(SelectorIntent.CaptureToggle toggle, long timeMillis) { toggles.add(toggle); }
	}
	private static TargetSnapshot surface(TargetSnapshot value, WorldVector hit) {
		return value.withCandidateHit(new CandidateHit(hit, equivalenceKey(value.target())));
	}
	private static CaptureEquivalenceKey equivalenceKey(nx.pingwheel.common.domain.Target target) {
		if (target instanceof nx.pingwheel.common.domain.Target.ExternalBlockTarget external)
			return new CaptureEquivalenceKey.ExternalKey(external.dimensionId(), external.providerId(),
				external.providerLocator(), external.expectedBlockRegistryId());
		return CaptureEquivalenceKey.nativeTarget(target);
	}
	private static boolean disabled(Fixture f, String choiceId) {
		return f.menu().choices().stream().filter(choice -> choice.id().equals(choiceId)).findFirst().orElseThrow().disabled();
	}
	private static final class ManualClock implements InteractionTimeSource {
		long value;
		boolean advanceOnRead;
		public long nowMillis() {
			return advanceOnRead ? ++value : value;
		}
	}

	private static final class Fixture {
		long now;
		boolean fail, gone;
		Runnable validationHook = () -> {};
		Runnable removeSendHook = () -> {};
		final ManualClock clock = new ManualClock();
		final Access access = new Access();
		final List<IPacket> sent = new ArrayList<>();
		final List<String> errors = new ArrayList<>();
		final List<ResolvedTarget> validations = new ArrayList<>();
		final ClientPresentation presentation = new ClientPresentation(this::send);
		final ClientInventory inventory = new ClientInventory(this::send, clock);
		final ClientPresentationPreview preview;
		final ClientPingRuntime runtime;
		final RecordingLogger pingLogger = new RecordingLogger();
		boolean previewObserve = true;
		double propertyValue = 7;
		Fixture() { this(new ClientRateLimitPolicy(20, 1000), true); }
		Fixture(ClientRateLimitPolicy policy, boolean ready) {
			// The interaction/Unping cases intentionally allow post-server-expiry visuals.
			this(policy, ready, localStore(ClientPingRuntime.FALLBACK_EXPIRY_GRACE_TICKS, 200L));
		}
		Fixture(ClientRateLimitPolicy policy, boolean ready, ClientMarkerStore markerStore) {
			PreviewFieldAccess reader = new PreviewFieldAccess() {
				public String adapterId() { return PresentationBasic.ID; }
				public Map<String, Outcome> observe(nx.pingwheel.common.domain.Target target, Set<String> fields, ReadContext context) {
					Map<String, Outcome> result = new java.util.LinkedHashMap<>();
					if (!previewObserve) return result;
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
			runtime = ClientPingRuntime.createForInteraction((key, color) -> errors.add(key), this::send, policy, clock,
				target -> { validations.add(target); validationHook.run(); return gone ? TargetValidation.gone(TargetGoneReason.BLOCK_REPLACED) : TargetValidation.valid(); },
				access, presentation, inventory, preview, markerStore, pingLogger);
			if (ready) negotiate(Set.of(PresentationBasic.NAME));
		}
		void send(IPacket packet) {
			if (fail && packet instanceof PresentationC2SPacket p && p.kind() == PresentationC2SPacket.Kind.CREATE) throw new IllegalStateException("transport");
			sent.add(packet);
			if (packet instanceof PresentationC2SPacket p && p.kind() == PresentationC2SPacket.Kind.REMOVE) removeSendHook.run();
		}
		void negotiate(Set<String> fields) {
			presentation.tick(true);
			runtime.onPresentationPacket(PresentationS2CPacket.offer(100, Map.of(PresentationBasic.ID, List.of(
				new PresentationField(PresentationBasic.NAME, PresentationField.Kind.TEXT, true, 0, "Name"),
				new PresentationField(PresentationBasic.HEALTH, PresentationField.Kind.NUMBER, true, 0, "Health"),
				new PresentationField(PresentationBasic.MAX_HEALTH, PresentationField.Kind.NUMBER, true, 0, "Maximum health")),
				nx.pingwheel.common.presentation.inventory.InventoryPresentation.ADAPTER_ID, nx.pingwheel.common.presentation.inventory.InventoryPresentation.INSTANCE.fields()),
				Map.of(PresentationBasic.ID, 1, nx.pingwheel.common.presentation.inventory.InventoryPresentation.ADAPTER_ID,
					nx.pingwheel.common.presentation.inventory.InventoryPresentation.INSTANCE.schema())));
			reset(1, fields);
			runtime.onInventoryPacket(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(5, 3, 5, 0)).stamp(100, 1));
			runtime.onInventoryPacket(InventoryS2CPacket.policy(7, 100, 1, Set.of("entity_block", "block", "entity")));
		}
		void reset(long view, Set<String> fields) {
			runtime.onPresentationPacket(PresentationS2CPacket.reset(100, view, Map.of(
				"entity_block", Map.of(PresentationBasic.ID, fields), "block", Map.of(PresentationBasic.ID, fields), "entity", Map.of(PresentationBasic.ID, fields))));
		}
		void press(long time) { now = time; clock.value = time; runtime.onPress(time); }
		void release(long time) { now = time; clock.value = time; runtime.onRelease(); }
		void frame(long time, boolean held) { now = time; clock.value = time; runtime.onRenderFrame(held); }
		void tick(long time) { now = time; clock.value = time; runtime.onTick(); }
		void serverTime(ObservedServerClock serverClock, long gameTime) {
			serverClock.observeSample(access.world, gameTime);
			runtime.onObservedServerTime(serverClock);
		}
		/** Recording the paint handoff only, not exercising GuiGraphics/GPU. */
		void paint() {
			var snapshot = snapshot(); runtime.acknowledgeSelectorPaint(snapshot, snapshot.radial().menus().getLast().choices().stream()
				.map(SpatialController.ChoiceView::id).collect(java.util.stream.Collectors.toSet()));
		}
		SpatialSelectorSession.Snapshot snapshot() { return runtime.selectorSnapshot().orElseThrow(); }
		SpatialController.MenuView menu() { return snapshot().radial().menus().getLast(); }
		double rawX = 1000, rawY = 700;
		void prime() { access.mouseX = rawX; access.mouseY = rawY; runtime.onMouseMove(1, rawX, rawY); }
		void move(double dx, double dy, long time) {
			now = time; clock.value = time; rawX += dx * access.frame.windowWidth() / access.frame.guiWidth();
			rawY += dy * access.frame.windowHeight() / access.frame.guiHeight();
			access.mouseX = rawX; access.mouseY = rawY; runtime.onMouseMove(1, rawX, rawY);
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
		void enterBack(String choiceId, long time) {
			int depth = snapshot().radial().menus().size(); focus(choiceId, time); frame(time + snapshot().settings().dwellMillis(), true);
			assertEquals(depth - 1, snapshot().radial().menus().size());
		}
		long request() { return sent.stream().filter(p -> p instanceof InventoryC2SPacket i && i.kind() == InventoryC2SPacket.Kind.OPEN)
			.map(p -> ((InventoryC2SPacket) p).requestId()).reduce((a, b) -> b).orElseThrow(); }
		void inventoryRows() {
			long request = request();
			runtime.onInventoryPacket(InventoryS2CPacket.opened(7, 100, 1, request, 3));
			runtime.onInventoryPacket(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, request, null, 9, 1, 1, 0, 1, true,
				InventoryS2CPacket.Status.READY, 0, List.of(new InventoryS2CPacket.Entry("opaque", "minecraft:stone", "Stone", null, 7, 1, false, null))).stamp(100, 1));
		}
		void openList() {
			press(0); inventoryRows(); frame(250, true); prime(); enter("content", 260);
			String list = menu().choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow().id();
			enter(list, 460); assertTrue(snapshot().inventory().open()); assertEquals("ITEM:opaque", snapshot().inventory().selectedKey());
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

	@Test void gestureWaitsUntilExactMinimumFrameWithoutAnotherMoveAndKeepsPressTarget() {
		ClientConfig.HANDLER.getConfig().setGestureSkipHoldEnabled(true);
		Fixture f = new Fixture();
		var pressed = TargetSnapshotFactory.entity(DIMENSION, new UUID(9, 10), "minecraft:zombie");
		f.access.snapshot = pressed; f.press(0); var ray = f.access.capturedRay;
		f.move(0, -30, 10); f.frame(74, true);
		assertEquals(PingInteractionPhase.PRESSED, f.runtime.phase()); assertTrue(f.access.grabbed);
		f.access.snapshot = STONE; f.access.ray = new CapturedRay(new WorldVector(10, 20, 30), new WorldVector(1, 0, 0));
		f.frame(75, true);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, f.runtime.phase()); assertFalse(f.access.grabbed);
		assertEquals(new SpatialController.Point(0, -30), f.snapshot().radial().pointer());
		assertEquals(2, f.snapshot().radial().trail().size());
		f.release(80);
		assertEquals(pressed.target(), f.creates().getFirst().target()); assertEquals(1, f.access.captures);
		assertEquals(ray, f.access.capturedRay);
	}

	@Test void gestureUsesStrictFrozenDeadzoneAndLateCrossingWhileOrdinaryHoldRemainsIndependent() {
		var config = ClientConfig.HANDLER.getConfig(); config.setGestureSkipHoldEnabled(true);
		config.getSpatialSelector().setDeadzone(24);
		Fixture f = new Fixture(); f.press(0);
		config.getSpatialSelector().setDeadzone(12); config.getSpatialSelector().setGestureSkipHoldMillis(200);
		f.move(24, 0, 10); f.frame(75, true);
		assertEquals(PingInteractionPhase.PRESSED, f.runtime.phase(), "deadzone equality is not a gesture");
		f.move(0.5, 0, 90); assertTrue(f.runtime.selectorSnapshot().isEmpty(), "moves cannot fabricate a present frame");
		f.frame(90, true); assertEquals(new SpatialController.Point(24.5, 0), f.snapshot().radial().pointer());
		assertEquals(24, f.snapshot().settings().deadzone()); assertEquals(75, f.snapshot().settings().gestureSkipHoldMillis());
		f.runtime.abort(); f.press(100); f.frame(349, true); assertTrue(f.runtime.selectorSnapshot().isEmpty());
		f.frame(350, true); assertTrue(f.runtime.selectorSnapshot().isPresent(), "ordinary threshold needs no gesture travel");
	}

	@Test void returningToDeadzoneAndFastShortFlickBothKeepTheDefaultTap() {
		ClientConfig.HANDLER.getConfig().setGestureSkipHoldEnabled(true);
		Fixture returned = new Fixture(); returned.press(0); returned.move(100, 0, 10); returned.move(-100, 0, 20); returned.frame(75, true);
		assertTrue(returned.runtime.selectorSnapshot().isEmpty()); returned.release(80); assertEquals(1, returned.creates().size());
		Fixture flick = new Fixture(); flick.press(0); flick.move(0, -100, 10); flick.release(20);
		flick.move(100, -100, 25); flick.frame(75, true);
		assertTrue(flick.runtime.selectorSnapshot().isEmpty()); assertEquals(1, flick.creates().size());
		flick.press(100); flick.frame(175, true);
		assertTrue(flick.runtime.selectorSnapshot().isEmpty(), "neither held nor post-release input leaks into the next press");
		flick.move(0, -30, 180); flick.frame(180, true);
		assertEquals(new SpatialController.Point(0, -30), flick.snapshot().radial().pointer());
		assertEquals(1, flick.creates().size());
	}

	@Test void fullPreOpenBufferReplaysExactly4096SamplesOnlyOnce() {
		ClientConfig.HANDLER.getConfig().setGestureSkipHoldEnabled(true);
		Fixture f = new Fixture(); f.press(0);
		for (int i = 0; i < 4095; i++) f.move(i % 2 == 0 ? 1 : -1, 0, 10);
		f.move(-1, -30, 10); f.frame(75, true);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, f.runtime.phase());
		assertEquals(new SpatialController.Point(0, -30), f.snapshot().radial().pointer());
		assertEquals(4097, f.snapshot().radial().trailSequence(), "one seed plus every admitted sample, without compression");
		f.frame(76, true); assertEquals(4097, f.snapshot().radial().trailSequence(), "the next frame cannot replay the handoff");
		assertTrue(f.creates().isEmpty());
	}

	@Test void overflowPreservesTheOriginalCaptureTokenAndHoldTimerButOrdinaryOpeningStartsAtCenter() {
		var config = ClientConfig.HANDLER.getConfig(); config.setGestureSkipHoldEnabled(true);
		Fixture f = new Fixture(); f.access.pending = true; f.press(100);
		var token = f.access.capturedToken; var ray = f.access.capturedRay;
		for (int i = 0; i < 4096; i++) f.move(i % 2 == 0 ? 1 : -1, 0, 110);
		f.move(0, -30, 110); f.frame(175, true);
		assertEquals(PingInteractionPhase.PRESSED, f.runtime.phase()); assertTrue(f.runtime.selectorSnapshot().isEmpty());
		assertTrue(f.creates().isEmpty()); assertTrue(f.errors.isEmpty()); assertEquals(1, f.access.captures);
		f.move(-100, 0, 180); f.access.completions.getFirst().run(); f.frame(349, true);
		assertTrue(f.runtime.selectorSnapshot().isEmpty(), "ready capture and later movement cannot revive the shortcut");
		assertTrue(f.creates().isEmpty()); assertTrue(f.access.grabbed);
		f.frame(350, true);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, f.runtime.phase(), "ordinary hold is still measured from the original press");
		assertEquals(token.sequence(), f.snapshot().contentFence().selectorSession());
		assertEquals(ray, f.access.capturedRay); assertEquals(1, f.access.captures);
		assertEquals(new SpatialController.Point(0, 0), f.snapshot().radial().pointer());
		assertEquals(List.of(new SpatialController.Point(0, 0)), f.snapshot().radial().trail());
		assertEquals(1, f.snapshot().radial().trailSequence(), "no recorded prefix or tail reaches the ordinary selector");
		assertTrue(config.isGestureSkipHoldEnabled(), "fallback is local to this hold, not a preference edit");
		f.move(1000, 1000, 360); assertEquals(new SpatialController.Point(0, 0), f.snapshot().radial().pointer());
		f.move(2, 3, 370); assertEquals(new SpatialController.Point(2, 3), f.snapshot().radial().pointer());
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void overflowReleaseStillCreatesExactlyOneDefaultAndTheNextPressCanUseTheGesture(boolean pending) {
		var config = ClientConfig.HANDLER.getConfig(); config.setGestureSkipHoldEnabled(true);
		Fixture f = new Fixture(); f.access.pending = pending; f.press(0);
		for (int i = 0; i < 4096; i++) f.move(i % 2 == 0 ? 1 : -1, 0, 10);
		f.move(0, -30, 10); f.frame(75, true);
		assertTrue(f.runtime.selectorSnapshot().isEmpty()); assertTrue(f.creates().isEmpty());
		f.release(80); f.move(-100, -100, 90); f.frame(100, true);
		assertTrue(f.runtime.selectorSnapshot().isEmpty()); assertEquals(pending ? 0 : 1, f.creates().size());
		if (pending) { f.access.completions.getFirst().run(); f.frame(110, false); }
		f.release(120); f.frame(130, false);
		assertEquals(1, f.creates().size()); assertEquals("attention", f.creates().getFirst().pingType());
		assertEquals(CHEST.target(), f.creates().getFirst().target()); assertEquals(1, f.access.captures);
		assertTrue(config.isGestureSkipHoldEnabled());
		f.access.pending = false; f.press(200); f.move(0, -30, 210); f.frame(275, true);
		assertEquals(new SpatialController.Point(0, -30), f.snapshot().radial().pointer());
		assertEquals(2, f.access.captures); assertEquals(1, f.creates().size());
	}

	@Test void disabledAndCompatibilityModesNeverAdmitTheGestureEvenForVirtualHolds() {
		Fixture disabled = new Fixture(); disabled.press(0); disabled.move(100, 0, 10); disabled.frame(100, true);
		assertTrue(disabled.runtime.selectorSnapshot().isEmpty()); disabled.runtime.abort();
		var config = ClientConfig.HANDLER.getConfig(); config.setGestureSkipHoldEnabled(true); config.setLongPressCompatibilityMode(true);
		// Even an unvalidated nested write cannot activate a compatibility baseline.
		config.getSpatialSelector().setGestureSkipHoldEnabled(true);
		Fixture compatible = new Fixture(); compatible.press(0); compatible.move(100, 0, 10); compatible.frame(100, true);
		assertTrue(compatible.runtime.selectorSnapshot().isEmpty()); compatible.release(110);
		compatible.press(120); compatible.release(121); compatible.move(100, 0, 130); compatible.frame(200, false);
		assertTrue(compatible.runtime.selectorSnapshot().isEmpty()); assertEquals(1, compatible.creates().size());
		compatible.runtime.abort();
	}

	@Test void captureAndValidHeldViewportStillGateGestureOpeningAndReleaseAlwaysWins() {
		ClientConfig.HANDLER.getConfig().setGestureSkipHoldEnabled(true);
		Fixture f = new Fixture(); f.access.pending = true; f.press(0); f.move(0, -30, 10); f.frame(75, true);
		assertTrue(f.runtime.selectorSnapshot().isEmpty()); f.access.completions.getFirst().run();
		f.access.validFrame = false; f.frame(80, true); assertTrue(f.runtime.selectorSnapshot().isEmpty());
		f.access.validFrame = true; f.frame(90, true); assertEquals(new SpatialController.Point(0, -30), f.snapshot().radial().pointer());
		Fixture released = new Fixture(); released.access.pending = true; released.press(0); released.move(0, -30, 10);
		released.frame(75, true); released.release(80); released.access.completions.getFirst().run(); released.frame(90, true);
		assertTrue(released.runtime.selectorSnapshot().isEmpty()); released.frame(100, false);
		assertEquals(1, released.creates().size());
	}

	@Test void preOpenTrajectoryPaysStrokeAndTurnInOrderOnceAndNativeHandoffAddsNoJump() {
		ClientConfig.HANDLER.getConfig().setGestureSkipHoldEnabled(true);
		Fixture f = new Fixture(); f.press(0); f.move(-100, 0, 10); f.move(0, -20, 60); f.frame(75, true);
		assertEquals(2, f.snapshot().radial().menus().size(), "a pre-open right-angle turn enters Intent, not just its final bearing");
		assertEquals("intent", f.snapshot().radial().menus().getLast().entryChoiceId());
		assertEquals(new SpatialController.Point(-100, 0), f.menu().origin());
		var before = f.snapshot().radial().pointer(); var trail = f.snapshot().radial().trail();
		f.frame(76, true); assertEquals(before, f.snapshot().radial().pointer()); assertEquals(trail, f.snapshot().radial().trail());
		f.move(1000, 1000, 80); assertEquals(before, f.snapshot().radial().pointer(), "first owned callback primes after the native reset");
		f.move(2, 3, 90); assertEquals(new SpatialController.Point(before.x() + 2, before.y() + 3), f.snapshot().radial().pointer());
		f.access.nativeOwned = false; f.move(1000, 1000, 100); before = f.snapshot().radial().pointer();
		f.access.nativeOwned = true; f.move(1000, 1000, 110); assertEquals(before, f.snapshot().radial().pointer());
		f.move(1, 2, 120); assertEquals(new SpatialController.Point(before.x() + 1, before.y() + 2), f.snapshot().radial().pointer());
	}

	@Test void preOpenStrokeStillPaysDwellAfterOpeningInsteadOfOnlySeedingTheFinalPointer() {
		ClientConfig.HANDLER.getConfig().setGestureSkipHoldEnabled(true);
		Fixture f = new Fixture(); f.press(0); f.move(-100, 0, 10); f.frame(75, true);
		assertEquals(1, f.snapshot().radial().menus().size());
		assertEquals(List.of(new SpatialController.Point(0, 0), new SpatialController.Point(-100, 0)), f.snapshot().radial().trail());
		f.frame(189, true); assertEquals(1, f.snapshot().radial().menus().size());
		f.frame(190, true); assertEquals(2, f.snapshot().radial().menus().size());
		assertEquals("intent", f.menu().entryChoiceId());
	}

	@Test void frameObservedReleaseAndMidHoldCompatibilityEnableDiscardThePendingGesture() {
		var config = ClientConfig.HANDLER.getConfig(); config.setGestureSkipHoldEnabled(true);
		Fixture released = new Fixture(); released.access.pending = true; released.press(0); released.move(0, -100, 10);
		released.frame(50, false); released.access.completions.getFirst().run(); released.frame(75, true);
		assertTrue(released.runtime.selectorSnapshot().isEmpty()); released.frame(80, false); assertEquals(1, released.creates().size());
		Fixture compatible = new Fixture(); compatible.press(0); compatible.move(0, -100, 10);
		config.setLongPressCompatibilityMode(true); compatible.frame(75, true);
		assertTrue(compatible.runtime.selectorSnapshot().isEmpty()); compatible.frame(250, true);
		assertEquals(new SpatialController.Point(0, 0), compatible.snapshot().radial().pointer(), "compatibility discarded the pre-open path");
		compatible.runtime.abort();
	}

	@ParameterizedTest @ValueSource(strings = {"reset", "screen", "focus", "level", "dimension"})
	void abortedGestureAndOldCaptureCannotLeakIntoANewHold(String reason) {
		ClientConfig.HANDLER.getConfig().setGestureSkipHoldEnabled(true);
		Fixture f = new Fixture(); f.access.pending = true; f.press(0); f.move(0, -100, 10);
		var late = f.access.completions.getFirst();
		switch (reason) {
			case "reset" -> f.runtime.abort();
			case "screen" -> { f.access.screen = new Object(); f.frame(20, true); f.access.screen = null; f.frame(21, false); }
			case "focus" -> { f.access.focus = false; f.frame(20, true); f.access.focus = true; }
			case "level" -> { f.access.world = new Object(); f.frame(20, true); }
			case "dimension" -> { f.access.dimension = "minecraft:the_nether"; f.frame(20, true); }
			default -> throw new AssertionError();
		}
		f.access.pending = false; f.press(100); late.run(); f.frame(175, true);
		assertTrue(f.runtime.selectorSnapshot().isEmpty()); assertTrue(f.creates().isEmpty());
		f.move(0, -30, 180); f.frame(180, true);
		assertEquals(new SpatialController.Point(0, -30), f.snapshot().radial().pointer());
	}

	@Test void clientLevelChangeEntryUpdatesTheObservedWorldBeforeTheNextFrame() {
		Fixture f = new Fixture();
		f.tick(0);
		f.access.world = new Object();
		int beforeObservation = f.access.resets;
		f.runtime.onClientLevelChanged();
		assertTrue(f.access.resets > beforeObservation, "the replacement aborts through the existing lifecycle observation");
		int afterObservation = f.access.resets;
		f.tick(10);
		assertEquals(afterObservation, f.access.resets, "the same replacement is not re-observed");
		f.access.world = new Object();
		f.tick(20);
		assertTrue(f.access.resets > afterObservation, "ordinary tick observation still detects the next replacement");
	}

	@Test void resizeWithoutCallbackRecomputesFocusBeforeImmediateRelease() {
		Fixture f = new Fixture();
		f.access.snapshot = TargetSnapshotFactory.entity(DIMENSION, new UUID(9, 10), "minecraft:zombie");
		f.press(0); f.frame(250, true); f.prime(); f.move(200, -200, 260);
		assertEquals("content", f.snapshot().radial().focusId());
		f.access.frame = new NativeSelectorInput.Frame(1, 2000, 1400, 100, 700, true, false);
		// Do not read a snapshot, deliver a move, or paint after the resize.
		f.release(270);
		assertEquals(1, f.creates().size());
		assertEquals("danger", f.creates().getFirst().pingType(), "the confined x=49,y=-200 focuses Up, not the old diagonal");
		assertEquals(f.access.snapshot.target(), f.creates().getFirst().target());
	}

	@Test void nativeRawBaselineContinuesAcrossSaturationAndReversalIsImmediate() {
		Fixture f = new Fixture(); f.press(0); f.frame(250, true); f.prime();
		f.move(10000, -10000, 260);
		assertEquals(new SpatialController.Point(499, -350), f.snapshot().radial().pointer());
		var trail = f.snapshot().radial().trail();
		f.move(10000, -10000, 270);
		assertEquals(trail, f.snapshot().radial().trail());
		f.move(-1, 2, 280);
		assertEquals(new SpatialController.Point(498, -348), f.snapshot().radial().pointer());
		assertEquals(trail.size() + 1, f.snapshot().radial().trail().size());
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
	@ParameterizedTest @ValueSource(booleans = {false, true})
	void cancellationBeforeResetIsDroppedAndNeverReplayedWhenReady(boolean offered) {
		Fixture f = new Fixture(new ClientRateLimitPolicy(1, 1000), false);
		if (offered) {
			f.presentation.tick(true);
			f.runtime.onPresentationPacket(PresentationS2CPacket.offer(100,
				Map.of(PresentationBasic.ID, PresentationBasic.fields()), Map.of(PresentationBasic.ID, 1)));
		}
		f.press(0); f.frame(250, true); f.prime(); f.focus("cancel-marker", 260); f.release(270);
		assertEquals(1, f.access.cancellations, "the frozen cancellation action reaches the runtime dispatch boundary");
		assertEquals(0, f.sent.stream().filter(p -> p instanceof PresentationC2SPacket c && c.kind() == PresentationC2SPacket.Kind.REMOVE).count());
		f.negotiate(Set.of(PresentationBasic.NAME));
		assertTrue(f.presentation.ready());
		assertEquals(0, f.sent.stream().filter(p -> p instanceof PresentationC2SPacket c && c.kind() == PresentationC2SPacket.Kind.REMOVE).count(),
			"RESET must not replay a discarded cancellation");
		f.press(300); f.frame(550, true); f.prime(); f.focus("cancel-marker", 560); f.release(570);
		assertEquals(1, f.sent.stream().filter(p -> p instanceof PresentationC2SPacket c && c.kind() == PresentationC2SPacket.Kind.REMOVE).count());
		f.press(600); f.release(610);
		assertEquals(1, f.creates().size(), "neither an unready nor a ready cancellation consumes the sole create allowance");
	}

	@Test
	void expiredOwnUnpingClearsOnlyTheSelectedMarkerAndKeepsItsTombstone() {
		Fixture f = new Fixture();
		MarkerId own = new MarkerId(42);
		MarkerId other = new MarkerId(43);
		TargetKey key = TargetKey.from(CHEST.target());
		TargetKey otherKey = TargetKey.from(STONE.target());
		PresentationS2CPacket ownCreate = markerCreated(own, OWNER, CHEST, "entity_block");
		f.runtime.onPresentationPacket(ownCreate);
		f.runtime.onPresentationPacket(markerCreated(other, new UUID(3, 4), STONE, "block"));
		f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 1, key, Optional.of(own)));
		f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 1, otherKey, Optional.of(other)));
		f.runtime.onPresentationPacket(expired(own));
		f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 1, key, Optional.empty()));
		assertEquals(own, f.runtime.store().outlineWinnersInDimension(DIMENSION).get(key).id());
		assertTrue(f.runtime.nameStore().find(own).isPresent());

		unping(f);
		assertTrue(removals(f).isEmpty());
		assertTrue(f.creates().isEmpty());
		assertTrue(f.runtime.store().marker(own).isEmpty());
		assertTrue(f.runtime.store().isAuthoritativelyRemoved(own));
		assertTrue(f.runtime.nameStore().find(own).isEmpty());
		assertTrue(f.presentation.markerGrant(own).isEmpty());
		assertEquals(Set.of(otherKey), f.runtime.store().outlineWinnersInDimension(DIMENSION).keySet());
		assertTrue(f.runtime.nameStore().find(other).isPresent());
		f.runtime.onPresentationPacket(ownCreate);
		assertTrue(f.runtime.store().marker(own).isEmpty(), "delayed creation must not undo local dismissal");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void oneActiveUnpingHandlesEitherOrderOfExpiryAndNotFound(boolean rejectionFirst) {
		Fixture f = new Fixture();
		MarkerId id = new MarkerId(42);
		f.runtime.onPresentationPacket(markerCreated(id, OWNER, CHEST, "entity_block"));
		PresentationS2CPacket notFound = PresentationS2CPacket.rejected(100, 1, id.value(), MarkerRequestKind.REMOVE, MarkerRejectReason.NOT_FOUND);
		// Deliver during transport to verify that correlation is registered before
		// the sender runs, not after it returns.
		f.removeSendHook = () -> {
			if (rejectionFirst) {
				f.runtime.onPresentationPacket(notFound);
				assertTrue(f.runtime.store().marker(id).orElseThrow().isSynchronized(), "NOT_FOUND alone is not an expiry receipt");
				f.runtime.onPresentationPacket(expired(id));
			} else {
				f.runtime.onPresentationPacket(expired(id));
				f.runtime.onPresentationPacket(notFound);
			}
		};
		unping(f);
		assertEquals(List.of(id), removals(f));
		assertTrue(f.runtime.store().marker(id).isEmpty());
		assertTrue(f.presentation.markerGrant(id).isEmpty());
		assertTrue(f.errors.isEmpty());
	}

	@Test
	void fallbackStaleOwnUnpingSendsOneRemoveWithoutDismissingTheVisual() {
		Fixture f = new Fixture(ClientRateLimitPolicy.DEFAULT, true, localStore(0L, 100L));
		MarkerId id = new MarkerId(42);
		f.runtime.onPresentationPacket(markerCreated(100, 1, 1L, id, OWNER, CHEST, "entity_block", 1L));
		f.tick(0L);
		var stale = f.runtime.store().marker(id).orElseThrow();
		assertTrue(stale.isStale());
		assertFalse(f.runtime.store().isAuthoritativelyRemoved(id));

		unping(f);
		assertEquals(List.of(id), removals(f));
		assertEquals(List.of(stale), f.runtime.store().renderMarkers(), "the local visual waits for authoritative removal");

		f.runtime.onPresentationPacket(PresentationS2CPacket.removed(100, 1, id, MarkerRemovalReason.CANCELLED));
		assertTrue(f.runtime.store().marker(id).isEmpty());
		assertEquals(List.of(id), removals(f));
	}

	@Test
	void removeDenialOrDifferentStoredOwnerDoesNotDismissAnExpiredRecord() {
		MarkerId id = new MarkerId(42);
		Fixture denied = new Fixture();
		denied.runtime.onPresentationPacket(markerCreated(id, OWNER, CHEST, "entity_block"));
		unping(denied);
		denied.runtime.onPresentationPacket(PresentationS2CPacket.rejected(100, 1, id.value(), MarkerRequestKind.REMOVE, MarkerRejectReason.NOT_OWNER));
		denied.runtime.onPresentationPacket(expired(id));
		assertEquals(List.of(id), removals(denied));
		assertTrue(denied.runtime.store().marker(id).orElseThrow().isStale());

		Fixture mismatch = new Fixture();
		mismatch.runtime.onPresentationPacket(markerCreated(id, new UUID(3, 4), CHEST, "entity_block"));
		mismatch.runtime.onPresentationPacket(expired(id));
		// The fake candidate port still names 42 as own; the applied record must
		// independently agree before any destructive local action.
		unping(mismatch);
		assertTrue(removals(mismatch).isEmpty());
		assertTrue(mismatch.runtime.store().marker(id).orElseThrow().isStale());
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void acceptedPolicyResetRetiresAssignmentsButKeepsLastWinnerAcrossEitherExpiryOrder(boolean expiryBeforeReset) {
		Fixture f = new Fixture();
		MarkerId id = new MarkerId(42);
		MarkerId replacement = new MarkerId(43);
		TargetKey key = TargetKey.from(CHEST.target());
		f.runtime.onPresentationPacket(markerCreated(id, OWNER, CHEST, "entity_block"));
		f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 1, key, Optional.of(id)));
		if (expiryBeforeReset) {
			f.runtime.onPresentationPacket(expired(id));
			f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 1, key, Optional.empty()));
		}
		assertEquals(id, f.runtime.store().outlineWinnersInDimension(DIMENSION).get(key).id());

		f.reset(2, Set.of(PresentationBasic.NAME));
		// Old-view assignment and hard removal can neither replace the history nor mutate the record.
		f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 1, key, Optional.of(replacement)));
		f.runtime.onPresentationPacket(PresentationS2CPacket.removed(100, 1, id, MarkerRemovalReason.CANCELLED));
		if (!expiryBeforeReset) {
			assertTrue(f.runtime.store().outlineWinnersInDimension(DIMENSION).isEmpty(),
				"an old current assignment is retired even without an empty winner packet");
			assertTrue(f.runtime.store().marker(id).orElseThrow().isSynchronized());
			f.runtime.onPresentationPacket(PresentationS2CPacket.removed(100, 2, id, MarkerRemovalReason.EXPIRED));
			assertEquals(id, f.runtime.store().outlineWinnersInDimension(DIMENSION).get(key).id());
			f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 2, key, Optional.empty()));
		}
		assertEquals(id, f.runtime.store().outlineWinnersInDimension(DIMENSION).get(key).id());
		assertEquals(id, f.runtime.store().hudRenderMarkers().getFirst().id());
		assertTrue(f.runtime.nameStore().find(id).isPresent());

		f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 2, key, Optional.of(replacement)));
		assertTrue(f.runtime.store().outlineWinnersInDimension(DIMENSION).isEmpty(), "a new assignment suppresses the old tail while its snapshot is pending");
		f.runtime.onPresentationPacket(markerCreated(100, 2, replacement, OWNER, CHEST, "entity_block"));
		assertEquals(replacement, f.runtime.store().outlineWinnersInDimension(DIMENSION).get(key).id());
	}

	@Test
	void policyResetDropsAnOldAssignmentWithoutAMatchingLocalRecord() {
		Fixture f = new Fixture();
		MarkerId pending = new MarkerId(42);
		TargetKey key = TargetKey.from(CHEST.target());
		f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 1, key, Optional.of(pending)));
		f.reset(2, Set.of(PresentationBasic.NAME));
		f.runtime.onPresentationPacket(markerCreated(100, 2, pending, OWNER, CHEST, "entity_block"));
		f.runtime.onPresentationPacket(PresentationS2CPacket.removed(100, 2, pending, MarkerRemovalReason.EXPIRED));
		assertTrue(f.runtime.store().marker(pending).orElseThrow().isStale());
		assertTrue(f.runtime.store().outlineWinnersInDimension(DIMENSION).isEmpty());
	}

	@Test
	void sessionCloseAndNewEpochDiscardOutlineHistoryAndRemovalEvidence() {
		Fixture f = new Fixture();
		MarkerId id = new MarkerId(42);
		TargetKey key = TargetKey.from(CHEST.target());
		f.runtime.onPresentationPacket(markerCreated(id, OWNER, CHEST, "entity_block"));
		f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 1, key, Optional.of(id)));
		f.runtime.onPresentationPacket(expired(id));
		f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 1, key, Optional.empty()));
		assertEquals(id, f.runtime.store().outlineWinnersInDimension(DIMENSION).get(key).id());

		f.runtime.close();
		assertTrue(f.runtime.store().marker(id).isEmpty());
		assertFalse(f.runtime.store().isAuthoritativelyRemoved(id));
		assertTrue(f.runtime.store().outlineWinnersInDimension(DIMENSION).isEmpty());
		f.presentation.tick(true);
		f.runtime.onPresentationPacket(PresentationS2CPacket.offer(101,
			Map.of(PresentationBasic.ID, PresentationBasic.fields()), Map.of(PresentationBasic.ID, 1)));
		f.runtime.onPresentationPacket(PresentationS2CPacket.reset(101, 1, Map.of()));
		assertTrue(f.presentation.ready());
		f.runtime.onPresentationPacket(markerCreated(101, 1, id, OWNER, CHEST, "entity_block"));
		assertTrue(f.runtime.store().marker(id).orElseThrow().isSynchronized());
		f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 1, key, Optional.of(id)));
		f.runtime.onPresentationPacket(PresentationS2CPacket.removed(101, 1, id, MarkerRemovalReason.EXPIRED));
		assertTrue(f.runtime.store().marker(id).orElseThrow().isStale());
		assertTrue(f.runtime.store().outlineWinnersInDimension(DIMENSION).isEmpty(), "old session evidence cannot attach to a reused ID");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void followServerExpiryDeletesAndCleansRegardlessOfBoundarySampleOrWinnerOrder(boolean sampleFirst) {
		var clock = new ObservedServerClock();
		var f = new Fixture(ClientRateLimitPolicy.DEFAULT, true,
			new ClientMarkerStore(5L, clock, ignored -> ClientMarkerDisplayDuration.fromConfig(0)));
		var id = new MarkerId(42);
		var key = TargetKey.from(CHEST.target());
		f.serverTime(clock, 100L);
		f.runtime.onPresentationPacket(markerCreated(100, 1, 1, id, OWNER, CHEST, "entity_block", 20L));
		f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 1, key, Optional.of(id)));
		f.serverTime(clock, 150L);
		assertEquals(id, f.runtime.store().outlineWinnersInDimension(DIMENSION).get(key).id(),
			"the interval containing receipt is not charged even when longer than the duration");
		assertTrue(f.runtime.nameStore().find(id).isPresent());
		assertTrue(f.presentation.markerGrant(id).isPresent());
		var emptyWinner = PresentationS2CPacket.winner(100, 1, key, Optional.empty());
		if (sampleFirst) {
			f.runtime.onPresentationPacket(emptyWinner);
			f.serverTime(clock, 170L);
			assertTrue(f.runtime.store().marker(id).orElseThrow().isSynchronized());
		}
		f.runtime.onPresentationPacket(expired(id));
		assertTrue(f.runtime.store().marker(id).isEmpty());
		assertTrue(f.runtime.store().isAuthoritativelyRemoved(id));
		assertTrue(f.runtime.store().hudRenderMarkers().isEmpty());
		assertTrue(f.runtime.store().outlineWinnersInDimension(DIMENSION).isEmpty());
		assertTrue(f.runtime.nameStore().find(id).isEmpty());
		assertTrue(f.presentation.markerGrant(id).isEmpty());
		if (!sampleFirst) {
			f.serverTime(clock, 170L);
			f.runtime.onPresentationPacket(emptyWinner);
		}
		f.runtime.onPresentationPacket(markerCreated(100, 1, 2, id, OWNER, CHEST, "entity_block", 20L));
		assertTrue(f.runtime.store().marker(id).isEmpty(), "a delayed same-ID upsert cannot undo the removal");
	}

	@Test
	void followServerUsesObservedProgressAndLatestUpsertForLossRecoveryWhileCustomStaysLocal() {
		var seconds = new AtomicInteger(0);
		var clock = new ObservedServerClock();
		var f = new Fixture(ClientRateLimitPolicy.DEFAULT, true,
			new ClientMarkerStore(3L, clock, ignored -> ClientMarkerDisplayDuration.fromConfig(seconds.get())));
		var follow = new MarkerId(42);
		var custom = new MarkerId(43);
		var key = TargetKey.from(CHEST.target());
		f.serverTime(clock, 10L);
		f.runtime.onPresentationPacket(markerCreated(100, 1, 1, follow, OWNER, CHEST, "entity_block", 20L));
		f.runtime.onPresentationPacket(PresentationS2CPacket.winner(100, 1, key, Optional.of(follow)));
		seconds.set(1);
		f.runtime.onPresentationPacket(markerCreated(100, 1, 1, custom, OWNER, STONE, "block", 20L));
		seconds.set(0);
		f.runtime.onPresentationPacket(markerCreated(100, 1, 2, custom, OWNER, STONE, "block", 20L));
		assertFalse(f.runtime.store().marker(custom).orElseThrow().followsServer());
		f.serverTime(clock, 1000L);
		for (int tick = 0; tick < 200; tick++) f.tick(3_600_000L + tick * 50L);
		assertTrue(f.runtime.store().marker(custom).isEmpty());
		assertEquals(follow, f.runtime.store().hudRenderMarkers().getFirst().id(),
			"an extended local pause and client ticks cannot spend server time");

		f.serverTime(clock, 1001L);
		seconds.set(1);
		f.runtime.onPresentationPacket(markerCreated(100, 1, 2, follow, OWNER, CHEST, "entity_block", 20L));
		assertTrue(f.runtime.store().marker(follow).orElseThrow().followsServer());
		f.serverTime(clock, 1018L);
		f.serverTime(clock, 1019L);
		assertEquals(follow, f.runtime.store().outlineWinnersInDimension(DIMENSION).get(key).id());
		f.serverTime(clock, 1020L);
		assertTrue(f.runtime.store().hudRenderMarkers().isEmpty(), "same-ID upsert did not refresh the visual span");
		assertTrue(f.runtime.store().outlineWinnersInDimension(DIMENSION).isEmpty());

		f.serverTime(clock, 1037L);
		for (int tick = 0; tick < 20; tick++) f.tick(3_610_000L + tick * 50L);
		assertTrue(f.runtime.store().marker(follow).orElseThrow().isSynchronized(),
			"the latest fallback origin still lacks one observed tick");
		assertTrue(f.runtime.nameStore().find(follow).isPresent());

		f.serverTime(clock, 1038L);
		f.tick(3_611_000L);
		f.tick(3_611_050L);
		assertTrue(f.runtime.store().marker(follow).isPresent(), "local grace allows late authority even once visually hidden");
		f.tick(3_611_100L);
		assertTrue(f.runtime.store().marker(follow).isEmpty());
		assertTrue(f.runtime.nameStore().find(follow).isEmpty());
		assertTrue(f.presentation.markerGrant(follow).isEmpty());
		f.runtime.onPresentationPacket(markerCreated(100, 1, 3, follow, OWNER, CHEST, "entity_block", 20L));
		assertTrue(f.runtime.store().marker(follow).isEmpty(), "the existing presentation eviction tombstone rejects old receipts");
	}

	private static void unping(Fixture fixture) {
		fixture.press(0); fixture.frame(250, true); fixture.prime(); fixture.focus("cancel-marker", 260); fixture.release(270);
	}

	private static @Unmodifiable List<MarkerId> removals(Fixture fixture) {
		return fixture.sent.stream().filter(PresentationC2SPacket.class::isInstance).map(PresentationC2SPacket.class::cast)
			.filter(packet -> packet.kind() == PresentationC2SPacket.Kind.REMOVE).map(PresentationC2SPacket::markerId).toList();
	}

	private static PresentationS2CPacket markerCreated(MarkerId id, UUID owner, TargetSnapshot target, String targetType) {
		return markerCreated(100, 1, id, owner, target, targetType);
	}

	private static PresentationS2CPacket markerCreated(long epoch, long view, MarkerId id, UUID owner, TargetSnapshot target, String targetType) {
		return markerCreated(epoch, view, 1L, id, owner, target, targetType, 99L);
	}

	private static PresentationS2CPacket markerCreated(long epoch, long view, long revision, MarkerId id, UUID owner,
		TargetSnapshot target, String targetType, long duration) {
		MarkerSnapshot snapshot = new MarkerSnapshot(id, owner, target.target(), targetType, "attention", new MarkerAnchor(0, 0, 2),
			1L, 1L + duration);
		PresentationSection basic = new PresentationSection(PresentationBasic.ID, 1,
			Map.of(PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"Target\"}")), false);
		return PresentationS2CPacket.created(epoch, view, revision, snapshot, "Owner",
			PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME),
			TestContentGrants.content(TestContentGrants.basic(basic)), PresentationReceiptContent.whole(), basic);
	}

	private static PresentationS2CPacket expired(MarkerId id) {
		return PresentationS2CPacket.removed(100, 1, id, MarkerRemovalReason.EXPIRED);
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
	@Test void enabledInventoryStayKeepsFirstReleaseThenOneTapSelectsBeforeClosingOnce() {
		ClientConfig.HANDLER.getConfig().getSpatialSelector().setInventorySelectionStayOpenEnabled(true);
		Fixture f = new Fixture(); f.openList(); long request = f.request();
		f.release(700);

		assertTrue(f.selects().isEmpty());
		assertTrue(f.runtime.selectorSnapshot().isPresent());
		assertEquals(PingInteractionPhase.WHEEL_OPEN, f.runtime.phase());
		assertFalse(f.access.grabbed, "staying keeps native selector mouse ownership");
		assertEquals(1, f.inventory.stats().previewChannels());
		assertTrue(f.runtime.onMouseScroll(1, 0, 1), "scroll ownership remains while staying");
		int captures = f.access.captures;

		f.press(800); f.release(810);
		assertEquals(captures, f.access.captures, "a staying tap never recaptures or reopens");
		assertEquals(1, f.selects().size()); assertEquals("opaque", f.selects().getFirst().entryKey());
		assertEquals(request, f.selects().getFirst().requestId());
		assertTrue(f.runtime.selectorSnapshot().isEmpty());
		assertEquals(PingInteractionPhase.IDLE, f.runtime.phase());
		assertEquals(0, f.inventory.stats().previewChannels());
		assertTrue(f.access.grabbed, "normal close restores the mouse ownership it released");
		assertEquals(1, f.sent.stream().filter(p -> p instanceof InventoryC2SPacket c
			&& c.kind() == InventoryC2SPacket.Kind.CLOSE && c.requestId() == request).count(),
			"SELECT precedes exactly one CLOSE");
		int selected = f.sent.indexOf(f.selects().getFirst());
		int closed = java.util.stream.IntStream.range(0, f.sent.size()).filter(i -> f.sent.get(i) instanceof InventoryC2SPacket c
			&& c.kind() == InventoryC2SPacket.Kind.CLOSE && c.requestId() == request).findFirst().orElseThrow();
		assertTrue(selected < closed);
	}

	@Test void stayingHoldReachingTheFrozenThresholdCancelsWithoutSelectOrCancelMarker() {
		ClientConfig.HANDLER.getConfig().getSpatialSelector().setInventorySelectionStayOpenEnabled(true);
		Fixture f = new Fixture(); f.openList(); long request = f.request();
		f.release(700);

		f.press(1000); f.release(1250);
		assertTrue(f.selects().isEmpty()); assertTrue(f.creates().isEmpty());
		assertEquals(0, f.access.cancellations);
		assertTrue(f.runtime.selectorSnapshot().isEmpty());
		assertEquals(PingInteractionPhase.IDLE, f.runtime.phase());
		assertEquals(0, f.inventory.stats().previewChannels());
		assertEquals(1, f.sent.stream().filter(p -> p instanceof InventoryC2SPacket c
			&& c.kind() == InventoryC2SPacket.Kind.CLOSE && c.requestId() == request).count());
	}

	@Test void frameCancelSwallowsTheRemainingPhysicalReleaseWithoutASecondSubmit() {
		ClientConfig.HANDLER.getConfig().getSpatialSelector().setInventorySelectionStayOpenEnabled(true);
		Fixture f = new Fixture(); f.openList();
		f.release(700);

		f.press(1000); f.frame(1250, true);
		assertTrue(f.selects().isEmpty()); assertTrue(f.runtime.selectorSnapshot().isEmpty());
		assertEquals(PingInteractionPhase.IDLE, f.runtime.phase());
		f.release(1260);
		assertTrue(f.selects().isEmpty()); assertTrue(f.creates().isEmpty());
		assertEquals(PingInteractionPhase.IDLE, f.runtime.phase());
	}

	@Test void stayingItemBackReturnsToListAndStaysUntilThatListTapCommits() {
		var settings = ClientConfig.HANDLER.getConfig().getSpatialSelector();
		boolean oldContentIntent = settings.isContentIntentEnabled();
		settings.setContentIntentEnabled(true);
		settings.setInventorySelectionStayOpenEnabled(true);
		try {
			Fixture f = new Fixture(); f.openList();
			double forward = f.snapshot().inventory().direction().forward() == nx.pingwheel.common.client.spatial.InventoryListModel.Side.RIGHT ? 1 : -1;
			f.move(forward * 300, 0, 700);
			assertTrue(f.snapshot().radial().menus().getLast().menuId().contains(":item:"));
			f.release(710);
			assertTrue(f.selects().isEmpty()); assertTrue(f.runtime.selectorSnapshot().isPresent());

			String back = f.menu().choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow().id();
			f.enterBack(back, 800);
			assertTrue(f.snapshot().radial().menus().getLast().menuId().endsWith(":inventory"));
			assertTrue(f.runtime.selectorSnapshot().isPresent(), "item Back to list remains on the staying path");
			assertTrue(f.selects().isEmpty());

			f.press(1000); f.release(1010);
			assertEquals(1, f.selects().size()); assertEquals("opaque", f.selects().getFirst().entryKey());
			assertTrue(f.runtime.selectorSnapshot().isEmpty());
		} finally {
			settings.setContentIntentEnabled(oldContentIntent);
			settings.setInventorySelectionStayOpenEnabled(false);
		}
	}

	@Test void stayingListBackLeavesInventoryAncestryAndClosesWithoutContentReturn() {
		ClientConfig.HANDLER.getConfig().getSpatialSelector().setInventorySelectionStayOpenEnabled(true);
		Fixture f = new Fixture(); f.openList();
		f.release(700);

		f.move(-1000, 0, 800);
		assertTrue(f.runtime.selectorSnapshot().isEmpty());
		assertTrue(f.selects().isEmpty()); assertTrue(f.creates().isEmpty());
		assertEquals(0, f.access.cancellations);
		assertEquals(PingInteractionPhase.IDLE, f.runtime.phase());
	}

	@Test void lifecycleAbortClosesAStayingSelectorAndKeepsItsMouseOwnershipDisposal() {
		ClientConfig.HANDLER.getConfig().getSpatialSelector().setInventorySelectionStayOpenEnabled(true);
		Fixture f = new Fixture(); f.openList();
		f.release(700);
		f.runtime.abortForScreenTransition();

		assertTrue(f.access.screenDisposal);
		assertTrue(f.runtime.selectorSnapshot().isEmpty());
		assertEquals(0, f.inventory.stats().previewChannels());
		f.release(800);
		assertTrue(f.selects().isEmpty()); assertTrue(f.creates().isEmpty());
	}

	@Test void resetRevocationKeepsTheFrozenStayButAnyStaleRowCannotSubmit() {
		ClientConfig.HANDLER.getConfig().getSpatialSelector().setInventorySelectionStayOpenEnabled(true);
		Fixture f = new Fixture(); f.openList();
		f.release(700);
		f.reset(2, Set.of());

		assertTrue(f.runtime.selectorSnapshot().isPresent(),
			"a same-epoch reset re-fences the view but keeps the frozen Preview request");
		assertEquals(PingInteractionPhase.WHEEL_OPEN, f.runtime.phase());
		assertFalse(f.access.grabbed, "the staying selector keeps its mouse ownership");
		f.press(800);
		f.release(810);
		assertTrue(f.runtime.selectorSnapshot().isEmpty(), "a stale old-view row cannot submit");
		assertEquals(PingInteractionPhase.IDLE, f.runtime.phase());
		assertTrue(f.access.grabbed);
		assertTrue(f.selects().isEmpty()); assertTrue(f.creates().isEmpty());
		assertEquals(0, f.access.cancellations);
	}

	@Test void policyPurgeKeepsTheFrozenStayAndReleaseUsesItsGrant() {
		ClientConfig.HANDLER.getConfig().getSpatialSelector().setInventorySelectionStayOpenEnabled(true);
		Fixture f = new Fixture(); f.openList();
		long request = f.request();
		f.release(700);
		f.runtime.onInventoryPacket(InventoryS2CPacket.policy(7, 100, 1, Set.of("block", "entity")).stamp(100, 1));
		f.runtime.onInventoryPacket(InventoryS2CPacket.policy(nx.pingwheel.common.presentation.inventory.InventoryResourceKind.FLUID,
			7, 100, 1, Set.of("block", "entity")).stamp(100, 1));

		assertTrue(f.runtime.selectorSnapshot().isPresent(),
			"POLICY is only a hint and cannot close an accepted Preview grant");
		assertEquals(PingInteractionPhase.WHEEL_OPEN, f.runtime.phase());
		f.press(800);
		f.release(810);
		assertEquals(1, f.selects().size(), "the held release still uses the frozen Preview grant");
		assertEquals("opaque", f.selects().getFirst().entryKey());
		assertEquals(request, f.selects().getFirst().requestId());
		assertTrue(f.creates().isEmpty());
	}
	@Test void allInvalidPreviewLanesKeepRetryStatusWhileOneInvalidLaneKeepsTheOtherNavigable() {
		ClientConfig.HANDLER.getConfig().getSpatialSelector().setInventorySelectionStayOpenEnabled(true);
		Fixture f = new Fixture(); f.openList();
		var fluid = nx.pingwheel.common.presentation.inventory.InventoryResourceKind.FLUID;
		f.runtime.onInventoryPacket(InventoryS2CPacket.policy(fluid, 7, 100, 1, Set.of("entity_block")));
		f.release(700);
		f.runtime.onInventoryPacket(InventoryS2CPacket.data(fluid, InventoryS2CPacket.Kind.PREVIEW, 7, f.request(), null, 9, 1, 1, 0, 1,
			false, InventoryS2CPacket.Status.INVALID, 0, List.of()).stamp(100, 1));
		assertTrue(f.runtime.selectorSnapshot().isPresent()); assertTrue(f.snapshot().inventory().open());
		assertEquals("ITEM:opaque", f.snapshot().inventory().selectedKey());
		f.runtime.onInventoryPacket(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, f.request(), null, 10, 2, 2, 0, 1,
			false, InventoryS2CPacket.Status.INVALID, 0, List.of(), nx.pingwheel.common.presentation.inventory.InventoryFailureReason.SOURCE_CHANGED).stamp(100, 1));
		assertTrue(f.runtime.selectorSnapshot().isPresent()); assertEquals(PingInteractionPhase.WHEEL_OPEN, f.runtime.phase());
		assertTrue(f.snapshot().inventoryView().open()); assertTrue(f.snapshot().inventoryView().rows().isEmpty());
		assertEquals(net.minecraft.network.chat.Component.translatable("pingforit.inventory.retry",
			net.minecraft.network.chat.Component.translatable(nx.pingwheel.common.presentation.inventory.InventoryFailureReason.SOURCE_CHANGED.translationKey()), 1),
			f.snapshot().inventoryView().statusMessage());
		long retired = f.request(); f.clock.value = 5_700; f.inventory.tick(true); f.frame(5_700, false);
		assertNotEquals(retired, f.request()); assertTrue(f.snapshot().inventoryView().open());
		assertTrue(f.snapshot().inventoryView().rows().isEmpty()); assertFalse(f.snapshot().inventoryView().selectedRowCommittable());
		f.press(5_800); f.release(5_810);
		assertTrue(f.selects().isEmpty()); assertTrue(f.creates().isEmpty());
	}

	@Test void revokingWhileAStayingTapIsHeldSwallowsItsRemainingRelease() {
		ClientConfig.HANDLER.getConfig().getSpatialSelector().setInventorySelectionStayOpenEnabled(true);
		Fixture f = new Fixture(); f.openList();
		f.release(700);
		f.press(800);
		f.reset(2, Set.of());

		assertTrue(f.runtime.selectorSnapshot().isPresent(),
			"a same-epoch reset cannot revoke the frozen request under a held stay tap");
		f.release(810);
		assertTrue(f.selects().isEmpty()); assertTrue(f.creates().isEmpty());
		assertTrue(f.runtime.selectorSnapshot().isEmpty(), "the swallowed release closes without submitting");
		assertEquals(PingInteractionPhase.IDLE, f.runtime.phase());
		assertEquals(0, f.access.cancellations);
	}

	@Test void legitimateUpdatingPreviewWithoutRowsKeepsTheStayingListOpen() {
		ClientConfig.HANDLER.getConfig().getSpatialSelector().setInventorySelectionStayOpenEnabled(true);
		Fixture f = new Fixture();
		f.press(0); f.frame(250, true); f.prime();
		f.enter("content", 260);
		String list = f.menu().choices().stream().filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow().id();
		f.enter(list, 460);
		assertTrue(f.snapshot().inventory().open());
		assertTrue(f.snapshot().inventory().entries().isEmpty(), "the preview is still legitimately loading");

		f.release(700);
		assertTrue(f.runtime.selectorSnapshot().isPresent());
		assertEquals(PingInteractionPhase.WHEEL_OPEN, f.runtime.phase());
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
	@Test void preciseReleaseConsumesPaintedLiveCandidateWithoutOrdinaryRecaptureOrReleaseCast() {
		Fixture f = new Fixture(); f.access.supplements = List.of(STONE); f.press(0); f.frame(250, true); f.prime(); f.enter("precise", 260);
		f.focus("precise:block", 460); f.paint(); f.access.snapshot = CHEST; f.release(470);
		assertEquals(STONE.target(), f.creates().getFirst().target()); assertEquals(1, f.access.captures); assertEquals(1, f.validations.size());
		assertEquals(1, f.access.liveRequests.size());
	}
	@Test void nearerPreciseContactWithoutFacePreservesPressFaceForOrdinaryInventoryContent() {
		Fixture f = new Fixture();
		TargetSnapshot noFaceChest = TargetSnapshotFactory.block(DIMENSION, 1, 2, 3, "minecraft:chest", true);
		f.access.supplements = List.of(noFaceChest);
		f.access.supplementHit = new WorldVector(0.1, 2, 3);
		f.press(0); f.inventoryRows(); f.frame(250, true); f.prime(); f.enter("content", 260);
		assertEquals(BlockFace.WEST, f.sent.stream().filter(packet -> packet instanceof InventoryC2SPacket p
			&& p.kind() == InventoryC2SPacket.Kind.OPEN).map(packet -> ((InventoryC2SPacket) packet).face()).findFirst().orElseThrow());
		SpatialController.ChoiceView inventory = f.menu().choices().stream()
			.filter(choice -> choice.id().endsWith(":inventory")).findFirst().orElseThrow();
		assertFalse(inventory.disabled(), "Precise contact provenance must not erase the press-time inventory face");
		f.enter(inventory.id(), 460); f.release(700);
		assertEquals(1, f.selects().size()); assertTrue(f.creates().isEmpty());
	}
	@Test void broadBlockSlotKeepsBehindChestSeparateFromOrdinaryEntityBlock() {
		Fixture front = new Fixture(); front.access.supplements = List.of(BEHIND_CHEST);
		front.press(0); front.frame(250, true); front.prime(); front.enter("precise", 260);
		assertFalse(disabled(front, "precise:entity_block")); assertFalse(disabled(front, "precise:block"));
		front.focus("precise:entity_block", 460); front.paint(); front.release(470);
		assertEquals(CHEST.target(), front.creates().getFirst().target()); assertEquals("attention", front.creates().getFirst().pingType());

		Fixture behind = new Fixture(); behind.access.supplements = List.of(BEHIND_CHEST);
		behind.press(0); behind.frame(250, true); behind.prime(); behind.enter("precise", 260);
		behind.focus("precise:block", 460); behind.paint(); behind.release(470);
		assertEquals(BEHIND_CHEST.target(), behind.creates().getFirst().target());
		assertEquals("attention", behind.creates().getFirst().pingType());
	}
	@Test void externalBlockPressWithProviderLocalFaceOpensSelectorAndReleasesItsOwnTarget() {
		Fixture f = new Fixture();
		TargetSnapshot external = TargetSnapshotFactory.externalBlockCandidate(DIMENSION, "sable", "minecraft:chest",
			"opaque-locator", true, BlockFace.WEST);
		f.access.snapshot = external;
		f.press(0); f.frame(250, true);
		assertTrue(f.runtime.selectorSnapshot().isPresent(), "an external block target actually opens the selector");
		assertEquals(BlockFace.WEST, f.sent.stream().filter(packet -> packet instanceof InventoryC2SPacket p
			&& p.kind() == InventoryC2SPacket.Kind.OPEN).map(packet -> ((InventoryC2SPacket) packet).face())
			.findFirst().orElseThrow(), "the frozen provider-local face is the preview read scope");
		f.prime(); f.enter("intent", 260); f.focus("intent:attention", 460); f.release(470);
		assertEquals(1, f.creates().size());
		assertEquals(external.target(), f.creates().getFirst().target());
		assertEquals("attention", f.creates().getFirst().pingType());
		assertEquals(1, f.validations.size());
	}
	@Test void broadEntitySlotKeepsBehindDroppedItemSeparateFromOrdinaryDroppedItem() {
		Fixture front = new Fixture(); front.access.snapshot = ITEM; front.access.supplements = List.of(BEHIND_ITEM);
		front.press(0); front.frame(250, true); front.prime(); front.enter("precise", 260);
		assertFalse(disabled(front, "precise:dropped_item")); assertFalse(disabled(front, "precise:entity"));
		front.focus("precise:dropped_item", 460); front.paint(); front.release(470);
		assertEquals(ITEM.target(), front.creates().getFirst().target()); assertEquals("loot", front.creates().getFirst().pingType());

		Fixture behind = new Fixture(); behind.access.snapshot = ITEM; behind.access.supplements = List.of(BEHIND_ITEM);
		behind.press(0); behind.frame(250, true); behind.prime(); behind.enter("precise", 260);
		behind.focus("precise:entity", 460); behind.paint(); behind.release(470);
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
	@Test void cursorEntryPreservesTheOwnedRuntimeBaselineAndExplicitResetStillReprimes() {
		Fixture f = new Fixture(); f.press(0); f.frame(250, true); f.prime();
		f.runtime.onMouseCursorEntered(); f.move(10, -5, 260);
		assertEquals(new SpatialController.Point(10, -5), f.snapshot().radial().pointer());
		f.runtime.onMouseCursorEntered(); f.move(3, 2, 270);
		assertEquals(new SpatialController.Point(13, -3), f.snapshot().radial().pointer());
		f.runtime.rePrimeSelectorInput(); f.move(1000, 1000, 280);
		assertEquals(new SpatialController.Point(13, -3), f.snapshot().radial().pointer());
		f.move(3, 2, 290); assertEquals(new SpatialController.Point(16, -1), f.snapshot().radial().pointer());
	}
	@Test void cursorEntryDuringNativeHandoffOrInvalidFrameClearsTheRuntimeBaselineWithoutMovementCallback() {
		Fixture f = new Fixture(); f.press(0); f.frame(250, true); f.prime(); f.move(10, 5, 260);
		var before = f.snapshot().radial().pointer();
		f.access.nativeOwned = false; f.runtime.onMouseCursorEntered(); f.access.nativeOwned = true;
		f.move(1000, 1000, 270); assertEquals(before, f.snapshot().radial().pointer());
		f.move(3, 2, 280); assertEquals(new SpatialController.Point(before.x() + 3, before.y() + 2), f.snapshot().radial().pointer());
		before = f.snapshot().radial().pointer();
		f.access.validFrame = false; f.runtime.onMouseCursorEntered(); f.access.validFrame = true;
		f.move(1000, 1000, 290); assertEquals(before, f.snapshot().radial().pointer());
		f.move(3, 2, 300); assertEquals(new SpatialController.Point(before.x() + 3, before.y() + 2), f.snapshot().radial().pointer());
	}
	@Test void nativeCaptureHandoffWithoutVanillaGrabChangeGatesBothRuntimeCallbackPaths() {
		Fixture f = new Fixture(); f.press(0); f.frame(250, true); f.prime(); f.move(10, 5, 260);
		var before = f.snapshot().radial().pointer(); f.access.nativeOwned = false;
		f.move(1000, 1000, 270); assertEquals(before, f.snapshot().radial().pointer());
		assertFalse(f.runtime.onMouseScroll(1, 2, 0)); assertFalse(f.runtime.onMouseScroll(1, 0, 1));
		assertFalse(f.access.grabbed, "native ownership is independent of vanilla camera capture");
		f.access.nativeOwned = true; f.prime(); f.move(10, 5, 280);
		assertEquals(new SpatialController.Point(before.x() + 10, before.y() + 5), f.snapshot().radial().pointer());
		assertTrue(f.runtime.onMouseScroll(1, 2, 0));
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
		f.runtime.onInventoryPacket(InventoryS2CPacket.opened(7, 100, 1, other, 3));
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
	@Test void frozenTrailPreferencesConfigureActualNativeHistoryAndPaintStyleUntilNextHold() {
		var settings = ClientConfig.HANDLER.getConfig().getSpatialSelector();
		int oldLength = settings.getTrailLength(), oldOpacity = settings.getTrailOpacity();
		boolean oldShown = settings.isShowTrail();
		try {
			settings.setTrailLength(19); settings.setTrailOpacity(31); settings.setShowTrail(true);
			Fixture f = new Fixture(); f.press(0);
			settings.setTrailLength(512); settings.setTrailOpacity(0); settings.setShowTrail(false);
			f.frame(250, true); f.prime();
			// Small alternating callbacks stay in the deadzone and cannot navigate or hit a viewport edge.
			for (int i = 0; i < 600; i++) f.move(i % 2 == 0 ? 1 : -1, 0, 260);
			assertEquals(19, f.snapshot().radial().trail().size(), "runtime tuning consumes the frozen cap, not live/default config");
			assertEquals(31, f.access.selectorStyle.trailOpacityPercent()); assertTrue(f.access.selectorStyle.showTrail());
			assertEquals(19, f.access.selectorStyle.trailLength(), "native painter and controller share the same bound");
			f.runtime.abort(); f.press(300); f.frame(550, true); f.prime();
			for (int i = 0; i < 600; i++) f.move(i % 2 == 0 ? 1 : -1, 0, 560);
			assertEquals(512, f.snapshot().radial().trail().size(), "new hold adopts edited max cap even with trail disabled");
			assertEquals(0, f.access.selectorStyle.trailOpacityPercent()); assertFalse(f.access.selectorStyle.showTrail());
			f.runtime.abort();
		} finally { settings.setTrailLength(oldLength); settings.setTrailOpacity(oldOpacity); settings.setShowTrail(oldShown); }
	}
	@Test void pressFreezesInventoryProportionsIntoTheActualNativePaintAndGeometryStyleUntilNextHold() {
		var settings = ClientConfig.HANDLER.getConfig().getSpatialSelector();
		var oldWidth = settings.getInventoryMaxWidthFraction();
		var oldHeight = settings.getInventoryMaxHeightFraction();
		try {
			settings.setInventoryMaxWidthFraction(new BigDecimal("0.65"));
			settings.setInventoryMaxHeightFraction(new BigDecimal("0.85"));
			Fixture f = new Fixture(); f.press(0);
			settings.setInventoryMaxWidthFraction(new BigDecimal("0.20"));
			settings.setInventoryMaxHeightFraction(new BigDecimal("0.30"));
			f.frame(250, true);
			var held = f.access.selectorStyle;
			assertEquals(new BigDecimal("0.65"), f.snapshot().settings().inventoryMaxWidthFraction());
			assertEquals(new BigDecimal("0.85"), f.snapshot().settings().inventoryMaxHeightFraction());
			assertEquals(0.65, held.inventoryMaxWidthFraction(), "the production press path explicitly injects snapshot width into Style");
			assertEquals(0.85, held.inventoryMaxHeightFraction(), "the native geometry port receives the same frozen height as painting");
			f.access.frame = new NativeSelectorInput.Frame(1, 2000, 1400, 500, 350, true, false);
			f.frame(260, true);
			assertSame(held, f.access.selectorStyle, "resize cannot rebuild Style from the live preferences");
			f.runtime.abort(); f.press(300); f.frame(550, true);
			assertEquals(0.20, f.access.selectorStyle.inventoryMaxWidthFraction());
			assertEquals(0.30, f.access.selectorStyle.inventoryMaxHeightFraction());
			assertNotSame(held, f.access.selectorStyle, "the next physical hold adopts both edits");
			f.runtime.abort();
		} finally {
			settings.setInventoryMaxWidthFraction(oldWidth);
			settings.setInventoryMaxHeightFraction(oldHeight);
		}
	}
	@ParameterizedTest @ValueSource(booleans = {false, true})
	void reentrantAbortInValidatorBlocksEveryReleasePacket(boolean menu) {
		Fixture f = new Fixture(); f.press(0);
		if (menu) { f.frame(250, true); f.prime(); f.enter("intent", 260); f.focus("intent:attention", 460); }
		f.validationHook = f.runtime::abort; f.release(menu ? 470 : 20);
		assertTrue(f.creates().isEmpty()); assertTrue(f.selects().isEmpty()); assertEquals(PingInteractionPhase.IDLE, f.runtime.phase());
		assertEquals(0, f.inventory.stats().previewChannels());
	}

	@Test void ordinaryPressAStaysFrozenWhilePreciseLiveBThenUnpaintedCReleasesB() {
		Fixture f = new Fixture(); f.press(0); var pressRay = f.access.capturedRay; long request = f.request();
		f.access.ray = new CapturedRay(new WorldVector(10, 11, 12), new WorldVector(1, 0, 0)); f.access.snapshot = STONE;
		f.frame(250, true); assertEquals(0, f.access.liveRequests.size());
		f.prime(); f.enter("precise", 260); assertEquals(1, f.access.liveRequests.size());
		assertEquals(f.access.ray, f.access.liveRequests.getFirst().inputs().ray());
		assertEquals(pressRay, f.access.capturedRay); assertEquals(1, f.access.captures);
		f.focus("precise:block", 460); f.paint(); var painted = f.snapshot().preciseFrame();
		f.access.snapshot = TargetSnapshotFactory.block(DIMENSION, 20, 5, 6, "minecraft:stone", false, BlockFace.SOUTH);
		f.access.ray = new CapturedRay(new WorldVector(20, 21, 22), new WorldVector(0, 1, 0)); f.tick(465);
		assertEquals("precise:block", f.menu().focusId());
		assertNotEquals(painted.choices().get("precise:block").candidateId(), f.snapshot().preciseFrame().choices().get("precise:block").candidateId());
		assertEquals(1, f.inventory.stats().previewChannels()); assertEquals(request, f.request());
		f.release(470); assertEquals(STONE.target(), f.creates().getFirst().target());
		assertEquals(2, f.access.liveRequests.size(), "release does not cast"); assertEquals(1, f.access.captures);
		assertEquals(0, f.access.cancellations); assertEquals(0, f.inventory.stats().previewChannels());

		Fixture ordinary = new Fixture(); ordinary.press(0); ordinary.access.snapshot = STONE; ordinary.frame(250, true);
		ordinary.prime(); ordinary.enter("intent", 260); ordinary.focus("intent:attention", 460); ordinary.release(470);
		assertEquals(CHEST.target(), ordinary.creates().getFirst().target()); assertTrue(ordinary.access.liveRequests.isEmpty());
	}

	@Test void frameIsNotPaintAndUnpaintedLiveCandidateNeverCommits() {
		Fixture f = new Fixture(); f.press(0); f.frame(250, true); f.prime(); f.enter("precise", 260);
		f.focus("precise:entity_block", 460); f.frame(465, true); f.release(470);
		assertTrue(f.creates().isEmpty()); assertTrue(f.validations.isEmpty()); assertEquals(0, f.access.cancellations);
	}

	@Test void incompleteNativeAcquisitionDisablesEveryPreciseLeafWithoutUnfreezingOrdinaryCapture() {
		Fixture precise = new Fixture(); precise.press(0); precise.frame(250, true); precise.prime();
		precise.access.preciseIncomplete = true; precise.enter("precise", 260);
		for (String leaf : List.of("precise:dropped_item", "precise:entity", "precise:entity_block", "precise:block", "precise:location")) {
			assertTrue(disabled(precise, leaf), leaf);
		}
		precise.release(470); assertTrue(precise.creates().isEmpty());
		assertEquals(1, precise.access.captures);

		Fixture ordinary = new Fixture(); ordinary.press(0); ordinary.access.preciseIncomplete = true;
		ordinary.access.snapshot = STONE; ordinary.release(20);
		assertEquals(CHEST.target(), ordinary.creates().getFirst().target());
		assertTrue(ordinary.access.liveRequests.isEmpty());
	}

	@Test void partialNativeUpdateKeepsPendingDistantLocationAndItsOriginalRayAtRelease() {
		Fixture f = new Fixture(); f.press(0); f.frame(250, true); f.prime(); f.access.distantPending = true;
		CapturedRay rayA = f.access.ray; f.enter("precise", 260);
		assertTrue(disabled(f, "precise:location"));
		var locationSnapshot = TargetSnapshotFactory.location(DIMENSION, 100, 2, 3);
		var resolved = nx.pingwheel.common.resolve.DefaultTargetResolver.builtIn(nx.pingwheel.common.resolve.TargetResolutionLogger.noop())
			.resolve(locationSnapshot.target(), locationSnapshot.matchContext());
		var location = new nx.pingwheel.common.interaction.candidate.Candidate(0, resolved, new WorldVector(100, 2, 3), 100,
			Optional.empty(), Optional.empty(), CaptureEquivalenceKey.nativeTarget(locationSnapshot.target()));
		f.access.distantCompletions.getFirst().accept(PreciseCaptureRefresh.Outcome.available(location));
		f.access.ray = new CapturedRay(new WorldVector(10, 20, 30), new WorldVector(1, 0, 0)); f.access.snapshot = STONE; f.tick(450);
		assertFalse(disabled(f, "precise:location")); assertFalse(disabled(f, "precise:block"));
		var live = f.snapshot().preciseFrame();
		assertEquals(rayA, live.contexts().get(live.choices().get("precise:location").candidateId()).ray());
		assertEquals(f.access.ray, live.contexts().get(live.choices().get("precise:block").candidateId()).ray());
		f.focus("precise:location", 460); f.paint(); f.release(470);
		assertEquals(locationSnapshot.target(), f.creates().getFirst().target()); assertEquals(1, f.validations.size());
		assertEquals(1, f.access.captures); assertEquals(0, f.access.cancellations);
	}

	@Test void liveRefreshLeavesCancelAndContentBoundToOrdinaryPressAndPausesOnBranchExit() {
		Fixture f = new Fixture(); f.press(0); CapturedRay pressRay = f.access.capturedRay; long request = f.request();
		f.frame(250, true); f.prime(); f.access.ray = new CapturedRay(new WorldVector(10, 20, 30), new WorldVector(1, 0, 0));
		f.access.snapshot = STONE; f.enter("precise", 260); f.tick(450);
		String back = f.menu().choices().stream().filter(SpatialController.ChoiceView::back).findFirst().orElseThrow().id();
		f.enterBack(back, 460); int scans = f.access.liveRequests.size(); f.tick(700);
		assertEquals(scans, f.access.liveRequests.size()); assertEquals(request, f.request()); assertEquals(1, f.inventory.stats().previewChannels());
		f.focus("cancel-marker", 710); f.release(720); assertEquals(pressRay, f.access.cancellationRay);
		assertTrue(f.creates().isEmpty()); assertEquals(1, f.access.cancellations); assertEquals(1, f.access.captures);
	}

	@Test void precisePeriodAndRangePolicyFreezeAtPressAndChangesApplyNextHoldOnly() {
		var config = ClientConfig.HANDLER.getConfig(); var settings = config.getSpatialSelector();
		int oldPeriod = settings.getPreciseCapturePeriodTicks(), oldPing = config.getPingDistance(), oldRaycast = config.getRaycastDistance();
		boolean oldFluid = config.isMarkFluids();
		try {
			settings.setPreciseCapturePeriodTicks(50); config.setPingDistance(100); config.setRaycastDistance(50); config.setMarkFluids(false);
			Fixture f = new Fixture(); f.press(0);
			settings.setPreciseCapturePeriodTicks(1); config.setPingDistance(200); config.setRaycastDistance(150); config.setMarkFluids(true);
			f.frame(250, true); f.prime(); f.enter("precise", 260);
			for (int tick = 1; tick < 50; tick++) f.tick(440 + tick);
			assertEquals(1, f.access.liveRequests.size()); f.tick(500); assertEquals(2, f.access.liveRequests.size());
			var inputs = f.access.liveRequests.getLast().inputs(); assertEquals(100, inputs.pingDistance()); assertEquals(50, inputs.nativeDistance());
			assertEquals(nx.pingwheel.common.math.RaycastPolicy.FluidMode.NONE, inputs.policy().fluidMode());
			f.runtime.abort(); f.press(600); f.frame(850, true); f.prime(); f.enter("precise", 860); f.tick(1050);
			assertEquals(4, f.access.liveRequests.size()); assertEquals(200, f.access.liveRequests.getLast().inputs().pingDistance());
			assertEquals(nx.pingwheel.common.math.RaycastPolicy.FluidMode.ANY, f.access.liveRequests.getLast().inputs().policy().fluidMode());
		} finally { settings.setPreciseCapturePeriodTicks(oldPeriod); config.setPingDistance(oldPing); config.setRaycastDistance(oldRaycast); config.setMarkFluids(oldFluid); }
	}

	@Test void adjacentSecondShortReleaseCreatesDangerFromFirstFrozenTargetWithNewRequest() {
		Fixture f = new Fixture(); f.press(0); f.release(10);
		assertEquals(1, f.creates().size()); assertEquals("attention", f.creates().getFirst().pingType());
		CapturedRay firstRay = f.access.capturedRay;
		f.access.snapshot = STONE; f.access.ray = new CapturedRay(new WorldVector(9, 9, 9), new WorldVector(1, 0, 0));

		f.press(60); f.release(70);
		assertEquals(2, f.creates().size());
		var first = f.creates().get(0); var second = f.creates().get(1);
		assertEquals("danger", second.pingType()); assertEquals(CHEST.target(), second.target());
		assertNotEquals(first.requestId(), second.requestId(), "every re-tap is a fresh token and request id");
		assertEquals(1, f.access.captures, "a short re-tap needs neither a second ray nor its capture result");
		assertEquals(firstRay, f.access.capturedRay, "the first press ray remains frozen");
		assertTrue(f.runtime.selectorSnapshot().isEmpty());
	}

	@Test void thirdAndLaterAdjacentTapsAreIndependentDangersWithDistinctRequests() {
		Fixture f = new Fixture(); f.press(0); f.release(10);
		f.press(60); f.release(70); f.press(120); f.release(130);

		assertEquals(3, f.creates().size());
		assertNotEquals(f.creates().get(0).requestId(), f.creates().get(1).requestId());
		assertNotEquals(f.creates().get(1).requestId(), f.creates().get(2).requestId());
		assertEquals("danger", f.creates().get(1).pingType());
		assertEquals("danger", f.creates().get(2).pingType());
		assertEquals(CHEST.target(), f.creates().get(2).target());
		assertEquals(1, f.access.captures);
	}

	@Test void pendingFirstCaptureReleasesEveryEarlierTapOnlyAfterItsLocalCreateSent() {
		Fixture f = new Fixture(); f.access.pending = true; f.press(0); f.release(10);
		f.press(60); f.release(70); f.press(120); f.release(130);
		assertTrue(f.creates().isEmpty());

		f.access.pending = false; f.access.completions.getFirst().run(); f.frame(500, false);
		assertEquals(3, f.creates().size(), "the two admitted taps are not expired by the later completion time");
		assertEquals("attention", f.creates().getFirst().pingType());
		assertEquals("danger", f.creates().get(1).pingType());
		assertEquals("danger", f.creates().get(2).pingType());
		assertNotEquals(f.creates().get(0).requestId(), f.creates().get(1).requestId());
		assertNotEquals(f.creates().get(1).requestId(), f.creates().get(2).requestId());
	}

	@Test void fourCompletedTapsBeforeTheFirstCaptureAllFlushAfterItsLocalCreateSent() {
		Fixture f = new Fixture(); f.access.pending = true; f.press(0); f.release(10);
		for (long down : new long[] { 50, 100, 150, 200 }) {
			f.press(down); f.release(down + 10);
		}
		assertTrue(f.creates().isEmpty());

		f.access.pending = false; f.access.completions.getFirst().run(); f.frame(5000, false);
		assertEquals(5, f.creates().size());
		assertEquals("attention", f.creates().getFirst().pingType());
		for (int i = 1; i < 5; i++) assertEquals("danger", f.creates().get(i).pingType());
		assertEquals(5, f.creates().stream().map(PresentationC2SPacket::requestId).distinct().count());
	}

	@Test void transportFailureOnTheFirstCreateClearsTheRapidSequence() {
		Fixture f = new Fixture(); f.fail = true;
		f.press(0); f.release(10); f.press(60); f.release(70);

		assertTrue(f.creates().isEmpty());
		assertEquals(2, f.access.captures);
		assertTrue(f.runtime.selectorSnapshot().isEmpty());
	}

	@Test void enablingCompatibilityDuringPendingRapidInputDrainsTheFirstCreateWithoutSeedingOrDanger() {
		Fixture f = new Fixture(); f.access.pending = true; f.press(0); f.release(10);
		f.press(60); f.release(70);
		ClientConfig.HANDLER.getConfig().setLongPressCompatibilityMode(true);

		f.access.pending = false; f.access.completions.getFirst().run(); f.frame(5000, false);
		assertEquals(1, f.creates().size());
		assertEquals("attention", f.creates().getFirst().pingType());
		assertFalse(f.runtime.hasCompatibilityState());

		f.press(5100); f.release(5110);
		assertEquals(2, f.creates().size());
		assertEquals("attention", f.creates().get(1).pingType());
	}

	@Test void advancingClockStillFlushesEveryPendingDangerWithOneConsistentObservation() {
		Fixture f = new Fixture(); f.access.pending = true; f.clock.advanceOnRead = true;
		f.press(100); f.release(110);
		for (long down : new long[] { 150, 200, 250 }) {
			f.press(down); f.release(down + 10);
		}

		f.access.pending = false; f.access.completions.getFirst().run(); f.frame(5000, false);
		assertEquals(4, f.creates().size());
		assertEquals("attention", f.creates().getFirst().pingType());
		for (int i = 1; i < 4; i++) assertEquals("danger", f.creates().get(i).pingType());
	}

	@Test void advancingClockPresentsHeldCandidateAfterPendingDangerFlush() {
		Fixture f = new Fixture(); f.access.pending = true; f.clock.advanceOnRead = true;
		f.press(100); f.release(110);
		f.press(150); f.release(160);
		f.press(200);

		f.access.pending = false; f.access.completions.getFirst().run();
		assertDoesNotThrow(() -> f.frame(5000, true));

		assertEquals(2, f.creates().size(), "the first ordinary create and one flushed pending Danger");
		assertEquals("attention", f.creates().get(0).pingType());
		assertEquals("danger", f.creates().get(1).pingType());
		assertEquals(PingInteractionPhase.WHEEL_OPEN, f.runtime.phase(),
			"the held candidate's own baseline actually opened its wheel");
		assertEquals(2, f.access.captures, "the first press and the held candidate captured once each");

		f.release(5100);
		assertEquals(2, f.creates().size(), "no wheel selection was made, so release adds no create");
		assertEquals(1L, f.creates().stream().filter(create -> create.pingType().equals("danger")).count(),
			"the actually opened wheel never also emits the candidate as Danger");
		f.release(5200);
		assertEquals(2, f.creates().size(), "a duplicate release stays actionless");
	}

	@Test void advancingClockPresentsHeldCandidateWithoutPendingDangerFlush() {
		Fixture f = new Fixture(); f.access.pending = true; f.clock.advanceOnRead = true;
		CapturedRay secondRay = new CapturedRay(new WorldVector(5, 5, 5), new WorldVector(1, 0, 0));
		f.press(100); f.release(110);
		f.access.ray = secondRay;
		f.press(200);

		f.access.pending = false; f.access.completions.getFirst().run();
		assertDoesNotThrow(() -> f.frame(400, true));

		assertEquals(1, f.creates().size(), "no pending Danger was admitted");
		assertEquals("attention", f.creates().getFirst().pingType());
		assertEquals(PingInteractionPhase.PRESSED, f.runtime.phase(),
			"200 ms from the candidate's own down edge, not 300 ms from the first press");
		assertTrue(f.runtime.selectorSnapshot().isEmpty());

		f.frame(460, true);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, f.runtime.phase());
		assertEquals(secondRay, f.access.capturedRay, "the held candidate uses its own press ray");
		assertEquals(2, f.access.captures, "the first press and the candidate baseline captured once each");
	}

	@Test void reentrantAbortInsideDangerValidationDispatchesNoDangerButKeepsTheFirstOrdinaryCreate() {
		Fixture f = new Fixture(); f.press(0); f.release(10);
		assertEquals(1, f.creates().size());
		f.validationHook = f.runtime::abort;

		f.press(60); f.release(70);

		assertEquals(1, f.creates().size(), "the aborted frozen tap never reaches dispatch");
		assertEquals(PingInteractionPhase.IDLE, f.runtime.phase());
		assertTrue(f.runtime.selectorSnapshot().isEmpty());
		assertFalse(f.runtime.hasCompatibilityState());
	}

	@Test void reentrantAbortOnTheFirstFlushedDangerStopsAllRemainingPendingDangers() {
		Fixture f = new Fixture(); f.access.pending = true;
		f.press(0); f.release(10); f.press(50); f.release(60); f.press(100); f.release(110);
		final int[] validations = {0};
		f.validationHook = () -> { if (++validations[0] > 1) f.runtime.abort(); };

		f.access.pending = false; f.access.completions.getFirst().run(); f.frame(5000, false);

		assertEquals(1, f.creates().size(), "only the first ordinary create survives the reentrant abort");
		assertEquals("attention", f.creates().getFirst().pingType());
		assertEquals(2, validations[0], "the first danger validation aborted the flush");
	}

	@ParameterizedTest @ValueSource(strings = {"gap", "angle"})
	void nonMatchingFreshPressSupersedesThePendingFirstAndItsAdmittedTaps(String mismatch) {
		Fixture f = new Fixture(); f.access.pending = true;
		f.press(0); f.release(10);
		f.press(50); f.release(60);
		int candidates = f.access.completions.size();
		assertEquals(1, candidates);

		f.access.snapshot = STONE;
		if (mismatch.equals("angle")) f.runtime.onLookInput(30, 0);
		f.press(mismatch.equals("angle") ? 100 : 300); f.release(mismatch.equals("angle") ? 110 : 310);
		assertEquals(2, f.access.completions.size(), "the fresh non-matching press starts its own capture");
		assertEquals(2, f.access.captures);

		Runnable old = f.access.completions.get(0), current = f.access.completions.get(1);
		old.run();
		assertTrue(f.creates().isEmpty(), "the superseded completion cannot create or release a Danger");
		current.run(); f.frame(5000, false);

		assertEquals(1, f.creates().size());
		assertEquals(STONE.target(), f.creates().getFirst().target());
		assertEquals("attention", f.creates().getFirst().pingType());
		assertTrue(f.access.completions.size() > 1);
	}

	@Test void failedFirstCaptureClearsPendingTapsWithoutRetryOrFallback() {
		Fixture f = new Fixture(); f.access.pending = true; f.gone = true;
		f.press(0); f.release(10); f.press(60); f.release(70);
		f.access.pending = false; f.access.completions.getFirst().run(); f.frame(500, false);

		assertTrue(f.creates().isEmpty()); assertEquals(1, f.errors.size());
		assertTrue(f.runtime.selectorSnapshot().isEmpty()); assertEquals(1, f.access.captures);
	}

	@Test void throttledFirstCreateClearsTheRapidSequenceInsteadOfDispatchingDanger() {
		Fixture f = new Fixture(new ClientRateLimitPolicy(1, 1000), true);
		f.press(0); f.release(1); f.runtime.abort(); f.sent.clear();
		f.access.pending = true;
		f.press(20); f.release(30); f.press(60); f.release(70);
		f.access.pending = false; f.access.completions.getLast().run(); f.frame(500, false);

		assertTrue(f.creates().isEmpty()); assertEquals(2, f.access.captures);
	}

	@Test void candidateHoldUsesItsOwnPressTimeAndRayThenReleasesOnlyTheWheel() {
		Fixture f = new Fixture(); f.press(0); f.release(10);
		CapturedRay secondRay = new CapturedRay(new WorldVector(5, 5, 5), new WorldVector(1, 0, 0));
		f.access.ray = secondRay; f.access.snapshot = STONE;

		f.press(60); f.frame(300, true);
		assertEquals(PingInteractionPhase.PRESSED, f.runtime.phase(),
			"240 ms from this down and 300 ms from the first press are both below the frozen 250 ms threshold");
		f.frame(311, true);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, f.runtime.phase());
		assertEquals(secondRay, f.access.capturedRay);
		f.release(320);
		assertEquals(1, f.creates().size(), "an actually opened wheel owns its release and adds no Danger");
		assertEquals(2, f.access.captures);
	}

	@Test void advancingClockCandidatePresentWithoutActualOpenReleasesExactlyOneFrozenDanger() {
		Fixture f = new Fixture(); f.press(0); f.release(10);
		assertEquals(1, f.creates().size());
		f.press(60);
		f.clock.advanceOnRead = true;
		assertDoesNotThrow(() -> f.frame(200, true));
		assertEquals(PingInteractionPhase.PRESSED, f.runtime.phase(),
			"the candidate baseline started but the wheel did not actually open");

		f.release(210);
		assertEquals(2, f.creates().size(), "the short candidate contributes exactly one frozen Danger");
		assertEquals("danger", f.creates().get(1).pingType());

		f.release(220);
		assertEquals(2, f.creates().size(), "a repeated release cannot replay the frozen tap");
		f.runtime.abort();
		assertEquals(2, f.creates().size(), "abort after the short release cannot double-commit");
		assertEquals(PingInteractionPhase.IDLE, f.runtime.phase());
	}

	@Test void candidateHeldWithoutAFrameStillReleasesAtTapBoundaryRatherThanOpeningRetroactively() {
		Fixture f = new Fixture(); f.press(0); f.release(10);
		f.press(60); f.release(350);

		assertEquals(2, f.creates().size()); assertEquals("danger", f.creates().get(1).pingType());
		assertTrue(f.runtime.selectorSnapshot().isEmpty()); assertEquals(1, f.access.captures);
	}

	@Test void cameraAngleAndAdjacentWindowMismatchesStartAFreshOrdinaryPress() {
		Fixture angle = new Fixture(); angle.press(0); angle.release(10);
		angle.runtime.onLookInput(30, 0);
		angle.press(60); angle.release(70);
		assertEquals(2, angle.creates().size());
		assertEquals("attention", angle.creates().get(1).pingType());
		assertEquals(2, angle.access.captures);

		Fixture gap = new Fixture(); gap.press(0); gap.release(10);
		gap.press(200); gap.release(210);
		assertEquals(2, gap.creates().size());
		assertEquals("attention", gap.creates().get(1).pingType());
	}

	@Test void liveCompatibilityModeOwnsRapidInputAndNeverProducesDanger() {
		ClientConfig.HANDLER.getConfig().setLongPressCompatibilityMode(true);
		Fixture f = new Fixture(); f.press(0); f.release(10); f.press(60); f.release(70); f.frame(200, false);

		assertEquals(1, f.creates().size()); assertTrue(f.runtime.selectorSnapshot().isEmpty());
		assertFalse(f.runtime.hasCompatibilityState());
	}

	@Test void liveCompatibilityDisableOnPressConsumesThatEdgeBeforeRapidRecognition() {
		ClientConfig.HANDLER.getConfig().setLongPressCompatibilityMode(true);
		Fixture f = new Fixture();
		f.press(0); f.release(10);
		int captures = f.access.captures, creates = f.creates().size();
		ClientConfig.HANDLER.getConfig().setLongPressCompatibilityMode(false);

		f.press(100);
		assertEquals(captures, f.access.captures, "the first disabled edge must not start a rapid capture");
		assertEquals(creates, f.creates().size());
		assertEquals(PingInteractionPhase.IDLE, f.runtime.phase());
		f.release(110);
		assertEquals(captures, f.access.captures); assertEquals(creates, f.creates().size());

		f.press(200); f.release(210);
		assertEquals(captures + 1, f.access.captures);
		assertEquals(creates + 1, f.creates().size());
		assertFalse(f.runtime.hasCompatibilityState());
	}

	@Test void authorizedPropertyWithPendingResetPreviewLogsPresentationNotReadyWithoutExtraWork() {
		Fixture f = new Fixture();
		f.access.snapshot = TargetSnapshotFactory.entity(DIMENSION, new UUID(5, 6), "minecraft:pig");
		f.reset(2, Set.of(PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH));
		f.press(0); f.frame(250, true); f.prime(); f.enter("content", 260);
		String property = f.menu().choices().stream().filter(choice -> choice.label().contains(PresentationBasic.HEALTH))
			.findFirst().orElseThrow().id();
		f.focus(property, 460);
		f.previewObserve = false;
		f.reset(3, Set.of(PresentationBasic.HEALTH, PresentationBasic.MAX_HEALTH));
		assertTrue(f.presentation.propertyAllowed("entity",
			PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.HEALTH)),
			"the reset keeps the property authorization even while the new preview is pending");
		f.preview.tick();
		assertEquals(PreviewFieldAccess.Missing.PENDING, f.preview.projection().orElseThrow().fields()
			.get(PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.HEALTH)),
			"establish the reset view's one-shot request before checking release adds no polling");

		long previewReadsBefore = f.sent.stream().filter(packet -> packet instanceof PresentationPreviewC2SPacket preview
			&& preview.kind() == PresentationPreviewC2SPacket.Kind.READ).count();
		assertEquals(1, previewReadsBefore, "the fixture has exactly one genuinely pending preview read");
		int validationsBefore = f.validations.size();
		int liveRequestsBefore = f.access.liveRequests.size();
		int cancellationsBefore = f.access.cancellations;

		f.release(470);

		assertTrue(f.creates().isEmpty());
		assertTrue(f.errors.isEmpty());
		assertEquals(validationsBefore + 1, f.validations.size(), "the release validator runs once, as before");
		assertEquals(liveRequestsBefore, f.access.liveRequests.size(), "diagnostic classification adds no precise scan");
		assertEquals(cancellationsBefore, f.access.cancellations, "no cancellation was requested");
		assertEquals(previewReadsBefore, f.sent.stream().filter(packet -> packet instanceof PresentationPreviewC2SPacket preview
			&& preview.kind() == PresentationPreviewC2SPacket.Kind.READ).count(),
			"the pending preview is not polled again at release");
		assertTrue(f.pingLogger.messages().stream().anyMatch(message ->
			message.contains("reason=presentation-not-ready") && message.contains("route=selector")));
		assertFalse(f.pingLogger.messages().stream().anyMatch(message -> message.contains("reason=property-denied")));
		assertTrue(f.runtime.selectorSnapshot().isEmpty());
	}

	private static final class RecordingLogger implements PingInteractionLogger {
		private final List<String> messages = new ArrayList<>();

		@Override public void debug(String message, Object... args) {
			String rendered = message;
			for (Object arg : args) {
				int placeholder = rendered.indexOf("{}");
				if (placeholder >= 0) rendered = rendered.substring(0, placeholder) + arg + rendered.substring(placeholder + 2);
			}
			messages.add(rendered);
		}

		@Override public void debug(java.util.function.Supplier<@org.jetbrains.annotations.Nullable String> detail) {
			messages.add(String.valueOf(detail.get()));
		}

		List<String> messages() { return List.copyOf(messages); }
	}

	@Test void worldChangeAndNewHoldFenceLateLiveCompletionWithoutTouchingNewPreview() {
		Fixture f = new Fixture(); f.press(0); f.frame(250, true); f.prime(); f.access.distantPending = true; f.enter("precise", 260);
		var late = f.access.distantCompletions.getFirst(); f.access.world = new Object(); f.tick(450);
		assertTrue(f.runtime.selectorSnapshot().isEmpty()); assertEquals(0, f.inventory.stats().previewChannels());
		f.access.distantPending = false; f.press(500); long request = f.request(); f.frame(750, true);
		late.accept(PreciseCaptureRefresh.Outcome.missing()); assertEquals(request, f.request()); assertEquals(1, f.inventory.stats().previewChannels());
		f.prime(); f.enter("intent", 760); f.focus("intent:attention", 960); f.release(970);
		assertEquals(CHEST.target(), f.creates().getFirst().target()); assertEquals(0, f.access.cancellations);
	}
}
