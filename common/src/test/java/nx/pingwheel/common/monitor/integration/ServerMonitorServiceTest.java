package nx.pingwheel.common.monitor.integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import nx.pingwheel.common.monitor.MonitorAccumulator;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.monitor.server.ServerMonitorCoordinator.CollectionState;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket.SampleCapability;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.platform.RouteSupport;
import nx.pingwheel.common.platform.TransportHandoff;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ServerMonitorServiceTest {
	private static final UUID ID = new UUID(303, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void publicStatusSurvivesPermissionDenialAndNumericHandoffRechecksTrustedPlayer() {
		try (var f = new Fixture()) {
			f.player.permission = 0;
			assertTrue(f.service.subscribe(f.player, watch(1)));
			f.service.tick(0);
			assertFalse(f.network.statuses.getFirst().authorized());
			assertTrue(f.network.statuses.getFirst().previewEnabled());
			assertEquals(0, f.latestReads); assertTrue(f.network.samples.isEmpty());
			f.player.permission = 3;
			f.complete(1); f.service.tick(5);
			assertTrue(f.network.statuses.getLast().authorized());
			f.complete(2); f.revokeOnRead = true; f.service.tick(10);
			assertTrue(f.network.samples.isEmpty(), "a permission held at subscription/tick is not a numeric send grant");
			f.service.tick(15);
			assertFalse(f.network.statuses.getLast().authorized());
		}
	}

	@ParameterizedTest @EnumSource(value = TransportHandoff.class, names = {"NOT_HANDED_OFF", "UNOBSERVED"})
	void onlyActualStatusHandoffUnlocksNumbersAndNeverFallsBack(TransportHandoff outcome) {
		try (var f = new Fixture()) {
			f.network.handoff = outcome;
			assertTrue(f.service.subscribe(f.player, watch(1)));
			f.complete(0); f.service.tick(0);
			assertEquals(1, f.network.statuses.size()); assertTrue(f.network.samples.isEmpty());
			f.service.tick(1); assertEquals(1, f.network.statuses.size());
			assertEquals(0, f.latestReads);
			f.network.handoff = TransportHandoff.HANDED_OFF;
			f.complete(1); f.service.tick(100);
			assertEquals(2, f.network.statuses.size()); assertEquals(1, f.network.samples.size());
		}
	}

	@Test void capabilityDirectionsRemainIndependentAndExceptionsStayUnknownNotUnsupported() {
		try (var f = new Fixture()) {
			assertTrue(f.service.subscribe(f.player, watch(1)));
			f.network.sampleSupport = RouteSupport.UNKNOWN; f.service.tick(0);
			assertEquals(SampleCapability.UNKNOWN, f.network.statuses.getLast().sampleCapability());
			f.network.sampleSupport = RouteSupport.UNAVAILABLE; f.service.tick(5);
			assertEquals(SampleCapability.UNAVAILABLE, f.network.statuses.getLast().sampleCapability());
			assertTrue(f.network.statuses.getLast().previewEnabled());
			f.network.capabilityFailure = new IllegalStateException("query failed"); f.service.tick(10);
			assertTrue(f.network.samples.isEmpty());
			f.network.capabilityFailure = null; f.network.sampleSupport = RouteSupport.AVAILABLE;
			f.network.sendFailure = new IllegalStateException("send failed");
			f.complete(0); assertDoesNotThrow(() -> f.service.tick(15));
			assertTrue(f.network.samples.isEmpty());
		}
	}

	@Test void replacementConnectionGetsFreshRevisionSpaceAndOldLogoutCannotClearItsWatch() {
		try (var f = new Fixture()) {
			assertTrue(f.service.subscribe(f.player, watch(9)));
			var replacement = player();
			f.server.players.entries.put(ID, replacement);
			assertFalse(f.service.subscribe(f.player, watch(10)), "old queued player must not replace current connection demand");
			assertTrue(f.service.subscribe(replacement, watch(1)));
			f.service.disconnect(f.player);
			assertEquals(1, f.service.watchingConnections());
			f.service.tick(0);
			assertEquals(1, f.network.statuses.getLast().demandRevision());
			f.service.disconnect(replacement);
			assertEquals(0, f.service.retainedConnections());
		}
	}

	@Test void respawnRetainsRealClientDemandAndContinuesDeliveryThroughTheSameListener() {
		try (var f = new Fixture()) {
			var client = new ClientMonitorState();
			var demands = new AtomicInteger();
			IPlatformNetworkService clientNetwork = new IPlatformNetworkService() {
				@Override public RouteSupport serverboundSupport(ResourceLocation route) { return RouteSupport.AVAILABLE; }
				@Override public void sendToServer(IPacket packet) {
					demands.incrementAndGet();
					assertTrue(f.service.subscribe(Objects.requireNonNull(f.server.players.entries.get(ID)),
						assertInstanceOf(InventoryMonitorSubscribeC2SPacket.class, packet)));
				}
				@Override public void sendToClient(IPacket packet, ServerPlayer player) { fail("client cannot send S2C"); }
			};
			var adapter = new ClientMonitorService(client, clientNetwork);
			adapter.resetConnection();
			var config = new ClientConfig(); config.setLoggingProfile(LoggingProfile.DEBUG); config.setMonitorHudEnabled(true);
			adapter.configure(config);
			long revision = client.demandRevision(), token = client.connectionGeneration();
			f.complete(0); f.service.tick(0);
			assertTrue(client.accept(token, f.network.statuses.getLast()));
			assertTrue(client.accept(token, f.network.samples.getLast()));
			var respawned = player(); respawned.connection = f.player.connection;
			f.server.players.entries.put(ID, respawned);
			for (long tick = 1; tick <= 110; tick++) { adapter.configure(config); f.complete(tick); f.service.tick(tick); }
			assertEquals(1, f.service.watchingConnections(), "respawn replaces the entity, not the connection");
			assertEquals(1, demands.get()); assertEquals(revision, client.demandRevision());
			assertTrue(f.network.statuses.size() > 1); assertTrue(f.network.samples.size() > 1);
			assertEquals(revision, f.network.statuses.getLast().demandRevision());
			assertTrue(client.accept(token, f.network.statuses.getLast()));
			assertTrue(client.accept(token, f.network.samples.getLast()));
			assertEquals(111, Objects.requireNonNull(client.view().remote()).sampleSequence());
			assertSame(respawned, f.network.recipients.getLast(), "handoff must use the current respawned player");
			assertSame(respawned, f.network.capabilityPlayers.getLast(), "capability must use the current respawned player");
		}
	}

	@Test void respawnUsesCurrentPlayerPermissionRatherThanTheOldAdminObject() {
		try (var f = new Fixture()) {
			assertTrue(f.service.subscribe(f.player, watch(1))); f.complete(0); f.service.tick(0);
			int samples = f.network.samples.size();
			var respawned = player(); respawned.connection = f.player.connection; respawned.permission = 0;
			f.server.players.entries.put(ID, respawned);
			f.complete(1); f.service.tick(5);
			assertEquals(1, f.service.watchingConnections());
			assertFalse(f.network.statuses.getLast().authorized());
			assertEquals(2, f.network.statuses.getLast().permissionGeneration());
			assertEquals(samples, f.network.samples.size()); assertEquals(3, f.player.permission);
			assertSame(respawned, f.network.recipients.getLast());
			assertTrue(f.service.subscribe(f.player, watch(2)), "same-listener queued subscription remains valid after respawn");
			f.complete(2); f.service.tick(10);
			assertFalse(f.network.statuses.getLast().authorized()); assertEquals(samples, f.network.samples.size());
		}
	}

	@Test void replacementListenerNeverInheritsThePreviousWatchWithoutAFreshSubscription() {
		try (var f = new Fixture()) {
			assertTrue(f.service.subscribe(f.player, watch(9))); f.complete(0); f.service.tick(0);
			int statuses = f.network.statuses.size(), samples = f.network.samples.size();
			f.server.players.entries.put(ID, player());
			f.complete(1); f.service.tick(100);
			assertEquals(0, f.service.retainedConnections());
			assertEquals(statuses, f.network.statuses.size()); assertEquals(samples, f.network.samples.size());
			assertFalse(f.service.subscribe(f.player, watch(10)), "UUID equality cannot authenticate a new listener");
		}
	}

	@Test void respawnQueuedSubscriptionAndStopUseTheSameConnectionFence() {
		try (var f = new Fixture()) {
			var tasks = new ArrayList<Runnable>();
			MonitorPacketDispatch.server(tasks::add, f.server, f.player, f.player.connection, watch(1),
				() -> assertTrue(f.service.subscribe(f.player, watch(1))));
			var respawned = player(); respawned.connection = f.player.connection;
			f.server.players.entries.put(ID, respawned); tasks.removeFirst().run();
			assertEquals(1, f.service.watchingConnections());
			MonitorPacketDispatch.server(tasks::add, f.server, f.player, f.player.connection,
				InventoryMonitorSubscribeC2SPacket.stop(2),
				() -> assertTrue(f.service.subscribe(f.player, InventoryMonitorSubscribeC2SPacket.stop(2))));
			tasks.removeFirst().run(); assertEquals(0, f.service.watchingConnections());
			assertTrue(f.service.subscribe(respawned, watch(3)));
			f.service.disconnect(f.player);
			assertEquals(0, f.service.retainedConnections(), "logout carrying an old entity still ends its unchanged connection");
		}
	}

	@Test void queuedConsumerReceivesTheCurrentRespawnedPlayerAndItsRevokedPermission() {
		try (var f = new Fixture()) {
			var tasks = new ArrayList<Runnable>();
			var respawned = player(); respawned.connection = f.player.connection; respawned.permission = 0;
			MonitorPacketDispatch.server(tasks::add, f.server, f.player, f.player.connection, watch(1), current -> {
				assertSame(respawned, current); assertFalse(current.hasPermissions(3));
				assertTrue(f.service.subscribe(current, watch(1)));
			});
			f.server.players.entries.put(ID, respawned); tasks.removeFirst().run();
			f.complete(0); f.service.tick(0);
			assertFalse(f.network.statuses.getLast().authorized()); assertTrue(f.network.samples.isEmpty());
			assertSame(respawned, f.network.recipients.getLast());
		}
	}

	@Test void dispatcherCannotPairAnOldEntityWithAnotherConnectionsListener() {
		try (var f = new Fixture()) {
			var tasks = new ArrayList<Runnable>(); var replacement = player();
			f.server.players.entries.put(ID, replacement);
			MonitorPacketDispatch.server(tasks::add, f.server, f.player, replacement.connection, watch(1),
				current -> fail("a UUID does not authenticate an unrelated listener"));
			assertTrue(tasks.isEmpty());
		}
	}

	@Test void explicitStopDisconnectAndCloseClearWatchesWithoutMutatingCollection() {
		try (var f = new Fixture()) {
			long generation = f.collector.collectionGeneration();
			assertTrue(f.service.subscribe(f.player, watch(1)));
			assertTrue(f.service.subscribe(f.player, InventoryMonitorSubscribeC2SPacket.stop(2)));
			assertEquals(0, f.service.watchingConnections());
			assertFalse(f.service.subscribe(f.player, watch(1)));
			f.service.disconnect(f.player);
			assertTrue(f.service.subscribe(f.player, watch(1)));
			f.service.close();
			assertEquals(0, f.service.retainedConnections());
			assertFalse(f.service.subscribe(f.player, watch(2)));
			assertTrue(f.collector.enabled(Lane.PREVIEW));
			assertEquals(generation, f.collector.collectionGeneration());
		}
	}

	@Test void offStartupHasPositiveControlGenerationsAndNoNumericCollectorRead() {
		try (var f = new Fixture()) {
			f.collector.configure(false, false);
			assertTrue(f.service.subscribe(f.player, watch(1)));
			f.service.tick(0);
			var status = f.network.statuses.getFirst();
			assertTrue(status.serverGeneration() > 0); assertTrue(status.collectionGeneration() > 0);
			assertFalse(status.previewEnabled()); assertFalse(status.trackingEnabled());
			assertEquals(0, f.latestReads); assertTrue(f.network.samples.isEmpty());
		}
	}

	@Test void defaultDisabledPathDoesNotReadCapabilitiesAuthorityOrLatestWhenNobodyWatches() {
		try (var f = new Fixture()) {
			f.server.players.entries.clear();
			f.network.capabilityFailure = new IllegalStateException("must not query without a watch");
			f.collector.configure(false, false);
			f.service.tick(0); f.service.tick(1);
			assertEquals(0, f.latestReads); assertTrue(f.network.statuses.isEmpty()); assertTrue(f.network.samples.isEmpty());
		}
	}

	@Test void foreignThreadCannotSubscribeTickOrDisconnectAndStatusSendFailureIsIsolated() {
		try (var f = new Fixture()) {
			f.server.ownerThread = false;
			assertFalse(f.service.subscribe(f.player, watch(1)));
			assertEquals(0, f.service.retainedConnections());
			f.server.ownerThread = true; assertTrue(f.service.subscribe(f.player, watch(1)));
			f.server.ownerThread = false;
			f.service.disconnect(f.player); f.service.tick(0);
			assertEquals(1, f.service.watchingConnections()); assertTrue(f.network.statuses.isEmpty());
			f.server.ownerThread = true; f.network.sendFailure = new IllegalStateException("status handoff failed");
			assertDoesNotThrow(() -> f.service.tick(0));
			assertEquals(0, f.latestReads); assertTrue(f.network.samples.isEmpty());
			f.network.sendFailure = null; f.service.tick(100);
			assertEquals(1, f.network.statuses.size());
		}
	}

	@Test void productionPortsFeedClientFencesAndPermissionLossClearsOnlyRemoteNumbers() {
		try (var f = new Fixture()) {
			var client = new ClientMonitorState();
			var metric = Objects.requireNonNull(Metric.byId("server.preview.requests.received"));
			client.configure(true, List.of(metric)); client.resetConnection();
			long token = client.connectionGeneration();
			assertTrue(f.service.subscribe(f.player, InventoryMonitorSubscribeC2SPacket.watch(client.demandRevision(), client.selected())));
			f.complete(0); f.service.tick(0);
			assertTrue(client.accept(token, f.network.statuses.getLast()));
			assertTrue(client.accept(token, f.network.samples.getLast()));
			assertNotNull(client.view().remote());
			f.player.permission = 0; f.complete(1); f.service.tick(5);
			assertTrue(client.accept(token, f.network.statuses.getLast()));
			assertNull(client.view().remote()); assertTrue(client.enabled());
			assertEquals(ClientMonitorState.Status.UNAUTHORIZED, client.view().previewStatus());
			assertEquals(List.of(metric), client.selected());
			client.resetConnection();
			assertFalse(client.accept(token, f.network.statuses.getLast()));
			assertFalse(client.accept(token, f.network.samples.getLast()));
		}
	}

	@Test void serverDispatchQueuesAuthenticatedWorkAndDropsObsoleteConnectionAndCorruptBodies() {
		try (var f = new Fixture()) {
			var tasks = new ArrayList<Runnable>();
			MonitorPacketDispatch.server(tasks::add, f.server, f.player, f.player.connection, watch(1),
				() -> assertTrue(f.service.subscribe(f.player, watch(1))));
			assertEquals(0, f.service.watchingConnections());
			assertEquals(1, tasks.size()); tasks.removeFirst().run();
			assertEquals(1, f.service.watchingConnections());

			MonitorPacketDispatch.server(tasks::add, f.server, f.player, f.player.connection, watch(2),
				() -> fail("old listener must not dispatch"));
			f.player.connection = allocate(Listener.class);
			tasks.removeFirst().run();
			MonitorPacketDispatch.server(tasks::add, f.server, f.player, f.player.connection, watch(2),
				() -> fail("replaced player must not dispatch"));
			f.server.players.entries.put(ID, player());
			tasks.removeFirst().run();
			MonitorPacketDispatch.server(tasks::add, f.server, f.player, f.player.connection,
				new InventoryMonitorSubscribeC2SPacket(), () -> fail("corrupt body must not queue"));
			assertTrue(tasks.isEmpty());
		}
	}

	@Test void clientDispatchQueuesAndRechecksTheOriginalConnectionAtExecution() {
		var tasks = new ArrayList<Runnable>();
		var connection = new ClientConnection();
		var current = new java.util.concurrent.atomic.AtomicReference<Connection>(connection);
		var receives = new java.util.concurrent.atomic.AtomicInteger();
		var packet = new InventoryMonitorStatusS2CPacket(1, 1, 1, 1, true, SampleCapability.AVAILABLE, true, false);
		MonitorPacketDispatch.client(tasks::add, connection, current::get, packet, receives::incrementAndGet);
		assertEquals(0, receives.get()); tasks.removeFirst().run(); assertEquals(1, receives.get());
		MonitorPacketDispatch.client(tasks::add, connection, current::get, packet, () -> fail("old connection"));
		current.set(new ClientConnection()); tasks.removeFirst().run();
		current.set(connection);
		MonitorPacketDispatch.client(tasks::add, connection, current::get, packet, () -> fail("disconnected"));
		connection.connected = false; tasks.removeFirst().run();
		MonitorPacketDispatch.client(tasks::add, null, current::get, packet, () -> fail("absent connection"));
		MonitorPacketDispatch.client(tasks::add, connection, current::get,
			new InventoryMonitorStatusS2CPacket(), () -> fail("corrupt status"));
		assertTrue(tasks.isEmpty()); assertEquals(1, receives.get());
	}

	private static InventoryMonitorSubscribeC2SPacket watch(long revision) {
		return InventoryMonitorSubscribeC2SPacket.watch(revision,
			List.of(Objects.requireNonNull(Metric.byId("server.preview.requests.received"))));
	}

	@NotNullByDefault
	private static final class Fixture implements AutoCloseable {
		final Server server = allocate(Server.class);
		final Player player = player();
		final Network network = new Network();
		final MonitorAccumulator collector = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), () -> 0);
		final ServerMonitorService service;
		int latestReads;
		boolean revokeOnRead;
		Fixture() {
			server.ownerThread = true;
			server.players = allocate(Players.class);
			server.players.entries = new HashMap<>(); server.players.entries.put(ID, player);
			collector.resetGeneration(1); collector.configure(true, false);
			service = new ServerMonitorService(server, network, () -> {
				latestReads++;
				if (revokeOnRead) player.permission = 0;
				return collector.latest();
			}, () -> new CollectionState(collector.serverGeneration(), collector.collectionGeneration(),
				collector.enabled(Lane.PREVIEW), collector.enabled(Lane.TRACKING)));
		}
		void complete(long tick) { collector.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 1); collector.completeTick(tick); }
		@Override public void close() { service.close(); }
	}

	@NotNullByDefault
	private static final class Network implements IPlatformNetworkService {
		final List<InventoryMonitorStatusS2CPacket> statuses = new ArrayList<>();
		final List<InventoryMonitorSampleS2CPacket> samples = new ArrayList<>();
		final List<ServerPlayer> recipients = new ArrayList<>();
		final List<ServerPlayer> capabilityPlayers = new ArrayList<>();
		TransportHandoff handoff = TransportHandoff.HANDED_OFF;
		RouteSupport sampleSupport = RouteSupport.AVAILABLE;
		@Nullable RuntimeException sendFailure;
		@Nullable RuntimeException capabilityFailure;
		@Override public void sendToServer(IPacket packet) { fail("server service never sends C2S"); }
		@Override public void sendToClient(IPacket packet, ServerPlayer player) { fail("no fallback to legacy void"); }
		@Override public TransportHandoff sendToClientObserved(IPacket packet, ServerPlayer player) {
			if (sendFailure != null) throw sendFailure;
			recipients.add(player);
			if (packet instanceof InventoryMonitorStatusS2CPacket status) statuses.add(status);
			else samples.add(assertInstanceOf(InventoryMonitorSampleS2CPacket.class, packet));
			return handoff;
		}
		@Override public RouteSupport clientboundSupport(ServerPlayer player, ResourceLocation route) {
			if (capabilityFailure != null) throw capabilityFailure;
			capabilityPlayers.add(player);
			if (route.equals(InventoryMonitorStatusS2CPacket.PACKET_ID)) return RouteSupport.AVAILABLE;
			assertEquals(InventoryMonitorSampleS2CPacket.PACKET_ID, route);
			return sampleSupport;
		}
	}

	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		private @Nullable Players players;
		private boolean ownerThread;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return ownerThread; }
		@Override public DedicatedPlayerList getPlayerList() { return Objects.requireNonNull(players); }
	}
	@NotNullByDefault
	private static final class Players extends DedicatedPlayerList {
		private Map<UUID, ServerPlayer> entries = new HashMap<>();
		private Players() { super(null, null, null); }
		@Override public @Nullable ServerPlayer getPlayer(UUID id) { return entries.get(id); }
	}
	@NotNullByDefault
	private static final class Player extends ServerPlayer {
		private int permission;
		private Player() { super(null, null, null, null); }
		@Override public UUID getUUID() { return ID; }
		@Override public boolean hasPermissions(int required) { return permission >= required; }
	}
	@NotNullByDefault
	private static final class Listener extends ServerGamePacketListenerImpl {
		private Listener() { super(null, null, null, null); }
		@Override public boolean isAcceptingMessages() { return true; }
	}
	@NotNullByDefault
	private static final class ClientConnection extends Connection {
		private boolean connected = true;
		private ClientConnection() { super(PacketFlow.CLIENTBOUND); }
		@Override public boolean isConnected() { return connected; }
	}
	private static Player player() {
		var player = allocate(Player.class); player.permission = 3;
		player.connection = allocate(Listener.class); return player;
	}
	private static <T> T allocate(Class<T> type) {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
