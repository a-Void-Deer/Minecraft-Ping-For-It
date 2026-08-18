package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.TargetResolver;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongPressCompatibilityControllerTest {

	@Test
	void oneShortClickDispatchesImmediatelyAndExpiresItsSeedWithoutStartingVirtualInput() {
		Harness h = enabledHarness(200L, 20L);

		h.click(0L);
		assertEquals(1, h.port.defaultCreates);
		assertEquals(0, h.port.virtualStarts);

		h.frame(21L);
		assertFalse(h.controller.hasPendingState());
	}

	@Test
	void rapidSecondClickIsSwallowedAndAbandonedWithoutReplay() {
		Harness h = enabledHarness(200L, 20L);

		h.click(0L);
		h.controller.onPress(15L);
		assertEquals(PingInteractionPhase.PRESSED, h.port.phase,
			"the second click must not open the wheel synchronously");
		h.controller.onRelease();
		h.frame(36L);

		assertEquals(1, h.port.defaultCreates);
		assertEquals(1, h.port.virtualStarts);
		assertEquals(1, h.port.aborts);
		assertTrue(h.logger.messages().stream().anyMatch(message -> message.contains("abandoned")));
	}

	@Test
	void adjacentRapidClicksChainByAdjacentDeltaAndOnlyAFormedWheelCanCommit() {
		Harness h = enabledHarness(200L, 20L);
		long[] presses = { 0L, 15L, 31L, 47L, 63L, 79L, 95L, 111L, 127L, 143L, 159L, 175L, 191L };

		h.click(presses[0]);
		for (int i = 1; i < presses.length; i++) {
			h.controller.onPress(presses[i]);
			h.controller.onRelease();
		}

		assertEquals(1, h.port.defaultCreates, "suppressed clicks must not become default creates");
		assertEquals(1, h.port.virtualStarts);

		h.port.commitVirtualWheel = true;
		h.frame(200L);
		assertEquals(PingInteractionPhase.WHEEL_OPEN, h.port.phase);
		h.frame(212L);

		assertEquals(2, h.port.totalCreates());
		assertEquals(1, h.port.virtualWheelCreates);
		assertTrue(h.logger.messages().stream().anyMatch(message -> message.contains("formed")));
	}

	@Test
	void gapGreaterThanSliceEndsTheOldSequenceBeforeAFreshNormalClick() {
		Harness h = enabledHarness(200L, 20L);

		h.click(0L);
		h.controller.onPress(15L);
		h.controller.onRelease();
		h.controller.onPress(31L);
		h.controller.onRelease();
		h.controller.onPress(70L);
		h.controller.onRelease();

		assertEquals(2, h.port.defaultCreates);
		assertEquals(1, h.port.aborts);
		assertTrue(h.logger.messages().stream().anyMatch(message -> message.contains("abandoned")));
	}

	@Test
	void disabledModeIsAnExactBaselinePassThrough() {
		Harness h = harness(false, 200L, 20L);

		for (long press : new long[] { 0L, 15L, 31L }) {
			h.click(press);
		}

		assertEquals(3, h.port.defaultCreates);
		assertEquals(3, h.port.normalStarts);
		assertEquals(0, h.port.virtualStarts);
		assertFalse(h.controller.hasPendingState());
	}

	@Test
	void exactSliceDeltaRemainsEligibleButAStrictlyLargerGapAbandons() {
		Harness h = enabledHarness(200L, 20L);

		h.click(0L);
		h.controller.onPress(20L);
		h.controller.onRelease();
		h.frame(40L);
		assertEquals(0, h.port.aborts, "the inclusive boundary must remain eligible");

		h.frame(41L);
		assertEquals(1, h.port.aborts);
	}

	@Test
	void asyncFirstCaptureIsNeverSupersededAndStartsVirtualOnlyAfterFirstCreate() {
		Harness h = enabledHarness(200L, 20L);
		h.port.asyncFirstCapture = true;

		h.controller.onPress(0L);
		h.controller.onRelease();
		h.controller.onPress(15L);
		h.controller.onRelease();
		assertEquals(0, h.port.defaultCreates);
		assertEquals(0, h.port.virtualStarts);

		h.port.firstCaptureReady = true;
		h.frame(20L);
		assertEquals(1, h.port.defaultCreates);
		assertEquals(1, h.port.virtualStarts);
		assertEquals(0L, h.port.virtualPressTimestamps.get(0));

		h.frame(36L);
		assertEquals(1, h.port.aborts);
	}

	@Test
	void deferredFreshPressWithoutAnImmutableRayIsDroppedInsteadOfReRaycasting() {
		Harness h = enabledHarness(200L, 20L);
		h.port.asyncFirstCapture = true;
		h.port.captureRayAvailable = false;

		h.controller.onPress(0L);
		h.controller.onRelease();
		h.controller.onPress(30L);
		h.controller.onRelease();

		h.port.firstCaptureReady = true;
		h.frame(31L);

		assertEquals(1, h.port.defaultCreates, "the first normal ping must still dispatch");
		assertEquals(0, h.port.virtualStarts);
		assertEquals(1, h.port.normalStarts, "the deferred click must not start a late raycast");
		assertFalse(h.controller.hasPendingState());
	}

	@Test
	void multipleRawEdgesBeforeAFrameUseTheirRawAdjacentTimestamps() {
		Harness h = enabledHarness(200L, 20L);

		h.click(0L);
		h.controller.onPress(15L);
		h.controller.onRelease();
		h.controller.onPress(31L);
		h.controller.onRelease();

		assertEquals(1, h.port.virtualStarts);
		assertEquals(1, h.port.defaultCreates);
		assertEquals(0, h.port.aborts);
	}

	@Test
	void disablingModeOnTheNextFrameAbortsVirtualInteractionAndDoesNotReplay() {
		Harness h = enabledHarness(200L, 20L);
		h.click(0L);
		h.controller.onPress(15L);
		h.controller.onRelease();
		assertTrue(h.controller.hasPendingState());

		h.mode.set(false);
		h.frame(16L);
		assertEquals(1, h.port.aborts);
		assertFalse(h.controller.hasPendingState());
		assertEquals(1, h.port.defaultCreates);

		h.click(100L);
		assertEquals(2, h.port.defaultCreates);
	}

	@Test
	void loggerContainsOnlyScalarCompatibilityFields() {
		Harness h = enabledHarness(200L, 20L);
		h.click(0L);
		h.controller.onPress(15L);
		h.controller.onRelease();
		h.frame(36L);

		String joined = String.join("|", h.logger.messages());
		assertTrue(joined.contains("deltaMillis=15"));
		assertTrue(joined.contains("thresholdMillis=20"));
		assertTrue(joined.contains("count=2"));
		assertFalse(joined.contains("minecraft:"));
		assertFalse(joined.contains("{"));
	}

	private static Harness enabledHarness(long holdMillis, long sliceMillis) {
		return harness(true, holdMillis, sliceMillis);
	}

	private static Harness harness(boolean enabled, long holdMillis, long sliceMillis) {
		ManualClock clock = new ManualClock();
		AtomicBoolean mode = new AtomicBoolean(enabled);
		AtomicLong hold = new AtomicLong(holdMillis);
		AtomicLong slice = new AtomicLong(sliceMillis);
		RecordingLogger logger = new RecordingLogger();
		FakePort port = new FakePort(clock);
		LongPressCompatibilityController controller = new LongPressCompatibilityController(
			port,
			clock,
			mode::get,
			hold::get,
			slice::get,
			logger);
		return new Harness(controller, port, clock, mode, hold, slice, logger);
	}

	private record Harness(
		LongPressCompatibilityController controller,
		FakePort port,
		ManualClock clock,
		AtomicBoolean mode,
		AtomicLong hold,
		AtomicLong slice,
		RecordingLogger logger
	) {
		void click(long timestamp) {
			controller.onPress(timestamp);
			controller.onRelease();
		}

		void frame(long timestamp) {
			clock.now = timestamp;
			controller.onRenderFrame(true);
		}
	}

	private static final class ManualClock implements InteractionTimeSource {
		long now;

		@Override
		public long nowMillis() {
			return now;
		}
	}

	private static final class FakePort implements LongPressCompatibilityController.InteractionPort {
		private static final TargetResolver RESOLVER =
			DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());

		private final ManualClock clock;
		private final ActiveInteraction interaction = new ActiveInteraction();
		private final List<Long> virtualPressTimestamps = new ArrayList<>();
		private PingInteractionPhase phase = PingInteractionPhase.IDLE;
		private long currentPressTimestamp;
		private boolean currentVirtual;
		private long nextToken;
		private boolean firstPending;
		private boolean currentReleaseObserved;
		private boolean firstCaptureReady;
		private boolean asyncFirstCapture;
		private boolean captureRayAvailable = true;
		private boolean commitVirtualWheel;
		private int normalStarts;
		private int virtualStarts;
		private int defaultCreates;
		private int virtualWheelCreates;
		private int aborts;

		private FakePort(ManualClock clock) {
			this.clock = clock;
		}

		@Override
		public Optional<PingInteractionAction> pressAt(long rawPressTimestamp) {
			normalStarts++;
			currentPressTimestamp = rawPressTimestamp;
			currentVirtual = false;
			currentReleaseObserved = false;
			firstPending = asyncFirstCapture && defaultCreates == 0;
			phase = PingInteractionPhase.PRESSED;
			return Optional.empty();
		}

		@Override
		public Optional<PingInteractionAction> pressAt(long rawPressTimestamp, CapturedRay pressRay) {
			virtualStarts++;
			virtualPressTimestamps.add(rawPressTimestamp);
			currentPressTimestamp = rawPressTimestamp;
			currentVirtual = true;
			currentReleaseObserved = false;
			firstPending = false;
			phase = PingInteractionPhase.PRESSED;
			return Optional.empty();
		}

		@Override
		public Optional<CapturedRay> capturePressRay() {
			return captureRayAvailable
				? Optional.of(CapturedRay.defaultRay())
				: Optional.empty();
		}

		@Override
		public Optional<PingInteractionAction> release() {
			currentReleaseObserved = true;
			if (phase == PingInteractionPhase.WHEEL_OPEN) {
				phase = PingInteractionPhase.IDLE;
				if (currentVirtual && commitVirtualWheel) {
					virtualWheelCreates++;
					return Optional.of(createAction());
				}
				return Optional.empty();
			}

			if (phase != PingInteractionPhase.PRESSED) {
				return Optional.empty();
			}

			if (currentVirtual) {
				phase = PingInteractionPhase.IDLE;
				return Optional.empty();
			}

			if (firstPending && !firstCaptureReady) {
				return Optional.empty();
			}

			phase = PingInteractionPhase.IDLE;
			defaultCreates++;
			firstPending = false;
			return Optional.of(createAction());
		}

		@Override
		public Optional<PingInteractionAction> presentFrame(boolean keyDown) {
			if (phase != PingInteractionPhase.PRESSED) {
				return Optional.empty();
			}

			if (currentVirtual) {
				if (clock.now - currentPressTimestamp >= 200L) {
					phase = PingInteractionPhase.WHEEL_OPEN;
				}
				return Optional.empty();
			}

			if (firstPending && firstCaptureReady && !keyDown) {
				phase = PingInteractionPhase.IDLE;
				firstPending = false;
				defaultCreates++;
				return Optional.of(createAction());
			}

			return Optional.empty();
		}

		@Override
		public void abort() {
			if (phase != PingInteractionPhase.IDLE) {
				aborts++;
			}
			phase = PingInteractionPhase.IDLE;
			firstPending = false;
		}

		@Override
		public PingInteractionPhase phase() {
			return phase;
		}

		private int totalCreates() {
			return defaultCreates + virtualWheelCreates;
		}
		private PingInteractionAction.CreatePing createAction() {
			InteractionToken token = interaction.begin();
			var snapshot = TargetSnapshotFactory.location("minecraft:overworld", 0, 0, 0);
			ResolvedTarget resolved = RESOLVER.resolve(snapshot.target(), snapshot.matchContext());
			CapturedPingContext context = new CapturedPingContext(token, resolved);
			PingType type = resolved.targetType().defaultPingType();
			return new PingInteractionAction.CreatePing(context, type);
		}
	}

	private static final class RecordingLogger implements PingInteractionLogger {
		private final List<String> messages = new ArrayList<>();

		@Override
		public void debug(String message, Object... args) {
			String output = message;
			for (Object arg : args) {
				int placeholder = output.indexOf("{}");
				if (placeholder >= 0) {
					output = output.substring(0, placeholder)
						+ String.valueOf(arg)
						+ output.substring(placeholder + 2);
				}
			}
			messages.add(output);
		}

		private List<String> messages() {
			return List.copyOf(messages);
		}
	}
}
