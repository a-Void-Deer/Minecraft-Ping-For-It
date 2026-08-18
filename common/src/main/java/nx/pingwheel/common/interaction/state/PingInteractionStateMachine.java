package nx.pingwheel.common.interaction.state;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

import nx.pingwheel.common.config.ClientConfigBounds;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.PingCaptureCoordinator;
import nx.pingwheel.common.interaction.cancel.CancelCandidatePicker;
import nx.pingwheel.common.interaction.cancel.CancelMarkerCandidate;
import nx.pingwheel.common.interaction.cancel.CancellationContext;
import nx.pingwheel.common.interaction.wheel.WheelSelection;

/**
 * The pure, single-threaded state machine driving one ping-key interaction.
 *
 * <p>It is injected with the phase-4 {@link PingCaptureCoordinator} (to mint
 * tokens and let captures arrive asynchronously) and the shared
 * {@link ActiveInteraction} holder (to observe frozen captures and detect
 * staleness). It applies the phase-5 timing, wheel, and cancellation rules and
 * emits at most one {@link PingInteractionAction} per interaction.
 *
 * <p>Timing is driven by an injected {@link InteractionTimeSource}; all hold
 * durations are computed from monotonic differences, and a clock that moves
 * backwards while an interaction is active is rejected with an
 * {@link IllegalStateException}. Presentation-only threshold and timeout
 * transitions are driven by {@link #presentFrame(boolean)} on GUI/render
 * cadence, while {@link #update(boolean, WheelSelection, CancellationContext)}
 * remains the tick-authoritative action boundary. No Minecraft, networking, or
 * rendering concerns live here: phase 6 remains authoritative for validation
 * and marker storage, and this class never touches client/server state.
 *
 * <p><strong>Key-down contract:</strong> {@link #press()} must be invoked on
 * every physical key-down rising edge, exactly once per press. Releasing the
 * key and then holding it again without an intervening {@link #press()} is
 * invalid caller behavior: the machine would misinterpret the second hold as a
 * continuation of the first, producing an ambiguous accumulated-hold duration.
 * A key-down that arrives without a {@link #press()} is not a supported input.
 */
public final class PingInteractionStateMachine {

	/**
	 * The default long-press threshold in milliseconds: holding at least this
	 * long opens the wheel.
	 */
	public static final long LONG_PRESS_MILLIS = 300L;

	/**
	 * The default maximum wheel-open duration in milliseconds: once the wheel
	 * has been open this long it closes with no action.
	 */
	public static final long WHEEL_TIMEOUT_MILLIS = 5000L;

	private final PingCaptureCoordinator coordinator;
	private final ActiveInteraction activeInteraction;
	private final InteractionTimeSource timeSource;
	private final TargetValidator targetValidator;
	private final CancelCandidatePicker cancelCandidatePicker;
	private final PingInteractionLogger logger;
	private final LongSupplier wheelHoldMillisSupplier;
	private final LongSupplier wheelTimeoutMillisSupplier;
	private final boolean supplierValuesUseClientConfigBounds;
	private long longPressMillis = LONG_PRESS_MILLIS;
	private long wheelTimeoutMillis = WHEEL_TIMEOUT_MILLIS;

	private PingInteractionPhase phase = PingInteractionPhase.IDLE;
	private InteractionToken token;
	private CapturedPingContext capturedContext;
	private long pressTimeMillis;
	private long wheelOpenTimeMillis;
	private long lastObservedTimeMillis;
	private boolean releaseObserved;
	private WheelSelection selection = WheelSelection.NONE;
	private List<PingType> wheelPingTypes = List.of();

	/**
	 * Creates a state machine with the default thresholds.
	 */
	public PingInteractionStateMachine(
		PingCaptureCoordinator coordinator,
		ActiveInteraction activeInteraction,
		InteractionTimeSource timeSource,
		TargetValidator targetValidator,
		CancelCandidatePicker cancelCandidatePicker,
		PingInteractionLogger logger
	) {
		this(
			coordinator,
			activeInteraction,
			timeSource,
			targetValidator,
			cancelCandidatePicker,
			logger,
			() -> LONG_PRESS_MILLIS,
			() -> WHEEL_TIMEOUT_MILLIS);
	}

	/**
	 * Creates a state machine whose interaction settings are read lazily from
	 * the supplied providers. The hold threshold is read once by
	 * {@link #press()}, and the wheel timeout is read once when the wheel opens,
	 * so changing a live config never changes an interaction already in progress.
	 */
	public PingInteractionStateMachine(
		PingCaptureCoordinator coordinator,
		ActiveInteraction activeInteraction,
		InteractionTimeSource timeSource,
		TargetValidator targetValidator,
		CancelCandidatePicker cancelCandidatePicker,
		PingInteractionLogger logger,
		LongSupplier wheelHoldMillisSupplier,
		LongSupplier wheelTimeoutMillisSupplier
	) {
		this(
			coordinator,
			activeInteraction,
			timeSource,
			targetValidator,
			cancelCandidatePicker,
			logger,
			wheelHoldMillisSupplier,
			wheelTimeoutMillisSupplier,
			true);
	}

	/**
	 * Creates a state machine with custom positive thresholds.
	 *
	 * <p>Package-private test seam: production callers use the default
	 * thresholds above.
	 */
		PingInteractionStateMachine(
		PingCaptureCoordinator coordinator,
		ActiveInteraction activeInteraction,
		InteractionTimeSource timeSource,
		TargetValidator targetValidator,
		CancelCandidatePicker cancelCandidatePicker,
		PingInteractionLogger logger,
		long longPressMillis,
		long wheelTimeoutMillis
	) {
		this(
			coordinator,
			activeInteraction,
			timeSource,
			targetValidator,
			cancelCandidatePicker,
			logger,
			constantThresholdSupplier("longPressMillis", longPressMillis),
			constantThresholdSupplier("wheelTimeoutMillis", wheelTimeoutMillis),
			false);
	}

	private PingInteractionStateMachine(
		PingCaptureCoordinator coordinator,
		ActiveInteraction activeInteraction,
		InteractionTimeSource timeSource,
		TargetValidator targetValidator,
		CancelCandidatePicker cancelCandidatePicker,
		PingInteractionLogger logger,
		LongSupplier wheelHoldMillisSupplier,
		LongSupplier wheelTimeoutMillisSupplier,
		boolean supplierValuesUseClientConfigBounds
	) {
		this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
		this.activeInteraction = Objects.requireNonNull(activeInteraction, "activeInteraction");
		this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
		this.targetValidator = Objects.requireNonNull(targetValidator, "targetValidator");
		this.cancelCandidatePicker = Objects.requireNonNull(cancelCandidatePicker, "cancelCandidatePicker");
		this.logger = Objects.requireNonNull(logger, "logger");
		this.wheelHoldMillisSupplier = Objects.requireNonNull(wheelHoldMillisSupplier, "wheelHoldMillisSupplier");
		this.wheelTimeoutMillisSupplier = Objects.requireNonNull(wheelTimeoutMillisSupplier, "wheelTimeoutMillisSupplier");
		this.supplierValuesUseClientConfigBounds = supplierValuesUseClientConfigBounds;
	}

	/**
	 * Starts a new interaction, superseding any pending or open prior machine
	 * state, and returns the fresh token.
	 *
	 * <p><strong>Caller contract:</strong> every physical key-down rising edge
	 * must call this method exactly once. A release followed by a re-hold
	 * without an intervening {@code press()} is invalid caller behavior — the
	 * machine would treat the second hold as a continuation of the first and
	 * miscompute the accumulated hold.
	 *
	 * <p>The press timestamp is captured through {@link #observeTime()}, the
	 * same monotonic path used by {@link #update}, so a clock that regresses
	 * across interactions (for example on a fresh press after a completed
	 * interaction) is rejected consistently instead of silently producing a
	 * shorter hold duration. {@link #resetMachineState()} deliberately leaves
	 * {@link #lastObservedTimeMillis} untouched, so backward time is never
	 * accepted across a reset.
	 */
	public InteractionToken press() {
		long now = observeTime();
		return pressAtObserved(now);
	}

	/**
	 * Starts an interaction using an optional physical press timestamp from the
	 * same monotonic clock. A timestamp older than the current observation is a
	 * supported backdated start; a future timestamp is rejected before any token
	 * or machine state is changed. The clock itself is still observed through
	 * {@link #observeTime()}, so source rollback preserves the existing
	 * invariant and fails with {@link IllegalStateException}.
	 */
	public InteractionToken pressAt(long physicalPressTimeMillis) {
		long now = observeTime();

		if (physicalPressTimeMillis > now) {
			throw new IllegalArgumentException(
				"physical press time is in the future: " + physicalPressTimeMillis + " > " + now);
		}

		return pressAtObserved(physicalPressTimeMillis);
	}

	/** Alias for callers that prefer the existing {@code press(...)} naming. */
	public InteractionToken press(long physicalPressTimeMillis) {
		return pressAt(physicalPressTimeMillis);
	}

	private InteractionToken pressAtObserved(long physicalPressTimeMillis) {
		long configuredHoldMillis = readConfiguredThreshold(
			"wheelHoldMillis",
			wheelHoldMillisSupplier,
			ClientConfigBounds.MIN_WHEEL_HOLD_MILLIS,
			ClientConfigBounds.MAX_WHEEL_HOLD_MILLIS);
		InteractionToken freshToken = coordinator.begin();
		resetMachineState();
		this.token = freshToken;
		this.phase = PingInteractionPhase.PRESSED;
		this.longPressMillis = configuredHoldMillis;
		this.selection = WheelSelection.NONE;
		this.pressTimeMillis = physicalPressTimeMillis;
		logger.debug("press: token={}", freshToken.sequence());
		return freshToken;
	}

	/**
	 * Abandons this machine's current interaction without emitting an action.
	 * Invalidation precedes local clearing so asynchronous capture completion
	 * cannot be accepted after the reset.
	 */
	public void abort() {
		if (token != null) {
			activeInteraction.invalidate(token);
		}

		resetMachineState();
	}

	/**
	 * Advances the state machine with the current key state, wheel selection,
	 * and cancellation snapshot, returning the single action to perform (if
	 * any).
	 *
	 * <p>Polling {@link ActiveInteraction#currentContext()} is authoritative:
	 * only a capture for this machine's own token is accepted, and a token
	 * superseded by a newer press (or an externally begun interaction) is
	 * treated as stale: logged, reset to idle, and left action-less.
	 */
	public Optional<PingInteractionAction> update(
		boolean keyDown,
		WheelSelection wheelSelection,
		CancellationContext cancellationContext
	) {
		Objects.requireNonNull(wheelSelection, "wheelSelection");
		Objects.requireNonNull(cancellationContext, "cancellationContext");
		if (phase == PingInteractionPhase.IDLE) {
			return Optional.empty();
		}

		return updateAt(keyDown, wheelSelection, cancellationContext, observeTime());
	}

	/**
	 * Advances the machine using a timestamp already sampled by the enclosing
	 * client-frame boundary.  This keeps presentation and action advancement on
	 * one monotonic observation when a caller owns the frame clock read.
	 */
	public Optional<PingInteractionAction> updateAt(
		boolean keyDown,
		WheelSelection wheelSelection,
		CancellationContext cancellationContext,
		long observedTimeMillis
	) {
		Objects.requireNonNull(wheelSelection, "wheelSelection");
		Objects.requireNonNull(cancellationContext, "cancellationContext");
		if (phase == PingInteractionPhase.IDLE) {
			return Optional.empty();
		}

		return updateObserved(keyDown, wheelSelection, cancellationContext, observeTimeValue(observedTimeMillis));
	}

	private Optional<PingInteractionAction> updateObserved(
		boolean keyDown,
		WheelSelection wheelSelection,
		CancellationContext cancellationContext,
		long now
	) {

		if (phase == PingInteractionPhase.IDLE) {
			return Optional.empty();
		}

		if (!activeInteraction.isCurrent(token)) {
			logger.debug("interaction superseded: token={}", token.sequence());
			resetMachineState();
			return Optional.empty();
		}

		Optional<CapturedPingContext> capture = activeInteraction.currentContext();

		if (capture.isPresent() && capture.get().token() != token) {
			logger.debug("interaction superseded: token={}", token.sequence());
			resetMachineState();
			return Optional.empty();
		}

		if (phase == PingInteractionPhase.WHEEL_OPEN) {
			return updateWheelOpen(keyDown, wheelSelection, cancellationContext, now);
		}

		return updatePressed(keyDown, capture, now);
	}

	/**
	 * Advances presentation-only timing from one GUI/render frame.
	 *
	 * <p>This method may open the wheel once a capture-ready interaction has
	 * reached the long-press threshold, or silently close an already-open wheel
	 * after its maximum duration. It never validates a target, consumes a wheel
	 * selection, or emits an action. The press timestamp remains the baseline
	 * even when the capture arrived asynchronously after the threshold.
	 *
	 * <p>A release observed by a frame does not commit or cancel anything by
	 * itself; the event/frame action path owns the single release action.
	 * Consequently a release between this method and the next frame cannot cause
	 * a duplicate action or make an interaction that never presented as a wheel
	 * retroactively become a wheel interaction. Such an interaction is still a
	 * short press when the release event/frame arrives, even if the elapsed time
	 * has reached the threshold.
	 */
	public void presentFrame(boolean keyDown) {
		if (phase == PingInteractionPhase.IDLE) {
			return;
		}

		presentFrameAt(keyDown, observeTime());
	}

	/**
	 * Presentation counterpart to {@link #updateAt(boolean, WheelSelection,
	 * CancellationContext, long)}.  The caller supplies the same frame timestamp
	 * to both paths so one rendered frame cannot cross a compatibility boundary
	 * between two clock reads.
	 */
	public void presentFrameAt(boolean keyDown, long observedTimeMillis) {
		if (phase == PingInteractionPhase.IDLE) {
			return;
		}

		long now = observeTimeValue(observedTimeMillis);

		if (!activeInteraction.isCurrent(token)) {
			logger.debug("interaction superseded: token={}", token.sequence());
			resetMachineState();
			return;
		}

		Optional<CapturedPingContext> capture = activeInteraction.currentContext();

		if (capture.isPresent() && capture.get().token() != token) {
			logger.debug("interaction superseded: token={}", token.sequence());
			resetMachineState();
			return;
		}

		if (phase == PingInteractionPhase.WHEEL_OPEN) {
			long openDuration = now - wheelOpenTimeMillis;

			if (openDuration >= wheelTimeoutMillis) {
				logger.debug("wheel timeout: token={} openMillis={}", token.sequence(), openDuration);
				resetMachineState();
			}

			return;
		}

		if (!keyDown || capture.isEmpty()) {
			return;
		}

		long elapsed = now - pressTimeMillis;

		if (elapsed >= longPressMillis) {
			openWheel(capture.get(), now);
		}
	}

	/**
	 * The current lifecycle phase.
	 */
	public PingInteractionPhase phase() {
		return phase;
	}

	/**
	 * The token of the current interaction; empty when idle.
	 */
	public Optional<InteractionToken> currentToken() {
		return Optional.ofNullable(token);
	}

	/**
	 * The frozen, ordered ping type list for the open wheel; empty when the
	 * wheel is not open.
	 */
	public List<PingType> wheelPingTypes() {
		return wheelPingTypes;
	}

	/**
	 * The current wheel selection (never null; {@link WheelSelection#NONE} when
	 * nothing is selected).
	 */
	public WheelSelection selection() {
		return selection;
	}

	private Optional<PingInteractionAction> updatePressed(
		boolean keyDown,
		Optional<CapturedPingContext> capture,
		long now
	) {
		if (capture.isEmpty()) {
			if (!keyDown && !releaseObserved) {
				releaseObserved = true;
				logger.debug("release pending capture: token={}", token.sequence());
			}

			// Wait for the capture indefinitely: no invented timeout.
			return Optional.empty();
		}

		CapturedPingContext context = capture.get();

		if (!keyDown) {
			if (!releaseObserved) {
				releaseObserved = true;
			}

			// The wheel is a real interaction only after presentFrame() has opened
			// it. If release wins before that transition, commit the captured
			// target's default ping regardless of tick-quantized elapsed time. This
			// also handles a capture that completes after the key was released.
			return commitPing(context, context.resolvedTarget().targetType().defaultPingType());
		}

		// Key still down: presentation-only threshold handling belongs to
		// presentFrame(), never to the tick/action path.
		return Optional.empty();
	}

	private Optional<PingInteractionAction> updateWheelOpen(
		boolean keyDown,
		WheelSelection wheelSelection,
		CancellationContext cancellationContext,
		long now
	) {
		// Rendering normally owns visible timeout transitions, but a release tick
		// can arrive after the last frame. The same observed monotonic timestamp
		// must win before selection or release can commit an action.
		long openDuration = now - wheelOpenTimeMillis;

		if (openDuration >= wheelTimeoutMillis) {
			logger.debug("wheel timeout: token={} openMillis={}", token.sequence(), openDuration);
			resetMachineState();
			return Optional.empty();
		}

		WheelSelection effective = normalizeSelection(wheelSelection);

		if (!effective.equals(selection)) {
			selection = effective;
			logger.debug("wheel selection: token={} selection={}", token.sequence(), describe(effective));
		}

		if (!keyDown) {
			return commitWheelSelection(cancellationContext);
		}

		return Optional.empty();
	}

	private Optional<PingInteractionAction> commitWheelSelection(CancellationContext cancellationContext) {
		WheelSelection committed = selection;

		if (committed instanceof WheelSelection.Sector sector) {
			return commitPing(capturedContext, sector.pingType());
		}

		if (committed == WheelSelection.CENTER) {
			Optional<CancelMarkerCandidate> candidate = cancelCandidatePicker.pick(cancellationContext);

			if (candidate.isPresent()) {
				logger.debug("cancel selected: token={} candidateCount={} markerId={}",
					token.sequence(), cancellationContext.candidates().size(), candidate.get().markerId().value());
				resetMachineState();
				return Optional.of(new PingInteractionAction.CancelMarker(candidate.get().markerId()));
			}

			logger.debug("cancel empty: token={} candidateCount={}",
				token.sequence(), cancellationContext.candidates().size());
			resetMachineState();
			return Optional.empty();
		}

		// No selection (or an invalid sector normalised to None): no action.
		resetMachineState();
		return Optional.empty();
	}

	private Optional<PingInteractionAction> commitPing(CapturedPingContext context, PingType pingType) {
		logger.debug("ping commit: token={} kind={} pingType={}",
			token.sequence(), context.resolvedTarget().target().kind(), pingType.id());

		TargetValidation validation = targetValidator.validate(context.resolvedTarget());

		if (validation.isValid()) {
			resetMachineState();
			return Optional.of(new PingInteractionAction.CreatePing(context, pingType));
		}

		TargetGoneReason reason = validation.goneReason().orElseThrow();
		logger.debug("target gone: token={} kind={} reason={}",
			token.sequence(), context.resolvedTarget().target().kind(), reason);
		resetMachineState();
		return Optional.of(new PingInteractionAction.TargetGone(context, reason));
	}

	private void openWheel(CapturedPingContext context, long now) {
		long configuredTimeoutMillis = readConfiguredThreshold(
			"wheelTimeoutMillis",
			wheelTimeoutMillisSupplier,
			ClientConfigBounds.MIN_WHEEL_TIMEOUT_MILLIS,
			ClientConfigBounds.MAX_WHEEL_TIMEOUT_MILLIS);
		this.capturedContext = context;
		this.wheelPingTypes = List.copyOf(context.resolvedTarget().targetType().pingTypes());
		this.wheelOpenTimeMillis = now;
		this.wheelTimeoutMillis = configuredTimeoutMillis;
		this.selection = WheelSelection.NONE;
		this.phase = PingInteractionPhase.WHEEL_OPEN;
		logger.debug("wheel open: token={} pingTypeCount={}", token.sequence(), wheelPingTypes.size());
	}

	private WheelSelection normalizeSelection(WheelSelection wheelSelection) {
		if (wheelSelection instanceof WheelSelection.Sector sector
			&& !wheelPingTypes.contains(sector.pingType())) {
			return WheelSelection.NONE;
		}

		return wheelSelection;
	}

	private String describe(WheelSelection selection) {
		if (selection instanceof WheelSelection.Sector sector) {
			return "Sector(" + sector.pingType().id() + ")";
		}

		return selection.toString();
	}

	private long observeTime() {
		long now = timeSource.nowMillis();
		return observeTimeValue(now);
	}

	private long observeTimeValue(long now) {

		if (now < lastObservedTimeMillis) {
			throw new IllegalStateException(
				"interaction time moved backwards: " + now + " < " + lastObservedTimeMillis);
		}

		lastObservedTimeMillis = now;
		return now;
	}

	private void resetMachineState() {
		phase = PingInteractionPhase.IDLE;
		token = null;
		capturedContext = null;
		pressTimeMillis = 0L;
		wheelOpenTimeMillis = 0L;
		longPressMillis = LONG_PRESS_MILLIS;
		wheelTimeoutMillis = WHEEL_TIMEOUT_MILLIS;
		releaseObserved = false;
		selection = WheelSelection.NONE;
		wheelPingTypes = List.of();
	}

	private long readConfiguredThreshold(
		String settingName,
		LongSupplier supplier,
		int minimum,
		int maximum
	) {
		long value = supplier.getAsLong();

		if (supplierValuesUseClientConfigBounds) {
			if (value < minimum || value > maximum) {
				throw new IllegalArgumentException(
					settingName + " must be in [" + minimum + ", " + maximum + "], got " + value);
			}
		} else if (value <= 0L) {
			throw new IllegalArgumentException(settingName + " must be positive: " + value);
		}

		return value;
	}

	private static LongSupplier constantThresholdSupplier(String settingName, long value) {
		if (value <= 0L) {
			throw new IllegalArgumentException(settingName + " must be positive: " + value);
		}

		return () -> value;
	}
}
