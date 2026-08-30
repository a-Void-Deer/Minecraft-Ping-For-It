package nx.pingwheel.common.client.outline;

/**
 * Result of the world-aware baked-model outline seam.
 *
 * <p>{@link #UNHANDLED} means that no adapter claimed the target. The
 * entity-block baked source has no common virtual {@code BlockDisplay}
 * equivalent, so this result is an empty built-in source attempt. The other
 * results are only returned after an adapter claimed the target and likewise
 * never fall back to a virtual display.</p>
 */
public enum WorldAwareBlockModelOutlineOutcome {
	/** No registered adapter claimed this target. */
	UNHANDLED,
	/** A claimed adapter emitted and committed a complete model outline. */
	RENDERED,
	/** A claimed adapter emitted no geometry. */
	EMPTY,
	/** A claimed adapter failed before a complete model could be committed. */
	FAILED
}
