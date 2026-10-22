package nx.pingwheel.neoforge.platform;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformInventoryDiagnosticsTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void deniedMembershipIsSilentButThrowingGateKeepsInvalidAndReportsCause() {
		try (var capture = new Capture()) {
			assertFalse(new PlatformInventoryServiceImpl.HandlerAccess(new ItemStackHandler(1), BlockPos.ZERO, pos -> false).valid());
			assertTrue(capture.events.isEmpty());
			assertFalse(new PlatformInventoryServiceImpl.HandlerAccess(new ItemStackHandler(1), BlockPos.ZERO, pos -> {
				throw new IllegalStateException("NeoForge gate failure", new RuntimeException("NeoForge gate cause"));
			}).valid());
			assertEquals(1, capture.events.size()); assertEquals(Level.WARN, capture.events.getFirst().getLevel());
			String message = capture.events.getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("NeoForge gate failure")); assertTrue(message.contains("NeoForge gate cause"));
			assertTrue(message.contains("handlerClass=" + ItemStackHandler.class.getName())); assertTrue(message.contains("position="));
		}
	}
	private static final class Capture implements AutoCloseable {
		final List<LogEvent> events = new ArrayList<>();
		private final Logger logger = (Logger) LogManager.getLogger("pingforit");
		private final org.apache.logging.log4j.core.config.Configuration configuration = logger.getContext().getConfiguration();
		private final LoggerConfig previous = configuration.getLoggers().get(logger.getName());
		private final AbstractAppender appender = new AbstractAppender("neoforge-inventory-diagnostics", null, null, true, Property.EMPTY_ARRAY) {
			@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
		};
		Capture() {
			appender.start(); var config = new LoggerConfig(logger.getName(), Level.WARN, false); config.addAppender(appender, Level.ALL, null);
			configuration.removeLogger(logger.getName()); configuration.addLogger(logger.getName(), config); logger.getContext().updateLoggers();
		}
		@Override public void close() {
			configuration.removeLogger(logger.getName()); if (previous != null) configuration.addLogger(logger.getName(), previous);
			logger.getContext().updateLoggers(); appender.stop();
		}
	}
}
