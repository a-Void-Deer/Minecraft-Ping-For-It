package nx.pingwheel.common.monitor.integration;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import nx.pingwheel.common.network.IPacket;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Captures connection identity before queueing and rejects obsolete work on the owner thread. */
@NotNullByDefault
public final class MonitorPacketDispatch {
	private MonitorPacketDispatch() {}

	public static void server(Executor owner, MinecraftServer server, ServerPlayer player,
		ServerGamePacketListenerImpl listener, IPacket packet, Runnable receive) {
		server(owner, server, player, listener, packet, current -> receive.run());
	}

	/** Resolves a respawned player through the original listener, never UUID alone. */
	public static void server(Executor owner, MinecraftServer server, ServerPlayer player,
		ServerGamePacketListenerImpl listener, IPacket packet, Consumer<ServerPlayer> receive) {
		Objects.requireNonNull(owner);
		if (packet.isCorrupt() || player.connection != listener) return;
		var playerId = player.getUUID();
		owner.execute(() -> {
			if (!server.isSameThread() || !listener.isAcceptingMessages()) return;
			@Nullable ServerPlayer current = server.getPlayerList().getPlayer(playerId);
			if (current != null && playerId.equals(current.getUUID()) && current.connection == listener) receive.accept(current);
		});
	}

	public static void client(Executor owner, @Nullable Connection connection,
		Supplier<@Nullable Connection> current, IPacket packet, Runnable receive) {
		if (connection == null || packet.isCorrupt()) return;
		owner.execute(() -> {
			if (current.get() == connection && connection.isConnected()) receive.run();
		});
	}
}
