package nx.pingwheel.neoforge;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import nx.pingwheel.common.monitor.MonitorAccumulator;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket.SampleCapability;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryMonitorDispatchTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void actualMainThreadWrappedHandlersDropQueuedAndAlreadyObsoleteClientConnections() throws Exception {
		PresentationPayloadRegistrationTest.registeredCodec();
		Map<ResourceLocation, PayloadRegistration<?>> registrations = registrations();
		var ids = List.of(InventoryMonitorSubscribeC2SPacket.PACKET_ID, InventoryMonitorStatusS2CPacket.PACKET_ID,
			InventoryMonitorSampleS2CPacket.PACKET_ID);
		var old = new java.util.HashMap<ResourceLocation, PayloadRegistration<?>>();
		for (var id : ids) { var registration = registrations.remove(id); if (registration != null) old.put(id, registration); }
		var instance = Minecraft.class.getDeclaredField("instance"); instance.setAccessible(true);
		@Nullable Object previous = instance.get(null);
		var client = allocate(Minecraft.class); var calls = new AtomicInteger();
		try {
			instance.set(null, client);
			NeoMain.registerMonitorPackets(new PayloadRegistrar("pingforit").optional(),
				(server, player, packet) -> fail("client test must not enter subscribe"),
				(token, packet) -> calls.incrementAndGet(), (token, packet) -> calls.incrementAndGet());
			var collector = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), () -> 0);
			collector.resetGeneration(1); collector.configure(true, false);
			check(Objects.requireNonNull(registrations.get(InventoryMonitorStatusS2CPacket.PACKET_ID)),
				new InventoryMonitorStatusS2CPacket(1, 1, 1, 1, true, SampleCapability.AVAILABLE, true, false), client, calls);
			check(Objects.requireNonNull(registrations.get(InventoryMonitorSampleS2CPacket.PACKET_ID)),
				new InventoryMonitorSampleS2CPacket(1, 1, Objects.requireNonNull(collector.completeTick(0))), client, calls);
			for (var id : ids) {
				var registration = Objects.requireNonNull(registrations.get(id));
				assertTrue(registration.optional()); assertEquals("pingforit", registration.version());
			}
			serverRoute();
		} finally {
			instance.set(null, previous);
			for (var id : ids) registrations.remove(id);
			registrations.putAll(old);
		}
	}

	private static void serverRoute() {
		var server = allocate(Server.class); server.players = allocate(Players.class);
		var player = allocate(ServerUser.class); player.owner = server;
		var listener = allocate(ServerListener.class); player.connection = listener; server.players.player = player;
		var tasks = new ArrayList<Runnable>();
		IPayloadContext context = (IPayloadContext) Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
			new Class<?>[] {IPayloadContext.class}, (proxy, method, arguments) -> switch (method.getName()) {
				case "player" -> player;
				case "listener" -> listener;
				case "enqueueWork" -> { tasks.add((Runnable) Objects.requireNonNull(arguments)[0]); yield CompletableFuture.completedFuture(null); }
				case "flow" -> PacketFlow.SERVERBOUND;
				default -> throw new AssertionError("unexpected server context access " + method.getName());
			});
		// The installed test subscriber rejects client delivery; invoke the actual
		// production receiver using a callback that records the authenticated player.
		var calls = new AtomicInteger();
		Map<ResourceLocation, PayloadRegistration<?>> registry;
		try { registry = registrations(); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		var saved = registry.remove(InventoryMonitorSubscribeC2SPacket.PACKET_ID);
		var savedStatus = registry.remove(InventoryMonitorStatusS2CPacket.PACKET_ID);
		var savedSample = registry.remove(InventoryMonitorSampleS2CPacket.PACKET_ID);
		try {
			NeoMain.registerMonitorPackets(new PayloadRegistrar("pingforit").optional(), (actual, sender, packet) -> {
				assertSame(server, actual); assertSame(server.players.player, sender); calls.incrementAndGet();
			}, (token, packet) -> fail("not client dispatch"), (token, packet) -> fail("not client dispatch"));
			var current = Objects.requireNonNull(registry.get(InventoryMonitorSubscribeC2SPacket.PACKET_ID));
			handle(current, InventoryMonitorSubscribeC2SPacket.stop(1), context);
			assertEquals(0, calls.get()); drain(tasks); assertEquals(1, calls.get());
			handle(current, InventoryMonitorSubscribeC2SPacket.stop(1), context); tasks.removeFirst().run();
			var respawned = allocate(ServerUser.class); respawned.owner = server; respawned.connection = listener;
			server.players.player = respawned; drain(tasks); assertEquals(2, calls.get(), "same-listener respawn survives owner enqueue");
			handle(current, InventoryMonitorSubscribeC2SPacket.stop(1), context); drain(tasks); assertEquals(3, calls.get());
			server.players.player = player;
			handle(current, InventoryMonitorSubscribeC2SPacket.stop(1), context); tasks.removeFirst().run();
			player.connection = allocate(ServerListener.class); drain(tasks); assertEquals(3, calls.get());
			player.connection = listener;
			handle(current, InventoryMonitorSubscribeC2SPacket.stop(1), context); tasks.removeFirst().run();
			server.players.player = allocate(ServerUser.class); drain(tasks); assertEquals(3, calls.get());
			handle(current, new InventoryMonitorSubscribeC2SPacket(), context); drain(tasks); assertEquals(3, calls.get());
		} finally {
			registry.put(InventoryMonitorSubscribeC2SPacket.PACKET_ID, Objects.requireNonNull(saved));
			registry.put(InventoryMonitorStatusS2CPacket.PACKET_ID, Objects.requireNonNull(savedStatus));
			registry.put(InventoryMonitorSampleS2CPacket.PACKET_ID, Objects.requireNonNull(savedSample));
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<ResourceLocation, PayloadRegistration<?>> registrations() throws ReflectiveOperationException {
		var field = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS"); field.setAccessible(true);
		return Objects.requireNonNull(((Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>) field.get(null))
			.get(ConnectionProtocol.PLAY));
	}

	private static <T extends CustomPacketPayload> void check(PayloadRegistration<T> registration, IPacket packet,
		Minecraft client, AtomicInteger calls) {
		var tasks = new ArrayList<Runnable>(); var connection = new ClientConnection();
		var listener = allocate(Listener.class); listener.transport = connection;
		var local = allocate(LocalPlayer.class); setField(LocalPlayer.class, local, "connection", listener); client.player = local;
		IPayloadContext context = (IPayloadContext) Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
			new Class<?>[] {IPayloadContext.class}, (proxy, method, arguments) -> switch (method.getName()) {
				case "connection" -> connection;
				case "enqueueWork" -> { tasks.add((Runnable) Objects.requireNonNull(arguments)[0]); yield CompletableFuture.completedFuture(null); }
				case "flow" -> PacketFlow.CLIENTBOUND;
				default -> throw new AssertionError("unexpected context access " + method.getName());
			});
		int before = calls.get();
		handle(registration, packet, context); assertEquals(before, calls.get()); drain(tasks); assertEquals(before + 1, calls.get());
		handle(registration, packet, context); tasks.removeFirst().run();
		client.player = allocate(LocalPlayer.class); drain(tasks); assertEquals(before + 1, calls.get());
		handle(registration, packet, context); drain(tasks); assertEquals(before + 1, calls.get(), "old wrapper may start after reconnect");
	}

	@SuppressWarnings("unchecked")
	private static <T extends CustomPacketPayload> void handle(PayloadRegistration<T> registration, IPacket packet, IPayloadContext context) {
		registration.handler().handle((T) packet, context);
	}
	private static void drain(List<Runnable> tasks) { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
	@NotNullByDefault
	private static final class Listener extends ClientPacketListener {
		private @Nullable Connection transport;
		private Listener() { super(null, null, null); }
		@Override public Connection getConnection() { return Objects.requireNonNull(transport); }
	}
	@NotNullByDefault
	private static final class ClientConnection extends Connection {
		private ClientConnection() { super(PacketFlow.CLIENTBOUND); }
		@Override public boolean isConnected() { return true; }
	}
	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		private @Nullable Players players;
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
		@Override public DedicatedPlayerList getPlayerList() { return Objects.requireNonNull(players); }
	}
	@NotNullByDefault
	private static final class Players extends DedicatedPlayerList {
		private @Nullable ServerPlayer player;
		private Players() { super(null, null, null); }
		@Override public @Nullable ServerPlayer getPlayer(UUID id) { return player; }
	}
	@NotNullByDefault
	private static final class ServerUser extends ServerPlayer {
		private @Nullable MinecraftServer owner;
		private ServerUser() { super(null, null, null, null); }
		@Override public UUID getUUID() { return new UUID(310, 1); }
		@Override public @Nullable MinecraftServer getServer() { return owner; }
	}
	@NotNullByDefault
	private static final class ServerListener extends ServerGamePacketListenerImpl implements ICommonPacketListener {
		private ServerListener() { super(null, null, null, null); }
		@Override public boolean isAcceptingMessages() { return true; }
		@Override public Connection getConnection() { throw new AssertionError("not transport query"); }
		@Override public ReentrantBlockableEventLoop<?> getMainThreadEventLoop() { throw new AssertionError("not loop query"); }
		@Override public ConnectionType getConnectionType() { return ConnectionType.NEOFORGE; }
		@Override public void send(CustomPacketPayload payload) { fail("not send"); }
	}
	private static void setField(Class<?> owner, Object object, String name, Object value) {
		try { var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static <T> T allocate(Class<T> type) {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
