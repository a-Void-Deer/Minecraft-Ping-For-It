package nx.pingwheel.common.name;

import java.util.Objects;

import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;

import nx.pingwheel.common.domain.MarkerId;

/**
 * Client-side decoding of authoritative target name JSON into display
 * components, using the current level's registry access.
 *
 * <p>Malformed or unparseable payloads never crash the HUD: they fall back to
 * the {@link TargetNameComposer#unknown() unknown} display name and emit one
 * safe debug record carrying the marker id and the exception class only —
 * never the JSON, a name, a UUID, a position, or a registry id.
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

		/**
		 * A logger that discards every record.
		 */
		static Logger noop() {
			return (message, args) -> {
				// intentionally empty
			};
		}

		/**
		 * A logger backed by the mod's global Log4j logger.
		 *
		 * <p>The reference to {@link nx.pingwheel.common.Global#LOGGER} is
		 * deferred into the returned lambda body, so calling this factory does
		 * not initialize the game logger; only the first {@code debug(...)}
		 * invocation does.
		 */
		static Logger global() {
			return (message, args) -> nx.pingwheel.common.Global.LOGGER.debug(message, args);
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
			logger.debug("target name decode failed: markerId={} reason={}",
				markerId.value(), e.getClass().getSimpleName());
			return TargetNameComposer.unknown();
		}
	}
}
