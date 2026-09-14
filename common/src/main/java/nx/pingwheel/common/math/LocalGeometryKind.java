package nx.pingwheel.common.math;

/**
 * The native local geometry selected for an owned entity raycast.
 *
 * <p>The distinction is retained with capture metadata so a later optional
 * loader adapter can validate and present the exact local subject that won the
 * press-time ray.</p>
 */
public enum LocalGeometryKind {

	BLOCK,
	FLUID
}
