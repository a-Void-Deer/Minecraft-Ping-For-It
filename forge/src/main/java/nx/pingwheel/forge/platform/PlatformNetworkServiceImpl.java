package nx.pingwheel.forge.platform;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.PacketDistributor;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;

import java.util.HashMap;
import java.util.Map;

import static nx.pingwheel.common.CommonClient.Game;

public class PlatformNetworkServiceImpl implements IPlatformNetworkService {

	public static final Map<ResourceLocation, Channel<FriendlyByteBuf>> CHANNEL_MAP = new HashMap<>();

	@Override
	public void sendToServer(IPacket packet) {
		var channel = CHANNEL_MAP.get(packet.getId());

		if (Game.getConnection() == null || channel == null) {
			return;
		}

		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		packet.write(buffer);
		channel.send(buffer, PacketDistributor.SERVER.noArg());
	}

	@Override
	public void sendToClient(IPacket packet, ServerPlayer player) {
		var channel = CHANNEL_MAP.get(packet.getId());

		if (channel == null) {
			return;
		}

		var buffer = new FriendlyByteBuf(Unpooled.buffer());
		packet.write(buffer);
		channel.send(buffer, PacketDistributor.PLAYER.with(player));
	}
}
