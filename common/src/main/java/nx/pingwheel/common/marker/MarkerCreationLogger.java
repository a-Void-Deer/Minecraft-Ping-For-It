package nx.pingwheel.common.marker;

import nx.pingwheel.common.diagnostics.Diagnostics;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Injectable marker-orchestration traces; production formatting follows the server profile. */
@NotNullByDefault
@FunctionalInterface
public interface MarkerCreationLogger {
	void debug(String message, @Nullable Object @Unmodifiable ... args);

	default void debugException(String constantContext, Throwable throwable) {
		Diagnostics.server().atDebug().debug("marker-creation-failure", "reject-create", throwable, () -> constantContext);
	}

	static MarkerCreationLogger noop() {
		return new MarkerCreationLogger() {
			@Override public void debug(String message, @Nullable Object @Unmodifiable ... args) {}
			@Override public void debugException(String constantContext, Throwable throwable) {}
		};
	}

	static MarkerCreationLogger global() {
		return new MarkerCreationLogger() {
			@Override public void debug(String message, @Nullable Object @Unmodifiable ... args) {
				Diagnostics.server().atDebug().site("marker-creation-trace").debug(
					() -> org.apache.logging.log4j.message.ParameterizedMessage.format(message, args));
			}
			@Override public void debugException(String constantContext, Throwable throwable) {
				Diagnostics.server().atDebug().debug("marker-creation-failure", "reject-create", throwable, () -> constantContext);
			}
		};
	}
}
