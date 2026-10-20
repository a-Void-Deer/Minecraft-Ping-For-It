package nx.pingwheel.common.client;

import java.util.Objects;
import java.util.Optional;

import nx.pingwheel.common.client.input.LookInputTracker;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;

/**
 * Rapid adjacent-tap sequencing for the double-click Danger gesture.
 *
 * <p>This controller is used only while long-press compatibility is disabled;
 * the compatibility controller exclusively owns the raw sequence when its mode
 * is live. The ordinary first press still travels through the normal baseline
 * port and produces its normal default {@code CreatePing} at the established
 * short-press boundary. Only after that first create reached the qualifying
 * local dispatch boundary does a following adjacent press become a candidate.
 *
 * <p>A candidate needs no second raycast for its short release: it reuses the
 * frozen first capture through the state machine's narrow frozen-target tap
 * seam, which mints a fresh token and request id and still validates locally.
 * A candidate that stays held starts an ordinary baseline anchored to its own
 * press time and its own press ray, so it can open the wheel normally rather
 * than being backdated to the first press.
 *
 * <p>All state is bounded one-slot state. While the first capture is pending,
 * released candidates contribute only to a bounded pending count; they are
 * never stored as an unbounded click queue and never expire by the later
 * completion time. A failed or non-qualifying first outcome clears every
 * dependency without retrying, falling back, or replaying an input.
 */
public final class RapidDangerController {

	/** The tracker's immutable look-input sample, aliased for this controller's port. */
	public record LookSample(double yawDegrees, double pitchDegrees, long generation) {
		public LookSample {
			if (!Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees))
				throw new IllegalArgumentException("look samples must be finite");
		}

		static LookSample of(LookInputTracker.Sample sample) {
			return sample == null ? null
				: new LookSample(sample.yawDegrees(), sample.pitchDegrees(), sample.generation());
		}
	}

	/**
	 * The runtime-owned interaction port. Outcome methods carry the actual
	 * local dispatch receipt and actual wheel opening; bare actions are never
	 * treated as proof of a successful create.
	 */
	public interface Port {
		Optional<PingInteractionAction> press(long rawPressTimestamp);

		Optional<PingInteractionAction> press(long rawPressTimestamp, CapturedRay pressRay);

		Optional<CapturedRay> capturePressRay();

		LongPressCompatibilityController.BaselineOutcome releaseOutcome();

		LongPressCompatibilityController.BaselineOutcome presentFrameOutcome(boolean keyDown, long frameTimeMillis);

		/**
		 * Presents one frame with the frame timestamp kept separate from the
		 * monotonic observation handed to the state machine. An earlier port
		 * call in the same controller entry (a frozen-tap flush or a candidate
		 * baseline press) may already have read and advanced the machine clock,
		 * so {@code observedTimeMillis} must not predate that observation.
		 */
		LongPressCompatibilityController.BaselineOutcome presentFrameOutcome(
			boolean keyDown, long frameTimeMillis, long observedTimeMillis);

		/** Abandons the current ordinary baseline without inventing a release action. */
		void abortInteraction();

		/** State-machine frozen-target tap: fresh token, same target, one local validation. */
		Optional<PingInteractionAction> frozenDangerTap(long observedTimeMillis,
			CapturedPingContext firstCapture, PingType dangerType);

		PingInteractionPhase phase();

		long nowMillis();

		LookSample lookSample();

		boolean compatibilityEnabled();

		int dangerWindowMillis();

		double dangerAngleDegrees();
	}

	private final Port port;
	private Sequence sequence;
	private Candidate candidate;

	public RapidDangerController(Port port) {
		this.port = Objects.requireNonNull(port, "port");
	}

	/**
	 * Handles one claimed physical press.
	 *
	 * @return {@code false} only when live compatibility mode took the edge
	 *         over, so the runtime must route it through the compatibility
	 *         controller instead
	 */
	public boolean onPress(long rawPressTimestamp) {
		if (compatibilityTookOver()) return false;

		if (sequence != null) {
			// Adjacency is decided by the physical down timestamps, not by the
			// clock read at a later release/frame.
			expireIfBeyondWindow(sequence, rawPressTimestamp);
		}

		if (candidate != null) {
			// A repeated rising edge without the candidate's release is not a
			// supported adjacent tap and cannot supersede its owned baseline.
			return true;
		}

		if (sequence != null) {
			if (!sequence.firstResolved && !sequence.firstReleased) {
				return true;
			}
			if (matches(sequence, rawPressTimestamp)) {
				sequence.lastDownTimestamp = rawPressTimestamp;
				candidate = new Candidate(rawPressTimestamp, port.capturePressRay().orElse(null));
				// A short candidate deliberately starts no capture. Its ordinary
				// held baseline starts on the first frame that still has the key
				// down, so a tap release needs neither a second ray nor its result.
				return true;
			}
			sequence = null;
		}

		startFirst(rawPressTimestamp);
		return true;
	}

	/** Handles one claimed physical release. */
	public Optional<PingInteractionAction> onRelease() {
		if (compatibilityTookOver()) return Optional.empty();

		long now = port.nowMillis();
		if (candidate != null) return releaseCandidate(now);

		if (sequence != null && !sequence.firstResolved) {
			if (sequence.firstReleased) {
				// A repeated release has no new baseline work; the ordinary port
				// remains idempotent instead of manufacturing another tap.
				return port.releaseOutcome().action();
			}
			sequence.firstReleased = true;
			var outcome = port.releaseOutcome();
			observeFirst(outcome, now);
			return outcome.action();
		}

		return port.releaseOutcome().action();
	}

	/** Advances one rendered frame; the candidate's own press time remains its baseline. */
	public Optional<PingInteractionAction> onRenderFrame(boolean keyDown, long frameTimeMillis) {
		if (compatibilityTookOver()) return Optional.empty();

		if (candidate != null) {
			if (sequence != null && !sequence.firstResolved) {
				// The first press was already released, so this frame must keep
				// presenting it as released even while the candidate is held.
				var firstOutcome = port.presentFrameOutcome(false, frameTimeMillis);
				observeFirst(firstOutcome, frameTimeMillis);
				if (candidate == null) return firstOutcome.action();
				if (!candidate.started && sequence != null && sequence.firstContext != null)
					startCandidate();
				var candidateOutcome = presentCandidate(keyDown, frameTimeMillis);
				return candidateOutcome.isPresent() ? candidateOutcome : firstOutcome.action();
			}
			return presentCandidate(keyDown, frameTimeMillis);
		}

		if (sequence != null && !sequence.firstResolved) {
			// A released first press keeps the release boundary even while the
			// physical key may already be held again.
			boolean firstKeyDown = !sequence.firstReleased && keyDown;
			var outcome = port.presentFrameOutcome(firstKeyDown, frameTimeMillis);
			observeFirst(outcome, frameTimeMillis);
			return outcome.action();
		}

		expireIfBeyondWindow(sequence, frameTimeMillis);
		return port.presentFrameOutcome(keyDown, frameTimeMillis).action();
	}

	/** Clears recognition state only; an already-owned baseline is never aborted here. */
	public void reset() {
		sequence = null;
		candidate = null;
	}

	/**
	 * Live compatibility owns the sequence from now on. Only a candidate
	 * baseline created by this controller is discarded; an ordinary first
	 * baseline is left to finish naturally under compatibility's release/frame
	 * path.
	 */
	public void disableForCompatibility() {
		if (candidate != null && candidate.started) port.abortInteraction();
		reset();
	}

	public boolean hasPendingState() {
		return sequence != null || candidate != null;
	}

	private void startFirst(long rawPressTimestamp) {
		// The first press records the current accumulator as its baseline. The
		// tracker itself is reset only by an actual grab/focus/cursor lifecycle
		// boundary, never merely because a new ordinary press began.
		LookSample look = port.lookSample();
		CapturedRay ray = port.capturePressRay().orElse(null);
		Sequence started = new Sequence(rawPressTimestamp, ray, look);
		sequence = started;
		port.press(rawPressTimestamp);
	}

	private void startCandidate() {
		if (candidate == null || candidate.started) return;
		if (sequence == null || sequence.firstContext == null) return;
		if (candidate.pressRay == null) {
			// This down has no immutable ray of its own, so it cannot become an
			// ordinary long press; it can still produce the frozen short release.
			return;
		}
		candidate.started = true;
		port.press(candidate.pressTimestamp, candidate.pressRay);
	}

	private Optional<PingInteractionAction> presentCandidate(boolean keyDown, long frameTimeMillis) {
		startCandidate();
		if (!candidate.started) return Optional.empty();
		// Starting the candidate baseline or flushing pending taps crosses the
		// port and may observe a later machine clock. The frame timestamp stays
		// the presentation time; the machine gets a fresh monotonic observation.
		long observedTimeMillis = Math.max(frameTimeMillis, port.nowMillis());
		var outcome = port.presentFrameOutcome(keyDown, frameTimeMillis, observedTimeMillis);
		if (outcome.menuOpened() || port.phase() == PingInteractionPhase.WHEEL_OPEN) {
			candidate.wheelOpened = true;
		}
		return outcome.action();
	}

	private Optional<PingInteractionAction> releaseCandidate(long now) {
		Candidate ending = candidate;
		candidate = null;

		if (ending.started && (ending.wheelOpened || port.phase() == PingInteractionPhase.WHEEL_OPEN)) {
			// Once a wheel actually opened, release belongs only to that wheel.
			sequence = null;
			return port.releaseOutcome().action();
		}

		if (ending.started) port.abortInteraction();

		if (sequence == null) return Optional.empty();
		if (sequence.firstContext == null) {
			// Every admitted tap is retained; only the counter representation is
			// finite (saturating at the int limit), not an artificial small cap.
			if (sequence.pendingDangers != Integer.MAX_VALUE) sequence.pendingDangers++;
			return Optional.empty();
		}
		return dangerTap(now);
	}

	private void observeFirst(LongPressCompatibilityController.BaselineOutcome outcome, long now) {
		if (sequence == null) return;

		if (outcome.qualifyingDefaultCreate()
			&& outcome.action().orElse(null) instanceof PingInteractionAction.CreatePing create) {
			PingType danger = dangerType(create.context());
			if (danger == null) {
				reset();
				return;
			}
			sequence.firstResolved = true;
			sequence.firstContext = create.context();
			sequence.dangerType = danger;
			flushPendingDangers(now);
			if (candidate != null) startCandidate();
			return;
		}

		if (outcome.action().isPresent() || outcome.menuOpened()
			|| port.phase() == PingInteractionPhase.IDLE
			|| port.phase() == PingInteractionPhase.WHEEL_OPEN) {
			if (candidate != null && candidate.started) port.abortInteraction();
			reset();
		}
	}

	private void flushPendingDangers(long now) {
		int pending = sequence.pendingDangers;
		sequence.pendingDangers = 0;
		for (int i = 0; i < pending; i++) {
			// The input was already admitted at its own down edge. Dispatch time
			// must not re-evaluate or expire it against the later completion.
			dangerTap(now);
			// A reentrant abort (for example from the send path) ends this
			// sequence; later pending taps must not be sent after it.
			if (sequence == null) return;
		}
	}

	private Optional<PingInteractionAction> dangerTap(long now) {
		if (sequence == null || sequence.firstContext == null || sequence.dangerType == null)
			return Optional.empty();
		return port.frozenDangerTap(now, sequence.firstContext, sequence.dangerType);
	}

	private boolean matches(Sequence active, long rawPressTimestamp) {
		if (rawPressTimestamp < active.lastDownTimestamp) return false;
		int window = port.dangerWindowMillis();
		if (window <= 0 || rawPressTimestamp - active.lastDownTimestamp > window) return false;
		double threshold = port.dangerAngleDegrees();
		if (!(threshold > 0.0)) return false;
		if (active.firstRay == null || active.firstLook == null) return false;
		LookSample current = port.lookSample();
		if (current == null || current.generation() != active.firstLook.generation()) return false;
		double angle = LookInputTracker.angleBetween(active.firstRay.direction(),
			new LookInputTracker.Sample(active.firstLook.yawDegrees(), active.firstLook.pitchDegrees(),
				active.firstLook.generation()),
			new LookInputTracker.Sample(current.yawDegrees(), current.pitchDegrees(), current.generation()));
		return angle < threshold;
	}

	private void expireIfBeyondWindow(Sequence active, long now) {
		if (active == null || !active.firstResolved || candidate != null) return;
		int window = port.dangerWindowMillis();
		if (window <= 0 || now < active.lastDownTimestamp
			|| now - active.lastDownTimestamp > window) {
			sequence = null;
		}
	}

	private boolean compatibilityTookOver() {
		if (!port.compatibilityEnabled()) return false;
		disableForCompatibility();
		return true;
	}

	private static PingType dangerType(CapturedPingContext context) {
		for (PingType type : context.resolvedTarget().targetType().pingTypes()) {
			if ("danger".equals(type.id())) return type;
		}
		return null;
	}

	private static final class Sequence {
		private final long firstDownTimestamp;
		private final CapturedRay firstRay;
		private final LookSample firstLook;
		private long lastDownTimestamp;
		private boolean firstReleased;
		private boolean firstResolved;
		private CapturedPingContext firstContext;
		private PingType dangerType;
		private int pendingDangers;

		private Sequence(long firstDownTimestamp, CapturedRay firstRay, LookSample firstLook) {
			this.firstDownTimestamp = firstDownTimestamp;
			this.lastDownTimestamp = firstDownTimestamp;
			this.firstRay = firstRay;
			this.firstLook = firstLook;
		}
	}

	private static final class Candidate {
		private final long pressTimestamp;
		private final CapturedRay pressRay;
		private boolean started;
		private boolean wheelOpened;

		private Candidate(long pressTimestamp, CapturedRay pressRay) {
			this.pressTimestamp = pressTimestamp;
			this.pressRay = pressRay;
		}
	}
}
