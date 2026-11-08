package nx.pingwheel.common.network;

import java.util.Optional;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

public record MarkerWinnerChangedS2CPacket(TargetKey targetKey, Optional<MarkerId> winnerId) implements IPacket {

	public static final ResourceLocation PACKET_ID = new ResourceLocation(S2C_NAMESPACE, "marker-winner-changed");

	public MarkerWinnerChangedS2CPacket() {
		this(null, null);
	}

	public MarkerWinnerChangedS2CPacket(FriendlyByteBuf buf) {
		this(
			MarkerPacketCodec.readTargetKey(buf),
			MarkerPacketCodec.readOptionalMarkerId(buf)
		);
	}

	public void write(FriendlyByteBuf buf) {
		MarkerPacketCodec.writeTargetKey(buf, targetKey);
		MarkerPacketCodec.writeOptionalMarkerId(buf, winnerId);
	}

	public boolean isCorrupt() {
		return targetKey == null || winnerId == null;
	}

	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static MarkerWinnerChangedS2CPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, MarkerWinnerChangedS2CPacket.class);
	}
}
