package nx.pingwheel.common.monitor.integration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.monitor.server.ServerMonitorCoordinator;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket.SampleCapability;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.platform.RouteSupport;
import nx.pingwheel.common.platform.TransportHandoff;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Server-thread telemetry ports. The inventory lifetime owns and completes the sole collector. */
@NotNullByDefault
public final class ServerMonitorService implements AutoCloseable {
	private static final DiagnosticChannel TRANSPORT_FAILURES =
		DiagnosticChannel.interval("inventory-monitor/transport", Duration.ofSeconds(10));
	private final MinecraftServer server;
	private final IPlatformNetworkService network;
	private final ServerMonitorCoordinator coordinator;
	private final Map<UUID, Binding> bindings = new HashMap<>();
	private boolean closed;

	public ServerMonitorService(MinecraftServer server, IPlatformNetworkService network,
		ServerMonitorCoordinator.LatestSample latest, ServerMonitorCoordinator.EnabledState enabled) {
		this.server = Objects.requireNonNull(server);
		this.network = Objects.requireNonNull(network);
		coordinator = new ServerMonitorCoordinator(this::authorized, new ServerMonitorCoordinator.Capabilities() {
			@Override public boolean statusAvailable(UUID key) {
				return support(key, InventoryMonitorStatusS2CPacket.PACKET_ID) == RouteSupport.AVAILABLE;
			}
			@Override public SampleCapability sampleCapability(UUID key) {
				return switch (support(key, InventoryMonitorSampleS2CPacket.PACKET_ID)) {
					case UNKNOWN -> SampleCapability.UNKNOWN;
					case AVAILABLE -> SampleCapability.AVAILABLE;
					case UNAVAILABLE -> SampleCapability.UNAVAILABLE;
				};
			}
		}, new ServerMonitorCoordinator.Sender() {
			@Override public boolean sendStatus(UUID key, InventoryMonitorStatusS2CPacket packet) {
				return send(key, packet, false);
			}
			@Override public boolean sendSample(UUID key, InventoryMonitorSampleS2CPacket packet) {
				return send(key, packet, true);
			}
		}, latest, enabled);
		coordinator.reset(enabled.current().serverGeneration());
	}

	/** The loader supplies the authenticated player, never an identity from the packet body. */
	public boolean subscribe(ServerPlayer player, InventoryMonitorSubscribeC2SPacket packet) {
		if (closed || !server.isSameThread() || packet.isCorrupt() || player.connection == null) return false;
		UUID key = player.getUUID();
		@Nullable ServerPlayer current = current(key, player.connection);
		if (current == null) return false;
		@Nullable Binding previous = bindings.get(key);
		if (previous != null && !previous.matches(current)) {
			coordinator.disconnect(key);
			bindings.remove(key);
		}
		Binding binding = new Binding(current.connection);
		bindings.put(key, binding);
		if (coordinator.subscribe(key, packet)) return true;
		if (previous == null || !previous.matches(current)) bindings.remove(key);
		return false;
	}

	/** Called only after the collector's configuration and completeTick for this server tick. */
	public void tick(long tick) {
		if (closed || !server.isSameThread()) return;
		if (bindings.isEmpty()) return;
		bindings.entrySet().removeIf(entry -> {
			if (current(entry.getKey()) != null) return false;
			coordinator.disconnect(entry.getKey());
			return true;
		});
		coordinator.tick(tick);
	}

	/** A delayed old-connection logout must not clear a replacement connection's watch. */
	public void disconnect(ServerPlayer player) {
		if (closed || !server.isSameThread()) return;
		@Nullable Binding binding = bindings.get(player.getUUID());
		if (binding == null || !binding.matches(player)) return;
		bindings.remove(player.getUUID());
		coordinator.disconnect(player.getUUID());
	}

	public int watchingConnections() { return coordinator.watchingConnections(); }
	public int retainedConnections() { return coordinator.retainedConnections(); }
	@Override public void close() {
		closed = true;
		bindings.clear();
		coordinator.close();
	}

	private boolean authorized(UUID key) {
		@Nullable ServerPlayer player = current(key);
		return player != null && player.hasPermissions(3);
	}

	private @Nullable ServerPlayer current(UUID key) {
		if (closed || !server.isSameThread()) return null;
		@Nullable Binding binding = bindings.get(key);
		return binding == null ? null : current(key, binding.connection());
	}

	/** Respawn replaces the entity while retaining its authenticated play listener. */
	private @Nullable ServerPlayer current(UUID key, ServerGamePacketListenerImpl connection) {
		@Nullable ServerPlayer player = server.getPlayerList().getPlayer(key);
		return player != null && key.equals(player.getUUID()) && player.connection == connection
			&& connection.isAcceptingMessages() ? player : null;
	}

	private RouteSupport support(UUID key, net.minecraft.resources.ResourceLocation route) {
		@Nullable ServerPlayer player = current(key);
		if (player == null) return RouteSupport.UNKNOWN;
		try { return network.clientboundSupport(player, route); }
		catch (RuntimeException | LinkageError failure) {
			transportFailure("capability-unobserved", failure);
			return RouteSupport.UNKNOWN;
		}
	}

	private boolean send(UUID key, IPacket packet, boolean numeric) {
		@Nullable ServerPlayer player = current(key);
		if (player == null || (numeric && !player.hasPermissions(3))) return false;
		try { return network.sendToClientObserved(packet, player) == TransportHandoff.HANDED_OFF; }
		catch (RuntimeException | LinkageError failure) {
			transportFailure("handoff-unobserved", failure);
			return false;
		}
	}

	private static void transportFailure(String handling, Throwable failure) {
		Diagnostics.server().atDebug().rateLimited(TRANSPORT_FAILURES)
			.debug("inventory-monitor/transport", handling, failure);
	}

	@NotNullByDefault
	private record Binding(ServerGamePacketListenerImpl connection) {
		boolean matches(ServerPlayer current) { return current.connection == connection; }
	}
}
