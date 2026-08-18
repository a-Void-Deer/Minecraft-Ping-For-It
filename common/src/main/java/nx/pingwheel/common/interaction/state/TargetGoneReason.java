package nx.pingwheel.common.interaction.state;

/**
 * Why a captured target failed validation before a marker could be created.
 *
 * <p>Each value maps directly to the phase-6 authoritative validation contract:
 * an entity that no longer exists or is dead, a target that moved to another
 * dimension, or a block whose block type at the captured position changed.
 */
public enum TargetGoneReason {
	ENTITY_GONE_OR_DEAD,
	DIMENSION_CHANGED,
	BLOCK_REPLACED
}
