package nx.pingwheel.forge.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.EventNetworkChannel;
import net.minecraftforge.network.HeadlessEventChannel;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoaderDiagnosticScope;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.network.ServerConfigSnapshotS2CPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ServerConfigTransportDiagnosticsTest {
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test void c2sConnectionMissingIsDebugOnlyAndUsesTheKnownPacketKind() {
		@Nullable Minecraft previous = CommonClient.Game;
		try (var capture = new Capture()) {
			CommonClient.Game = clientWithoutConnection();
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			new PlatformNetworkServiceImpl().sendToServer(new ServerConfigRequestC2SPacket(7));
			assertTrue(capture.events.isEmpty());

			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			new PlatformNetworkServiceImpl().sendToServer(new ServerConfigRequestC2SPacket(7));
			new PlatformNetworkServiceImpl().sendToServer(new ServerConfigUpdateC2SPacket(
				ServerConfigUpdateC2SPacket.RATE_LIMIT, ChannelMode.AUTO, true, 1000, 5, 7,
				InventoryConfigValues.defaults()));
			assertEquals(2, capture.events.size());
			assertDecision(capture.events.get(0), "CLIENT", "connection-missing", "request");
			assertDecision(capture.events.get(1), "CLIENT", "connection-missing", "update");
		} finally {
			CommonClient.Game = previous;
		}
	}

	@Test void snapshotRouteAndActualDispatchUseOneTerminalDecision() {
		var snapshot = new ServerConfigSnapshotS2CPacket(17, true, ChannelMode.AUTO, true, 1000, 5, 7,
			InventoryConfigValues.defaults(), true, LoggingProfile.DEEP);
		var sent = new ArrayList<Packet<?>>();
		var routeChecks = new AtomicInteger();
		var recipient = recipient();
		@Nullable EventNetworkChannel previous = PlatformNetworkServiceImpl.CHANNEL_MAP.put(
			ServerConfigSnapshotS2CPacket.PACKET_ID,
			HeadlessEventChannel.create(ServerConfigSnapshotS2CPacket.PACKET_ID));
		try (var capture = new Capture()) {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			new PlatformNetworkServiceImpl((channel, player) -> { routeChecks.incrementAndGet(); return false; },
				(player, packet) -> fail("unavailable route must not send")).sendToClient(snapshot, recipient);
			assertEquals(1, routeChecks.get());
			assertTrue(sent.isEmpty());
			assertTrue(capture.events.isEmpty());

			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			new PlatformNetworkServiceImpl((channel, player) -> { routeChecks.incrementAndGet(); return false; },
				(player, packet) -> fail("unavailable route must not send")).sendToClient(snapshot, recipient);
			assertEquals(2, routeChecks.get());
			assertTrue(sent.isEmpty());
			assertEquals(1, capture.events.size());
			assertDecision(capture.events.getFirst(), "SERVER", "route-unavailable", "snapshot");

			var sending = new PlatformNetworkServiceImpl((channel, player) -> { routeChecks.incrementAndGet(); return true; },
				(player, packet) -> sent.add(packet));
			sending.sendToClient(snapshot, recipient);
			assertEquals(3, routeChecks.get());
			assertEquals(1, sent.size());
			assertNotNull(sent.getFirst());
			assertEquals(2, capture.events.size());
			assertDecision(capture.events.getLast(), "SERVER", "send-dispatched", "snapshot");
		} finally {
			if (previous == null) PlatformNetworkServiceImpl.CHANNEL_MAP.remove(ServerConfigSnapshotS2CPacket.PACKET_ID);
			else PlatformNetworkServiceImpl.CHANNEL_MAP.put(ServerConfigSnapshotS2CPacket.PACKET_ID, previous);
		}
	}

	private static void assertDecision(LogEvent event, String side, String handling, String packet) {
		var message = event.getMessage().getFormattedMessage();
		assertEquals(Level.DEBUG, event.getLevel());
		assertTrue(message.contains("side=" + side));
		assertTrue(message.contains("site=server-config/transport"));
		assertTrue(message.contains("handling=" + handling));
		assertTrue(message.contains("packet=" + packet));
		assertNull(event.getThrown());
	}

	private static Minecraft clientWithoutConnection() {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe");
			var field = unsafe.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return java.util.Objects.requireNonNull((Minecraft) unsafe.getMethod("allocateInstance", Class.class)
				.invoke(field.get(null), Minecraft.class));
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private static ServerPlayer recipient() {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe");
			var field = unsafe.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return java.util.Objects.requireNonNull((ServerPlayer) unsafe.getMethod("allocateInstance", Class.class)
				.invoke(field.get(null), ServerPlayer.class));
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	@NotNullByDefault
	private static final class Capture implements AutoCloseable {
		private final LoaderDiagnosticScope diagnostics = new LoaderDiagnosticScope();
		private final List<LogEvent> events = new ArrayList<>();
		private final Logger logger = (Logger) LogManager.getLogger("pingforit");
		private final org.apache.logging.log4j.core.config.Configuration configuration = logger.getContext().getConfiguration();
		private final LoggerConfig previous = configuration.getLoggers().get(logger.getName());
		private final AbstractAppender appender = new AbstractAppender("forge-server-config-transport", null, null,
			true, Property.EMPTY_ARRAY) {
			@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
		};

		private Capture() {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEBUG);
			appender.start();
			var config = new LoggerConfig(logger.getName(), Level.DEBUG, false);
			config.addAppender(appender, Level.ALL, null);
			configuration.removeLogger(logger.getName());
			configuration.addLogger(logger.getName(), config);
			logger.getContext().updateLoggers();
		}

		@Override public void close() {
			configuration.removeLogger(logger.getName());
			if (previous != null) configuration.addLogger(logger.getName(), previous);
			logger.getContext().updateLoggers();
			appender.stop();
			diagnostics.close();
		}
	}
}
