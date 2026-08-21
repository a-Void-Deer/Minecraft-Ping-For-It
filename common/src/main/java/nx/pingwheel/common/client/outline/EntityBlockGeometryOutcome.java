package nx.pingwheel.common.client.outline;

/**
 * Result of one entity-block geometry-source attempt.
 *
 * <p>This type belongs to an internal compatibility seam for a future
 * separately-loaded optional adapter package. It has no public API stability
 * guarantee.</p>
 */
public enum EntityBlockGeometryOutcome {
	/** At least one outline vertex was submitted by this source, including a partial commit. */
	RENDERED,
	/** The source was applicable but submitted no geometry. */
	EMPTY,
	/** The source could not complete its attempt. */
	FAILED
}
