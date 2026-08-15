package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import nx.pingwheel.common.domain.Target;

public record MarkerCreateC2SPacket(long requestId, Target target, String pingTypeId) implements IPacket {

	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath("ping-wheel-c2s", "marker-create");
	public static final Type<MarkerCreateC2SPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public MarkerCreateC2SPacket() {
		this(-1L, null, null);
	}

	public MarkerCreateC2SPacket(FriendlyByteBuf buf) {
		this(
			buf.readLong(),
			MarkerPacketCodec.readTarget(buf),
			MarkerPacketCodec.readIdString(buf)
		);
	}

	public void write(FriendlyByteBuf buf) {
		buf.writeLong(requestId);
		MarkerPacketCodec.writeTarget(buf, target);
		MarkerPacketCodec.writeIdString(buf, pingTypeId);
	}

	public boolean isCorrupt() {
		return requestId < 0L || target == null || pingTypeId == null || pingTypeId.isBlank();
	}

	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static MarkerCreateC2SPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, MarkerCreateC2SPacket.class);
	}

	@Override
	public @NotNull Type<MarkerCreateC2SPacket> type() {
		return PACKET_TYPE;
	}
}
