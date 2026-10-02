package nx.pingwheel.common.domain;

/**
 * The face of an ordinary block that the press-time capture ray hit.
 *
 * <p>This is capture-only metadata attached to an ordinary
 * {@link Target.BlockTarget}. It never participates in target, target-key,
 * match-context or marker identity. Inventory protocols may serialize it as
 * read-view context, independently of those identities. The frozen
 * capture context retains it only while target resolution preserves the
 * captured ordinary block identity.
 */
public enum BlockFace {
	DOWN,
	UP,
	NORTH,
	SOUTH,
	WEST,
	EAST
}
