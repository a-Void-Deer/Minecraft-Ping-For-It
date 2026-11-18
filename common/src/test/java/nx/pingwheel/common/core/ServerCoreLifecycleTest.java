package nx.pingwheel.common.core;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.CommonServer;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.integration.ExternalBlockServerProviders;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.util.RateLimiter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real loader stop entry and the real first-tick transition. The
 * allocated servers are fixtures, not running worlds; the assertions protect
 * the server-instance lifecycle and its exactly-once release boundary.
 */
@NotNullByDefault
class ServerCoreLifecycleTest {
	private static final UUID OWNER = new UUID(4, 4);
	private static final String OVERWORLD = "minecraft:overworld";
	private static final PingTypeCatalog PING_TYPES = PingTypeCatalog.builtIn();
	private static final TargetTypeCatalog TARGET_TYPES = TargetTypeCatalog.builtIn();

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void realStopReleasesActiveMarkerStoreProviderAndPlayerStateOnceAndIgnoresLateStopsForReplacedServers() throws Exception {
		var activeField = field(ServerCore.class, "ACTIVE_SERVER");
		var storeField = field(ServerCore.class, "MARKER_STORE");
		var channels = playerState(field(ServerCore.class, "PLAYER_CHANNELS"));
		var rates = playerState(field(ServerCore.class, "PLAYER_RATES"));
		@Nullable Object previousActive = activeField.get(null);
		@Nullable Object previousStore = storeField.get(null);
		@Unmodifiable Map<UUID, Object> previousChannels = Map.copyOf(channels);
		@Unmodifiable Map<UUID, Object> previousRates = Map.copyOf(rates);
		@Unmodifiable List<ExternalBlockServerProvider> previousProviders =
			ExternalBlockServerProviders.registry().providers();
		var provider = new CountingProvider();
		var first = newServer();
		var second = newServer();
		try {
			ServerCore.initMarkers();
			ExternalBlockServerProviders.registry().clear();
			ExternalBlockServerProviders.registry().register(provider);

			// The ordinary first tick activates the store for the first server.
			ServerCore.onServerTick(first);
			assertSame(first, activeField.get(null));
			var firstStore = markerStore(storeField);
			var firstExternal = createMarker(firstStore, external("stable-first"), "entity_block");
			createMarker(firstStore, new Target.EntityTarget(OVERWORLD, new UUID(9, 9)), "entity");
			var firstPlayer = new UUID(7, 7);
			channels.put(firstPlayer, "team-first");
			rates.put(firstPlayer, new RateLimiter());
			assertEquals(2, firstStore.size());

			CommonServer.INSTANCE.onServerStopped(first);
			assertNull(activeField.get(null), "the stopped server identity is no longer active");
			assertNull(storeField.get(null), "the marker store is released with its server");
			assertTrue(channels.isEmpty(), "channel state belongs to the stopped server");
			assertTrue(rates.isEmpty(), "rate state belongs to the stopped server");
			assertEquals(List.of(first), provider.releaseServers);
			assertEquals(List.of(Long.toString(firstExternal.id().value())), provider.releaseMarkerIds);
			assertEquals(List.of(first), provider.closeServers);

			// A repeated stop and the next common initialization never release twice.
			CommonServer.INSTANCE.onServerStopped(first);
			ServerCore.initMarkers();
			assertEquals(1, provider.releaseServers.size());
			assertEquals(1, provider.closeServers.size());

			// A replacement server reuses the real first-tick entry and keeps its own state.
			ServerCore.onServerTick(second);
			assertSame(second, activeField.get(null));
			var secondStore = markerStore(storeField);
			assertEquals(0, secondStore.size(), "a replacement server never inherits stale markers");
			var secondExternal = createMarker(secondStore, external("stable-second"), "entity_block");
			var secondPlayer = new UUID(8, 8);
			channels.put(secondPlayer, "team-second");
			rates.put(secondPlayer, new RateLimiter());

			// The old instance's late stop must not clear the replacement's lifetime.
			CommonServer.INSTANCE.onServerStopped(first);
			assertSame(second, activeField.get(null));
			assertEquals(1, markerStore(storeField).size());
			assertEquals("team-second", channels.get(secondPlayer));
			assertTrue(rates.containsKey(secondPlayer));
			assertEquals(List.of(first), provider.releaseServers);
			assertEquals(List.of(first), provider.closeServers);

			CommonServer.INSTANCE.onServerStopped(second);
			assertNull(activeField.get(null));
			assertNull(storeField.get(null));
			assertTrue(channels.isEmpty());
			assertTrue(rates.isEmpty());
			assertEquals(List.of(first, second), provider.releaseServers);
			assertEquals(List.of(Long.toString(firstExternal.id().value()),
				Long.toString(secondExternal.id().value())), provider.releaseMarkerIds);
			assertEquals(List.of(first, second), provider.closeServers);

			// Both stop entries stay idempotent after the lifetime has ended.
			CommonServer.INSTANCE.onServerStopped(first);
			CommonServer.INSTANCE.onServerStopped(second);
			assertEquals(2, provider.releaseServers.size());
			assertEquals(2, provider.closeServers.size());
		} finally {
			ServerCore.stopped(activeServer(activeField));
			ServerCore.initMarkers();
			channels.clear();
			channels.putAll(previousChannels);
			rates.clear();
			rates.putAll(previousRates);
			ExternalBlockServerProviders.registry().clear();
			for (ExternalBlockServerProvider existing : previousProviders) {
				ExternalBlockServerProviders.registry().register(existing);
			}
			activeField.set(null, previousActive);
			storeField.set(null, previousStore);
		}
	}

	private static Target.ExternalBlockTarget external(String stableId) {
		return Target.ExternalBlockTarget.committed(
			OVERWORLD, CountingProvider.PROVIDER_ID, stableId, "minecraft:chest", "locator-" + stableId, true);
	}

	private static ServerMarker createMarker(ServerMarkerStore store, Target target, String targetTypeId) {
		return store.create(
			OWNER,
			target,
			TARGET_TYPES.findById(targetTypeId).orElseThrow(),
			PING_TYPES.findById("attention").orElseThrow(),
			new MarkerAnchor(0, 0, 0),
			1L, 100L,
			List.of(OWNER)).marker();
	}

	private static ServerMarkerStore markerStore(Field storeField) throws IllegalAccessException {
		return Objects.requireNonNull((ServerMarkerStore) storeField.get(null));
	}

	private static @Nullable MinecraftServer activeServer(Field activeField) throws IllegalAccessException {
		return (MinecraftServer) activeField.get(null);
	}

	@SuppressWarnings("unchecked")
	private static Map<UUID, Object> playerState(Field field) throws IllegalAccessException {
		return Objects.requireNonNull((Map<UUID, Object>) field.get(null));
	}

	private static Field field(Class<?> owner, String name) {
		try {
			var field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException failure) {
			throw new AssertionError(failure);
		}
	}

	private static Server newServer() {
		var server = allocate(Server.class);
		server.players = allocate(Players.class);
		return server;
	}

	private static <T> T allocate(Class<T> type) {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe");
			var field = unsafe.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	@NotNullByDefault
	private static final class CountingProvider implements ExternalBlockServerProvider {
		private static final String PROVIDER_ID = "test:lifecycle";
		private final List<MinecraftServer> releaseServers = new ArrayList<>();
		private final List<String> releaseMarkerIds = new ArrayList<>();
		private final List<MinecraftServer> closeServers = new ArrayList<>();

		@Override public String providerId() { return PROVIDER_ID; }

		@Override public ValidationResult validate(ServerLevel level, Target.ExternalBlockTarget candidate) {
			return new ValidationResult.Invalid();
		}

		@Override public MaterializationResult materialize(ServerLevel level, Target.ExternalBlockTarget candidate) {
			return new MaterializationResult.Invalid();
		}

		@Override public RefreshResult refresh(ServerLevel level, Target.ExternalBlockTarget committed) {
			return new RefreshResult.TemporarilyUnavailable();
		}

		@Override public Optional<ExternalBlockName> resolveName(ServerLevel level, Target.ExternalBlockTarget target) {
			return Optional.empty();
		}

		@Override public void release(MinecraftServer server, Target.ExternalBlockTarget committed) {
			releaseServers.add(server);
			releaseMarkerIds.add("legacy");
		}

		@Override public void release(MinecraftServer server, Target.ExternalBlockTarget committed, String markerId) {
			releaseServers.add(server);
			releaseMarkerIds.add(markerId);
		}

		@Override public void close(MinecraftServer server) {
			closeServers.add(server);
		}
	}

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
}
