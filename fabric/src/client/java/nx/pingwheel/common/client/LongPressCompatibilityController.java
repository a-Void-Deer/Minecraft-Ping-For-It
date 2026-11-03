package nx.pingwheel.common.client;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import nx.pingwheel.common.config.ClientConfigBounds;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;

/**
 * Compatibility sequencing around the high-precision interaction port.
 *
 * <p>The normal port remains the only owner of capture, wheel presentation,
 * validation, selection, and dispatch.  This class only recognizes a bounded
 * sequence of claimed press edges and, after a normal short click has emitted
 * its {@link PingInteractionAction.CreatePing}, starts one backdated virtual
 * interaction.  Consequently compatibility mode cannot duplicate the wheel
 * implementation or bypass the existing action boundary.</p>
 *
 * <p>All state is one-slot state: a first interaction, one possible rapid
 * sequence, and one deferred fresh press while an asynchronous first capture
 * is resolving.  No timer, thread, or event history is retained.</p>
 */
public final class LongPressCompatibilityController {

	/**
	 * The high-precision baseline interaction port.  Returned actions have
	 * already crossed the port's dispatch boundary; the return value exists so
	 * this controller can observe whether a normal short click really emitted a
	 * CreatePing without taking over dispatching.
	 */
	public interface InteractionPort {

		Optional<PingInteractionAction> pressAt(long rawPressTimestamp);

		/**
		 * Starts a baseline interaction with a ray frozen at the physical press
		 * edge.  Implementations that do not need the extra capture seam may use
		 * the default overload; production uses it for an async-first-click
		 * compatibility candidate.
		 */
		default Optional<PingInteractionAction> pressAt(long rawPressTimestamp, CapturedRay pressRay) {
			return pressAt(rawPressTimestamp);
		}

		/**
		 * Captures the current physical press ray without beginning an interaction.
		 * An empty result means the caller cannot safely freeze a candidate and
		 * must drop it rather than re-raycast later.
		 */
		default Optional<CapturedRay> capturePressRay() {
			return Optional.of(CapturedRay.defaultRay());
		}

		Optional<PingInteractionAction> release();

		Optional<PingInteractionAction> presentFrame(boolean keyDown);

		default Optional<PingInteractionAction> presentFrame(boolean keyDown, long frameTimeMillis) {
			return presentFrame(keyDown);
		}

		void abort();

		PingInteractionPhase phase();
	}

	private final InteractionPort port;
	private final InteractionTimeSource timeSource;
	private final BooleanSupplier modeSupplier;
	private final LongSupplier wheelHoldMillisSupplier;
	private final LongSupplier sliceMillisSupplier;
	private final PingInteractionLogger logger;
	private final Runnable rawInputReset;

	private boolean modeObserved;
	private boolean compatibilityEnabled;
	private boolean modeDisabledTransitionObserved;
	private boolean hasLastRawPressTimestamp;
	private long lastRawPressTimestamp;
	private boolean hasLastFrameTimestamp;
	private long lastFrameTimestamp;

	private NormalInteraction normalInteraction;
	private Seed seed;
	private Candidate candidate;
	private DeferredPress deferredPress;

	public LongPressCompatibilityController(
		InteractionPort port,
		InteractionTimeSource timeSource,
		BooleanSupplier modeSupplier,
		LongSupplier wheelHoldMillisSupplier,
		LongSupplier sliceMillisSupplier,
		PingInteractionLogger logger
	) {
		this(
			port,
			timeSource,
			modeSupplier,
			wheelHoldMillisSupplier,
			sliceMillisSupplier,
			logger,
			() -> {});
	}

	/**
	 * Creates a controller with a lifecycle hook for the raw key arbiter.
	 * Production supplies the input-state reset; pure ports can use the
	 * no-op-compatible constructor above.
	 */
	public LongPressCompatibilityController(
		InteractionPort port,
		InteractionTimeSource timeSource,
		BooleanSupplier modeSupplier,
		LongSupplier wheelHoldMillisSupplier,
		LongSupplier sliceMillisSupplier,
		PingInteractionLogger logger,
		Runnable rawInputReset
	) {
		this.port = Objects.requireNonNull(port, "port");
		this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
		this.modeSupplier = Objects.requireNonNull(modeSupplier, "modeSupplier");
		this.wheelHoldMillisSupplier = Objects.requireNonNull(wheelHoldMillisSupplier, "wheelHoldMillisSupplier");
		this.sliceMillisSupplier = Objects.requireNonNull(sliceMillisSupplier, "sliceMillisSupplier");
		this.logger = Objects.requireNonNull(logger, "logger");
		this.rawInputReset = Objects.requireNonNull(rawInputReset, "rawInputReset");
	}

	/**
	 * Handles one claimed raw press.  The timestamp is the physical monotonic
	 * press timestamp, not a later tick or frame timestamp.
	 */
	public Optional<PingInteractionAction> onPress(long rawPressTimestamp) {
		if (!syncMode()) {
			if (modeDisabledTransitionObserved) {
				// The raw edge that first observes a disable belongs to the aborted
				// old interaction. Dropping it prevents its release from being
				//interpreted as a new baseline click after the raw claim reset.
				return Optional.empty();
			}

			return port.pressAt(rawPressTimestamp);
		}

		observeRawPressTime(rawPressTimestamp);

		if (candidate != null) {
			long threshold = effectiveSliceMillis();

			if (withinGap(rawPressTimestamp, candidate.lastPressTimestamp, threshold)) {
				candidate.lastPressTimestamp = rawPressTimestamp;
				candidate.count = incrementCount(candidate.count);
				return Optional.empty();
			}

			Optional<PingInteractionAction> formed = terminateCandidate(rawPressTimestamp, threshold);
			return appendAction(formed, startFreshPress(rawPressTimestamp, null));
		}

		if (normalInteraction != null) {
			NormalInteraction normal = normalInteraction;

			// A repeated click while the first key interaction is still held is not
			// a completed short click.  Do not supersede its frozen capture.
			if (!normal.releaseObserved) {
				return Optional.empty();
			}

			long threshold = effectiveSliceMillis();
			if (withinGap(rawPressTimestamp, normal.lastPressTimestamp, threshold)) {
				recordRapidPress(normal, rawPressTimestamp, threshold);
				return Optional.empty();
			}

			// A first capture that is still pending must not be aborted merely to
			// make room for a later click.  Defer the later fresh interaction until
			// the original CreatePing has crossed the baseline dispatch boundary.
			normal.sequenceExpired = true;
			logAbandoned(normal, threshold);
			deferredPress = new DeferredPress(
				rawPressTimestamp,
				Optional.ofNullable(capturePressRay()),
				false);
			return Optional.empty();
		}

		if (seed != null) {
			long threshold = effectiveSliceMillis();

			if (withinGap(rawPressTimestamp, seed.lastPressTimestamp, threshold)) {
				CapturedRay secondPressRay = capturePressRay();
				logCandidateEntry(rawPressTimestamp - seed.lastPressTimestamp, threshold);
				Optional<PingInteractionAction> started = startCandidate(
					seed.firstPressTimestamp,
					rawPressTimestamp,
					2,
					secondPressRay,
					threshold);
				seed = null;
				return started;
			}

			// Strictly greater than T expires the old seed.  A delta exactly equal
			// to T is handled above and remains eligible.
			seed = null;
		}

		return startFreshPress(rawPressTimestamp, null);
	}

	/**
	 * Handles one claimed raw release.  Compatibility releases are swallowed
	 * only after a candidate press has claimed the sequence; the first normal
	 * release always reaches the baseline immediately.
	 */
	public Optional<PingInteractionAction> onRelease() {
		if (!syncMode()) {
			return port.release();
		}

		if (candidate != null) {
			return Optional.empty();
		}

		if (deferredPress != null) {
			deferredPress.released = true;
			return Optional.empty();
		}

		if (normalInteraction == null) {
			// A release without a controller-owned first press is not expected from
			// the input arbiter, but passing it through is the least surprising
			// baseline behavior and keeps this wrapper non-invasive.
			return port.release();
		}

		if (normalInteraction.hasRapidPress) {
			return Optional.empty();
		}

		normalInteraction.releaseObserved = true;
		Optional<PingInteractionAction> action = port.release();
		return handleNormalAction(action, currentTimeForAction());
	}

	/**
	 * Advances the baseline once for one rendered frame.  A compatibility
	 * candidate is always virtual-held, even when its physical release edges
	 * have already been swallowed.
	 */
	public Optional<PingInteractionAction> onRenderFrame(boolean physicalKeyDown) {
		if (!syncMode()) {
			return port.presentFrame(physicalKeyDown);
		}

		long now = observeFrameTime();

		if (candidate != null) {
			long threshold = effectiveSliceMillis();

			if (strictlyBeyondGap(now, candidate.lastPressTimestamp, threshold)) {
				return terminateCandidate(now, threshold);
			}

			Optional<PingInteractionAction> action = port.presentFrame(true, now);
			if (port.phase() == PingInteractionPhase.WHEEL_OPEN) {
				candidate.wheelOpened = true;
			} else if (port.phase() == PingInteractionPhase.IDLE) {
				// The baseline can end a virtual interaction itself, for example by
				// its existing wheel timeout.  Never retain a compatibility seed after
				// that lifecycle has ended.
				candidate = null;
			}

			return action;
		}

		if (normalInteraction != null) {
			NormalInteraction normal = normalInteraction;
			if (normal.hasRapidPress
				&& strictlyBeyondGap(now, normal.lastPressTimestamp, effectiveSliceMillis())
				&& !normal.sequenceExpired) {
				normal.sequenceExpired = true;
				logAbandoned(normal, effectiveSliceMillis());
			}

			boolean baselineKeyDown = normal.releaseObserved ? false : physicalKeyDown;
			Optional<PingInteractionAction> action = port.presentFrame(baselineKeyDown, now);
			return handleNormalAction(action, now);
		}

		if (seed != null && strictlyBeyondGap(now, seed.lastPressTimestamp, effectiveSliceMillis())) {
			seed = null;
		}

		// With no compatibility-owned interaction there is no baseline action to
		// advance.  This keeps a seeded short click from being interpreted as a
		// second ordinary hold on a later frame.
		return port.phase() == PingInteractionPhase.IDLE
			? Optional.empty()
			: port.presentFrame(physicalKeyDown, now);
	}

	/**
	 * Clears every compatibility sequence and aborts the baseline interaction
	 * without an action.  Used for focus, screen, world, dimension, disconnect,
	 * and explicit configuration lifecycle resets.
	 */
	public void abort() {
		port.abort();
		clearInteractionState();
	}

	/**
	 * Whether an idle baseline still has compatibility state that must be
	 * cleared on a lifecycle boundary (notably a seeded first click).
	 */
	public boolean hasPendingState() {
		return normalInteraction != null || seed != null || candidate != null || deferredPress != null;
	}

	private Optional<PingInteractionAction> startFreshPress(long rawPressTimestamp, CapturedRay frozenRay) {
		NormalInteraction fresh = new NormalInteraction(rawPressTimestamp);
		normalInteraction = fresh;
		Optional<PingInteractionAction> action = frozenRay == null
			? port.pressAt(rawPressTimestamp)
			: port.pressAt(rawPressTimestamp, frozenRay);

		// A failed/inapplicable press cannot produce a short-click seed.  A
		// successful press remains owned by normalInteraction until release or
		// capture completion is observed.
		if (port.phase() == PingInteractionPhase.IDLE && action.isEmpty()) {
			normalInteraction = null;
		}

		return action;
	}

	private Optional<PingInteractionAction> startCandidate(
		long firstPressTimestamp,
		long secondPressTimestamp,
		int count,
		CapturedRay secondPressRay,
		long threshold
	) {
		if (secondPressRay == null || port.phase() != PingInteractionPhase.IDLE) {
			// The second click is intentionally dropped.  In particular it is not
			// replayed as a normal CreatePing when a ray or an idle baseline is not
			// safely available.
			return Optional.empty();
		}

		Candidate started = new Candidate(
			firstPressTimestamp,
			secondPressTimestamp,
			count,
			secondPressRay);
		candidate = started;
		seed = null;
		normalInteraction = null;

		Optional<PingInteractionAction> action;
		try {
			// The timestamp is deliberately the first click's timestamp.  The
			// supplied ray is deliberately the second click's frozen ray.
			action = port.pressAt(firstPressTimestamp, secondPressRay);
		} catch (RuntimeException failure) {
			candidate = null;
			port.abort();
			throw failure;
		}

		if (port.phase() == PingInteractionPhase.WHEEL_OPEN) {
			// A conforming port opens only from presentFrame; retain this guard for
			// a test port or a future adapter without asking it to open twice.
			started.wheelOpened = true;
		}

		if (port.phase() == PingInteractionPhase.IDLE) {
			candidate = null;
		}

		return action;
	}

	private void recordRapidPress(
		NormalInteraction normal,
		long rawPressTimestamp,
		long threshold
	) {
		if (!normal.hasRapidPress) {
			logCandidateEntry(rawPressTimestamp - normal.lastPressTimestamp, threshold);
				normal.secondPressRay = Optional.ofNullable(capturePressRay());
			normal.hasRapidPress = true;
		}

		normal.lastPressTimestamp = rawPressTimestamp;
		normal.count = incrementCount(normal.count);
	}

	private Optional<PingInteractionAction> handleNormalAction(
		Optional<PingInteractionAction> action,
		long now
	) {
		if (action.isEmpty()) {
			if (port.phase() == PingInteractionPhase.WHEEL_OPEN) {
				// A normal long press has become a wheel interaction and can never
				// seed compatibility, even if its eventual action is empty.
				normalInteraction.hasNormalWheel = true;
			} else if (port.phase() == PingInteractionPhase.IDLE) {
				normalInteraction = null;
				// The baseline ended without a dispatch-boundary action (for
				// example a stale/invalid capture). A deferred raw click must not
				// survive that lifecycle and be replayed by a later interaction.
				deferredPress = null;
			}

			return action;
		}

		NormalInteraction completed = normalInteraction;
		if (completed == null) {
			return action;
		}

		boolean create = action.get() instanceof PingInteractionAction.CreatePing;
		// The action itself is the dispatch-boundary proof that this was a real
		// normal short CreatePing. Do not require a separate release callback:
		// an asynchronous capture may complete on the frame path after the raw
		// release, and a frame-side release fallback must seed identically.
		boolean canSeed = create
			&& port.phase() == PingInteractionPhase.IDLE
			&& !completed.hasNormalWheel;

		long firstPressTimestamp = completed.firstPressTimestamp;
		long lastPressTimestamp = completed.lastPressTimestamp;
		int count = completed.count;
		CapturedRay secondPressRay = completed.secondPressRay.orElse(null);
		boolean pendingCandidate = completed.hasRapidPress
			&& !completed.sequenceExpired
			&& secondPressRay != null
			&& !strictlyBeyondGap(now, lastPressTimestamp, effectiveSliceMillis());

		normalInteraction = null;

		if (canSeed && pendingCandidate) {
			startCandidate(firstPressTimestamp, lastPressTimestamp, count, secondPressRay, effectiveSliceMillis());
			return action;
		}

		// A rapid click recorded while an async capture was pending is allowed to
		// disappear here.  The first action has already been dispatched and is
		// never replayed; no invalid second interaction is synthesized.
		if (canSeed && !completed.hasRapidPress && deferredPress == null) {
			seed = new Seed(firstPressTimestamp, firstPressTimestamp);
		}

		DeferredPress deferred = deferredPress;
		deferredPress = null;
		if (deferred != null) {
			// A deferred press was captured at its raw edge. If that immutable ray
			// could not be obtained, dropping the click is safer than calling the
			// ordinary no-ray overload, which would raycast again later.
			if (deferred.pressRay.isEmpty()) {
				return action;
			}

			Optional<PingInteractionAction> deferredAction = startFreshPress(
				deferred.rawPressTimestamp,
				deferred.pressRay.get());
			if (deferred.released) {
				if (normalInteraction != null) {
					normalInteraction.releaseObserved = true;
				}
				Optional<PingInteractionAction> releaseAction = port.release();
				handleNormalAction(releaseAction, now);
				return releaseAction.isPresent() ? releaseAction : deferredAction;
			}

			return deferredAction;
		}

		return action;
	}

	private Optional<PingInteractionAction> terminateCandidate(long endTimestamp, long threshold) {
		Candidate ending = candidate;
		if (ending == null) {
			return Optional.empty();
		}

		Optional<PingInteractionAction> action = Optional.empty();
		if (ending.wheelOpened && port.phase() == PingInteractionPhase.WHEEL_OPEN) {
			// This is the exact baseline release path, including the latest wheel
			// selection and its frozen cancellation context.
			action = port.release();
			logger.debug(
				"long press compatibility formed: durationMillis={} thresholdMillis={} count={}",
				elapsedMillis(ending.firstPressTimestamp, endTimestamp),
				threshold,
				ending.count);
		} else {
			if (port.phase() == PingInteractionPhase.PRESSED) {
				port.abort();
			}
			logger.debug(
				"long press compatibility abandoned: durationMillis={} thresholdMillis={} count={}",
				elapsedMillis(ending.firstPressTimestamp, endTimestamp),
				threshold,
				ending.count);
		}

		candidate = null;
		return action;
	}

	private void logCandidateEntry(long delta, long threshold) {
		logger.debug(
			"long press compatibility candidate: deltaMillis={} thresholdMillis={}",
			delta,
			threshold);
	}

	private void logAbandoned(NormalInteraction abandoned, long threshold) {
		logger.debug(
			"long press compatibility abandoned: durationMillis={} thresholdMillis={} count={}",
			elapsedMillis(abandoned.firstPressTimestamp, abandoned.lastPressTimestamp),
			threshold,
			abandoned.count);
	}

	private CapturedRay capturePressRay() {
		return port.capturePressRay().orElse(null);
	}

	private boolean syncMode() {
		boolean enabled = modeSupplier.getAsBoolean();
		modeDisabledTransitionObserved = false;

		if (modeObserved && compatibilityEnabled && !enabled) {
			// A configuration replacement/reset is observed at the next raw event
			// or frame.  The virtual interaction is always aborted without action.
			port.abort();
			clearInteractionState();
			rawInputReset.run();
			modeDisabledTransitionObserved = true;
		}

		modeObserved = true;
		compatibilityEnabled = enabled;

		if (!enabled) {
			// Disabled mode retains no compatibility state and delegates directly.
			clearInteractionState();
		}

		return enabled;
	}

	private void clearInteractionState() {
		normalInteraction = null;
		seed = null;
		candidate = null;
		deferredPress = null;
	}

	private void observeRawPressTime(long timestamp) {
		if (hasLastRawPressTimestamp && timestamp < lastRawPressTimestamp) {
			throw new IllegalStateException(
				"compatibility press time moved backwards: " + timestamp + " < " + lastRawPressTimestamp);
		}

		hasLastRawPressTimestamp = true;
		lastRawPressTimestamp = timestamp;
	}

	private long observeFrameTime() {
		long now = timeSource.nowMillis();
		if (hasLastFrameTimestamp && now < lastFrameTimestamp) {
			throw new IllegalStateException(
				"compatibility frame time moved backwards: " + now + " < " + lastFrameTimestamp);
		}

		hasLastFrameTimestamp = true;
		lastFrameTimestamp = now;
		return now;
	}

	private long currentTimeForAction() {
		long now = timeSource.nowMillis();
		if (hasLastFrameTimestamp && now < lastFrameTimestamp) {
			throw new IllegalStateException(
				"compatibility action time moved backwards: " + now + " < " + lastFrameTimestamp);
		}

		lastFrameTimestamp = now;
		hasLastFrameTimestamp = true;
		return now;
	}

	private long effectiveSliceMillis() {
		long suppliedHold = wheelHoldMillisSupplier.getAsLong();
		int holdMillis;
		if (suppliedHold < ClientConfigBounds.MIN_WHEEL_HOLD_MILLIS) {
			holdMillis = ClientConfigBounds.MIN_WHEEL_HOLD_MILLIS;
		} else if (suppliedHold > ClientConfigBounds.MAX_WHEEL_HOLD_MILLIS) {
			holdMillis = ClientConfigBounds.MAX_WHEEL_HOLD_MILLIS;
		} else {
			holdMillis = (int) suppliedHold;
		}

		long suppliedSlice = sliceMillisSupplier.getAsLong();
		int sliceMillis;
		if (suppliedSlice < Integer.MIN_VALUE) {
			sliceMillis = Integer.MIN_VALUE;
		} else if (suppliedSlice > Integer.MAX_VALUE) {
			sliceMillis = Integer.MAX_VALUE;
		} else {
			sliceMillis = (int) suppliedSlice;
		}

		return ClientConfigBounds.clampLongPressCompatibilitySliceMillis(sliceMillis, holdMillis);
	}

	private static boolean withinGap(long later, long earlier, long threshold) {
		return later >= earlier && later - earlier <= threshold;
	}

	private static boolean strictlyBeyondGap(long later, long earlier, long threshold) {
		return later >= earlier && later - earlier > threshold;
	}

	private static long elapsedMillis(long first, long last) {
		return last >= first ? last - first : 0L;
	}

	private static int incrementCount(int count) {
		return count == Integer.MAX_VALUE ? count : count + 1;
	}

	private static Optional<PingInteractionAction> appendAction(
		Optional<PingInteractionAction> first,
		Optional<PingInteractionAction> second
	) {
		return second.isPresent() ? second : first;
	}

	private static final class NormalInteraction {
		private final long firstPressTimestamp;
		private long lastPressTimestamp;
		private int count = 1;
		private boolean releaseObserved;
		private boolean hasRapidPress;
		private boolean sequenceExpired;
		private boolean hasNormalWheel;
		private Optional<CapturedRay> secondPressRay = Optional.empty();

		private NormalInteraction(long firstPressTimestamp) {
			this.firstPressTimestamp = firstPressTimestamp;
			this.lastPressTimestamp = firstPressTimestamp;
		}
	}

	private static final class Seed {
		private final long firstPressTimestamp;
		private long lastPressTimestamp;

		private Seed(long firstPressTimestamp, long lastPressTimestamp) {
			this.firstPressTimestamp = firstPressTimestamp;
			this.lastPressTimestamp = lastPressTimestamp;
		}
	}

	private static final class Candidate {
		private final long firstPressTimestamp;
		private long lastPressTimestamp;
		private int count;
		private final CapturedRay secondPressRay;
		private boolean wheelOpened;

		private Candidate(
			long firstPressTimestamp,
			long lastPressTimestamp,
			int count,
			CapturedRay secondPressRay
		) {
			this.firstPressTimestamp = firstPressTimestamp;
			this.lastPressTimestamp = lastPressTimestamp;
			this.count = count;
			this.secondPressRay = secondPressRay;
		}
	}

	private static final class DeferredPress {
		private final long rawPressTimestamp;
		private final Optional<CapturedRay> pressRay;
		private boolean released;

		private DeferredPress(long rawPressTimestamp, Optional<CapturedRay> pressRay, boolean released) {
			this.rawPressTimestamp = rawPressTimestamp;
			this.pressRay = pressRay;
			this.released = released;
		}
	}
}
