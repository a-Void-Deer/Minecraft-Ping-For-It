package nx.pingwheel.common.config;

import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.Consumer;
import java.util.function.Function;

/** Per-handler bridge between persisted preferences and the process logging policy. */
@NotNullByDefault
final class ConfigRuntime<T extends IConfig> {
	private final DiagnosticSide side;
	private final Function<T, LoggingProfile> profile;
	private final Consumer<LoggingProfile> apply;

	private ConfigRuntime(DiagnosticSide side, Function<T, LoggingProfile> profile, Consumer<LoggingProfile> apply) {
		this.side = side;
		this.profile = profile;
		this.apply = apply;
	}

	static <T extends IConfig> ConfigRuntime<T> fixture() {
		return new ConfigRuntime<>(DiagnosticSide.BOOTSTRAP, ignored -> LoggingProfile.NORMAL, ignored -> {});
	}

	static <T extends IConfig> ConfigRuntime<T> of(DiagnosticSide side, Function<T, LoggingProfile> profile,
			Consumer<LoggingProfile> apply) {
		return new ConfigRuntime<>(side, profile, apply);
	}

	static ConfigRuntime<ClientConfig> client() {
		return new ConfigRuntime<>(DiagnosticSide.CLIENT, ClientConfig::getLoggingProfile,
			profile -> Diagnostics.setProfile(DiagnosticSide.CLIENT, profile));
	}

	static ConfigRuntime<ServerConfig> server() {
		return new ConfigRuntime<>(DiagnosticSide.SERVER, ServerConfig::getLoggingProfile,
			profile -> Diagnostics.setProfile(DiagnosticSide.SERVER, profile));
	}

	DiagnosticLogger logger() {
		return switch (side) {
			case CLIENT -> Diagnostics.client();
			case SERVER -> Diagnostics.server();
			case BOOTSTRAP -> Diagnostics.bootstrap();
		};
	}

	void publish(T config) {
		apply.accept(profile.apply(config));
	}

	void beginLoad() {
		apply.accept(LoggingProfile.NORMAL);
	}
}
