package nx.pingwheel.neoforge.integration.create.presentation;

import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoaderDiagnosticScope;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class CreatePresentationLoggingProfileTest {
	@Test
	void normalServerNeverReadsDetailOrThrowableWhileDeepClientCannotWidenIt() {
		AtomicInteger reads = new AtomicInteger();
		Throwable failure = new ReadCountingFailure(reads);
		try (Capture capture = new Capture(LoggingProfile.DEBUG, LoggingProfile.NORMAL)) {
			CreatePresentationDiagnostics.failure(Diagnostics.server(), null, "create/presentation-source", "adapter-unavailable",
				() -> { reads.incrementAndGet(); return "private target"; }, failure);
			assertEquals(0, reads.get());
			assertEquals(1, capture.events.size());
			String text = capture.message();
			assertTrue(text.contains("side=SERVER"));
			assertFalse(text.contains("private target"));
			assertFalse(text.contains("original private cause"));
			assertNull(capture.events.getFirst().getThrown());
		}
	}

	@Test
	void suppressedAttemptDoesNotCollectDetailsAndProfileChangeDoesNotResetItsChannel() {
		AtomicLong clock = new AtomicLong();
		DiagnosticChannel channel = DiagnosticChannel.interval("create-presentation-test", Duration.ofSeconds(10), clock::get);
		AtomicInteger details = new AtomicInteger();
		Throwable failure = new IllegalStateException("original private cause", new RuntimeException("nested original"));
		try (Capture capture = new Capture(LoggingProfile.NORMAL, LoggingProfile.NORMAL)) {
			CreatePresentationDiagnostics.failure(Diagnostics.server(), channel, "create/presentation-source", "adapter-unavailable",
				() -> { details.incrementAndGet(); return "private target"; }, failure);
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			CreatePresentationDiagnostics.failure(Diagnostics.server(), channel, "create/presentation-source", "adapter-unavailable",
				() -> { details.incrementAndGet(); return "private target"; }, failure);
			assertEquals(0, details.get());
			assertEquals(1, capture.events.size());
			clock.set(Duration.ofSeconds(10).toNanos());
			CreatePresentationDiagnostics.failure(Diagnostics.server(), channel, "create/presentation-source", "adapter-unavailable",
				() -> { details.incrementAndGet(); return "private target"; }, failure);
			assertEquals(1, details.get());
			assertEquals(2, capture.events.size());
			String text = capture.events.getLast().getMessage().getFormattedMessage();
			assertTrue(text.contains("private target"));
			assertTrue(text.contains("original private cause"));
			assertTrue(text.contains("nested original"));
			assertTrue(text.contains("suppressed=1"));
			assertTrue(text.length() <= nx.pingwheel.common.util.SafeExceptionReport.MAX_CHARACTERS);
		}
	}

	@Test void serverChannelHistoryDoesNotSuppressTheClientAndProfilesStayIndependent() {
		var channel = DiagnosticChannel.interval("create-dual-side-test", Duration.ofSeconds(10), () -> 0);
		var reads = new AtomicInteger();
		try (Capture capture = new Capture(LoggingProfile.DEEP, LoggingProfile.NORMAL)) {
			CreatePresentationDiagnostics.failure(Diagnostics.server(), channel, "create/presentation-source", "adapter-unavailable",
				() -> { reads.incrementAndGet(); return "server private"; }, new IllegalStateException("server original"));
			CreatePresentationDiagnostics.failure(Diagnostics.client(), channel, "create/presentation-source", "adapter-unavailable",
				() -> { reads.incrementAndGet(); return "client private"; }, new IllegalStateException("client original"));
			assertEquals(1, reads.get()); assertEquals(2, capture.events.size());
			assertFalse(capture.events.getFirst().getMessage().getFormattedMessage().contains("server original"));
			assertTrue(capture.events.getLast().getMessage().getFormattedMessage().contains("client original"));
			assertTrue(capture.events.getLast().getMessage().getFormattedMessage().contains("side=CLIENT"));
		}
	}

	@NotNullByDefault
	private static final class ReadCountingFailure extends RuntimeException {
		private final AtomicInteger reads;
		private ReadCountingFailure(AtomicInteger reads) { this.reads = reads; }
		@Override public @Nullable String getMessage() { reads.incrementAndGet(); return "original private cause"; }
		@Override public synchronized @Nullable Throwable getCause() { reads.incrementAndGet(); return null; }
		@Override public StackTraceElement @Unmodifiable [] getStackTrace() { reads.incrementAndGet(); return new StackTraceElement @Unmodifiable [0]; }
	}

	@NotNullByDefault
	private static final class Capture implements AutoCloseable {
		private final List<LogEvent> events = new ArrayList<>();
		private final Logger logger = (Logger) LogManager.getLogger("pingforit");
		private final org.apache.logging.log4j.core.config.Configuration configuration = logger.getContext().getConfiguration();
		private final @Nullable LoggerConfig previous = configuration.getLoggers().get(logger.getName());
		private final AbstractAppender appender = new AbstractAppender("create-profile-test", null, null, true, Property.EMPTY_ARRAY) {
			@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
		};
		private final LoaderDiagnosticScope diagnostics;
		private Capture(LoggingProfile client, LoggingProfile server) {
			diagnostics = new LoaderDiagnosticScope(client, server);
			appender.start();
			LoggerConfig config = new LoggerConfig(logger.getName(), Level.ALL, false);
			config.addAppender(appender, Level.ALL, null);
			configuration.removeLogger(logger.getName()); configuration.addLogger(logger.getName(), config);
			logger.getContext().updateLoggers();
		}
		private String message() { return events.getLast().getMessage().getFormattedMessage(); }
		@Override public void close() {
			diagnostics.close();
			configuration.removeLogger(logger.getName());
			if (previous != null) configuration.addLogger(logger.getName(), previous);
			logger.getContext().updateLoggers(); appender.stop();
		}
	}
}
