package nx.pingwheel.common.interaction;

/**
 * An opaque, immutable token identifying one active ping-key interaction.
 *
 * <p>Tokens are created only by {@link ActiveInteraction#begin()} and compared
 * by object identity: this class deliberately does not override
 * {@code equals}/{@code hashCode}, so a stale token from a superseded press can
 * never be confused with a newer token carrying the same debug sequence.
 *
 * <p>The monotonically increasing {@linkplain #sequence() sequence} exists for
 * debug logging only. It carries no identity semantics, is never serialized,
 * and is never used for comparison. The sequence value is validated here so an
 * invalid token can never be constructed through the package-private
 * constructor.
 *
 * <p>The counter in {@link ActiveInteraction} is exhausted at
 * {@link Long#MAX_VALUE}; {@code ActiveInteraction.begin()} then fails with an
 * {@link IllegalStateException} rather than wrapping negative, so every minted
 * sequence is non-negative.
 */
public final class InteractionToken {

	private final long sequence;

	/**
	 * Creates a token with the given monotonic debug sequence.
	 *
	 * <p>Package-private: tokens are minted only by {@link ActiveInteraction}.
	 */
	InteractionToken(long sequence) {
		if (sequence < 0L) {
			throw new IllegalArgumentException("sequence must be non-negative: " + sequence);
		}

		this.sequence = sequence;
	}

	/**
	 * The monotonic debug sequence assigned by {@link ActiveInteraction}.
	 */
	public long sequence() {
		return sequence;
	}

	@Override
	public String toString() {
		return "InteractionToken{sequence=" + sequence + "}";
	}
}
