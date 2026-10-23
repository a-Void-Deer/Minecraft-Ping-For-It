package nx.pingwheel.common.name;

import java.util.Objects;

import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.util.SafeExceptionReport;

/**
 * Client-side decoding of authoritative target name JSON into display
 * components, using the current level's registry access.
 *
 * <p>Malformed or unparseable payloads never crash the HUD: they fall back to
 * the {@link TargetNameComposer#unknown() unknown} display name. Production
 * failures emit lazy, bounded, rate-limited DEBUG diagnostics carrying the
 * marker id and original exception details, without separately attaching JSON.
 *
 * <p>The logger is injectable and defaults to a no-op, so pure tests never
 * initialize the game logger; the client composition root wires
 * {@link Logger#global()} once during initialization.
 */
public final class ClientTargetNameDecoder {

	private ClientTargetNameDecoder() {}

	/**
	 * A tiny, injectable debug logger for name decode failures.
	 */
	@FunctionalInterface
	public interface Logger {

		/**
		 * Emits a debug message with {@code {}} placeholder arguments.
		 */
		void debug(String message, Object... args);

		/** Reports a decode failure without attaching an unbounded logger throwable. */
		default void debugException(MarkerId markerId, Throwable failure) {
			debug(SafeExceptionReport.formatWithContext("target name decode failed: markerId="
				+ markerId.value(), failure));
		}

		/**
		 * A logger that discards every record.
		 */
		static Logger noop() {
			return new Logger() {
				@Override
				public void debug(String message, Object... args) {
					// intentionally empty
				}

				@Override
				public void debugException(MarkerId markerId, Throwable failure) {
					// Do not format discarded reports.
				}
			};
		}

		/**
		 * A logger backed by the mod's global Log4j logger.
		 *
			 * <p>General debug messages resolve {@link nx.pingwheel.common.Global#LOGGER}
			 * only when emitted. Exception reports use the lazy, platform-independent
			 * rate-limited diagnostic logger instead. This factory initializes neither.
		 */
		static Logger global() {
			return new Logger() {
				@Override
				public void debug(String message, Object... args) {
					nx.pingwheel.common.Global.LOGGER.debug(message, args);
				}

				@Override
				public void debugException(MarkerId markerId, Throwable failure) {
					Diagnostics.debug("target-name-decode", ClientTargetNameDecoder.class.getName(),
						() -> "markerId=" + markerId.value(), failure);
				}
			};
		}
	}

	private static volatile Logger logger = Logger.noop();

	/**
	 * Replaces the logger used for decode-failure debug records.
	 */
	public static void setLogger(Logger logger) {
		ClientTargetNameDecoder.logger = Objects.requireNonNull(logger, "logger");
	}

	/**
	 * Decodes an authoritative name payload with the given registry access.
	 *
	 * <p>A parse failure falls back to the unknown display name; it never
	 * propagates to the caller.
	 */
	public static Component decode(MarkerId markerId, TargetNameJson json, HolderLookup.Provider registryAccess) {
		Objects.requireNonNull(markerId, "markerId");
		Objects.requireNonNull(json, "json");
		Objects.requireNonNull(registryAccess, "registryAccess");

		try {
			return Component.Serializer.fromJson(json.value(), registryAccess);
		} catch (RuntimeException e) {
			logger.debugException(markerId, e);
			return TargetNameComposer.unknown();
		}
	}
}
