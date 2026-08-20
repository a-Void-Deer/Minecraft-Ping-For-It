package nx.pingwheel.common.client.outline;

/**
 * Per-attempt sink for a complete deferred entity-block line batch.
 *
 * <p>A source may append lines while it is collecting geometry and must call
 * {@link #commit()} only after the complete batch has passed its own
 * validation. The implementation also validates and atomically publishes the
 * batch, so a stale, malformed, partial, or over-budget attempt can never
 * leak lines into a later frame.</p>
 */
public interface EntityBlockGeometryLineSink {
	/** Adds one candidate line to the current private batch. */
	boolean addLine(double x0, double y0, double z0, double x1, double y1, double z1);

	/** Atomically publishes the complete private batch for its target. */
	boolean commit();

	/** Discards the private batch without publishing anything. */
	void abort();

	/** Whether this sink has successfully published its batch. */
	boolean committed();

	/** A sink used by contexts that cannot publish deferred lines. */
	EntityBlockGeometryLineSink NOOP = new EntityBlockGeometryLineSink() {
		@Override
		public boolean addLine(double x0, double y0, double z0, double x1, double y1, double z1) {
			return false;
		}

		@Override
		public boolean commit() {
			return false;
		}

		@Override
		public void abort() {}

		@Override
		public boolean committed() {
			return false;
		}
	};
}
