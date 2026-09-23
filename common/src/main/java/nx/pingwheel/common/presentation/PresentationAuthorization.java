package nx.pingwheel.common.presentation;

import java.util.UUID;

/** Replaceable recipient permission check, independent of field policy and subscription. */
@FunctionalInterface
public interface PresentationAuthorization {
	boolean allows(UUID recipient, PresentationField field, int actualLevel, int requiredLevel);

	final class Holder {
		private static final PresentationAuthorization VANILLA =
			(recipient, field, actualLevel, requiredLevel) -> actualLevel >= requiredLevel;
		private static volatile PresentationAuthorization provider = VANILLA;
		private Holder() {}
	}

	static boolean canSee(UUID recipient, PresentationField field, int actualLevel, int requiredLevel) {
		if (recipient == null || field == null || actualLevel < 0 || actualLevel > 4
			|| requiredLevel < 0 || requiredLevel > 4) return false;
		try {
			return Holder.provider.allows(recipient, field, actualLevel, requiredLevel);
		} catch (RuntimeException | LinkageError ex) {
			return false;
		}
	}

	/** Replace the permission provider; null restores vanilla permission-level comparison. */
	static void setProvider(PresentationAuthorization provider) {
		Holder.provider = provider == null ? Holder.VANILLA : provider;
	}
}
