package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MarkerRemovalReason;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

public record MarkerRemovedS2CPacket(MarkerId markerId, MarkerRemovalReason reason) implements IPacket {

	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(S2C_NAMESPACE, "marker-removed");
	public static final Type<MarkerRemovedS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public MarkerRemovedS2CPacket() {
		this(null, null);
	}

	public MarkerRemovedS2CPacket(FriendlyByteBuf buf) {
		this(
			MarkerPacketCodec.readMarkerId(buf),
			MarkerPacketCodec.readEnum(buf, MarkerRemovalReason.class)
		);
	}

	public void write(FriendlyByteBuf buf) {
		MarkerPacketCodec.writeMarkerId(buf, markerId);
		MarkerPacketCodec.writeEnum(buf, reason);
	}

	public boolean isCorrupt() {
		return markerId == null || reason == null;
	}

	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static MarkerRemovedS2CPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, MarkerRemovedS2CPacket.class);
	}

	@Override
	public @NotNull Type<MarkerRemovedS2CPacket> type() {
		return PACKET_TYPE;
	}
}
