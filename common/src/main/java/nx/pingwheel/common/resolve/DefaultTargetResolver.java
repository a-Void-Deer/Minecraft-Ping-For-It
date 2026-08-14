package nx.pingwheel.common.resolve;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.domain.TargetResolver;
import nx.pingwheel.common.domain.TargetType;
import nx.pingwheel.common.domain.TargetTypeCatalog;

/**
 * The deterministic {@link TargetResolver} implementation.
 *
 * <p>Resolution iterates {@link TargetTypeCatalog#resolutionOrder()} only, so
 * the result never depends on unordered iteration. For each candidate in order:
 * a missing matcher binding and an inactive matcher are skipped; the first
 * matcher reporting {@link TargetMatchResult#MATCH} wins and produces a new
 * immutable {@link ResolvedTarget} that is never re-resolved. Each candidate is
 * evaluated exactly once via {@link TargetMatcher#evaluate}, so active and
 * match state are observed together. If no candidate matches, an
 * {@link IllegalStateException} is thrown because the catalog contract
 * guarantees a location fallback.
 *
 * <p>Debug logging is emitted at this orchestration boundary only, using safe
 * fields (target kind, dimension id, candidate count, target type ids). Custom
 * names, player names, item/entity names, and registry lookups are never logged.
 */
public final class DefaultTargetResolver implements TargetResolver {

	private final TargetTypeCatalog catalog;
	private final TargetMatcherRegistry matchers;
	private final TargetResolutionLogger logger;

	public DefaultTargetResolver(TargetTypeCatalog catalog, TargetMatcherRegistry matchers, TargetResolutionLogger logger) {
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.matchers = Objects.requireNonNull(matchers, "matchers");
		this.logger = Objects.requireNonNull(logger, "logger");
	}

	/**
	 * A resolver wired to the confirmed built-in catalog and matchers.
	 */
	public static DefaultTargetResolver builtIn(TargetResolutionLogger logger) {
		return new DefaultTargetResolver(TargetTypeCatalog.builtIn(), BuiltInTargetMatchers.registry(), logger);
	}

	@Override
	public ResolvedTarget resolve(Target target, TargetMatchContext context) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(context, "context");

		List<TargetType> order = catalog.resolutionOrder();

		logger.debug("resolve start: kind={} dimension={} candidates={}",
			target.kind(), target.dimensionId(), order.size());

		for (TargetType targetType : order) {
			Optional<TargetMatcher> found = matchers.find(targetType.id());

			if (found.isEmpty()) {
				logger.debug("skip '{}': no matcher bound", targetType.id());
				continue;
			}

			TargetMatcher matcher = found.get();
			TargetMatchResult result = matcher.evaluate(target, context);

			if (result == TargetMatchResult.INACTIVE) {
				logger.debug("skip '{}': matcher inactive", targetType.id());
				continue;
			}

			if (result == TargetMatchResult.NO_MATCH) {
				logger.debug("no match '{}'", targetType.id());
				continue;
			}

			logger.debug("resolved target type='{}' priority={}", targetType.id(), targetType.priority());
			return new ResolvedTarget(target, targetType);
		}

		logger.debug("no-match invariant violated: kind={}", target.kind());
		throw new IllegalStateException(
			"target resolution produced no match; a location fallback target type is required");
	}
}
