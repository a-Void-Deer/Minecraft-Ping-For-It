package nx.pingwheel.common.client.outline;

/**
 * Result of the world-aware baked-model outline seam.
 *
 * <p>{@link #UNHANDLED} means that no adapter claimed the target and the
 * common virtual {@code BlockDisplay} route may be used. The other results are
 * only returned after an adapter claimed the target; they must not fall back
 * to that route.</p>
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
