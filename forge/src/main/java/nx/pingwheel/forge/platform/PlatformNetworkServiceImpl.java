package nx.pingwheel.forge.platform;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.EventNetworkChannel;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.NetworkProtocol;
import nx.pingwheel.common.network.EncodedPresentationPayload;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.presentation.minecraft.InventoryCreationReceiptTransport.EncodedTransport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import static nx.pingwheel.common.CommonClient.Game;

@NotNullByDefault
public class PlatformNetworkServiceImpl implements IPlatformNetworkService, EncodedTransport {

	public static final Map<ResourceLocation, EventNetworkChannel> CHANNEL_MAP = new HashMap<>();
	private final BiPredicate<EventNetworkChannel, ServerPlayer> canSend;
	private final BiConsumer<ServerPlayer, Packet<?>> send;

	public PlatformNetworkServiceImpl() {
		this((channel, player) -> channel.isRemotePresent(player.connection.getConnection()),
			(player, packet) -> PacketDistributor.PLAYER.with(player).send(packet));
	}

	PlatformNetworkServiceImpl(BiPredicate<EventNetworkChannel, ServerPlayer> canSend,
		BiConsumer<ServerPlayer, Packet<?>> send) {
		this.canSend = canSend;
		this.send = send;
	}

	@Override
	public void sendToServer(IPacket packet) {
		@Nullable ClientPacketListener connection = Game.getConnection();

		if (connection == null) {
			return;
		}

		@Nullable EventNetworkChannel chan = CHANNEL_MAP.get(packet.getId());

		if (chan == null || !chan.isRemotePresent(connection.getConnection())) {
			return;
		}

		var buf = new FriendlyByteBuf(Unpooled.buffer());
		packet.write(buf);

		chan.send(buf, PacketDistributor.SERVER.noArg());
	}

	@Override
	public void sendToClient(IPacket packet, ServerPlayer player) {
		@Nullable EventNetworkChannel chan = CHANNEL_MAP.get(packet.getId());

		if (chan == null || !chan.isRemotePresent(player.connection.getConnection())) {
			return;
		}

		var buf = new FriendlyByteBuf(Unpooled.buffer());
		packet.write(buf);

		chan.send(buf, PacketDistributor.PLAYER.with(player));
	}

	@Override
	public boolean sendEncodedCreated(PresentationS2CPacket packet, ServerPlayer recipient, FriendlyByteBuf encoded) {
		@Nullable EventNetworkChannel channel = CHANNEL_MAP.get(PresentationS2CPacket.PACKET_ID);
		if (channel == null || !canSend.test(channel, recipient)) return false;
		EncodedPresentationPayload payload = EncodedPresentationPayload.copyCreated(packet, encoded);
		// Channel.send uses this same Forge protocol builder. Capture heap bytes, not a borrowed
		// FriendlyByteBuf: Forge invokes the supplied encoder later on the network thread.
		send.accept(recipient, NetworkProtocol.PLAY.buildPacket(PacketFlow.CLIENTBOUND, channel.getName(), payload::writeTo));
		return true;
	}
}
