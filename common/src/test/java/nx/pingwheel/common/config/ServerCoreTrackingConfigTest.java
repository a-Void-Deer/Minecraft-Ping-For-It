package nx.pingwheel.common.config;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import nx.pingwheel.common.core.ServerCore;
import nx.pingwheel.common.network.ServerConfigSnapshotS2CPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** Real packet-facing assignment, including reconstruction when only a numeric leaf is selected. */
@NotNullByDefault
class ServerCoreTrackingConfigTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void packetFacingCoreRetainsUnselectedFlagsAndRejectsDeniedOrMaliciousUpdates() throws Exception {
		var handler = ServerConfig.HANDLER;
		var configField = ConfigHandler.class.getDeclaredField("config");
		var hashField = ConfigHandler.class.getDeclaredField("configHash");
		var activeField = ServerCore.class.getDeclaredField("ACTIVE_SERVER");
		configField.setAccessible(true);
		hashField.setAccessible(true);
		activeField.setAccessible(true);
		Object previousConfig = Objects.requireNonNull(configField.get(handler));
		int previousHash = hashField.getInt(handler);
		@Nullable Object previousServer = activeField.get(null);
		var path = handler.getConfigPath();
		byte @Nullable [] previousBytes = Files.exists(path) ? Files.readAllBytes(path) : null;
		var config = new ServerConfig();
		config.getInventory().getTracking().setDistinguishFaces(true);
		config.getInventory().getTracking().setDistinguishPlayers(true);
		config.getInventory().getTracking().setPeriodTicks(3);
		config.setRateLimit(19);
		var player = allocate(Player.class);
		var server = allocate(DedicatedServer.class);
		player.permission = 3;
		try {
			activeField.set(null, null);
			configField.set(handler, config);
			var stale = InventoryConfigValues.defaults().with(Field.TRACKING_PERIOD_TICKS, Value.scalar(7));
			var numeric = wire(new ServerConfigUpdateC2SPacket(Field.TRACKING_PERIOD_TICKS.mask(),
				ChannelMode.AUTO, true, 1000, 5, 23, stale));
			assertFalse(numeric.isCorrupt());
			ServerCore.onServerConfigUpdate(server, player, numeric);
			assertEquals(7, config.getInventory().getTracking().getPeriodTicks());
			assertTrue(config.getInventory().getTracking().isDistinguishFaces());
			assertTrue(config.getInventory().getTracking().isDistinguishPlayers());
			assertEquals(19, config.getRateLimit());
			assertTrue(com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject()
				.getAsJsonObject("inventory").getAsJsonObject("tracking").get("distinguishPlayers").getAsBoolean());

			var faceOnly = wire(new ServerConfigUpdateC2SPacket(Field.TRACKING_DISTINGUISH_FACES.mask(),
				ChannelMode.DISABLED, false, 0, 0, 7, InventoryConfigValues.defaults()));
			player.permission = 2;
			ServerCore.onServerConfigUpdate(server, player, faceOnly);
			assertTrue(config.getInventory().getTracking().isDistinguishFaces());
			player.permission = 3;
			ServerCore.onServerConfigUpdate(server, player, faceOnly);
			assertFalse(config.getInventory().getTracking().isDistinguishFaces());
			assertTrue(config.getInventory().getTracking().isDistinguishPlayers());
			assertEquals(7, config.getInventory().getTracking().getPeriodTicks());
			var authoritative = ServerConfigSnapshot.from(config, true);
			var malicious = new ServerConfigUpdateC2SPacket(Field.TRACKING_DISTINGUISH_PLAYERS.mask()
				| ServerConfigUpdate.RATE_LIMIT, ChannelMode.AUTO, true, 0, 99, 7,
				InventoryConfigValues.defaults().with(Field.TRACKING_DISTINGUISH_PLAYERS, Value.scalar(2)));
			ServerCore.onServerConfigUpdate(server, player, malicious);
			ServerCore.onServerConfigUpdate(server, player, new ServerConfigUpdateC2SPacket(1 << 29,
				ChannelMode.AUTO, true, 0, 99, 7, InventoryConfigValues.defaults()));
			assertEquals(authoritative, ServerConfigSnapshot.from(config, true));
			var buf = new FriendlyByteBuf(Unpooled.buffer());
			try {
				new ServerConfigSnapshotS2CPacket(17, authoritative).write(buf);
				var snapshot = ServerConfigSnapshotS2CPacket.readSafe(buf);
				assertFalse(snapshot.isCorrupt());
				assertEquals(authoritative, snapshot.snapshot());
				assertFalse(snapshot.inventory().value(Field.TRACKING_DISTINGUISH_FACES).booleanValue());
				assertTrue(snapshot.inventory().value(Field.TRACKING_DISTINGUISH_PLAYERS).booleanValue());
			} finally { buf.release(); }
		} finally {
			configField.set(handler, previousConfig);
			hashField.setInt(handler, previousHash);
			activeField.set(null, previousServer);
			ServerCore.init();
			if (previousBytes == null) Files.deleteIfExists(path);
			else Files.write(path, previousBytes);
		}
	}

	private static ServerConfigUpdateC2SPacket wire(ServerConfigUpdateC2SPacket packet) {
		var buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(buf);
			return ServerConfigUpdateC2SPacket.readSafe(buf);
		} finally { buf.release(); }
	}

	private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
		Class<?> unsafe = Class.forName("sun.misc.Unsafe");
		var field = unsafe.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
	}

	@NotNullByDefault
	static final class Player extends ServerPlayer {
		int permission;
		private Player() { super(null, null, null, null); }
		@Override public boolean hasPermissions(int required) { return permission >= required; }
	}
}
