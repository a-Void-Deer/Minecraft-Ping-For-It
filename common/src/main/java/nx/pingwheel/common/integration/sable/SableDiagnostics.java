package nx.pingwheel.common.integration.sable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import nx.pingwheel.common.Global;

/**
 * Structured, injectable diagnostics for the optional Sable boundary.
 *
 * <p>The Sable integration is deliberately the only place where the detailed
 * provider payload is emitted.  Keeping the logger at this boundary makes the
 * capture and provider implementations testable without initializing the game
 * logger, while the global implementation still gives every event a stable
 * channel prefix.</p>
 */
public interface SableDiagnostics {

	String CAPTURE_PREFIX = "[sable-capture]";
	String SERVER_PREFIX = "[sable-server]";
	String REFRESH_PREFIX = "[sable-refresh]";

	/** Emits one structured DEBUG event. Fields are supplied as key/value pairs. */
	void debug(String prefix, String stage, String reason, Object... fields);

	/**
	 * Emits one structured DEBUG event and attaches the original throwable to the
	 * Log4j event.  The throwable is intentionally not flattened or redacted.
	 */
	void debugException(
		String prefix, String stage, String reason, Throwable throwable, Object... fields
	);

	default void capture(String stage, String reason, Object... fields) {
		debug(CAPTURE_PREFIX, stage, reason, fields);
	}

	default void captureException(
		String stage, String reason, Throwable throwable, Object... fields
	) {
		debugException(CAPTURE_PREFIX, stage, reason, throwable, fields);
	}

	default void server(String stage, String reason, Object... fields) {
		debug(SERVER_PREFIX, stage, reason, fields);
	}

	default void serverException(
		String stage, String reason, Throwable throwable, Object... fields
	) {
		debugException(SERVER_PREFIX, stage, reason, throwable, fields);
	}

	default void refresh(String stage, String reason, Object... fields) {
		debug(REFRESH_PREFIX, stage, reason, fields);
	}

	default void refreshException(
		String stage, String reason, Throwable throwable, Object... fields
	) {
		debugException(REFRESH_PREFIX, stage, reason, throwable, fields);
	}

	/** A no-op seam for tests and for callers that do not want logging. */
	static SableDiagnostics noop() {
		return new SableDiagnostics() {
			@Override
			public void debug(String prefix, String stage, String reason, Object... fields) {
				// intentionally empty
			}

			@Override
			public void debugException(
				String prefix, String stage, String reason, Throwable throwable, Object... fields
			) {
				// intentionally empty
			}
		};
	}

	/**
	 * A global implementation whose reference to {@link Global} is resolved only
	 * when the first event is emitted.
	 */
	static SableDiagnostics global() {
		return new SableDiagnostics() {
			@Override
			public void debug(String prefix, String stage, String reason, Object... fields) {
				Global.LOGGER.debug(format(prefix, stage, reason, fields));
			}

			@Override
			public void debugException(
				String prefix, String stage, String reason, Throwable throwable, Object... fields
			) {
				Global.LOGGER.debug(format(prefix, stage, reason, fields),
					Objects.requireNonNull(throwable, "throwable"));
			}
		};
	}

	/** Creates an in-memory recording implementation for focused tests. */
	static Recording recording() {
		return new Recording();
	}

	/** Immutable event captured by {@link Recording}. */
	record Event(
		String prefix, String stage, String reason, List<Object> fields, Throwable throwable
	) {
		public Event {
			Objects.requireNonNull(prefix, "prefix");
			Objects.requireNonNull(stage, "stage");
			Objects.requireNonNull(reason, "reason");
			Objects.requireNonNull(fields, "fields");
			fields = Collections.unmodifiableList(new ArrayList<>(fields));
		}
	}

	/** Thread-safe enough for test collection and deliberately not a game logger. */
	final class Recording implements SableDiagnostics {
		private final List<Event> events = new ArrayList<>();

		private Recording() {
		}

		@Override
		public synchronized void debug(String prefix, String stage, String reason, Object... fields) {
			events.add(new Event(prefix, stage, reason, copyFields(fields), null));
		}

		@Override
		public synchronized void debugException(
			String prefix, String stage, String reason, Throwable throwable, Object... fields
		) {
			events.add(new Event(
				prefix, stage, reason, copyFields(fields), Objects.requireNonNull(throwable, "throwable")));
		}

		public synchronized List<Event> events() {
			return List.copyOf(events);
		}

		private static List<Object> copyFields(Object[] fields) {
			Objects.requireNonNull(fields, "fields");
			return new ArrayList<>(Arrays.asList(fields.clone()));
		}
	}

	/**
	 * Renders an event without using parameter substitution.  This lets the
	 * throwable overload above remain the real Log4j throwable overload, which
	 * preserves the complete stack trace under the repository's custom message
	 * factory.
	 */
	static String format(String prefix, String stage, String reason, Object... fields) {
		Objects.requireNonNull(prefix, "prefix");
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(fields, "fields");

		StringBuilder message = new StringBuilder(prefix)
			.append(" stage=").append(stage)
			.append(" reason=").append(reason);

		for (int index = 0; index + 1 < fields.length; index += 2) {
			message.append(' ').append(String.valueOf(fields[index]))
				.append('=').append(String.valueOf(fields[index + 1]));
		}

		if ((fields.length & 1) != 0) {
			message.append(" fields_odd_tail=").append(String.valueOf(fields[fields.length - 1]));
		}

		return message.toString();
	}
}
