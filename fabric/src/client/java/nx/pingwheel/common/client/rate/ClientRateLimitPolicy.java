package nx.pingwheel.common.client.rate;

/**
 * The client-side policy used to limit marker-create packets.
 *
 * <p>The policy is deliberately a value object.  A zero in either field
 * disables the client gate, while negative values are never valid policy
 * values (negative values are reserved for corrupt wire packets).</p>
 */
public record ClientRateLimitPolicy(int rateLimit, int msToRegenerate) {

	public static final ClientRateLimitPolicy DEFAULT = new ClientRateLimitPolicy(5, 1000);

	public ClientRateLimitPolicy {
		if (rateLimit < 0) {
			throw new IllegalArgumentException("rateLimit must not be negative");
		}

		if (msToRegenerate < 0) {
			throw new IllegalArgumentException("msToRegenerate must not be negative");
		}
	}

	/**
	 * Whether this policy imposes a finite client-side limit.
	 */
	public boolean enabled() {
		return rateLimit > 0 && msToRegenerate > 0;
	}

}
