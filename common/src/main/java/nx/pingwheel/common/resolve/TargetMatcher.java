package nx.pingwheel.common.resolve;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;

/**
 * A matcher deciding whether a captured {@link Target} satisfies a single
 * {@link nx.pingwheel.common.domain.TargetType} classification rule.
 *
 * <p>Matchers must stay side-effect free and deterministic. The
 * {@link TargetMatchContext} carries only transient, non-identity data (for
 * example the entity type id needed to tell a dropped item from a generic
 * entity); it never participates in target identity and is never frozen.
 *
 * <p>Both {@code target} and {@code context} are always non-null in
 * {@link #matches(Target, TargetMatchContext)} and
 * {@link #evaluate(Target, TargetMatchContext)}. The resolver enforces this
 * contract, so matcher implementations may rely on it instead of re-checking.
 */
@FunctionalInterface
public interface TargetMatcher {

	/**
	 * Whether this matcher is currently usable. The default is {@code true}.
	 * A matcher backed entirely by unavailable optional content returns
	 * {@code false} here rather than throwing.
	 */
	default boolean isActive() {
		return true;
	}

	/**
	 * Whether {@code target} satisfies this classification rule given
	 * {@code context}. Both arguments are non-null.
	 */
	boolean matches(Target target, TargetMatchContext context);

	/**
	 * The single, internally consistent outcome of evaluating this matcher
	 * against {@code target} and {@code context}. Both arguments are non-null.
	 *
	 * <p>The default implementation derives the result from
	 * {@link #isActive()} and {@link #matches(Target, TargetMatchContext)} and
	 * is sufficient for static/custom matchers whose active state cannot change
	 * between the two checks. Matchers backed by optional registry lookups
	 * should override it so that presence and matching are observed together,
	 * querying each referenced entry at most once per evaluation.
	 */
	default TargetMatchResult evaluate(Target target, TargetMatchContext context) {
		if (!isActive()) {
			return TargetMatchResult.INACTIVE;
		}

		return matches(target, context) ? TargetMatchResult.MATCH : TargetMatchResult.NO_MATCH;
	}
}
