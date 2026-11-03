package nx.pingwheel.common.log;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import nx.pingwheel.common.Global;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Mirrors this mod's own log lines (the {@code "pingforit"} logger) into
 * {@code logs/devlog/<runId>/pingforit/pingforit.log}.
 *
 * <p>The mirror is an <em>extra</em> appender attached to the
 * {@code "pingforit"} logger only: the vanilla console and {@code latest.log}
 * appenders stay on the root logger and keep receiving the mod's lines, so the
 * normal log output is unchanged. The mirror is a <em>superset</em> of what
 * {@code latest.log} stores: it accepts every level the logger allows (DEBUG
 * included), while the {@code latest.log} appender only stores INFO and up.
 * Those extra DEBUG lines are the point of the mirror, so do not turn it into
 * an INFO-only copy. No other logger (vanilla, Fabric, another mod) can produce
 * events for this appender, and the root configuration is never modified.</p>
 *
 * <p>{@code logs/devlog/} is a shared root: every mod derives the same
 * {@code runId} from the process start time, so one run writes into one
 * directory without any coordination. A run directory also carries a
 * {@code session.txt} snapshot, written by whichever mod gets there first.
 * This class only creates its own {@code pingforit} sub-directory, only writes
 * its own {@code pingforit.log}, and never deletes anything: runs beyond
 * {@link #RETAINED_RUNS} are moved to {@code _archive}.</p>
 *
 * <p>The mirror is opt-in so a normal player install never collects devlog
 * folders: it is enabled for development environments or when
 * {@code -Dpingforit.devlog=true} is passed as a <em>JVM argument</em> (Loom's
 * {@code vmArg} or the launcher command line) — it is not read from any mod
 * config file, so editing config JSON does nothing. Installation is
 * best-effort: any failure is reported with a single warning, and a 64 MiB cap
 * detaches the mirror before a runaway log can fill the disk.</p>
 */
public final class ModLogFile {

	/** Shared root below the game directory; other mods write next to us. */
	private static final String DEVLOG_ROOT = "logs/devlog";
	private static final String ARCHIVE_DIRECTORY = "_archive";
	private static final String SESSION_FILE = "session.txt";
	private static final String MOD_FILE = Global.MOD_ID + ".log";
	/** Opt-in switch for non-development runs. */
	private static final String ENABLE_PROPERTY = Global.MOD_ID + ".devlog";

	private static final String LOG_PATTERN = "[%d{HH:mm:ss.SSS}] [%t/%level] %msg%n";
	private static final String APPENDER_NAME = "PingForItDevLog";
	private static final String RUN_ID_PATTERN_TEXT = "yyyy-MM-dd_HH-mm-ss";
	/** Locale.ROOT keeps the digits ASCII, so every devlog mod derives the same run id. */
	private static final DateTimeFormatter RUN_ID_FORMAT =
		DateTimeFormatter.ofPattern(RUN_ID_PATTERN_TEXT, Locale.ROOT);
	/** Shared {@code session.txt} timestamp format; must match the other devlog mods. */
	private static final DateTimeFormatter TIMESTAMP_FORMAT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
	private static final Pattern RUN_DIRECTORY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}");
	private static final int RETAINED_RUNS = 10;
	private static final long MAX_BYTES = 64L * 1024L * 1024L;
	private static final Object LOCK = new Object();

	/** Set before the first attempt, so a repeated call can never install twice. */
	private static volatile boolean installed;
	/** The logger the mirror is attached to; the size guard needs it to detach. */
	private static volatile Logger attachedLogger;

	private ModLogFile() {}

	/**
	 * Installs the file mirror exactly once. Safe to call from any thread and
	 * from any initialisation order; a failure never escalates.
	 */
	public static void install() {
		if (installed) {
			return;
		}

		synchronized (LOCK) {
			if (installed) {
				return;
			}
			// Mark first: one broken attempt must not retry on every caller.
			installed = true;

			if (!enabled()) {
				// A normal player install must not accumulate devlog folders. Return
				// silently: even a debug line here would initialise Global (and its
				// platform service lookup), so "disabled" must stay side-effect free.
				return;
			}

			try {
				installUnchecked();
			} catch (Throwable throwable) {
				try {
					Global.LOGGER.warn("Unable to mirror mod logs below " + DEVLOG_ROOT + ": " + throwable);
				} catch (Throwable ignored) {
					// Logging itself is unavailable; the mirror is optional.
				}
			}
		}
	}

	private static boolean enabled() {
		try {
			if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
				return true;
			}
		} catch (Throwable ignored) {
			// Fall back to the explicit switch below.
		}
		return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"));
	}

	private static void installUnchecked() throws IOException {
		final Path gameDir = FabricLoader.getInstance().getGameDir();
		final Path devlogRoot = gameDir.resolve(DEVLOG_ROOT);
		final String runId = runId();
		final Path runDirectory = devlogRoot.resolve(runId);
		final Path modDirectory = runDirectory.resolve(Global.MOD_ID);

		Files.createDirectories(modDirectory);
		writeSessionFile(runDirectory, runId);

		// The header is handed to the layout so it is written once, when the
		// file is opened, and only to this file (never to latest.log).
		final String header = "# Ping For It " + modVersion() + "\n"
			+ "# side: " + side() + "\n"
			+ "# runId=" + runId + "\n";

		final PatternLayout layout = PatternLayout.newBuilder()
			.setPattern(LOG_PATTERN)
			.setHeader(header)
			.build();

		final FileAppender fileAppender = FileAppender.newBuilder()
			.setName(APPENDER_NAME + "File")
			.setFileName(modDirectory.resolve(MOD_FILE).toString())
			.setAppend(false)
			.setBufferedIo(false)
			.setImmediateFlush(true)
			// Every run gets a fresh directory, so the ".lck" file that locking
			// would leave in the shared log tree buys nothing.
			.setLocking(false)
			.setLayout(layout)
			.build();
		if (fileAppender == null) {
			throw new IllegalStateException("log4j could not open " + modDirectory.resolve(MOD_FILE));
		}
		fileAppender.start();

		try {
			final BoundedAppender mirror = new BoundedAppender(
				APPENDER_NAME, layout, fileAppender, header.getBytes(StandardCharsets.UTF_8).length);
			mirror.start();

			// Bind by name to the mod logger: the appender lives on that
			// logger's config, not on the root logger.
			final Logger logger = (Logger) LogManager.getLogger(Global.MOD_ID);
			final Configuration configuration = logger.getContext().getConfiguration();
			if (!configuration.getLoggerConfig(Global.MOD_ID).getName().equals(Global.MOD_ID)) {
				// No node of our own exists yet. Create it with additivity on and let
				// it be published that way: addAppender would otherwise copy the
				// additivity of the node it found and publish that first, and an XML
				// <Root> node is not additive, which silently drops the mod's lines
				// from the console and latest.log until the node is corrected.
				// Only this absent-node case is touched, so an explicit
				// <Logger name="pingforit"> config (even additive="false") still wins.
				// The guard is conditional by design: a programmatic root defaults to
				// additive=true, so the regression cannot happen and this branch is
				// simply skipped.
				configuration.setLoggerAdditive(logger, true);
			}

			logger.addAppender(mirror);

			attachedLogger = logger;
			registerShutdownHook(mirror, fileAppender);
		} catch (RuntimeException | Error throwable) {
			stopQuietly(fileAppender);
			throw throwable;
		}

		archiveOldRuns(devlogRoot, runId);
	}

	/**
	 * Writes the environment snapshot of this run. Whichever mod starts first
	 * wins; a later mod simply finds the file already there.
	 */
	private static void writeSessionFile(final Path runDirectory, final String runId) {
		final Path session = runDirectory.resolve(SESSION_FILE);
		try (Writer writer = Files.newBufferedWriter(session, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			writer.write(sessionSnapshot(runId));
		} catch (FileAlreadyExistsException alreadyWritten) {
			// Another mod of this run wrote the snapshot first; that is expected.
		} catch (Throwable throwable) {
			warnOneLine("Unable to write " + session + ": " + throwable);
		}
	}

	private static String sessionSnapshot(final String runId) {
		final StringBuilder snapshot = new StringBuilder(320);
		append(snapshot, "runId", runId);
		append(snapshot, "startTime", startTime());
		append(snapshot, "side", side());
		append(snapshot, "minecraftVersion", minecraftVersion());
		append(snapshot, "modVersion", modVersion());
		append(snapshot, "javaVersion", System.getProperty("java.version"));
		append(snapshot, "osName", System.getProperty("os.name"));
		append(snapshot, "osArch", System.getProperty("os.arch"));
		append(snapshot, "processors", String.valueOf(Runtime.getRuntime().availableProcessors()));
		append(snapshot, "maxHeapMB", String.valueOf(Runtime.getRuntime().maxMemory() >> 20));
		append(snapshot, "jvmArgs", jvmArguments());
		return snapshot.toString();
	}

	private static void append(final StringBuilder snapshot, final String key, final String value) {
		snapshot.append(key).append('=').append(value == null || value.isEmpty() ? "unknown" : value)
			.append('\n');
	}

	/** Moves the oldest runs to {@code _archive} once more than {@link #RETAINED_RUNS} exist. */
	private static void archiveOldRuns(final Path devlogRoot, final String currentRunId) {
		try {
			final List<Path> previousRuns = new ArrayList<>();
			try (Stream<Path> children = Files.list(devlogRoot)) {
				children
					.filter(Files::isDirectory)
					.filter(path -> RUN_DIRECTORY.matcher(path.getFileName().toString()).matches())
					.filter(path -> !path.getFileName().toString().equals(currentRunId))
					.forEach(previousRuns::add);
			}

			// The run id sorts chronologically, so name order is age order.
			previousRuns.sort(Comparator.comparing(path -> path.getFileName().toString()));

			final int excess = previousRuns.size() + 1 - RETAINED_RUNS;
			if (excess <= 0) {
				return;
			}

			final Path archive = devlogRoot.resolve(ARCHIVE_DIRECTORY);
			Files.createDirectories(archive);

			for (int index = 0; index < excess && index < previousRuns.size(); index++) {
				moveToArchive(previousRuns.get(index), archive);
			}
		} catch (Throwable throwable) {
			warnOneLine("Unable to archive old devlog runs: " + throwable);
		}
	}

	private static void moveToArchive(final Path run, final Path archive) {
		final String name = run.getFileName().toString();
		Path target = archive.resolve(name);

		for (int suffix = 2; Files.exists(target); suffix++) {
			if (suffix > 100) {
				warnOneLine("Unable to archive " + run + ": too many name collisions");
				return;
			}
			target = archive.resolve(name + "-" + suffix);
		}

		try {
			Files.move(run, target);
		} catch (Throwable throwable) {
			warnOneLine("Unable to archive " + run + ": " + throwable);
		}
	}

	private static void registerShutdownHook(final Appender mirror, final Appender fileAppender) {
		try {
			final Thread hook = new Thread(() -> {
				stopQuietly(mirror);
				stopQuietly(fileAppender);
			}, "pingforit-devlog-shutdown");
			hook.setDaemon(true);
			Runtime.getRuntime().addShutdownHook(hook);
		} catch (Throwable ignored) {
			// Without the hook the last lines may stay unflushed; never fatal.
		}
	}

	private static void stopQuietly(final Appender appender) {
		if (appender == null) {
			return;
		}
		try {
			appender.stop();
		} catch (Throwable ignored) {
			// Shutdown must never fail because of the log mirror.
		}
	}

	private static void warnOneLine(final String message) {
		try {
			Global.LOGGER.warn(message);
		} catch (Throwable ignored) {
			// Nothing else to report into.
		}
	}

	private static String runId() {
		return startInstant()
			.map(instant -> LocalDateTime.ofInstant(instant, ZoneId.systemDefault()))
			.orElseGet(LocalDateTime::now)
			.format(RUN_ID_FORMAT);
	}

	private static String startTime() {
		return startInstant()
			.map(instant -> LocalDateTime.ofInstant(instant, ZoneId.systemDefault()))
			.orElseGet(LocalDateTime::now)
			.format(TIMESTAMP_FORMAT);
	}

	private static Optional<Instant> startInstant() {
		try {
			return ProcessHandle.current().info().startInstant();
		} catch (Throwable throwable) {
			return Optional.empty();
		}
	}

	private static String jvmArguments() {
		try {
			final List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
			return arguments.isEmpty() ? "(none)" : String.join(" ", arguments);
		} catch (Throwable throwable) {
			return "(unavailable)";
		}
	}

	private static String modVersion() {
		return FabricLoader.getInstance().getModContainer(Global.MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}

	private static String minecraftVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft")
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}

	private static String side() {
		return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
			? "client"
			: "dedicated_server";
	}

	/**
	 * Pass-through wrapper around the file appender that detaches the mirror
	 * before a runaway log can grow past {@link #MAX_BYTES}. Only events that
	 * still fit are forwarded, so the file never exceeds the cap; the three
	 * header lines are counted from the start.
	 */
	private static final class BoundedAppender extends AbstractAppender {

		private final Appender delegate;
		private long bytesWritten;
		private volatile boolean capped;

		private BoundedAppender(
			final String name,
			final Layout<? extends Serializable> layout,
			final Appender delegate,
			final long initialBytes
		) {
			super(name, null, layout, true, Property.EMPTY_ARRAY);
			this.delegate = delegate;
			this.bytesWritten = initialBytes;
		}

		@Override
		public synchronized void append(final LogEvent event) {
			if (capped) {
				return;
			}

			final int size = getLayout().toByteArray(event).length;
			if (bytesWritten + size > MAX_BYTES) {
				capped = true;
				capReached();
				return;
			}

			delegate.append(event);
			bytesWritten += size;
		}

		private void capReached() {
			// Detach before reporting: a warning emitted from inside this appender
			// would be a recursive call into it, which log4j reports as an ERROR.
			final Logger logger = attachedLogger;
			try {
				if (logger != null) {
					logger.removeAppender(this);
				}
			} catch (Throwable ignored) {
				// Detaching is best-effort; the capped flag already drops events.
			}

			stopQuietly(delegate);
			stopQuietly(this);

			try {
				Global.LOGGER.warn("Dev log mirror reached its " + (MAX_BYTES >> 20)
					+ " MiB cap; further lines are only in latest.log");
			} catch (Throwable ignored) {
				// The console copy is enough.
			}
		}
	}
}
