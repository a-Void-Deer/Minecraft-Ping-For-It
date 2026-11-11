package nx.pingwheel.common.config;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Selects where the local client shows the courtesy-cooldown notice for a
 * committed create that was not sent.
 *
 * <p>This is a client-only presentation preference. It never changes the
 * courtesy gate, request tracking or transport behavior. Persisted values use
 * the existing uppercase enum names; translation keys use {@link #toString()}.
 */
@NotNullByDefault
public enum CooldownFeedbackMode {
	ACTIONBAR,
	CHAT,
	HIDDEN;

	/** Case-insensitive parser matching the existing client enum settings. */
	public static @Nullable CooldownFeedbackMode get(@Nullable String name) {
		if (name == null) {
			return null;
		}

		for (CooldownFeedbackMode mode : values()) {
			if (mode.name().equalsIgnoreCase(name)) {
				return mode;
			}
		}

		return null;
	}

	/** Returns the safe runtime value for a missing or malformed persisted preference. */
	public static CooldownFeedbackMode effective(@Nullable CooldownFeedbackMode mode) {
		return mode == null ? ConfigDefaults.Client.COOLDOWN_FEEDBACK : mode;
	}

	@Override
	public String toString() {
		return super.toString().toLowerCase(Locale.ROOT);
	}
}
