package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.name.TargetNameJson;

public record MarkerCreatedS2CPacket(MarkerSnapshot snapshot, TargetNameJson targetName) implements IPacket {

	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath("ping-wheel-s2c", "marker-created");
	public static final Type<MarkerCreatedS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public MarkerCreatedS2CPacket() {
		this(null, null);
	}

	public MarkerCreatedS2CPacket(FriendlyByteBuf buf) {
		this(MarkerPacketCodec.readMarkerSnapshot(buf), MarkerPacketCodec.readTargetNameJson(buf));
	}

	public void write(FriendlyByteBuf buf) {
		MarkerPacketCodec.writeMarkerSnapshot(buf, snapshot);
		MarkerPacketCodec.writeTargetNameJson(buf, targetName);
	}

	public boolean isCorrupt() {
		return snapshot == null || targetName == null;
	}

	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static MarkerCreatedS2CPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, MarkerCreatedS2CPacket.class);
	}

	@Override
	public @NotNull Type<MarkerCreatedS2CPacket> type() {
		return PACKET_TYPE;
	}
}
