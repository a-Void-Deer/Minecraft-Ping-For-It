package nx.pingwheel.common.util;

import java.util.HashSet;
import java.util.Set;

/** Tracks physical toggle presses until their matching release edge. */
public final class ToggleInputState<K> {
	private final Set<K> pressed = new HashSet<>();

	/** Returns true only for the first click before the key is released. */
	public boolean claimPress(K key) {
		return pressed.add(key);
	}

	/** Claims a press without toggling anything, such as while a screen is open. */
	public boolean suppressPress(K key) {
		return pressed.add(key);
	}

	/** Rearms a key without changing the associated setting. */
	public void release(K key) {
		pressed.remove(key);
	}

	/** Clears all held physical keys, such as after focus loss or disconnect. */
	public void reset() {
		pressed.clear();
	}
}
