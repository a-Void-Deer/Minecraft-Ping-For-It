package nx.pingwheel.common.marker;

import nx.pingwheel.common.domain.MarkerId;

/**
 * A monotonic, thread-safe source of {@link MarkerId}s.
 *
 * <p>The generator starts at {@code 0} and hands out strictly increasing ids.
 * Ids are valid up to and including {@link Long#MAX_VALUE}; the call after
 * {@code Long.MAX_VALUE} has been issued fails with a clear
 * {@link IllegalStateException} rather than overflowing into a negative value.
 */
public final class MarkerIdSource {

	private long next;
	private boolean exhausted;

	public MarkerIdSource() {
		this(0L);
	}

	/**
	 * Test seam: starts the generator at an arbitrary non-negative value.
	 */
	MarkerIdSource(long initialValue) {
		if (initialValue < 0L) {
			throw new IllegalArgumentException("initial value must be non-negative: " + initialValue);
		}

		this.next = initialValue;
	}

	/**
	 * Returns the next monotonic id.
	 *
	 * @throws IllegalStateException if the id space is exhausted (a call is made
	 *                               after {@link Long#MAX_VALUE} was already issued)
	 */
	public synchronized MarkerId nextId() {
		if (exhausted) {
			throw new IllegalStateException("marker id space exhausted at Long.MAX_VALUE");
		}

		long value = next;

		if (value == Long.MAX_VALUE) {
			exhausted = true;
		} else {
			next = value + 1L;
		}

		return new MarkerId(value);
	}
}
