package nx.pingwheel.neoforge.integration.create.presentation;

import net.neoforged.fml.ModList;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import java.time.Duration;
import org.jetbrains.annotations.Nullable;

/** Safe to load before Create: contains no optional-mod classes. */
public final class CreatePresentationAvailability {
	private static final DiagnosticChannel DIAGNOSTICS = DiagnosticChannel.interval("create-availability", Duration.ofSeconds(10));
	/** Client-only reflection entry retained for PreviewLocalReaders; shared consumers pass their port. */
	public static boolean available() {
		return available(nx.pingwheel.common.diagnostics.Diagnostics.client());
	}
	// The tested Maven artifact is 6.0.10-281, while Create's generated
	// neoforge.mods.toml uses mod_version (6.0.10) as the runtime mod version.
	public static final String TESTED_MOD_VERSION = "6.0.10";
	public static final String TESTED_ARTIFACT_VERSION = "6.0.10-281";

	private CreatePresentationAvailability() {}

	/** Fail closed for absent mods and unknown API shapes. */
	public static boolean available(DiagnosticLogger diagnostics) {
		try {
			@Nullable ModList mods = ModList.get();
			return mods != null && mods.getMods().stream().anyMatch(mod ->
				"create".equals(mod.getModId()) && testedVersion(mod.getVersion().toString()));
		} catch (RuntimeException | LinkageError failure) {
			diagnostics.atNormal().rateLimited(DIAGNOSTICS).failure(org.apache.logging.log4j.Level.WARN,
				"create/presentation-availability", "integration-unavailable", () -> "tested Create version lookup", failure);
			return false;
		}
	}

	public static boolean testedVersion(@Nullable String version) {
		// This is the public mod metadata gate, not proof of artifact identity:
		// future builds retaining mod_version still need explicit API review.
		return TESTED_MOD_VERSION.equals(version) || TESTED_ARTIFACT_VERSION.equals(version);
	}
}
