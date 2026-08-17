package nx.pingwheel.common.util;

/**
 * Pure arbitration rules for a ping binding which may share Pick Block.
 *
 * <p>The mapping counters are intentionally consumed by the caller.  This
 * type only describes which counters may be drained and whether one of the
 * consumed edges is allowed to claim the raw event.  Keeping that decision
 * independent of {@code KeyMapping} makes the Fabric single-slot and
 * Forge/NeoForge multi-mapping cases testable without inspecting private
 * click counters.</p>
 */
public final class PingClickArbitration {

	private PingClickArbitration() {}

	public record Plan(boolean consumePick, boolean consumeCustom, boolean mayClaim) {

		/**
		 * Returns whether the event is claimed after the caller has consumed the
		 * counters named by this plan.
		 */
		public boolean claims(boolean pickConsumed, boolean customConsumed) {
			return mayClaim
				&& ((consumePick && pickConsumed) || (consumeCustom && customConsumed));
		}
	}

	/**
	 * Resolves the counter-drain/claim policy before any mapping counter is
	 * consumed.
	 */
	public static Plan plan(boolean sharedWithPick, boolean currentlyEligible) {
		if (!sharedWithPick) {
			// A dedicated binding has no Pick Block counter to drain.
			return new Plan(false, true, true);
		}

		if (!currentlyEligible) {
			// Preserve vanilla Pick Block while draining a stale custom count.
			return new Plan(false, true, false);
		}

		// Both mappings may have received this same raw event.  Draining both
		// counters handles Forge/NeoForge while the OR claim handles Fabric's
		// single-slot routing to either mapping.
		return new Plan(true, true, true);
	}
}
