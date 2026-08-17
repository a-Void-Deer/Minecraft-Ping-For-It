package nx.pingwheel.common.util;

import java.util.Objects;

/**
 * Small allocation-free state holder for one claimed physical input edge.
 *
 * <p>The generic key keeps the press/release and rebinding rules independent
 * of Minecraft's mapping implementation, so the event boundary can be tested
 * without a client instance or a tick loop.
 */
public final class ClaimedInputState<K> {

	private K claimedKey;
	private boolean armed;

	/** Arms the state for the exact physical key observed at press time. */
	public void arm(K key) {
		claimedKey = Objects.requireNonNull(key, "key");
		armed = true;
	}

	/**
	 * Observes a raw state transition and claims only the matching release.
	 * Press/repeat transitions and unrelated keys are cheap no-ops.
	 */
	public boolean observe(K key, boolean isDown) {
		if (isDown || !armed || key == null || !key.equals(claimedKey)) {
			return false;
		}

		claimedKey = null;
		armed = false;
		return true;
	}

	public boolean isArmed() {
		return armed;
	}

	public void reset() {
		claimedKey = null;
		armed = false;
	}
}
