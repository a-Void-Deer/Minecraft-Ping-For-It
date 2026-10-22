package nx.pingwheel.fabric.platform;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
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
		var calls = new AtomicInteger(); var failure = new IllegalStateException("Fabric lookup failure", new RuntimeException("Fabric lookup cause"));
		var entity = new BeehiveBlockEntity(BlockPos.ZERO, Blocks.BEEHIVE.defaultBlockState());
		var world = new PlatformInventoryServiceImpl.World() {
			@Override public boolean loaded(BlockPos pos) { return true; }
			@Override public BlockEntity blockEntity(BlockPos pos) { return entity; }
			@Override public Storage<ItemVariant> storage(BlockPos pos, Direction face) { calls.incrementAndGet(); throw failure; }
		};
		try (var capture = new Capture()) {
			assertTrue(PlatformInventoryServiceImpl.find(world, BlockPos.ZERO, Direction.NORTH, pos -> true).isEmpty());
			assertTrue(PlatformInventoryServiceImpl.find(world, new BlockPos(8, 0, 0), Direction.NORTH, pos -> true).isEmpty());
			assertEquals(2, calls.get()); assertEquals(1, capture.events.size());
			assertTrue(capture.message().contains("Fabric lookup failure")); assertTrue(capture.message().contains("Fabric lookup cause"));
			assertTrue(capture.message().contains("face=" + Direction.NORTH)); assertTrue(capture.message().contains(entity.getClass().getName()));
		}
	}
	@Test void absentCapabilityAndDeniedMembershipAreSilentButThrowingVersionAndGateReportWarnings() {
		var world = new PlatformInventoryServiceImpl.World() {
			@Override public boolean loaded(BlockPos pos) { return true; }
			@Override public BlockEntity blockEntity(BlockPos pos) { return null; }
			@Override public Storage<ItemVariant> storage(BlockPos pos, Direction face) { return null; }
		};
		@SuppressWarnings("unchecked") Storage<ItemVariant> storage = (Storage<ItemVariant>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Storage.class}, (proxy, method, args) -> {
			if (method.getName().equals("getVersion")) throw new IllegalStateException("Fabric version failure", new RuntimeException("Fabric version cause"));
			throw new AssertionError("diagnostics called " + method.getName());
		});
		try (var capture = new Capture()) {
			assertTrue(PlatformInventoryServiceImpl.find(world, BlockPos.ZERO, Direction.NORTH, pos -> true).isEmpty());
			assertFalse(new PlatformInventoryServiceImpl.EnumeratedAccess(storage, () -> false).valid());
			assertTrue(capture.events.isEmpty());
			assertTrue(new PlatformInventoryServiceImpl.EnumeratedAccess(storage).version().isEmpty());
			assertEquals(1, capture.events.size()); assertTrue(capture.message().contains("Fabric version cause"));
			assertFalse(new PlatformInventoryServiceImpl.EnumeratedAccess(storage, () -> {
				throw new IllegalStateException("Fabric gate failure", new RuntimeException("Fabric gate cause"));
			}).valid());
			assertEquals(2, capture.events.size()); assertTrue(capture.events.getLast().getMessage().getFormattedMessage().contains("Fabric gate cause"));
		}
	}
	private static final class Capture implements AutoCloseable {
		final List<LogEvent> events = new ArrayList<>();
		private final Logger logger = (Logger) LogManager.getLogger("pingforit");
		private final org.apache.logging.log4j.core.config.Configuration configuration = logger.getContext().getConfiguration();
		private final LoggerConfig previous = configuration.getLoggers().get(logger.getName());
		private final AbstractAppender appender = new AbstractAppender("fabric-inventory-diagnostics", null, null, true, Property.EMPTY_ARRAY) {
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
