package nx.pingwheel.common.domain;

/**
 * A stable, comparable marker identifier.
 *
 * <p>A marker id is server-assigned, globally unique within a server session,
 * and non-negative. Ids are monotonic: each new marker receives a larger id
 * than the one before it, which makes the "larger id wins equal arrival time"
 * tie-break deterministic and stable across clients.
 *
 * <p>No owner component is needed on this value: because the server generates
 * the id and guarantees uniqueness, the id alone already distinguishes markers
 * from different players. Larger numeric ids sort later (see {@link #compareTo}).
 */
public final class MarkerId implements Comparable<MarkerId> {

	private final long value;

	public MarkerId(long value) {
		if (value < 0L) {
			throw new IllegalArgumentException("marker id must be non-negative: " + value);
		}

		this.value = value;
	}

	public long value() {
		return value;
	}

	@Override
	public int compareTo(MarkerId other) {
		return Long.compare(value, other.value);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}

		if (!(obj instanceof MarkerId other)) {
			return false;
		}

		return value == other.value;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(value);
	}

	@Override
	public String toString() {
		return "MarkerId{" + value + "}";
	}
}
