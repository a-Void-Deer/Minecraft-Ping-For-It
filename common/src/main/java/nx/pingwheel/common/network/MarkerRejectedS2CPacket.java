package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.MarkerRequestKind;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

public record MarkerRejectedS2CPacket(long requestId, MarkerRequestKind requestKind, MarkerRejectReason reason) implements IPacket {

	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(S2C_NAMESPACE, "marker-rejected");
	public static final Type<MarkerRejectedS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public MarkerRejectedS2CPacket() {
		this(-1L, null, null);
	}

	public MarkerRejectedS2CPacket(FriendlyByteBuf buf) {
		this(
			buf.readLong(),
			MarkerPacketCodec.readEnum(buf, MarkerRequestKind.class),
			MarkerPacketCodec.readEnum(buf, MarkerRejectReason.class)
		);
	}

	public void write(FriendlyByteBuf buf) {
		buf.writeLong(requestId);
		MarkerPacketCodec.writeEnum(buf, requestKind);
		MarkerPacketCodec.writeEnum(buf, reason);
	}

	public boolean isCorrupt() {
		return requestId < 0L || requestKind == null || reason == null;
	}

	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static MarkerRejectedS2CPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, MarkerRejectedS2CPacket.class);
	}

	@Override
	public @NotNull Type<MarkerRejectedS2CPacket> type() {
		return PACKET_TYPE;
	}
}
