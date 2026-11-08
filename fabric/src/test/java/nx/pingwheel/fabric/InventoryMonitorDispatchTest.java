package nx.pingwheel.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.impl.networking.NetworkHandlerExtensions;
import net.fabricmc.fabric.impl.networking.AbstractNetworkAddon;
import net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon;
import net.fabricmc.fabric.impl.networking.client.ClientNetworkingImpl;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket.SampleCapability;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.monitor.MonitorAccumulator;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorTuning;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryMonitorDispatchTest {
	private static final UUID ID = new UUID(309, 1);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void actualRegisteredSubscribeHandlerPreservesRespawnButDropsAReplacementConnection() {
		PresentationPayloadRegistrationTest.registeredCodec();
		var server = allocate(Server.class); server.tasks = new ArrayList<>(); server.players = allocate(Players.class);
		var player = allocate(Player.class); player.connection = allocate(Listener.class); server.players.player = player;
		var calls = new AtomicInteger();
		ServerPlayNetworking.unregisterGlobalReceiver(InventoryMonitorSubscribeC2SPacket.PACKET_ID);
		try {
			FabricMain.registerMonitorReceiver((currentServer, currentPlayer, packet) -> {
				assertSame(server, currentServer); assertSame(server.players.player, currentPlayer);
				assertEquals(InventoryMonitorSubscribeC2SPacket.stop(1), packet); calls.incrementAndGet();
			});
			@SuppressWarnings("unchecked")
			var handler = (ServerPlayNetworking.PlayPayloadHandler<InventoryMonitorSubscribeC2SPacket>)
				Objects.requireNonNull(ServerNetworkingImpl.PLAY.getHandler(InventoryMonitorSubscribeC2SPacket.PACKET_ID));
			var context = new Context(server, player);
			handler.receive(InventoryMonitorSubscribeC2SPacket.stop(1), context);
			assertEquals(0, calls.get()); assertEquals(1, server.tasks.size());
			server.tasks.removeFirst().run(); assertEquals(1, calls.get());
			handler.receive(InventoryMonitorSubscribeC2SPacket.stop(1), context);
			var respawned = allocate(Player.class); respawned.connection = player.connection; server.players.player = respawned;
			server.tasks.removeFirst().run(); assertEquals(2, calls.get(), "queued request must resolve the respawned player on the same listener");
			handler.receive(InventoryMonitorSubscribeC2SPacket.stop(1), context);
			server.tasks.removeFirst().run(); assertEquals(3, calls.get(), "old entity context remains bound to the unchanged connection");
			server.players.player = player;
			handler.receive(InventoryMonitorSubscribeC2SPacket.stop(1), context);
			player.connection = allocate(Listener.class); server.tasks.removeFirst().run(); assertEquals(3, calls.get());
			handler.receive(InventoryMonitorSubscribeC2SPacket.stop(1), context);
			server.players.player = allocate(Player.class); server.tasks.removeFirst().run(); assertEquals(3, calls.get());
			handler.receive(new InventoryMonitorSubscribeC2SPacket(), context);
			assertTrue(server.tasks.isEmpty());
		} finally { ServerPlayNetworking.unregisterGlobalReceiver(InventoryMonitorSubscribeC2SPacket.PACKET_ID); }
	}

	@Test void actualClientHandlersFenceOriginatingAddonBeforeCapturingDynamicContextAndQueuedConnection() throws Exception {
		PresentationPayloadRegistrationTest.registeredCodec();
		var instance = Minecraft.class.getDeclaredField("instance"); instance.setAccessible(true);
		@Nullable Object previous = instance.get(null);
		var client = allocate(Client.class); client.tasks = new ArrayList<>();
		var calls = new AtomicInteger();
		ClientPlayNetworking.unregisterGlobalReceiver(InventoryMonitorStatusS2CPacket.PACKET_ID);
		ClientPlayNetworking.unregisterGlobalReceiver(InventoryMonitorSampleS2CPacket.PACKET_ID);
		try {
			instance.set(null, client);
			FabricClient.registerMonitorReceivers((token, packet) -> calls.incrementAndGet(), (token, packet) -> calls.incrementAndGet());
			var collector = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), () -> 0);
			collector.resetGeneration(1); collector.configure(true, false);
			clientRoute(new InventoryMonitorStatusS2CPacket(1, 1, 1, 1, true, SampleCapability.AVAILABLE, true, false), client, calls);
			clientRoute(new InventoryMonitorSampleS2CPacket(1, 1, Objects.requireNonNull(collector.completeTick(0))), client, calls);
		} finally {
			ClientPlayNetworking.unregisterGlobalReceiver(InventoryMonitorStatusS2CPacket.PACKET_ID);
			ClientPlayNetworking.unregisterGlobalReceiver(InventoryMonitorSampleS2CPacket.PACKET_ID);
			instance.set(null, previous);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends IPacket> void clientRoute(T packet, Client client, AtomicInteger calls) throws ReflectiveOperationException {
		var connection = new ClientConnection();
		var listener = allocate(ClientListener.class); listener.transport = connection; listener.addon = allocate(ClientPlayNetworkAddon.class);
		var local = allocate(LocalPlayer.class); setField(LocalPlayer.class, local, "connection", listener); client.player = local;
		var context = new ClientContext(client, listener.addon);
		var handler = (ClientPlayNetworking.PlayPayloadHandler<T>) Objects.requireNonNull(ClientNetworkingImpl.PLAY.getHandler(packet.getId()));
		int before = calls.get(); handler.receive(packet, context);
		assertEquals(before, calls.get()); client.tasks.removeFirst().run(); assertEquals(before + 1, calls.get());
		handler.receive(packet, context);
		var replacement = allocate(ClientListener.class); replacement.transport = new ClientConnection(); replacement.addon = allocate(ClientPlayNetworkAddon.class);
		var replacementPlayer = allocate(LocalPlayer.class); setField(LocalPlayer.class, replacementPlayer, "connection", replacement); client.player = replacementPlayer;
		client.tasks.removeFirst().run(); assertEquals(before + 1, calls.get());
		handler.receive(packet, context); assertTrue(client.tasks.isEmpty(), "old context addon is not the new current sender");
	}

	@NotNullByDefault
	private record ClientContext(Minecraft client, PacketSender responseSender) implements ClientPlayNetworking.Context {
		@Override public LocalPlayer player() { return Objects.requireNonNull(client.player); }
	}

	@NotNullByDefault
	private record Context(MinecraftServer server, ServerPlayer player) implements ServerPlayNetworking.Context {
		@Override public PacketSender responseSender() { return SENDER; }
	}
	private static final PacketSender SENDER = new PacketSender() {
		@Override public Packet<?> createPacket(CustomPacketPayload payload) { throw new AssertionError("not a send test"); }
		@Override public void sendPacket(Packet<?> packet, @Nullable PacketSendListener callback) { fail("not a send test"); }
		@Override public void disconnect(Component reason) { fail("not a disconnect test"); }
	};
	@NotNullByDefault
	private static final class Server extends DedicatedServer {
		private @Nullable Players players;
		private List<Runnable> tasks = new ArrayList<>();
		private Server() { super(null, null, null, null, null, null, null, null); }
		@Override public boolean isSameThread() { return true; }
		@Override public void execute(Runnable action) { tasks.add(action); }
		@Override public DedicatedPlayerList getPlayerList() { return Objects.requireNonNull(players); }
	}
	@NotNullByDefault
	private static final class Players extends DedicatedPlayerList {
		private @Nullable ServerPlayer player;
		private Players() { super(null, null, null); }
		@Override public @Nullable ServerPlayer getPlayer(UUID id) { return ID.equals(id) ? player : null; }
	}
	@NotNullByDefault
	private static final class Player extends ServerPlayer {
		private Player() { super(null, null, null, null); }
		@Override public UUID getUUID() { return ID; }
	}
	@NotNullByDefault
	private static final class Listener extends ServerGamePacketListenerImpl {
		private Listener() { super(null, null, null, null); }
		@Override public boolean isAcceptingMessages() { return true; }
	}
	@NotNullByDefault
	private static final class Client extends Minecraft {
		private List<Runnable> tasks = new ArrayList<>();
		private Client() { super(null); }
		@Override public void execute(Runnable action) { tasks.add(action); }
	}
	@NotNullByDefault
	private static final class ClientListener extends ClientPacketListener implements NetworkHandlerExtensions {
		private @Nullable Connection transport;
		private @Nullable ClientPlayNetworkAddon addon;
		private ClientListener() { super(null, null, null); }
		@Override public Connection getConnection() { return Objects.requireNonNull(transport); }
		@Override public boolean isAcceptingMessages() { return true; }
		@Override public AbstractNetworkAddon<?> getAddon() { return Objects.requireNonNull(addon); }
	}
	@NotNullByDefault
	private static final class ClientConnection extends Connection {
		private ClientConnection() { super(PacketFlow.CLIENTBOUND); }
		@Override public boolean isConnected() { return true; }
	}
	private static void setField(Class<?> owner, Object object, String name, Object value) throws ReflectiveOperationException {
		var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
	}
	private static <T> T allocate(Class<T> type) {
		try {
			Class<?> unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
