package nx.pingwheel.common.domain;

/**
 * Resolves a captured {@link Target} into a {@link ResolvedTarget} by selecting
 * the winning {@link TargetType}.
 *
 * <p>This is intentionally a contract only. Implementations must:
 * <ul>
 *   <li>evaluate all currently active {@link TargetType}s deterministically,
 *       using ascending numeric priority with equal-priority declaration-order
 *       tie-breaking, and ignoring inactive definitions whose optional content
 *       is entirely unavailable;</li>
 *   <li>always return a non-null {@link ResolvedTarget};</li>
 *   <li>guarantee a result via the pure location fallback when no concrete
 *       block/entity type matches.</li>
 * </ul>
 *
 * <p>{@code context} carries only transient, non-identity data needed to
 * distinguish finer target specializations (for example an entity type id); it
 * must not be added to {@link ResolvedTarget} and must not be re-derived during
 * the interaction.
 *
 * <p>That resolver/orchestration boundary is the first appropriate site for
 * debug logging; this interface and the pure domain values stay logger-free.
 */
@FunctionalInterface
public interface TargetResolver {

	ResolvedTarget resolve(Target target, TargetMatchContext context);
}
