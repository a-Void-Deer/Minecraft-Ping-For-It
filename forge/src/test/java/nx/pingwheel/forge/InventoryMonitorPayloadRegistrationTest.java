package nx.pingwheel.forge;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.ForgePayload;
import net.minecraftforge.network.HeadlessEventChannel;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket.SampleCapability;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.monitor.MonitorAccumulator;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorTuning;
import java.util.function.Function;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryMonitorPayloadRegistrationTest {
	private static final UUID ID = new UUID(308, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void registeredMonitorChannelStrictlyDecodesAndDispatchesOnlyTheCurrentAuthenticatedServerPlayer() {
		var channel = HeadlessEventChannel.wrap(ForgeMain.INVENTORY_MONITOR_SUBSCRIBE_CHANNEL_C2S);
		var server = allocate(Server.class); server.players = allocate(Players.class);
		var player = allocate(Player.class); player.owner = server;
		var connection = new ServerConnection();
		var listener = allocate(Listener.class); listener.transport = connection;
		connection.listener = listener;
		player.connection = listener; server.players.player = player;
		var calls = new AtomicInteger();
		ForgeMain.registerMonitorReceiver(channel, (actualServer, actualPlayer, packet) -> {
			assertSame(server, actualServer); assertSame(server.players.player, actualPlayer);
			assertEquals(InventoryMonitorSubscribeC2SPacket.stop(1), packet); calls.incrementAndGet();
		});
		var context = new HeldContext(connection, player);
		receive(channel, context, false);
		assertEquals(0, calls.get()); assertEquals(1, context.tasks.size());
		context.tasks.removeFirst().run(); assertEquals(1, calls.get()); assertTrue(context.getPacketHandled());
		receive(channel, context, false);
		var respawned = allocate(Player.class); respawned.owner = server; respawned.connection = listener;
		server.players.player = respawned;
		context.tasks.removeFirst().run(); assertEquals(2, calls.get(), "queued request resolves current same-listener player");
		receive(channel, context, false); context.tasks.removeFirst().run(); assertEquals(3, calls.get());
		server.players.player = player;

		receive(channel, context, false);
		player.connection = allocate(Listener.class);
		context.tasks.removeFirst().run(); assertEquals(3, calls.get(), "old listener task must be dropped");
		player.connection = listener;
		receive(channel, context, false);
		server.players.player = allocate(Player.class);
		context.tasks.removeFirst().run(); assertEquals(3, calls.get(), "same UUID with another listener is not the authenticated connection");
		server.players.player = player;
		receive(channel, context, true);
		assertTrue(context.tasks.isEmpty(), "registered readSafe must reject trailing data before owner callback");
		receive(channel, new HeldContext(connection, null), false);
		receive(channel, new HeldContext(new Connection(PacketFlow.CLIENTBOUND), player), false);
		channel.receive(new CustomPayloadEvent(channel.getName(), null, context, 0));
		assertTrue(context.tasks.isEmpty());
		assertEquals(3, calls.get());
	}

	@Test void actualProductionChannelsRemainOptionalAndKeepTheirVersionedRouteNames() {
		assertEquals(InventoryMonitorSubscribeC2SPacket.PACKET_ID, ForgeMain.INVENTORY_MONITOR_SUBSCRIBE_CHANNEL_C2S.getName());
		assertEquals(InventoryMonitorStatusS2CPacket.PACKET_ID, ForgeMain.INVENTORY_MONITOR_STATUS_CHANNEL_S2C.getName());
		assertEquals(InventoryMonitorSampleS2CPacket.PACKET_ID, ForgeMain.INVENTORY_MONITOR_SAMPLE_CHANNEL_S2C.getName());
		for (var channel : List.of(ForgeMain.INVENTORY_MONITOR_SUBSCRIBE_CHANNEL_C2S,
			ForgeMain.INVENTORY_MONITOR_STATUS_CHANNEL_S2C, ForgeMain.INVENTORY_MONITOR_SAMPLE_CHANNEL_S2C)) {
			assertNotNull(net.minecraftforge.network.NetworkRegistry.findTarget(channel.getName()));
			assertTrue(HeadlessEventChannel.wrap(channel).acceptsMissingPeer());
		}
	}

	@Test void registeredStatusAndSampleReadersFenceBothQueuedAndAlreadyObsoleteClientCallbacks() throws ReflectiveOperationException {
		var instance = Minecraft.class.getDeclaredField("instance"); instance.setAccessible(true);
		@Nullable Object previous = instance.get(null);
		var client = allocate(Minecraft.class);
		try {
			instance.set(null, client);
			var collector = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), () -> 0);
			collector.resetGeneration(1); collector.configure(true, false);
			clientRoute(HeadlessEventChannel.wrap(ForgeMain.INVENTORY_MONITOR_STATUS_CHANNEL_S2C),
				InventoryMonitorStatusS2CPacket::readSafe,
				new InventoryMonitorStatusS2CPacket(1, 1, 1, 1, true, SampleCapability.AVAILABLE, true, false), client);
			clientRoute(HeadlessEventChannel.wrap(ForgeMain.INVENTORY_MONITOR_SAMPLE_CHANNEL_S2C),
				InventoryMonitorSampleS2CPacket::readSafe,
				new InventoryMonitorSampleS2CPacket(1, 1, Objects.requireNonNull(collector.completeTick(0))), client);
		} finally { instance.set(null, previous); }
	}

	private static <T extends IPacket> void clientRoute(HeadlessEventChannel channel,
		Function<FriendlyByteBuf, T> reader, T packet, Minecraft client) {
		var connection = new ClientConnection();
		var listener = allocate(ClientListener.class); listener.transport = connection;
		var local = allocate(LocalPlayer.class); setField(LocalPlayer.class, local, "connection", listener); client.player = local;
		var calls = new AtomicInteger();
		ForgeClient.registerMonitorReceiver(channel, reader, (token, received) -> { assertEquals(packet, received); calls.incrementAndGet(); });
		var context = new HeldContext(connection, null);
		clientReceive(channel, context, packet, false);
		assertEquals(0, calls.get()); context.tasks.removeFirst().run(); assertEquals(1, calls.get());
		clientReceive(channel, context, packet, false);
		client.player = allocate(LocalPlayer.class);
		context.tasks.removeFirst().run(); assertEquals(1, calls.get());
		clientReceive(channel, context, packet, false);
		context.tasks.removeFirst().run(); assertEquals(1, calls.get(), "callback from old transport must not capture replacement");
		clientReceive(channel, context, packet, true); assertTrue(context.tasks.isEmpty());
	}

	private static void clientReceive(HeadlessEventChannel channel, HeldContext context, IPacket packet, boolean trailing) {
		var body = new FriendlyByteBuf(Unpooled.buffer());
		try {
			packet.write(body); if (trailing) body.writeByte(0);
			channel.receive(new CustomPayloadEvent(channel.getName(), ForgePayload.create(channel.getName(), body), context, 0));
			assertFalse(body.isReadable()); assertTrue(context.getPacketHandled());
		} finally { body.release(); }
	}

	private static void receive(HeadlessEventChannel channel, HeldContext context, boolean trailing) {
		var body = new FriendlyByteBuf(Unpooled.buffer());
		try {
			InventoryMonitorSubscribeC2SPacket.stop(1).write(body);
			if (trailing) body.writeByte(0);
			channel.receive(new CustomPayloadEvent(channel.getName(), ForgePayload.create(channel.getName(), body), context, 0));
			if (context.isServerSide() && context.getSender() != null) assertFalse(body.isReadable());
			assertTrue(context.getPacketHandled());
		} finally { body.release(); }
	}

	@NotNullByDefault
	private static final class HeldContext extends CustomPayloadEvent.Context {
		private final @Nullable ServerPlayer player;
		private final List<Runnable> tasks = new ArrayList<>();
		private HeldContext(Connection connection, @Nullable ServerPlayer player) { super(connection); this.player = player; }
		@Override public @Nullable ServerPlayer getSender() { return player; }
		@Override public CompletableFuture<Void> enqueueWork(Runnable action) { tasks.add(action); return CompletableFuture.completedFuture(null); }
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
		@Override public @Nullable ServerPlayer getPlayer(UUID id) { return id.equals(ID) ? player : null; }
	}
	@NotNullByDefault
	private static final class Player extends ServerPlayer {
		private @Nullable MinecraftServer owner;
		private Player() { super(null, null, null, null); }
		@Override public UUID getUUID() { return ID; }
		@Override public @Nullable MinecraftServer getServer() { return owner; }
	}
	@NotNullByDefault
	private static final class Listener extends ServerGamePacketListenerImpl {
		private @Nullable Connection transport;
		private Listener() { super(null, null, null, null); }
		@Override public boolean isAcceptingMessages() { return true; }
		@Override public Connection getConnection() { return Objects.requireNonNull(transport); }
	}
	@NotNullByDefault
	private static final class ClientListener extends ClientPacketListener {
		private @Nullable Connection transport;
		private ClientListener() { super(null, null, null); }
		@Override public Connection getConnection() { return Objects.requireNonNull(transport); }
	}
	@NotNullByDefault
	private static final class ClientConnection extends Connection {
		private ClientConnection() { super(PacketFlow.CLIENTBOUND); }
		@Override public boolean isConnected() { return true; }
	}
	@NotNullByDefault
	private static final class ServerConnection extends Connection {
		private @Nullable ServerGamePacketListenerImpl listener;
		private ServerConnection() { super(PacketFlow.SERVERBOUND); }
		@Override public @Nullable net.minecraft.network.PacketListener getPacketListener() { return listener; }
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
