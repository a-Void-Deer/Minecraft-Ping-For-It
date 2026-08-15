package nx.pingwheel.common.marker;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetResolver;

/**
 * Server-side orchestration for marker creation and removal.
 *
 * <p>Creation runs a strictly ordered, authoritative pipeline against the
 * injected {@link ServerMarkerStore}:
 * <ol>
 *   <li><b>Argument check:</b> a null owner/target/ping type id/recipient list
 *       or an invalid lifetime (negative arrival tick, expiry not after
 *       arrival) is rejected as {@link MarkerRejectReason#INVALID_REQUEST}
 *       without touching the validator, resolver, or store.</li>
 *   <li><b>Authoritative validation:</b> the injected
 *       {@link AuthoritativeTargetValidator} re-derives the target against live
 *       world state. A rejection (for example {@code TARGET_GONE} or
 *       {@code OUT_OF_RANGE}) is propagated unchanged. A thrown contract
 *       failure becomes {@code INVALID_REQUEST}.</li>
 *   <li><b>Server re-resolution:</b> the resolver classifies the validator's
 *       <em>normalized</em> target (never the client-supplied one) together
 *       with the validator's match context. A thrown contract failure becomes
 *       {@code INVALID_REQUEST}.</li>
 *   <li><b>Ping type lookup:</b> the requested id must exist in the catalog
 *       and be a member of the resolved target type's ping type set; otherwise
 *       {@code INVALID_PING_TYPE}.</li>
 *   <li><b>Store:</b> the marker is created with the resolved target and target
 *       type, the catalog ping type, the validator's anchor, and the
 *       caller-supplied owner, arrival/expiry ticks, and recipients. A store
 *       contract failure (for example a kind mismatch between the resolved
 *       target type and the target) becomes {@code INVALID_REQUEST}.</li>
 * </ol>
 *
 * <p>Every rejected outcome is produced without mutating the store. The
 * service never trusts client-supplied target classification, display names,
 * colors, or ownership: the API exposes no target type, name, or color input,
 * and the resolved target type comes exclusively from the server-side resolver.
 *
 * <p>Removal delegates to the store: {@link #removeOwned(UUID, MarkerId)} maps
 * the store's {@code NOT_FOUND}/{@code NOT_OWNER} statuses to
 * {@link MarkerRejectReason} for the runtime adapter, and
 * {@link #expire(long)} passes through the store's batch expiry result.
 *
 * <p>Debug logging is emitted at this orchestration boundary only, using safe
 * fields (target kind, dimension id, ping type ids, marker ids). Custom names,
 * player names, colors, and registry lookups are never logged.
 */
public final class MarkerCreationService {

	private final ServerMarkerStore store;
	private final TargetResolver resolver;
	private final PingTypeCatalog catalog;
	private final AuthoritativeTargetValidator validator;
	private final MarkerCreationLogger logger;

	public MarkerCreationService(
		ServerMarkerStore store,
		TargetResolver resolver,
		PingTypeCatalog catalog,
		AuthoritativeTargetValidator validator
	) {
		this(store, resolver, catalog, validator, MarkerCreationLogger.noop());
	}

	public MarkerCreationService(
		ServerMarkerStore store,
		TargetResolver resolver,
		PingTypeCatalog catalog,
		AuthoritativeTargetValidator validator,
		MarkerCreationLogger logger
	) {
		this.store = Objects.requireNonNull(store, "store");
		this.resolver = Objects.requireNonNull(resolver, "resolver");
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.validator = Objects.requireNonNull(validator, "validator");
		this.logger = Objects.requireNonNull(logger, "logger");
	}

	/**
	 * Runs the authoritative creation pipeline for one requested marker.
	 *
	 * @return an accepted outcome carrying the stored marker, or a rejected
	 *         outcome whose reason explains the failure; a rejected outcome
	 *         guarantees the store was not modified.
	 */
	public MarkerCreateOutcome create(
		UUID owner,
		Target requestedTarget,
		String pingTypeId,
		long arrivalTick,
		long expiresAtTick,
		List<UUID> recipients
	) {
		if (owner == null || requestedTarget == null || pingTypeId == null || recipients == null) {
			logger.debug("create rejected: null owner, target, ping type id, or recipients");
			return MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_REQUEST);
		}

		if (arrivalTick < 0L || expiresAtTick <= arrivalTick) {
			logger.debug(
				"create rejected: invalid lifetime arrivalTick={} expiresAtTick={}", arrivalTick, expiresAtTick);
			return MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_REQUEST);
		}

		logger.debug("create start: kind={} dimension={} pingTypeId={}",
			requestedTarget.kind(), requestedTarget.dimensionId(), pingTypeId);

		AuthoritativeTargetValidation validation = validate(owner, requestedTarget);

		if (!validation.isAccepted()) {
			MarkerRejectReason reason = validation.rejectReason().orElseThrow();

			logger.debug("create rejected by validation: {}", reason);
			return MarkerCreateOutcome.rejected(reason);
		}

		ValidatedMarkerTarget validated = validation.validatedTarget().orElseThrow();

		Optional<ResolvedTarget> resolved = resolve(validated);

		if (resolved.isEmpty()) {
			return MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_REQUEST);
		}

		ResolvedTarget resolvedTarget = resolved.get();

		Optional<PingType> found = catalog.findById(pingTypeId);

		if (found.isEmpty()) {
			logger.debug("create rejected: unknown ping type '{}'", pingTypeId);
			return MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_PING_TYPE);
		}

		PingType pingType = found.get();

		if (!resolvedTarget.targetType().pingTypes().contains(pingType)) {
			logger.debug("create rejected: ping type '{}' not allowed for target type '{}'",
				pingType.id(), resolvedTarget.targetType().id());
			return MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_PING_TYPE);
		}

		try {
			MarkerCreation creation = store.create(
				owner,
				resolvedTarget.target(),
				resolvedTarget.targetType(),
				pingType,
				validated.anchor(),
				arrivalTick,
				expiresAtTick,
				recipients);

			logger.debug("create accepted: id={} targetType={} pingType={}",
				creation.marker().id(), resolvedTarget.targetType().id(), pingType.id());

			return MarkerCreateOutcome.accepted(creation);
		} catch (RuntimeException e) {
			logger.debug("create rejected: store contract failure", e);
			return MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_REQUEST);
		}
	}

	/**
	 * Removes the marker with {@code id} if {@code requester} owns it, delegating
	 * the ownership check and winner recomputation to the store.
	 *
	 * @return empty when the marker was removed (no rejection); otherwise the
	 *         mapped {@link MarkerRejectReason}: {@code NOT_FOUND} when no such
	 *         marker exists and {@code NOT_OWNER} when another player owns it.
	 *         Either rejection leaves the store unmodified.
	 */
	public Optional<MarkerRejectReason> removeOwned(UUID requester, MarkerId id) {
		Objects.requireNonNull(requester, "requester");
		Objects.requireNonNull(id, "id");

		MarkerRemovalResult result = store.removeOwned(requester, id);

		return switch (result.status()) {
			case REMOVED -> Optional.empty();
			case NOT_FOUND -> Optional.of(MarkerRejectReason.NOT_FOUND);
			case NOT_OWNER -> Optional.of(MarkerRejectReason.NOT_OWNER);
		};
	}

	/**
	 * Expires every marker whose lifetime has elapsed at {@code currentTick},
	 * delegating the removal and winner recomputation to the store.
	 */
	public MarkerBatchRemoval expire(long currentTick) {
		return store.expire(currentTick);
	}

	/**
	 * Runs the authoritative validator, converting a thrown contract failure
	 * into a rejected verdict so the pipeline keeps its single error path.
	 */
	private AuthoritativeTargetValidation validate(UUID owner, Target requestedTarget) {
		try {
			AuthoritativeTargetValidation result = validator.validate(owner, requestedTarget);

			if (result == null) {
				logger.debug("create rejected: validator returned null");
				return AuthoritativeTargetValidation.rejected(MarkerRejectReason.INVALID_REQUEST);
			}

			return result;
		} catch (RuntimeException e) {
			logger.debug("create rejected: validator contract failure", e);
			return AuthoritativeTargetValidation.rejected(MarkerRejectReason.INVALID_REQUEST);
		}
	}

	/**
	 * Re-resolves the validator's normalized target, converting a thrown
	 * contract failure (or a null result) into an empty optional so the
	 * pipeline keeps its single error path.
	 */
	private Optional<ResolvedTarget> resolve(ValidatedMarkerTarget validated) {
		try {
			ResolvedTarget resolved = resolver.resolve(validated.normalizedTarget(), validated.matchContext());

			if (resolved == null) {
				logger.debug("create rejected: resolver returned null");
				return Optional.empty();
			}

			logger.debug("resolved targetType={} priority={}",
				resolved.targetType().id(), resolved.targetType().priority());

			return Optional.of(resolved);
		} catch (RuntimeException e) {
			logger.debug("create rejected: resolver contract failure", e);
			return Optional.empty();
		}
	}
}
