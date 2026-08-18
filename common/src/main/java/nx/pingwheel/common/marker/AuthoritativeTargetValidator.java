package nx.pingwheel.common.marker;

import java.util.UUID;

import nx.pingwheel.common.domain.Target;

/**
 * Authoritatively validates a requested ping target on the server before a
 * marker may be created.
 *
 * <p>This is a pure contract only: no Minecraft adapter is provided in this
 * phase. Platform adapters must:
 * <ul>
 *   <li>re-derive the concrete target against live world state (entity exists,
 *       is alive, and is in the captured dimension; block at the captured
 *       position still has the captured block type), rejecting with
 *       {@link MarkerRejectReason#TARGET_GONE} when it is invalid, and with
 *       {@link MarkerRejectReason#OUT_OF_RANGE} or the appropriate reason
 *       otherwise;</li>
 *   <li>never trust client-supplied target classification, display names,
 *       colors, or ownership;</li>
 *   <li>return the server-normalized target, the transient match context needed
 *       for finer specialization (for example an entity type id), and the
 *       server-computed anchor position via
 *       {@link AuthoritativeTargetValidation#accepted(ValidatedMarkerTarget)}.</li>
 * </ul>
 *
 * <p>The requester is provided so validations that depend on the requesting
 * player (for example range measured from the player) remain authoritative.
 * Implementations must not mutate any store state.
 */
@FunctionalInterface
public interface AuthoritativeTargetValidator {

	AuthoritativeTargetValidation validate(UUID requester, Target requestedTarget);
}
