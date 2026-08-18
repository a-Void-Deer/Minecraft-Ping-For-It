package nx.pingwheel.common.marker;

import java.util.Objects;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.name.TargetNameJson;

/**
 * The server-authoritative, normalized form of a requested ping target.
 *
 * <p>Produced by an {@link AuthoritativeTargetValidator} and consumed by
 * {@link MarkerCreationService}:
 * <ul>
 *   <li>{@link #normalizedTarget()} is the server's re-derived target identity.
 *       It replaces whatever identity the client sent, so client-supplied
 *       classification is never trusted;</li>
 *   <li>{@link #matchContext()} carries only the transient, non-identity data
 *       needed to distinguish finer specializations (for example an entity type
 *       id for dropped items); it never participates in the target's
 *       identity;</li>
 *   <li>{@link #anchor()} is the server-computed world-space anchor position the
 *       marker must use;</li>
 *   <li>{@link #authoritativeName()} is the server-derived display name JSON
 *       for the target. It is produced by the validator from server state
 *       only; client-supplied names never reach this value.</li>
 * </ul>
 *
 * <p>Only JDK types are used here; there are no {@code net.minecraft}
 * references, so this value can be constructed and validated without a game
 * client. All four components are non-null and immutable.
 */
public record ValidatedMarkerTarget(
	Target normalizedTarget,
	TargetMatchContext matchContext,
	MarkerAnchor anchor,
	TargetNameJson authoritativeName
) {

	public ValidatedMarkerTarget {
		Objects.requireNonNull(normalizedTarget, "normalizedTarget");
		Objects.requireNonNull(matchContext, "matchContext");
		Objects.requireNonNull(anchor, "anchor");
		Objects.requireNonNull(authoritativeName, "authoritativeName");
	}
}
