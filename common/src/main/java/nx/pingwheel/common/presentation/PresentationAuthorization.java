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

	/**
	 * The shared per-field authorization rule of every delivery path, SECTION and
	 * dedicated alike: the target type's selector policy decides the manifest
	 * default, then the recipient permission callback decides the effective
	 * required level. A missing setting, field or target type denies.
	 */
	static boolean fieldAllowed(PresentationSettings settings, UUID recipient, int actualLevel,
		PresentationField field, String targetTypeId) {
		if (settings == null || field == null || targetTypeId == null) return false;
		try {
			return settings.policyFor(targetTypeId).allows(field.id(), field.enabledByDefault())
				&& canSee(recipient, field, actualLevel,
					settings.permission(field.id(), field.permissionLevel()));
		} catch (RuntimeException | LinkageError ex) {
			return false;
		}
	}

	/** Replace the permission provider; null restores vanilla permission-level comparison. */
	static void setProvider(PresentationAuthorization provider) {
		Holder.provider = provider == null ? Holder.VANILLA : provider;
	}
}
