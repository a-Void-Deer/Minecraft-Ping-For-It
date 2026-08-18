package nx.pingwheel.common.interaction.state;

import nx.pingwheel.common.domain.ResolvedTarget;

/**
 * Validates a frozen {@link ResolvedTarget} immediately before a marker is
 * created.
 *
 * <p>This is a pure contract only: no Minecraft adapter is provided in this
 * phase. Phase 6 supplies the authoritative server/client validation, which
 * must never be replaced by a stale client-side guess. Implementations must not
 * re-resolve or re-capture the target; they only inspect the already-frozen
 * identity.
 */
@FunctionalInterface
public interface TargetValidator {

	TargetValidation validate(ResolvedTarget resolvedTarget);
}
