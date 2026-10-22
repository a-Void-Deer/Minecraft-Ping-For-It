package nx.pingwheel.neoforge.integration.create.presentation;

import net.neoforged.fml.ModList;

/** Safe to load before Create: contains no optional-mod classes. */
public final class CreatePresentationAvailability {
	// The tested Maven artifact is 6.0.10-281, while Create's generated
	// neoforge.mods.toml uses mod_version (6.0.10) as the runtime mod version.
	public static final String TESTED_MOD_VERSION = "6.0.10";
	public static final String TESTED_ARTIFACT_VERSION = "6.0.10-281";

	private CreatePresentationAvailability() {}

	/** Fail closed for absent mods and unknown API shapes. */
	public static boolean available() {
		try {
			ModList mods = ModList.get();
			return mods != null && mods.getMods().stream().anyMatch(mod ->
				"create".equals(mod.getModId()) && testedVersion(mod.getVersion().toString()));
		} catch (RuntimeException | LinkageError failure) {
			return false;
		}
	}

	public static boolean testedVersion(String version) {
		// This is the public mod metadata gate, not proof of artifact identity:
		// future builds retaining mod_version still need explicit API review.
		return TESTED_MOD_VERSION.equals(version) || TESTED_ARTIFACT_VERSION.equals(version);
	}
}
