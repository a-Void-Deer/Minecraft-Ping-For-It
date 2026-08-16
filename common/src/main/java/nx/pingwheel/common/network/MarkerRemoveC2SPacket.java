package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import nx.pingwheel.common.domain.MarkerId;

import static nx.pingwheel.common.Global.C2S_NAMESPACE;

public record MarkerRemoveC2SPacket(MarkerId markerId) implements IPacket {

	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(C2S_NAMESPACE, "marker-remove");
	public static final Type<MarkerRemoveC2SPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public MarkerRemoveC2SPacket() {
		this((MarkerId)null);
	}

	public MarkerRemoveC2SPacket(FriendlyByteBuf buf) {
		this(MarkerPacketCodec.readMarkerId(buf));
	}

	public void write(FriendlyByteBuf buf) {
		MarkerPacketCodec.writeMarkerId(buf, markerId);
	}

	public boolean isCorrupt() {
		return markerId == null;
	}

	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static MarkerRemoveC2SPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, MarkerRemoveC2SPacket.class);
	}

	@Override
	public @NotNull Type<MarkerRemoveC2SPacket> type() {
		return PACKET_TYPE;
	}
}
