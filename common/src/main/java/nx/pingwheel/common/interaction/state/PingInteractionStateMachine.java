package nx.pingwheel.common.interaction.state;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import nx.pingwheel.common.config.ClientConfigBounds;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.Target;
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
 * {@link IllegalStateException}. Presentation-only threshold transitions are
 * driven by {@link #presentFrame(boolean)} on GUI/render
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

	private final PingCaptureCoordinator coordinator;
	private final ActiveInteraction activeInteraction;
	private final InteractionTimeSource timeSource;
	private final TargetValidator targetValidator;
	private final CancelCandidatePicker cancelCandidatePicker;
	private final PingInteractionLogger logger;
	private final LongSupplier wheelHoldMillisSupplier;
	private final boolean supplierValuesUseClientConfigBounds;
	private long longPressMillis = LONG_PRESS_MILLIS;

	private PingInteractionPhase phase = PingInteractionPhase.IDLE;
	private InteractionToken token;
	/** Release ports may re-enter an abort after the visible machine is terminal. */
	private InteractionToken releasingSelectorToken;
	private CapturedPingContext capturedContext;
	private long pressTimeMillis;
	private long lastObservedTimeMillis;
	private boolean releaseObserved;
	private WheelSelection selection = WheelSelection.NONE;
	private List<PingType> wheelPingTypes = List.of();
	private long presentedRevision;
	private Map<String, CapturedPingContext> presentedPrecise = Map.of();

	/**
	 * Actual paint admission, not a frame/tick readiness signal. The runtime calls
	 * this only after drawing the selector's immutable publication. A mode flag on
	 * a release cannot authorize a cross-ray candidate absent this exact table.
	 */
	public boolean markSelectorPresented(InteractionToken expectedToken, long revision,
		Map<String, CapturedPingContext> contexts) {
		Objects.requireNonNull(contexts);
		if (token != expectedToken || phase != PingInteractionPhase.WHEEL_OPEN || revision <= 0
			|| revision < presentedRevision || !activeInteraction.isCurrent(expectedToken)) return false;
		for (var value : contexts.values()) if (value.token() != expectedToken
			|| !value.resolvedTarget().target().dimensionId().equals(capturedContext.resolvedTarget().target().dimensionId())) return false;
		presentedPrecise = Map.copyOf(contexts); presentedRevision = revision;
		return true;
	}

	/**
	 * Creates a state machine with the default long-press threshold.
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
			() -> LONG_PRESS_MILLIS);
	}

	/**
	 * Creates a state machine whose long-press threshold is read lazily from the
	 * supplied provider. The hold threshold is read once by {@link #press()}, so
	 * changing a live config never changes an interaction already in progress.
	 */
	public PingInteractionStateMachine(
		PingCaptureCoordinator coordinator,
		ActiveInteraction activeInteraction,
		InteractionTimeSource timeSource,
		TargetValidator targetValidator,
		CancelCandidatePicker cancelCandidatePicker,
		PingInteractionLogger logger,
		LongSupplier wheelHoldMillisSupplier
	) {
		this(
			coordinator,
			activeInteraction,
			timeSource,
			targetValidator,
			cancelCandidatePicker,
			logger,
			wheelHoldMillisSupplier,
			true);
	}

	/**
	 * Creates a state machine with a custom positive long-press threshold.
	 *
	 * <p>Package-private test seam: production callers use the default
	 * threshold above.
	 */
	PingInteractionStateMachine(
		PingCaptureCoordinator coordinator,
		ActiveInteraction activeInteraction,
		InteractionTimeSource timeSource,
		TargetValidator targetValidator,
		CancelCandidatePicker cancelCandidatePicker,
		PingInteractionLogger logger,
		long longPressMillis
	) {
		this(
			coordinator,
			activeInteraction,
			timeSource,
			targetValidator,
			cancelCandidatePicker,
			logger,
			constantThresholdSupplier("longPressMillis", longPressMillis),
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
		boolean supplierValuesUseClientConfigBounds
	) {
		this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
		this.activeInteraction = Objects.requireNonNull(activeInteraction, "activeInteraction");
		this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
		this.targetValidator = Objects.requireNonNull(targetValidator, "targetValidator");
		this.cancelCandidatePicker = Objects.requireNonNull(cancelCandidatePicker, "cancelCandidatePicker");
		this.logger = Objects.requireNonNull(logger, "logger");
		this.wheelHoldMillisSupplier = Objects.requireNonNull(wheelHoldMillisSupplier, "wheelHoldMillisSupplier");
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
		if (releasingSelectorToken != null) {
			activeInteraction.invalidate(releasingSelectorToken);
			releasingSelectorToken = null;
		}

		resetMachineState();
	}

	/**
	 * Releases an actually opened native selector. All ownership checks precede
	 * proposal evaluation; an old caller cannot release a newer interaction or
	 * advance its clock. Pre-open/default release remains on
	 * {@link #updateAt(boolean, WheelSelection, CancellationContext, long)}.
	 *
	 * <p>The candidate lookup must be the immutable table bound to this selector,
	 * not a resolver or a world read. Ordinary contexts retain the press ray;
	 * precise contexts must match the acknowledged paint table with their own
	 * capture ray and metadata. Cancellation context is acquired lazily
	 * only for an explicit Cancel. The machine is terminal before invoking any
	 * caller port, so duplicate/reentrant releases and failing ports cannot commit
	 * the same interaction twice. The result authorizes no sender side effect by
	 * itself; the runtime still owns dispatch and its actual outcome.
	 */
	public <P> SelectorReleaseResult<P> releaseSelectorAt(
		InteractionToken expectedToken,
		long observedTimeMillis,
		Supplier<SelectorReleaseProposal<P>> proposalSupplier,
		Function<String, Optional<CapturedPingContext>> frozenCandidateContext,
		Supplier<CancellationContext> cancellationContextSupplier
	) {
		Objects.requireNonNull(expectedToken, "expectedToken");
		Objects.requireNonNull(proposalSupplier, "proposalSupplier");
		Objects.requireNonNull(frozenCandidateContext, "frozenCandidateContext");
		Objects.requireNonNull(cancellationContextSupplier, "cancellationContextSupplier");
		if (token != expectedToken || phase != PingInteractionPhase.WHEEL_OPEN) {
			return SelectorReleaseResult.empty();
		}

		observeTimeValue(observedTimeMillis);
		Optional<CapturedPingContext> capture = activeInteraction.currentContext();
		if (!activeInteraction.isCurrent(expectedToken) || capture.isEmpty()
			|| capture.get() != capturedContext || capture.get().token() != expectedToken) {
			resetMachineState();
			return SelectorReleaseResult.empty();
		}

		CapturedPingContext openedCapture = capturedContext;
		Map<String, CapturedPingContext> painted = presentedPrecise;
		long paintRevision = presentedRevision;
		resetMachineState();
		releasingSelectorToken = expectedToken;
		try {
			return admitSelectorProposal(expectedToken, openedCapture, proposalSupplier,
				frozenCandidateContext, cancellationContextSupplier, painted, paintRevision);
		} finally {
			if (releasingSelectorToken == expectedToken) releasingSelectorToken = null;
		}
	}

	private <P> SelectorReleaseResult<P> admitSelectorProposal(InteractionToken expectedToken,
		CapturedPingContext openedCapture, Supplier<SelectorReleaseProposal<P>> proposalSupplier,
		Function<String, Optional<CapturedPingContext>> frozenCandidateContext,
		Supplier<CancellationContext> cancellationContextSupplier, Map<String, CapturedPingContext> painted, long paintRevision) {
		SelectorReleaseProposal<P> proposal = Objects.requireNonNull(proposalSupplier.get(), "proposal");
		if (!activeInteraction.isCurrent(expectedToken)) return SelectorReleaseResult.empty();
		return switch (proposal) {
			case SelectorReleaseProposal.None<P> ignored -> SelectorReleaseResult.empty();
			case SelectorReleaseProposal.Local<P> local ->
				new SelectorReleaseResult<>(Optional.of(local.intent()), Optional.empty());
			case SelectorReleaseProposal.Cancel<P> cancel -> {
				CancellationContext cancellation = Objects.requireNonNull(cancellationContextSupplier.get(), "cancellationContext");
				yield activeInteraction.isCurrent(expectedToken)
					? new SelectorReleaseResult<>(Optional.of(cancel.intent()), pickCancellation(expectedToken, cancellation))
					: SelectorReleaseResult.empty();
			}
			case SelectorReleaseProposal.Create<P> create -> {
				Optional<CapturedPingContext> candidate = Objects.requireNonNull(
					frozenCandidateContext.apply(create.candidateId()), "candidateContext");
				if (!activeInteraction.isCurrent(expectedToken) || candidate.isEmpty()
					|| !isSelectorCandidate(candidate.get(), openedCapture, create, painted, paintRevision)) {
					yield SelectorReleaseResult.empty();
				}
				Optional<PingInteractionAction> action = validatePing(candidate.get(), create.pingType());
				if (!activeInteraction.isCurrent(expectedToken)) yield SelectorReleaseResult.empty();
				yield new SelectorReleaseResult<>(action.orElseThrow() instanceof PingInteractionAction.CreatePing
					? Optional.of(create.intent()) : Optional.empty(), action);
			}
		};
	}

	private boolean isSelectorCandidate(CapturedPingContext candidate, CapturedPingContext openedCapture,
		SelectorReleaseProposal.Create<?> proposal, Map<String, CapturedPingContext> painted, long paintRevision) {
		return candidate.token() == openedCapture.token()
			&& (proposal.admission() == SelectorReleaseProposal.Admission.PRESS_RAY
				? candidate.ray().equals(openedCapture.ray())
				: proposal.presentationRevision() == paintRevision && painted.get(proposal.candidateId()) == candidate)
			&& candidate.resolvedTarget().target().dimensionId().equals(openedCapture.resolvedTarget().target().dimensionId())
			&& candidate.resolvedTarget().equals(proposal.target())
			&& (!(candidate.resolvedTarget().target() instanceof Target.ExternalBlockTarget external)
				|| proposal.target().target() instanceof Target.ExternalBlockTarget claimedExternal
					&& external.providerLocator().equals(claimedExternal.providerLocator()))
			&& candidate.resolvedTarget().targetType().pingTypes().contains(proposal.pingType());
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
			return updateWheelOpen(keyDown, wheelSelection, cancellationContext);
		}

		return updatePressed(keyDown, capture, now);
	}

	/**
	 * Advances presentation-only timing from one GUI/render frame.
	 *
	 * <p>This method may open the wheel once a capture-ready interaction has
	 * reached the long-press threshold. It never validates a target, consumes a
	 * wheel selection, or emits an action. The press timestamp remains the
	 * baseline even when the capture arrived asynchronously after the threshold.
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
			return;
		}

		if (!keyDown || capture.isEmpty()) {
			return;
		}

		long elapsed = now - pressTimeMillis;

		if (elapsed >= longPressMillis) {
			openWheel(capture.get());
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
		CancellationContext cancellationContext
	) {
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
			InteractionToken releasedToken = token;
			resetMachineState();
			return pickCancellation(releasedToken, cancellationContext);
		}

		// No selection (or an invalid sector normalised to None): no action.
		resetMachineState();
		return Optional.empty();
	}

	private Optional<PingInteractionAction> commitPing(CapturedPingContext context, PingType pingType) {
		resetMachineState();
		return validatePing(context, pingType);
	}

	private Optional<PingInteractionAction> pickCancellation(InteractionToken releasedToken,
		CancellationContext cancellationContext) {
		Optional<CancelMarkerCandidate> candidate = cancelCandidatePicker.pick(cancellationContext);
		if (candidate.isPresent()) {
			logger.debug("cancel selected: token={} candidateCount={} markerId={}",
				releasedToken.sequence(), cancellationContext.candidates().size(), candidate.get().markerId().value());
			return Optional.of(new PingInteractionAction.CancelMarker(candidate.get().markerId()));
		}
		logger.debug("cancel empty: token={} candidateCount={}",
			releasedToken.sequence(), cancellationContext.candidates().size());
		return Optional.empty();
	}

	private Optional<PingInteractionAction> validatePing(CapturedPingContext context, PingType pingType) {
		logger.debug("ping commit: token={} kind={} pingType={}",
			context.token().sequence(), context.resolvedTarget().target().kind(), pingType.id());

		TargetValidation validation = targetValidator.validate(context.resolvedTarget());

		if (validation.isValid()) {
			return Optional.of(new PingInteractionAction.CreatePing(context, pingType));
		}

		TargetGoneReason reason = validation.goneReason().orElseThrow();
		logger.debug("target gone: token={} kind={} reason={}",
			context.token().sequence(), context.resolvedTarget().target().kind(), reason);
		return Optional.of(new PingInteractionAction.TargetGone(context, reason));
	}

	private void openWheel(CapturedPingContext context) {
		this.capturedContext = context;
		this.wheelPingTypes = List.copyOf(context.resolvedTarget().targetType().pingTypes());
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
		longPressMillis = LONG_PRESS_MILLIS;
		releaseObserved = false;
		selection = WheelSelection.NONE;
		wheelPingTypes = List.of();
		presentedRevision = 0; presentedPrecise = Map.of();
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
