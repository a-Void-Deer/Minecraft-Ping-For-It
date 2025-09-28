package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public interface IPacket extends CustomPacketPayload {
	void write(FriendlyByteBuf buf);
	boolean isCorrupt();
	ResourceLocation getId();
}
