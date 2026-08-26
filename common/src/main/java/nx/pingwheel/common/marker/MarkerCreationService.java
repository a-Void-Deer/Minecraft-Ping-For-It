package nx.pingwheel.common.marker;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.domain.TargetResolver;
import nx.pingwheel.common.domain.TargetType;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProviderRegistry;
import nx.pingwheel.common.name.AuthoritativeTargetNameResolver;
import nx.pingwheel.common.name.TargetNameJson;

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
 * <p>An accepted outcome carries the validator's {@code authoritativeName}
 * unchanged: the service never derives, rewrites, or validates the name itself
 * beyond the validator's own guarantees. Rejected outcomes carry no name and
 * are produced without mutating the store. The service never trusts
 * client-supplied target classification, display names, colors, or ownership:
 * the API exposes no target type, name, or color input, and the resolved
 * target type comes exclusively from the server-side resolver.
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
	private final ExternalBlockServerProviderRegistry externalProviders;
	private final AuthoritativeTargetNameResolver nameResolver;

	public MarkerCreationService(
		ServerMarkerStore store,
		TargetResolver resolver,
		PingTypeCatalog catalog,
		AuthoritativeTargetValidator validator
	) {
		this(
			store,
			resolver,
			catalog,
			validator,
			MarkerCreationLogger.noop(),
			new ExternalBlockServerProviderRegistry(),
			null);
	}

	public MarkerCreationService(
		ServerMarkerStore store,
		TargetResolver resolver,
		PingTypeCatalog catalog,
		AuthoritativeTargetValidator validator,
		MarkerCreationLogger logger
	) {
		this(
			store,
			resolver,
			catalog,
			validator,
			logger,
			new ExternalBlockServerProviderRegistry(),
			null);
	}

	public MarkerCreationService(
		ServerMarkerStore store,
		TargetResolver resolver,
		PingTypeCatalog catalog,
		AuthoritativeTargetValidator validator,
		MarkerCreationLogger logger,
		ExternalBlockServerProviderRegistry externalProviders,
		AuthoritativeTargetNameResolver nameResolver
	) {
		this.store = Objects.requireNonNull(store, "store");
		this.resolver = Objects.requireNonNull(resolver, "resolver");
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.validator = Objects.requireNonNull(validator, "validator");
		this.logger = Objects.requireNonNull(logger, "logger");
		this.externalProviders = Objects.requireNonNull(externalProviders, "externalProviders");
		this.nameResolver = nameResolver;
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
		return create(null, owner, requestedTarget, pingTypeId, arrivalTick, expiresAtTick, recipients, null);
	}

	/**
	 * Minecraft-bound creation entry point.  The level is needed only for the
	 * final external-block materialization transaction; ordinary targets follow
	 * exactly the same pipeline as the compatibility overload above.
	 */
	public MarkerCreateOutcome create(
		ServerLevel level,
		UUID owner,
		Target requestedTarget,
		String pingTypeId,
		long arrivalTick,
		long expiresAtTick,
		List<UUID> recipients
	) {
		return create(level, owner, requestedTarget, pingTypeId, arrivalTick, expiresAtTick, recipients, null);
	}

	/**
	 * Test seam for the external materialization transaction. It keeps the
	 * creation pipeline independent of a live Minecraft server while preserving
	 * the production ordering and release rules.
	 */
	MarkerCreateOutcome createWithExternalTransaction(
		ExternalBlockTransaction transaction,
		UUID owner,
		Target requestedTarget,
		String pingTypeId,
		long arrivalTick,
		long expiresAtTick,
		List<UUID> recipients
	) {
		return create(
			null, owner, requestedTarget, pingTypeId, arrivalTick, expiresAtTick, recipients, transaction);
	}

	private MarkerCreateOutcome create(
		ServerLevel level,
		UUID owner,
		Target requestedTarget,
		String pingTypeId,
		long arrivalTick,
		long expiresAtTick,
		List<UUID> recipients,
		ExternalBlockTransaction transaction
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

		Target markerTarget = resolvedTarget.target();
		TargetType markerTargetType = resolvedTarget.targetType();
		MarkerAnchor markerAnchor = validated.anchor();
		TargetNameJson markerName = validated.authoritativeName();
		Target.ExternalBlockTarget materializedTarget = null;

		if (requestedTarget instanceof Target.ExternalBlockTarget
			|| resolvedTarget.target() instanceof Target.ExternalBlockTarget) {
			if (!(resolvedTarget.target() instanceof Target.ExternalBlockTarget external)
				|| (level == null && transaction == null)) {
				return MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_REQUEST);
			}

			ExternalBlockServerProvider.MaterializationResult materialization =
				transaction == null
					? externalProviders.materialize(level, external)
					: transaction.materialize(external);

			if (materialization instanceof ExternalBlockServerProvider.MaterializationResult.TemporarilyUnavailable) {
				return MarkerCreateOutcome.rejected(MarkerRejectReason.TARGET_GONE);
			}

			if (!(materialization instanceof ExternalBlockServerProvider.MaterializationResult.Materialized committed)) {
				return MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_REQUEST);
			}

			ExternalBlockServerProvider.MaterializedTarget materialized = committed.target();
			materializedTarget = materialized.target();
			markerTarget = materialized.target();
			markerAnchor = materialized.anchor();

			Optional<ResolvedTarget> reclassified = resolve(
				materialized.target(), materialized.matchContext());

			if (reclassified.isEmpty()) {
				releaseMaterialized(level, materializedTarget, transaction);
				return MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_REQUEST);
			}

			markerTargetType = reclassified.get().targetType();

			if (!markerTargetType.pingTypes().contains(pingType)) {
				releaseMaterialized(level, materializedTarget, transaction);
				return MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_PING_TYPE);
			}

			markerName = resolveName(owner, markerTarget, markerName);
		}

		try {
			MarkerCreation creation = store.create(
				owner,
				markerTarget,
				markerTargetType,
				pingType,
				markerAnchor,
				arrivalTick,
				expiresAtTick,
				recipients);

			logger.debug("create accepted: id={} targetType={} pingType={}",
				creation.marker().id(), markerTargetType.id(), pingType.id());

			return MarkerCreateOutcome.accepted(creation, markerName);
		} catch (RuntimeException e) {
			if (materializedTarget != null) {
				releaseMaterialized(level, materializedTarget, transaction);
			}

			logger.debugException("create rejected: store contract failure", e);
			return MarkerCreateOutcome.rejected(MarkerRejectReason.INVALID_REQUEST);
		}
	}

	private TargetNameJson resolveName(UUID owner, Target target, TargetNameJson fallback) {
		if (nameResolver == null) {
			return fallback;
		}

		try {
			TargetNameJson resolved = nameResolver.resolveName(owner, target);
			return resolved == null ? fallback : resolved;
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private void releaseMaterialized(
		ServerLevel level, Target.ExternalBlockTarget target, ExternalBlockTransaction transaction
	) {
		if (transaction != null) {
			transaction.release(target);
		} else if (level != null) {
			externalProviders.release(level.getServer(), target);
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
			logger.debugException("create rejected: validator contract failure", e);
			return AuthoritativeTargetValidation.rejected(MarkerRejectReason.INVALID_REQUEST);
		}
	}

	/**
	 * Re-resolves the validator's normalized target, converting a thrown
	 * contract failure (or a null result) into an empty optional so the
	 * pipeline keeps its single error path.
	 */
	private Optional<ResolvedTarget> resolve(ValidatedMarkerTarget validated) {
		return resolve(validated.normalizedTarget(), validated.matchContext());
	}

	private Optional<ResolvedTarget> resolve(Target target, TargetMatchContext context) {
		try {
			ResolvedTarget resolved = resolver.resolve(target, context);

			if (resolved == null) {
				logger.debug("create rejected: resolver returned null");
				return Optional.empty();
			}

			logger.debug("resolved targetType={} priority={}",
				resolved.targetType().id(), resolved.targetType().priority());

			return Optional.of(resolved);
		} catch (RuntimeException e) {
			logger.debugException("create rejected: resolver contract failure", e);
			return Optional.empty();
		}
	}
}

/** Package-private transaction seam used by the external-provider tests. */
interface ExternalBlockTransaction {

	ExternalBlockServerProvider.MaterializationResult materialize(Target.ExternalBlockTarget candidate);

	void release(Target.ExternalBlockTarget committed);
}
