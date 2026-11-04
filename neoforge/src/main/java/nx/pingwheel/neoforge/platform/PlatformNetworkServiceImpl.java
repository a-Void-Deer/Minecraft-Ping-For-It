package nx.pingwheel.neoforge.platform;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import nx.pingwheel.common.network.EncodedPresentationPayload;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.presentation.minecraft.InventoryCreationReceiptTransport.EncodedTransport;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

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
		var connection = Game.getConnection();

		if (connection == null || !connection.hasChannel(packet)) {
			return;
		}

		PacketDistributor.sendToServer(packet);
	}

	@Override
	public void sendToClient(IPacket packet, ServerPlayer player) {
		if (!canSend.test(player, packet)) {
			return;
		}

		send.accept(player, packet);
	}

	@Override
	public boolean sendEncodedCreated(PresentationS2CPacket packet, ServerPlayer recipient, FriendlyByteBuf encoded) {
		if (!canSend.test(recipient, packet)) return false;
		send.accept(recipient, EncodedPresentationPayload.copyCreated(packet, encoded));
		return true;
	}
}
