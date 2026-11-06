package nx.pingwheel.common.diagnostics;

import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Public explicit-side routes, without the retired implicit reporter/window adapter. */
@NotNullByDefault
class DiagnosticsTest {

	@Test
	void everySideHasIndependentDisclosureAndPreservesAssignedSeverity() {
		try (Capture capture = new Capture()) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			AtomicInteger reads = new AtomicInteger();
			RuntimeException failure = new RuntimeException("original payload", new IllegalArgumentException("root cause"));
			for (DiagnosticSide side : DiagnosticSide.values()) {
				logger(side).atNormal().failure(Level.ERROR, "public-read", "fallback",
					() -> { reads.incrementAndGet(); return "permitted context"; }, failure);
			}
			assertEquals(1, reads.get());
			assertEquals(List.of(DiagnosticSide.CLIENT, DiagnosticSide.SERVER, DiagnosticSide.BOOTSTRAP),
				capture.events.stream().map(Event::side).toList());
			for (Event event : capture.events) {
				assertEquals(Level.ERROR, event.level);
				assertTrue(event.text.startsWith(Diagnostics.PREFIX + "side=" + event.side));
				assertTrue(event.text.contains("site=public-read handling=fallback"));
				assertEquals(event.side == DiagnosticSide.SERVER, event.text.contains("original payload"));
				assertEquals(event.side == DiagnosticSide.SERVER, event.text.contains("root cause"));
			}
		}
	}

	@Test
	void reportsWithoutAChannelDoNotInventDuplicateWindowsAndScalarEventsStayExceptionFree() {
		try (Capture capture = new Capture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			AtomicInteger reads = new AtomicInteger();
			for (int attempt = 0; attempt < 3; attempt++) {
				Diagnostics.client().event(Level.WARN, "public-status", "unavailable",
					() -> "context=" + reads.incrementAndGet());
			}
			assertEquals(3, reads.get());
			assertEquals(3, capture.events.size());
			for (Event event : capture.events) {
				assertTrue(event.text.contains("site=public-status handling=unavailable"));
				assertFalse(event.text.contains("exception:"));
				assertFalse(event.text.contains("channel="));
			}
		}
	}

	@Test
	void disabledPublicRoutesReadNeitherContextNorThrowableAcrossSeverities() {
		try (Capture capture = new Capture()) {
			capture.enabled = false;
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			for (Level level : List.of(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR)) {
				Diagnostics.client().failure(level, "disabled-read", "fallback", () -> {
					throw new AssertionError("disabled failure context");
				}, new UnreadableFailure());
				Diagnostics.client().event(level, "disabled-status", "unavailable", () -> {
					throw new AssertionError("disabled scalar context");
				});
			}
			assertTrue(capture.events.isEmpty());
		}
	}

	@Test
	void explicitFailureAndScalarReportsRetainBoundedEscapedDetail() {
		try (Capture capture = new Capture()) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			String huge = "provider\n" + "x".repeat(30_000);
			RuntimeException failure = new RuntimeException("original " + "y".repeat(30_000));
			Diagnostics.client().error("bounded-read", "fallback", failure, () -> huge);
			Diagnostics.client().event(Level.ERROR, "bounded-status", "unavailable", () -> huge);
			assertEquals(2, capture.events.size());
			for (Event event : capture.events) {
				assertTrue(event.text.length() <= SafeExceptionReport.MAX_CHARACTERS);
				assertTrue(event.text.contains("provider\\n"));
				assertTrue(event.text.contains("truncated"));
			}
		}
	}

	@Test
	void publicDiagnosticsInitializeAndRunWithoutGlobalConfigOrPlatformServices() throws Exception {
		try (PlatformFreeLoader loader = new PlatformFreeLoader()) {
			Class<?> diagnostics = loader.loadClass(Diagnostics.class.getName());
			Object logger = diagnostics.getMethod("client").invoke(null);
			Object values = java.lang.reflect.Array.newInstance(loader.loadClass(SafeDiagnosticValue.class.getName()), 0);
			logger.getClass().getMethod("warn", String.class, values.getClass())
				.invoke(logger, "without platform services", values);
			assertTrue((boolean) logger.getClass().getMethod("isEnabled", Level.class).invoke(logger, Level.WARN));
		}
	}

	private static DiagnosticLogger logger(DiagnosticSide side) {
		return switch (side) {
			case CLIENT -> Diagnostics.client();
			case SERVER -> Diagnostics.server();
			case BOOTSTRAP -> Diagnostics.bootstrap();
		};
	}

	@NotNullByDefault
	private static final class PlatformFreeLoader extends URLClassLoader {
		private PlatformFreeLoader() {
			super(new URL @Unmodifiable [] {Diagnostics.class.getProtectionDomain().getCodeSource().getLocation()},
				Diagnostics.class.getClassLoader());
		}
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.equals("nx.pingwheel.common.Global") || name.startsWith("nx.pingwheel.common.platform.")
				|| name.startsWith("nx.pingwheel.common.config.")) {
				throw new ClassNotFoundException("Platform services intentionally unavailable: " + name);
			}
			if (!name.startsWith("nx.pingwheel.common.diagnostics.")
				&& !name.startsWith("nx.pingwheel.common.util.SafeExceptionReport")) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				@Nullable Class<?> loaded = findLoadedClass(name);
				if (loaded == null) loaded = findClass(name);
				if (resolve) resolveClass(loaded);
				return loaded;
			}
		}
	}

	@NotNullByDefault
	private static final class UnreadableFailure extends RuntimeException {
		@Override public String getMessage() { throw new AssertionError("disabled message accessor"); }
		@Override public StackTraceElement @Unmodifiable [] getStackTrace() { throw new AssertionError("disabled stack accessor"); }
	}

	@NotNullByDefault
	private record Event(DiagnosticSide side, Level level, String text) {}

	@NotNullByDefault
	private static final class Capture implements DiagnosticBackend, AutoCloseable {
		private final List<Event> events = new ArrayList<>();
		private final EnumMap<DiagnosticSide, LoggingProfile> profiles = new EnumMap<>(DiagnosticSide.class);
		private final DiagnosticBackend previous = Diagnostics.swapBackendForTesting(this);
		private boolean enabled = true;
		private Capture() {
			for (DiagnosticSide side : DiagnosticSide.values()) {
				profiles.put(side, Diagnostics.profile(side));
				Diagnostics.setProfile(side, LoggingProfile.NORMAL);
			}
		}
		@Override public boolean isEnabled(DiagnosticSide side, Level severity) { return enabled; }
		@Override public void log(DiagnosticSide side, Level severity, String message) { events.add(new Event(side, severity, message)); }
		@Override public void close() { Diagnostics.swapBackendForTesting(previous); profiles.forEach(Diagnostics::setProfile); }
	}
}
