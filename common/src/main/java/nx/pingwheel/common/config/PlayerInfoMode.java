package nx.pingwheel.common.config;

import java.util.Locale;

public enum PlayerInfoMode {
	HOLD, DISABLED, ALWAYS, COMPACT;

	public static PlayerInfoMode get(String name) {
		for (PlayerInfoMode mode : PlayerInfoMode.values()) {
			if (mode.name().equalsIgnoreCase(name)) {
				return mode;
			}
		}

		return null;
	}

	@Override
	public String toString() {
		return super.toString().toLowerCase(Locale.ROOT);
	}
}
