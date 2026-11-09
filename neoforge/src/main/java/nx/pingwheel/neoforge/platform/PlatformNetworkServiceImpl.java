package nx.pingwheel.neoforge.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.network.EncodedPresentationPayload;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.network.ServerConfigTransportDiagnostics;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.platform.RouteSupport;
import nx.pingwheel.common.platform.TransportHandoff;
import nx.pingwheel.common.presentation.minecraft.InventoryCreationReceiptTransport.EncodedTransport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static nx.pingwheel.common.CommonClient.Game;

@NotNullByDefault
public class PlatformNetworkServiceImpl implements IPlatformNetworkService, EncodedTransport {

	private final BiPredicate<ServerPlayer, CustomPacketPayload> canSend;
	private final BiConsumer<ServerPlayer, CustomPacketPayload> send;

	public PlatformNetworkServiceImpl() {
		this((player, payload) -> player.connection.hasChannel(payload), PacketDistributor::sendToPlayer);
	}

	PlatformNetworkServiceImpl(BiPredicate<ServerPlayer, CustomPacketPayload> canSend,
		BiConsumer<ServerPlayer, CustomPacketPayload> send) {
		this.canSend = canSend;
		this.send = send;
	}

	@Override
	public void sendToServer(IPacket packet) {
		var kind = ServerConfigTransportDiagnostics.kindOf(packet);
		var connection = Game.getConnection();

		if (connection == null) {
			ServerConfigTransportDiagnostics.decision(Diagnostics.client(), kind,
				ServerConfigTransportDiagnostics.Decision.CONNECTION_MISSING);
			return;
		}

		if (!connection.hasChannel(packet)) {
			ServerConfigTransportDiagnostics.decision(Diagnostics.client(), kind,
				ServerConfigTransportDiagnostics.Decision.ROUTE_UNAVAILABLE);
			return;
		}

		PacketDistributor.sendToServer(packet);
		ServerConfigTransportDiagnostics.decision(Diagnostics.client(), kind,
			ServerConfigTransportDiagnostics.Decision.SEND_DISPATCHED);
	}

	@Override
	public void sendToClient(IPacket packet, ServerPlayer player) {
		sendToClientObserved(packet, player);
	}

	@Override
	public TransportHandoff sendToClientObserved(IPacket packet, ServerPlayer player) {
		@Nullable ServerConfigTransportDiagnostics.Kind kind = ServerConfigTransportDiagnostics.kindOf(packet);
		if (!canSend.test(player, packet)) {
			ServerConfigTransportDiagnostics.decision(Diagnostics.server(), kind,
				ServerConfigTransportDiagnostics.Decision.ROUTE_UNAVAILABLE);
			return TransportHandoff.NOT_HANDED_OFF;
		}

		send.accept(player, packet);
		ServerConfigTransportDiagnostics.decision(Diagnostics.server(), kind,
			ServerConfigTransportDiagnostics.Decision.SEND_DISPATCHED);
		return TransportHandoff.HANDED_OFF;
	}

	@Override
	public RouteSupport serverboundSupport(ResourceLocation routeId) {
		@Nullable Minecraft client = Game;
		@Nullable ClientPacketListener connection = client == null ? null : client.getConnection();
		if (connection == null || !connection.isAcceptingMessages()) return RouteSupport.UNKNOWN;
		return routeSupport(routeId, true, connection::hasChannel);
	}

	@Override
	public RouteSupport clientboundSupport(ServerPlayer player, ResourceLocation routeId) {
		if (player.connection == null || !player.connection.isAcceptingMessages()) return RouteSupport.UNKNOWN;
		return routeSupport(routeId, true, player.connection::hasChannel);
	}

	static RouteSupport routeSupport(ResourceLocation routeId, boolean ready, Predicate<ResourceLocation> hasChannel) {
		// NeoForge finishes payload negotiation before entering the play listener.
		if (!ready) return RouteSupport.UNKNOWN;
		return hasChannel.test(routeId) ? RouteSupport.AVAILABLE : RouteSupport.UNAVAILABLE;
	}

	@Override
	public boolean sendEncodedCreated(PresentationS2CPacket packet, ServerPlayer recipient, FriendlyByteBuf encoded) {
		if (!canSend.test(recipient, packet)) return false;
		send.accept(recipient, EncodedPresentationPayload.copyCreated(packet, encoded));
		return true;
	}
}
