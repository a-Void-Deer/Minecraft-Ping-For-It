package nx.pingwheel.common.network;

import java.util.Optional;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;

public record MarkerWinnerChangedS2CPacket(TargetKey targetKey, Optional<MarkerId> winnerId) implements IPacket {

	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath("ping-wheel-s2c", "marker-winner-changed");
	public static final Type<MarkerWinnerChangedS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

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

	@Override
	public @NotNull Type<MarkerWinnerChangedS2CPacket> type() {
		return PACKET_TYPE;
	}
}
