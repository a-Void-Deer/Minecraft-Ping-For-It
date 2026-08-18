package nx.pingwheel.common.resolve;

/**
 * The single, internally consistent outcome of evaluating a
 * {@link TargetMatcher} against a captured target and its transient context.
 *
 * <p>A matcher reports exactly one of three states per evaluation:
 * <ul>
 *   <li>{@link #INACTIVE} — the matcher's optional content is entirely
 *       unavailable, so it must be skipped;</li>
 *   <li>{@link #NO_MATCH} — the matcher is active but the target does not
 *       satisfy its classification rule;</li>
 *   <li>{@link #MATCH} — the matcher is active and the target satisfies it.</li>
 * </ul>
 *
 * <p>This avoids callers separately asking {@code isActive()} then
 * {@code matches()}, which could observe inconsistent state from an optional
 * registry whose answers change between the two calls.
 */
public enum TargetMatchResult {
	INACTIVE,
	NO_MATCH,
	MATCH
}
