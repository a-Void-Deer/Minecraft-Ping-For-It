package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
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

class CreateVaultInventoryDiagnosticsTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void expectedRevocationAndTopologyChangeStaySilent() {
		var world = CreateVaultInventoryAccessTest.World.pair(); boolean[] allowed = {true};
		try (var capture = new Capture()) {
			assertTrue(CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH, pos -> false).isEmpty());
			var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH, pos -> allowed[0]).orElseThrow();
			allowed[0] = false; assertFalse(access.valid()); assertTrue(access.snapshotLayout().isEmpty());
			allowed[0] = true; world.members.get(BlockPos.ZERO).axis = Direction.Axis.Z;
			assertFalse(access.valid()); assertTrue(access.snapshotLayout().isEmpty());
			var unloaded = new CreateVaultInventoryAccessTest.World() {
				@Override public CreateVaultInventoryAccessTest.Member loaded(BlockPos pos) { throw new InventorySourceUnavailableException("Vault member unloaded"); }
			};
			assertTrue(CreateVaultInventoryAccess.find(unloaded, BlockPos.ZERO, Direction.NORTH).isEmpty());
			assertTrue(capture.events.isEmpty());
		}
	}
	@Test void throwingLookupAndValidationKeepFallbackAndOriginalCause() {
		var world = CreateVaultInventoryAccessTest.World.pair();
		var failure = new IllegalStateException("Vault callback failed", new RuntimeException("Vault callback cause"));
		try (var capture = new Capture()) {
			var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
			world.afterLoaded = pos -> { throw failure; };
			assertFalse(access.valid()); assertTrue(CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).isEmpty());
			assertEquals(2, capture.events.size());
			for (var event : capture.events) {
				assertEquals(Level.WARN, event.getLevel());
				assertNull(event.getThrown());
				String message = event.getMessage().getFormattedMessage();
				assertTrue(message.contains("Vault callback failed")); assertTrue(message.contains("Vault callback cause"));
				assertTrue(message.contains("create:item_vault")); assertTrue(message.contains("position="));
				assertTrue(message.length() <= nx.pingwheel.common.util.SafeExceptionReport.MAX_CHARACTERS);
			}
		}
	}
	@Test void throwingSnapshotMetadataRemainsAbsentAndReportsFullCause() {
		var world = CreateVaultInventoryAccessTest.World.pair();
		world.members.put(BlockPos.ZERO, new CreateVaultInventoryAccessTest.Member() {
			{ master = true; }
			@Override public String blockEntityId() { throw new IllegalStateException("Vault descriptor failure", new RuntimeException("Vault descriptor cause")); }
		});
		var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		try (var capture = new Capture()) {
			assertTrue(access.valid()); assertTrue(access.snapshotLayout().isEmpty());
			assertEquals(1, capture.events.size()); assertEquals(Level.WARN, capture.events.getFirst().getLevel());
			String message = capture.events.getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("inventory-vault-snapshot-layout")); assertTrue(message.contains("Vault descriptor cause"));
		}
	}
	private static final class Capture implements AutoCloseable {
		final List<LogEvent> events = new ArrayList<>();
		private final Logger logger = (Logger) LogManager.getLogger("pingforit");
		private final org.apache.logging.log4j.core.config.Configuration configuration = logger.getContext().getConfiguration();
		private final LoggerConfig previous = configuration.getLoggers().get(logger.getName());
		private final AbstractAppender appender = new AbstractAppender("vault-inventory-diagnostics", null, null, true, Property.EMPTY_ARRAY) {
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
