package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.LongPressCompatibilityController.BaselineOutcome;
import nx.pingwheel.common.client.LongPressCompatibilityController.DispatchOutcome;
import nx.pingwheel.common.client.rate.ClientCreateRateLimiter;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.PingCaptureCoordinator;
import nx.pingwheel.common.interaction.PingCaptureLogger;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.CancelCandidatePicker;
import nx.pingwheel.common.interaction.cancel.CancellationContext;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import nx.pingwheel.common.interaction.state.PingInteractionStateMachine;
import nx.pingwheel.common.interaction.state.TargetGoneReason;
import nx.pingwheel.common.interaction.state.TargetValidation;
import nx.pingwheel.common.interaction.wheel.WheelSelection;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.*;

/** Real baseline lifecycle and courtesy limiter; only the final sender/transport readiness are recording ports. */
class LongPressCompatibilityDispatchTest {
	private static final CapturedRay FIRST_RAY = new CapturedRay(new WorldVector(1, 0, 0), new WorldVector(0, 0, 1));
	private static final CapturedRay SECOND_RAY = new CapturedRay(new WorldVector(2, 0, 0), new WorldVector(0, 0, 1));
	private static final long HOLD = 200;
	private static final long SLICE = 20;

	@Test
	void rejectedSynchronousDefaultCreateCannotSeedRapidInput() {
		Harness h = new Harness(false, true, true, TargetValidation.valid());
		h.click(0);
		assertTrue(h.port.sent.isEmpty());
		assertFalse(h.controller.hasPendingState());
		h.at(15); h.controller.onPress(15);
		assertEquals(2, h.port.captureStarts.size(), "second independent press remains ordinary");
		assertEquals(0, h.port.frozenRayStarts);
		assertSame(SECOND_RAY, h.port.captureStarts.get(1).ray());
	}

	@Test
	void rejectedOrUnreadyAsyncDefaultCreateDropsBothRapidAndDeferredPresses() {
		for (boolean ready : new boolean[] { false, true }) {
			for (long secondPress : new long[] { SLICE - 1, SLICE + 1 }) {
				Harness h = new Harness(true, ready, ready, TargetValidation.valid());
				h.click(0);
				h.at(secondPress); h.controller.onPress(secondPress); h.controller.onRelease();
				h.port.completeFirst();
				h.frame(secondPress + 1);
				assertTrue(h.port.sent.isEmpty());
				assertTrue(h.port.recorded.isEmpty());
				assertEquals(1, h.port.captureStarts.size());
				assertEquals(0, h.port.frozenRayStarts);
				assertFalse(h.controller.hasPendingState());
				h.frame(500);
				assertEquals(1, h.port.captureStarts.size(), "no queued/retried create or capture");
			}
		}
	}

	@Test
	void targetGoneDropsDeferredPressWithoutSenderOrSecondCapture() {
		Harness h = new Harness(true, true, false, TargetValidation.gone(TargetGoneReason.BLOCK_REPLACED));
		h.click(0);
		h.at(SLICE + 1); h.controller.onPress(SLICE + 1); h.controller.onRelease();
		h.port.completeFirst();
		h.frame(SLICE + 2);
		assertEquals(1, h.port.feedback.size());
		assertTrue(h.port.sent.isEmpty());
		assertEquals(1, h.port.captureStarts.size());
		assertFalse(h.controller.hasPendingState());
	}

	@Test
	void successfulAsyncDefaultDispatchStartsRapidWithSecondRayAndFirstTimestamp() {
		Harness h = new Harness(true, true, false, TargetValidation.valid());
		h.click(0);
		h.at(SLICE - 1); h.controller.onPress(SLICE - 1); h.controller.onRelease();
		h.port.completeFirst();
		h.frame(SLICE);
		assertEquals(1, h.port.sent.size());
		assertEquals(1, h.port.recorded.size());
		assertEquals(1, h.port.frozenRayStarts);
		assertEquals(0L, h.port.captureStarts.get(1).timestamp());
		assertSame(SECOND_RAY, h.port.captureStarts.get(1).ray());
		assertEquals(List.of("capture", "record", "send", "capture"), h.port.events);
	}

	@Test
	void successfulDeferredDispatchStartsFreshCaptureThenAppliesRememberedRelease() {
		Harness h = new Harness(true, true, false, TargetValidation.valid());
		h.click(0);
		h.at(SLICE + 1); h.controller.onPress(SLICE + 1); h.controller.onRelease();
		h.port.completeFirst();
		h.frame(SLICE + 2);
		assertEquals(2, h.port.sent.size(), "first default and released fresh default are distinct sends");
		assertEquals(2, h.port.recorded.size());
		assertEquals(1, h.port.frozenRayStarts);
		assertEquals(SLICE + 1, h.port.captureStarts.get(1).timestamp());
		assertSame(SECOND_RAY, h.port.sent.get(1).context().ray());
		assertNotEquals(h.port.sent.get(0).context().token(), h.port.sent.get(1).context().token());
		assertEquals(List.of("capture", "record", "send", "capture", "record", "send"), h.port.events);
		h.frame(500);
		assertEquals(2, h.port.sent.size(), "no first-action replay or duplicate fresh release");
	}

	@Test
	void actualMenuOpeningDisqualifiesEvenASentDefaultType() {
		Harness h = new Harness(false, true, false, TargetValidation.valid());
		h.controller.onPress(0);
		h.at(HOLD); h.controller.onRenderFrame(true);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, h.port.machine.phase());
		h.port.selection = WheelSelection.sector(h.port.active.currentContext().orElseThrow().resolvedTarget()
			.targetType().defaultPingType());
		h.controller.onRelease();
		assertEquals(1, h.port.sent.size());
		assertFalse(h.controller.hasPendingState());
		h.at(HOLD + SLICE); h.controller.onPress(HOLD + SLICE);
		assertEquals(0, h.port.frozenRayStarts);
	}

	private static final class Harness {
		final Clock clock = new Clock();
		final Port port;
		final LongPressCompatibilityController controller;
		Harness(boolean delayedFirst, boolean ready, boolean exhaustLimiter, TargetValidation verdict) {
			port = new Port(clock, delayedFirst, ready, exhaustLimiter, verdict);
			controller = new LongPressCompatibilityController(port, clock, () -> true, () -> HOLD, () -> SLICE,
				PingInteractionLogger.noop());
		}
		void at(long now) { clock.now = now; }
		void click(long now) { at(now); controller.onPress(now); controller.onRelease(); }
		void frame(long now) { at(now); controller.onRenderFrame(false); }
	}

	private record CaptureStart(InteractionToken token, long timestamp, CapturedRay ray) {}
	private static final class Port implements LongPressCompatibilityController.InteractionPort {
		final Clock clock;
		final ActiveInteraction active = new ActiveInteraction();
		final PingCaptureCoordinator coordinator = new PingCaptureCoordinator(
			DefaultTargetResolver.builtIn(TargetResolutionLogger.noop()), active, PingCaptureLogger.noop());
		final PingInteractionStateMachine machine;
		final ClientCreateRateLimiter limiter;
		final boolean delayedFirst;
		final boolean ready;
		final List<CaptureStart> captureStarts = new ArrayList<>();
		final List<Long> recorded = new ArrayList<>();
		final List<PingInteractionAction.CreatePing> sent = new ArrayList<>();
		final List<PingInteractionAction.TargetGone> feedback = new ArrayList<>();
		final List<String> events = new ArrayList<>();
		boolean menuOpened;
		int raySamples;
		int frozenRayStarts;
		WheelSelection selection = WheelSelection.NONE;
		Port(Clock clock, boolean delayedFirst, boolean ready, boolean exhaustLimiter, TargetValidation verdict) {
			this.clock = clock;
			this.delayedFirst = delayedFirst;
			this.ready = ready;
			machine = new PingInteractionStateMachine(coordinator, active, clock, target -> verdict,
				new CancelCandidatePicker(), PingInteractionLogger.noop(), () -> HOLD, () -> 1000L);
			limiter = new ClientCreateRateLimiter(clock, new ClientRateLimitPolicy(exhaustLimiter ? 1 : 3, 1000));
			if (exhaustLimiter) assertTrue(limiter.tryAcquire(), "explicitly consume fixture allowance before first action");
		}
		public Optional<CapturedRay> capturePressRay() {
			return Optional.of(raySamples++ == 0 ? FIRST_RAY : SECOND_RAY);
		}
		public Optional<PingInteractionAction> pressAt(long now) { return start(now, capturePressRay().orElseThrow()); }
		public Optional<PingInteractionAction> pressAt(long now, CapturedRay ray) {
			frozenRayStarts++;
			return start(now, ray);
		}
		Optional<PingInteractionAction> start(long now, CapturedRay ray) {
			menuOpened = false;
			selection = WheelSelection.NONE;
			CaptureStart start = new CaptureStart(machine.pressAt(now), now, ray);
			captureStarts.add(start);
			events.add("capture");
			if (!delayedFirst || captureStarts.size() > 1) complete(start);
			return Optional.empty();
		}
		void completeFirst() { complete(captureStarts.getFirst()); }
		void complete(CaptureStart start) {
			assertTrue(coordinator.complete(start.token(), TargetSnapshotFactory.block("minecraft:overworld",
				(int) start.ray().origin().x(), 0, 0, "minecraft:stone"), start.ray()).isPresent());
		}
		public Optional<PingInteractionAction> release() { return releaseOutcome().action(); }
		public BaselineOutcome releaseOutcome() { return advance(false, clock.now); }
		public Optional<PingInteractionAction> presentFrame(boolean held) { return presentFrameOutcome(held).action(); }
		public BaselineOutcome presentFrameOutcome(boolean held) { return presentFrameOutcome(held, clock.now); }
		public BaselineOutcome presentFrameOutcome(boolean held, long now) {
			machine.presentFrameAt(held, now);
			menuOpened |= machine.phase() == PingInteractionPhase.WHEEL_OPEN;
			return advance(held || machine.phase() == PingInteractionPhase.WHEEL_OPEN, now);
		}
		BaselineOutcome advance(boolean held, long now) {
			var context = new CancellationContext(new UUID(0, 1), "minecraft:overworld", new WorldVector(0, 0, 0),
				new WorldVector(0, 0, 1), List.of());
			Optional<PingInteractionAction> action = machine.updateAt(held, selection, context, now);
			DispatchOutcome outcome = DispatchOutcome.NOT_SENT;
			if (action.orElse(null) instanceof PingInteractionAction.CreatePing create && ready && limiter.tryAcquire()) {
				recorded.add(create.context().token().sequence()); events.add("record");
				sent.add(create); events.add("send");
				outcome = DispatchOutcome.CREATE_SENT;
			} else if (action.orElse(null) instanceof PingInteractionAction.TargetGone gone) feedback.add(gone);
			return new BaselineOutcome(action, outcome, menuOpened);
		}
		public void abort() { machine.abort(); }
		public PingInteractionPhase phase() { return machine.phase(); }
	}
	private static final class Clock implements InteractionTimeSource {
		long now;
		public long nowMillis() { return now; }
	}
}
