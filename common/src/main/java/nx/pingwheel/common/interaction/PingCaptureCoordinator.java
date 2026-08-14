package nx.pingwheel.common.interaction;

import java.util.Objects;
import java.util.Optional;

import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.TargetResolver;

/**
 * Orchestrates key-down capture into a frozen {@link CapturedPingContext}.
 *
 * <p>{@link #begin()} mints a fresh token (superseding any prior interaction).
 * {@link #complete(InteractionToken, TargetSnapshot)} is the single entry point
 * used both synchronously at key-down and as the asynchronous callback guard:
 * it checks staleness <em>before</em> resolution, resolves exactly once,
 * atomically completes only if the token is still current, and checks again via
 * {@link ActiveInteraction} so a race or a newer press is rejected. A stale
 * asynchronous result A can therefore never overwrite a newer press B, and the
 * first accepted result for a token stays frozen.
 *
 * <p>This class never cancels futures and never touches client state; timing,
 * wheel, and cancellation behavior are reserved for the phase-5 state machine.
 */
public final class PingCaptureCoordinator {

	private final TargetResolver targetResolver;
	private final ActiveInteraction activeInteraction;
	private final PingCaptureLogger logger;

	public PingCaptureCoordinator(
		TargetResolver targetResolver,
		ActiveInteraction activeInteraction,
		PingCaptureLogger logger
	) {
		this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver");
		this.activeInteraction = Objects.requireNonNull(activeInteraction, "activeInteraction");
		this.logger = Objects.requireNonNull(logger, "logger");
	}

	/**
	 * Starts a new interaction, superseding any prior token, and returns the
	 * fresh token.
	 */
	public InteractionToken begin() {
		InteractionToken token = activeInteraction.begin();
		logger.debug("capture begin: token={}", token.sequence());
		return token;
	}

	/**
	 * Resolves {@code snapshot} and completes {@code token}, if it is still the
	 * current interaction.
	 *
	 * <p>Returns the frozen capture when the token is current and has not
	 * already completed; returns {@link Optional#empty()} when the token is
	 * stale (superseded by a newer press) or already completed, leaving the
	 * frozen capture (if any) untouched.
	 *
	 * <p>A {@link RuntimeException} thrown by the resolver is contained here:
	 * a safe debug message is logged (token sequence, target kind, dimension id,
	 * and exception simple class name only) and {@link Optional#empty()} is
	 * returned, leaving the token current and uncompleted so a caller may retry.
	 */
	public Optional<CapturedPingContext> complete(InteractionToken token, TargetSnapshot snapshot) {
		Objects.requireNonNull(token, "token");
		Objects.requireNonNull(snapshot, "snapshot");

		if (!activeInteraction.isCurrent(token)) {
			logger.debug("capture reject: stale token={} kind={} dimension={}",
				token.sequence(), snapshot.target().kind(), snapshot.target().dimensionId());
			return Optional.empty();
		}

		logger.debug("capture resolve start: token={} kind={} dimension={}",
			token.sequence(), snapshot.target().kind(), snapshot.target().dimensionId());

		ResolvedTarget resolved;

		try {
			resolved = targetResolver.resolve(snapshot.target(), snapshot.matchContext());
		} catch (RuntimeException failure) {
			// Contain the resolver failure: never propagate resolver internals, never
			// complete the token, and never log the exception message or any target
			// identity beyond the safe kind/dimension fields.
			logger.debug("capture reject: resolve failure token={} kind={} dimension={} cause={}",
				token.sequence(), snapshot.target().kind(), snapshot.target().dimensionId(),
				failure.getClass().getSimpleName());
			return Optional.empty();
		}

		CapturedPingContext context = new CapturedPingContext(token, resolved);

		if (!activeInteraction.tryComplete(token, context)) {
			logger.debug("capture reject: race/duplicate token={} kind={} dimension={}",
				token.sequence(), snapshot.target().kind(), snapshot.target().dimensionId());
			return Optional.empty();
		}

		logger.debug("capture accepted: token={} kind={} dimension={} targetType={}",
			token.sequence(), resolved.target().kind(), resolved.target().dimensionId(),
			resolved.targetType().id());

		return Optional.of(context);
	}
}
