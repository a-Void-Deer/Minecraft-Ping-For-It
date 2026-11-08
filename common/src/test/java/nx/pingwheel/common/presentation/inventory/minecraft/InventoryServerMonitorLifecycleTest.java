package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.CommonServer;
import nx.pingwheel.common.config.ConfigHandler;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.presentation.inventory.InventoryBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryServerMonitorLifecycleTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void realInventoryLifetimeOwnsPositiveOffGenerationAppliesFourMasksAndReplacesOnStopRestart() throws Exception {
		var configField = ConfigHandler.class.getDeclaredField("config"); configField.setAccessible(true);
		@Nullable Object previous = configField.get(ServerConfig.HANDLER);
		var config = new ServerConfig();
		var first = server(); var second = server();
		try {
			configField.set(ServerConfig.HANDLER, config);
			InventoryServer.reset(); InventoryServer.activate(first);
			var original = backend();
			assertTrue(original.monitor().serverGeneration() > 0); assertTrue(original.monitor().collectionGeneration() > 0);
			assertFalse(original.monitor().enabled());
			InventoryServer.tick(first); assertNull(original.monitor().latest());
			long generation = original.monitor().collectionGeneration();
			config.setLoggingProfile(LoggingProfile.DEBUG);
			for (int mask : new int[] {1, 3, 2, 0}) {
				config.setPreviewMonitorEnabled((mask & 1) != 0); config.setTrackingMonitorEnabled((mask & 2) != 0);
				first.now++;
				InventoryServer.tick(first);
				assertSame(original, backend());
				assertEquals((mask & 1) != 0, original.monitor().enabled(Lane.PREVIEW));
				assertEquals((mask & 2) != 0, original.monitor().enabled(Lane.TRACKING));
				assertTrue(original.monitor().collectionGeneration() > generation); generation = original.monitor().collectionGeneration();
				assertNull(original.monitor().latest(), "mask transition must clear old samples before a new period closes");
				for (int ticks = 1; ticks < 6; ticks++) { first.now++; InventoryServer.tick(first); }
				if (mask == 0) assertNull(original.monitor().latest());
				else assertNotNull(original.monitor().latest(), "collection runs with no telemetry watchers and no inventory audience");
			}
			long serverGeneration = original.monitor().serverGeneration();
			CommonServer.INSTANCE.onServerStopped(first);
			assertNull(readBackend());
			InventoryServer.activate(second);
			assertTrue(backend().monitor().serverGeneration() > serverGeneration); assertFalse(backend().monitor().enabled());
			CommonServer.INSTANCE.onServerStopped(first);
			assertNotNull(readBackend(), "obsolete stop must not destroy replacement server lifetime");
			CommonServer.INSTANCE.onServerStopped(second); assertNull(readBackend());
		} finally { InventoryServer.reset(); configField.set(ServerConfig.HANDLER, previous); }
	}

	private static @Nullable InventoryBackend readBackend() throws ReflectiveOperationException {
		var field = InventoryServer.class.getDeclaredField("backend"); field.setAccessible(true);
		return (InventoryBackend) field.get(null);
	}
	private static InventoryBackend backend() throws ReflectiveOperationException { return Objects.requireNonNull(readBackend()); }
	private static Server server() { var server = allocate(Server.class); server.players = allocate(Players.class); return server; }
	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		private @Nullable Players players;
		private int now;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
		@Override public int getTickCount() { return now; }
		@Override public DedicatedPlayerList getPlayerList() { return Objects.requireNonNull(players); }
		@Override public RegistryAccess.Frozen registryAccess() { return RegistryAccess.EMPTY; }
	}
	@NotNullByDefault
	private static final class Players extends DedicatedPlayerList {
		private Players() { super(null, null, null); }
		@Override public @Nullable ServerPlayer getPlayer(UUID id) { return null; }
		@Override public @Unmodifiable List<ServerPlayer> getPlayers() { return List.of(); }
	}
	private static <T> T allocate(Class<T> type) {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
