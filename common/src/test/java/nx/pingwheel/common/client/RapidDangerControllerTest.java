package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RapidDangerControllerTest {

	private static final CapturedRay RAY_A = new CapturedRay(
		new WorldVector(0, 0, 0), new WorldVector(0, 0, 1));
	private static final CapturedRay RAY_B = new CapturedRay(
		new WorldVector(5, 5, 5), new WorldVector(1, 0, 0));
	private static final CapturedRay EXTERNALLY_TURNED_RAY = new CapturedRay(
		new WorldVector(0, 0, 0), new WorldVector(1, 0, 0));

	@Test void firstShortCreateIsNeverDelayedByTheAdjacentWindow() {
		Harness h = new Harness();
		h.controller.onPress(0);
		h.controller.onRelease();

		assertEquals(1, h.port.normalCreates);
		assertEquals(1, h.port.normalStarts);
		assertEquals(0, h.port.dangerCreates, "nothing is held back or sent waiting for a potential double click");
	}

	@Test void pendingFirstCaptureFlushesEveryCompletedTapAfterTheLocalCreateIsSent() {
		Harness h = new Harness();
		h.port.firstPending = true;
		h.controller.onPress(0);
		h.controller.onRelease();
		for (long down : new long[] { 50, 100, 150, 200 }) {
			h.controller.onPress(down);
			h.controller.onRelease();
		}
		assertEquals(0, h.port.dangerCreates, "no Danger before the first local create dispatch");

		h.port.firstReady = true;
		h.controller.onRenderFrame(false, 5000);
		assertEquals(1, h.port.normalCreates);
		assertEquals(4, h.port.dangerCreates,
			"every admitted tap is retained; the later completion time never expires it");
		assertEquals(4, h.port.dangerPressTimes.size());
		assertTrue(h.port.dangerPressTimes.stream().allMatch(time -> time == 5000),
			"flush does not re-evaluate the original per-down window");
	}

	@Test void everyDangerTapGetsAFreshTokenAndRequestIdentity() {
		Harness h = new Harness();
		h.controller.onPress(0);
		h.controller.onRelease();
		for (long down : new long[] { 50, 100, 150, 200 }) {
			h.controller.onPress(down);
			h.controller.onRelease();
		}

		assertEquals(4, h.port.dangerCreates);
		assertEquals(4, h.port.dangerTokens.stream().distinct().count());
		assertTrue(h.port.dangerTokens.stream().allMatch(token -> token != h.port.firstToken));
		assertTrue(h.port.dangerTargets.stream().allMatch(target -> target.equals(h.port.firstTarget)));
	}

	@Test void reentrantAbortFromTheSendPathStopsTheRemainingFlush() {
		Harness h = new Harness();
		h.port.firstPending = true;
		h.controller.onPress(0);
		h.controller.onRelease();
		h.controller.onPress(50);
		h.controller.onRelease();
		h.controller.onPress(100);
		h.controller.onRelease();
		h.port.abortOnFirstDanger = h.controller::reset;

		h.port.firstReady = true;
		h.controller.onRenderFrame(false, 5000);

		assertEquals(1, h.port.dangerCreates, "the reentrant abort ends the sequence before the next pending tap");
	}

	@Test void nonQualifyingFirstOutcomeClearsPendingTapsWithoutFallback() {
		Harness h = new Harness();
		h.port.firstPending = true;
		h.controller.onPress(0);
		h.controller.onRelease();
		h.controller.onPress(50);
		h.controller.onRelease();
		h.port.firstSendOutcome = LongPressCompatibilityController.DispatchOutcome.NOT_SENT;
		h.port.firstReady = true;
		h.controller.onRenderFrame(false, 5000);

		assertEquals(0, h.port.dangerCreates);
		assertFalse(h.controller.hasPendingState());
		assertEquals(1, h.port.normalStarts, "no retry or fallback interaction is fabricated");
	}

	@Test void aLongCandidateUsesItsOwnFrozenPressTimeAndRayForTheWheel() {
		Harness h = new Harness();
		h.controller.onPress(0);
		h.controller.onRelease();
		h.port.nextCaptureRay = RAY_B;
		h.controller.onPress(100);
		h.controller.onRenderFrame(true, 349);
		assertEquals(PingInteractionPhase.PRESSED, h.port.phase, "the first press time is never the hold baseline");
		h.controller.onRenderFrame(true, 350);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, h.port.phase);
		assertEquals(100, h.port.candidatePressTimestamp);
		assertEquals(RAY_B, h.port.candidatePressRay);

		h.controller.onRelease();
		assertEquals(0, h.port.dangerCreates, "an actually opened wheel owns the release");
		assertEquals(1, h.port.normalCreates);
	}

	@Test void releasedCandidateWithoutAnOpenedWheelRemainsAFrozenDangerTap() {
		Harness h = new Harness();
		h.controller.onPress(0);
		h.controller.onRelease();
		h.port.nextCaptureRay = RAY_B;
		h.controller.onPress(100);
		h.controller.onRelease();

		assertEquals(1, h.port.dangerCreates);
		assertEquals(RAY_B, h.port.capturedCandidateRay);
		assertEquals(1, h.port.normalCreates);
	}

	@Test void lookGenerationResetInvalidatesTheSequenceInsteadOfReportingZeroAngle() {
		Harness h = new Harness();
		h.controller.onPress(0);
		h.controller.onRelease();
		h.port.yaw = 10.0;
		h.port.generation = 2L;
		h.controller.onPress(50);
		h.controller.onRelease();

		assertEquals(0, h.port.dangerCreates);
		assertEquals(2, h.port.normalStarts, "a stale generation starts a fresh ordinary press");
	}

	@Test void liveCompatibilityDisablesRecognitionWithoutAbortingAnOrdinaryBaseline() {
		Harness h = new Harness();
		h.port.compatibility = true;
		boolean handled = h.controller.onPress(0);

		assertFalse(handled);
		assertEquals(0, h.port.normalStarts);
		assertEquals(0, h.port.aborts);
	}

	@Test void zeroAngleWithCompatibilityOffKeepsEveryShortPressOrdinary() {
		Harness h = new Harness();
		h.port.compatibility = false;
		h.port.configuredAngleDegrees = 0.0;

		for (long down : new long[] { 0, 50, 100 }) {
			h.controller.onPress(down);
			h.controller.onRelease();
		}

		assertEquals(3, h.port.normalStarts, "every same-direction short press starts an ordinary press");
		assertEquals(3, h.port.normalCreates, "every short release keeps the ordinary default create");
		assertEquals(0, h.port.dangerCreates,
			"zero disables the gesture entirely even when the pointer never moved");
	}

	@Test void angleToleranceRejectsItsBoundaryAndOnlyAdmitsStrictlySmallerTurns() {
		double boundary = pureYawAngleDegrees(12.5);

		Harness atBoundary = secondTapWithAngle(boundary, 12.5);
		assertEquals(0, atBoundary.port.dangerCreates,
			"a pointer turn exactly equal to the tolerance is not strictly inside");
		assertEquals(2, atBoundary.port.normalStarts, "the rejected tap starts a fresh ordinary press");
		assertEquals(2, atBoundary.port.normalCreates);

		Harness inside = secondTapWithAngle(boundary + 0.01, 12.5);
		assertEquals(1, inside.port.dangerCreates, "a strictly smaller turn qualifies");
		assertEquals(1, inside.port.normalCreates, "the first press keeps its ordinary create");

		Harness outside = secondTapWithAngle(boundary - 0.01, 12.5);
		assertEquals(0, outside.port.dangerCreates, "a strictly larger turn is rejected");
		assertEquals(2, outside.port.normalStarts);
		assertEquals(2, outside.port.normalCreates);
	}

	@Test void adjacentWindowBoundaryQualifiesButTheAngleAlwaysUsesTheFirstBaseline() {
		Harness h = new Harness();
		h.port.configuredWindowMillis = 100;
		h.port.configuredAngleDegrees = 3.5;

		h.controller.onPress(0);
		h.controller.onRelease();
		h.port.yaw = 2.5;
		h.controller.onPress(100);
		h.controller.onRelease();
		assertEquals(1, h.port.dangerCreates,
			"a second down exactly at the adjacent window boundary still qualifies");

		h.port.yaw = 5.0;
		h.controller.onPress(200);
		h.controller.onRelease();

		assertEquals(1, h.port.dangerCreates,
			"the third press is inside the adjacent window but 5 degrees from the first baseline");
		assertEquals(2, h.port.normalStarts, "the rejected third press starts a fresh ordinary press");
		assertEquals(2, h.port.normalCreates);
	}

	@Test void oneMillisecondPastTheAdjacentWindowStartsANewOrdinaryPress() {
		Harness h = new Harness();
		h.port.configuredWindowMillis = 100;

		h.controller.onPress(0);
		h.controller.onRelease();
		h.controller.onPress(101);
		h.controller.onRelease();

		assertEquals(0, h.port.dangerCreates);
		assertEquals(2, h.port.normalStarts);
		assertEquals(2, h.port.normalCreates);
	}

	@Test void externalCameraTurnWithoutLookInputStillDerivesFromTheFirstTarget() {
		Harness h = new Harness();
		h.controller.onPress(0);
		h.controller.onRelease();
		h.port.nextCaptureRay = EXTERNALLY_TURNED_RAY;
		h.controller.onPress(50);
		h.controller.onRelease();

		assertEquals(1, h.port.dangerCreates);
		assertEquals(EXTERNALLY_TURNED_RAY, h.port.capturedCandidateRay,
			"the second press saw the externally turned camera ray");
		assertEquals(h.port.firstTarget, h.port.dangerTargets.get(0),
			"recognition still reuses the first press target");
		assertEquals(RAY_A, h.port.dangerRays.get(0),
			"the derived create keeps the first frozen ray");
	}

	@Test void lookInputBeyondToleranceRejectsEvenWhenTheLiveCameraRayIsUnchanged() {
		Harness h = new Harness();
		h.controller.onPress(0);
		h.controller.onRelease();
		h.port.yaw = 10.0;
		h.controller.onPress(50);
		h.controller.onRelease();

		assertEquals(0, h.port.dangerCreates);
		assertEquals(2, h.port.normalStarts, "the rejected tap starts a fresh ordinary press");
		assertEquals(2, h.port.normalCreates);
		assertEquals(RAY_A, h.port.capturedCandidateRay,
			"the live camera ray never moved; active look input alone rejected the tap");
	}

	private static Harness secondTapWithAngle(double thresholdDegrees, double secondYawDegrees) {
		Harness h = new Harness();
		h.port.configuredAngleDegrees = thresholdDegrees;
		h.controller.onPress(0);
		h.controller.onRelease();
		h.port.yaw = secondYawDegrees;
		h.controller.onPress(50);
		h.controller.onRelease();
		return h;
	}

	/**
	 * Independent pure-yaw geometry for the fixed unit +Z first ray: a yaw-only
	 * input rotates that ray by the shortest angle {@code atan2(|sin yaw|, cos yaw)}.
	 * This is not the production recognition predicate and uses no controller
	 * output, so an equality threshold computed here is a genuine boundary.
	 */
	private static double pureYawAngleDegrees(double yawDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		return Math.toDegrees(Math.atan2(Math.abs(Math.sin(yaw)), Math.cos(yaw)));
	}

	private static final class Harness {
		private final ManualClock clock = new ManualClock();
		private final FakePort port = new FakePort(clock);
		private final RapidDangerController controller = new RapidDangerController(port);

		private Harness() {
			port.controller = controller;
		}
	}

	private static final class ManualClock {
		private long now;

		private long nowMillis() { return now; }
	}

	private static final class FakePort implements RapidDangerController.Port {
		private static final nx.pingwheel.common.interaction.TargetSnapshot SNAPSHOT =
			TargetSnapshotFactory.block("minecraft:overworld", 1, 2, 3,
				"minecraft:chest", true, BlockFace.WEST);
		private static final ResolvedTarget TARGET = DefaultTargetResolver.builtIn(
			TargetResolutionLogger.noop()).resolve(SNAPSHOT.target(), SNAPSHOT.matchContext());

		private final ManualClock clock;
		private final ActiveInteraction interactions = new ActiveInteraction();
		private RapidDangerController controller;
		private PingInteractionPhase phase = PingInteractionPhase.IDLE;
		private boolean firstPending;
		private boolean firstReady;
		private LongPressCompatibilityController.DispatchOutcome firstSendOutcome =
			LongPressCompatibilityController.DispatchOutcome.CREATE_SENT;
		private boolean compatibility;
		private int normalStarts;
		private int normalCreates;
		private int dangerCreates;
		private int aborts;
		private long candidatePressTimestamp;
		private CapturedRay candidatePressRay;
		private CapturedRay nextCaptureRay = RAY_A;
		private CapturedRay capturedCandidateRay;
		private double yaw;
		private long generation = 1L;
		private int configuredWindowMillis = 100;
		private double configuredAngleDegrees = 3.5;
		private Runnable abortOnFirstDanger;
		private final List<Long> dangerPressTimes = new ArrayList<>();
		private final List<InteractionToken> dangerTokens = new ArrayList<>();
		private final List<nx.pingwheel.common.domain.Target> dangerTargets = new ArrayList<>();
		private final List<CapturedRay> dangerRays = new ArrayList<>();
		private InteractionToken firstToken;
		private nx.pingwheel.common.domain.Target firstTarget;

		private FakePort(ManualClock clock) {
			this.clock = clock;
		}

		@Override public Optional<PingInteractionAction> press(long rawPressTimestamp) {
			normalStarts++;
			phase = PingInteractionPhase.PRESSED;
			return Optional.empty();
		}

		@Override public Optional<PingInteractionAction> press(long rawPressTimestamp, CapturedRay pressRay) {
			candidatePressTimestamp = rawPressTimestamp;
			candidatePressRay = pressRay;
			phase = PingInteractionPhase.PRESSED;
			return Optional.empty();
		}

		@Override public Optional<CapturedRay> capturePressRay() {
			capturedCandidateRay = nextCaptureRay;
			return Optional.of(nextCaptureRay);
		}

		@Override public LongPressCompatibilityController.BaselineOutcome releaseOutcome() {
			if (phase == PingInteractionPhase.WHEEL_OPEN) {
				phase = PingInteractionPhase.IDLE;
				return new LongPressCompatibilityController.BaselineOutcome(Optional.empty(),
					LongPressCompatibilityController.DispatchOutcome.NOT_SENT, true);
			}
			if (phase != PingInteractionPhase.PRESSED) {
				return new LongPressCompatibilityController.BaselineOutcome(Optional.empty(),
					LongPressCompatibilityController.DispatchOutcome.NOT_SENT, false);
			}
			if (firstPending && !firstReady) {
				return new LongPressCompatibilityController.BaselineOutcome(Optional.empty(),
					LongPressCompatibilityController.DispatchOutcome.NOT_SENT, false);
			}
			phase = PingInteractionPhase.IDLE;
			if (firstPending) {
				firstPending = false;
				normalCreates++;
				return new LongPressCompatibilityController.BaselineOutcome(
					Optional.of(firstCreate()), firstSendOutcome, false);
			}
			normalCreates++;
			return new LongPressCompatibilityController.BaselineOutcome(
				Optional.of(firstCreate()), LongPressCompatibilityController.DispatchOutcome.CREATE_SENT, false);
		}

		@Override public LongPressCompatibilityController.BaselineOutcome presentFrameOutcome(
			boolean keyDown, long frameTimeMillis) {
			return presentFrameOutcome(keyDown, frameTimeMillis, frameTimeMillis);
		}

		@Override public LongPressCompatibilityController.BaselineOutcome presentFrameOutcome(
			boolean keyDown, long frameTimeMillis, long observedTimeMillis) {
			clock.now = observedTimeMillis;
			if (phase == PingInteractionPhase.PRESSED) {
				if (firstPending) {
					if (!firstReady) {
						return new LongPressCompatibilityController.BaselineOutcome(Optional.empty(),
							LongPressCompatibilityController.DispatchOutcome.NOT_SENT, false);
					}
					phase = PingInteractionPhase.IDLE;
					firstPending = false;
					normalCreates++;
					return new LongPressCompatibilityController.BaselineOutcome(
						Optional.of(firstCreate()), firstSendOutcome, false);
				}
				if (candidatePressRay != null && keyDown
					&& frameTimeMillis - candidatePressTimestamp >= 250L) {
					phase = PingInteractionPhase.WHEEL_OPEN;
					return new LongPressCompatibilityController.BaselineOutcome(Optional.empty(),
						LongPressCompatibilityController.DispatchOutcome.NOT_SENT, true);
				}
			}
			return new LongPressCompatibilityController.BaselineOutcome(Optional.empty(),
				LongPressCompatibilityController.DispatchOutcome.NOT_SENT,
				phase == PingInteractionPhase.WHEEL_OPEN);
		}

		@Override public void abortInteraction() {
			if (phase != PingInteractionPhase.IDLE) aborts++;
			phase = PingInteractionPhase.IDLE;
		}

		@Override public Optional<PingInteractionAction> frozenDangerTap(long observedTimeMillis,
			CapturedPingContext firstCapture, PingType dangerType) {
			if (phase != PingInteractionPhase.IDLE)
				throw new IllegalStateException("frozen tap requires idle");
			InteractionToken token = interactions.begin();
			phase = PingInteractionPhase.IDLE;
			dangerCreates++;
			dangerPressTimes.add(observedTimeMillis);
			dangerTokens.add(token);
			dangerTargets.add(firstCapture.resolvedTarget().target());
			dangerRays.add(firstCapture.ray());
			if (abortOnFirstDanger != null && dangerCreates == 1) abortOnFirstDanger.run();
			return Optional.of(new PingInteractionAction.CreatePing(
				new CapturedPingContext(token, firstCapture.resolvedTarget(), firstCapture.ray()), dangerType));
		}

		@Override public PingInteractionPhase phase() { return phase; }
		@Override public long nowMillis() { return clock.now; }
		@Override public RapidDangerController.LookSample lookSample() {
			return new RapidDangerController.LookSample(yaw, 0.0, generation);
		}
		@Override public boolean compatibilityEnabled() { return compatibility; }
		@Override public int dangerWindowMillis() { return configuredWindowMillis; }
		@Override public double dangerAngleDegrees() { return configuredAngleDegrees; }

		private PingInteractionAction.CreatePing firstCreate() {
			InteractionToken token = interactions.begin();
			firstToken = token;
			firstTarget = TARGET.target();
			return new PingInteractionAction.CreatePing(
				new CapturedPingContext(token, TARGET, RAY_A), TARGET.targetType().defaultPingType());
		}
	}
}
