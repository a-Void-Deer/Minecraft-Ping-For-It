package nx.pingwheel.forge.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
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
	@Test void failedLookupKeepsUnavailableAndReportsCauseWithoutExtraQueries() {
		var calls = new AtomicInteger(); var failure = new IllegalStateException("Forge lookup failure", new RuntimeException("Forge lookup cause"));
		var entity = new BeehiveBlockEntity(BlockPos.ZERO, Blocks.BEEHIVE.defaultBlockState());
		var world = new PlatformInventoryServiceImpl.World() {
			@Override public boolean loaded(BlockPos pos) { return true; }
			@Override public BlockEntity blockEntity(BlockPos pos) { return entity; }
			@Override public Optional<IItemHandler> handler(BlockEntity current, Direction face) { calls.incrementAndGet(); throw failure; }
		};
		try (var capture = new Capture()) {
			assertTrue(PlatformInventoryServiceImpl.find(world, BlockPos.ZERO, Direction.EAST, pos -> true).isEmpty());
			assertTrue(PlatformInventoryServiceImpl.find(world, new BlockPos(8, 0, 0), Direction.EAST, pos -> true).isEmpty());
			assertEquals(2, calls.get()); assertEquals(1, capture.events.size());
			assertNull(capture.events.getFirst().getThrown()); assertTrue(capture.message().length() <= nx.pingwheel.common.util.SafeExceptionReport.MAX_CHARACTERS);
			assertTrue(capture.message().contains("Forge lookup failure")); assertTrue(capture.message().contains("Forge lookup cause"));
			assertTrue(capture.message().contains("face=" + Direction.EAST)); assertTrue(capture.message().contains(entity.getClass().getName()));
		}
	}
	@Test void absentCapabilityAndDeniedMembershipAreSilentButThrowingGateReportsWarning() {
		var entity = new BeehiveBlockEntity(BlockPos.ZERO, Blocks.BEEHIVE.defaultBlockState());
		var world = new PlatformInventoryServiceImpl.World() {
			@Override public boolean loaded(BlockPos pos) { return true; }
			@Override public BlockEntity blockEntity(BlockPos pos) { return entity; }
			@Override public Optional<IItemHandler> handler(BlockEntity current, Direction face) { return Optional.empty(); }
		};
		try (var capture = new Capture()) {
			assertTrue(PlatformInventoryServiceImpl.find(world, BlockPos.ZERO, Direction.EAST, pos -> true).isEmpty());
			assertFalse(new PlatformInventoryServiceImpl.HandlerAccess(new ItemStackHandler(1), () -> false).valid());
			assertTrue(capture.events.isEmpty());
			assertFalse(new PlatformInventoryServiceImpl.HandlerAccess(new ItemStackHandler(1), () -> {
				throw new IllegalStateException("Forge gate failure", new RuntimeException("Forge gate cause"));
			}).valid());
			assertEquals(1, capture.events.size()); assertTrue(capture.message().contains("Forge gate cause"));
		}
	}
	private static final class Capture implements AutoCloseable {
		final List<LogEvent> events = new ArrayList<>();
		private final Logger logger = (Logger) LogManager.getLogger("pingforit");
		private final org.apache.logging.log4j.core.config.Configuration configuration = logger.getContext().getConfiguration();
		private final LoggerConfig previous = configuration.getLoggers().get(logger.getName());
		private final AbstractAppender appender = new AbstractAppender("forge-inventory-diagnostics", null, null, true, Property.EMPTY_ARRAY) {
			@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
		};
		Capture() {
			appender.start(); var config = new LoggerConfig(logger.getName(), Level.WARN, false); config.addAppender(appender, Level.ALL, null);
			configuration.removeLogger(logger.getName()); configuration.addLogger(logger.getName(), config); logger.getContext().updateLoggers();
		}
		String message() { assertEquals(Level.WARN, events.getFirst().getLevel()); return events.getFirst().getMessage().getFormattedMessage(); }
		@Override public void close() {
			configuration.removeLogger(logger.getName()); if (previous != null) configuration.addLogger(logger.getName(), previous);
			logger.getContext().updateLoggers(); appender.stop();
		}
	}
}
