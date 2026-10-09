package nx.pingwheel.common.domain;

/**
 * The native face of a block that the capture ray actually hit.
 *
 * <p>This is capture-only metadata attached to a {@link Target.BlockTarget}
 * or {@link Target.ExternalBlockTarget}. External faces are provider-local native
 * hit directions, never world-transformed directions. It never participates in target, target-key,
 * match-context or marker identity. Inventory protocols may serialize it as
 * read-view context, independently of those identities. The frozen
 * capture context retains it only while target resolution preserves the
 * captured block read binding (including an external locator).
 */
public enum BlockFace {
	DOWN,
	UP,
	NORTH,
	SOUTH,
	WEST,
	EAST
}
