package nx.pingwheel.common.client.outline;

/**
 * Pure decisions used by the entity-outline render redirects.
 */
public final class EntityOutlineRenderDecision {

	private EntityOutlineRenderDecision() {}

	/**
	 * Returns whether the ordinary ping outline should remain in the vanilla
	 * entity pass. A registered source may suppress that ping route only after
	 * the outline-effect request succeeded and the source claims the entity.
	 */
	public static boolean shouldShowPingOutline(
		boolean pingOutline,
		boolean requestSucceeded,
		boolean sourceHandlesEntity
	) {
		return pingOutline && !(requestSucceeded && sourceHandlesEntity);
	}
}
