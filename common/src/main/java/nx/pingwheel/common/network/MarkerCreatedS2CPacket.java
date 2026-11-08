package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.name.TargetNameJson;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

public record MarkerCreatedS2CPacket(
	MarkerSnapshot snapshot, TargetNameJson targetName, String ownerName
) implements IPacket {

	public static final ResourceLocation PACKET_ID = new ResourceLocation(S2C_NAMESPACE, "marker-created");

	public MarkerCreatedS2CPacket() {
		this(null, null, null);
	}

	public MarkerCreatedS2CPacket(FriendlyByteBuf buf) {
		this(
			MarkerPacketCodec.readMarkerSnapshot(buf),
			MarkerPacketCodec.readTargetNameJson(buf),
			MarkerPacketCodec.readOwnerName(buf));
	}

	public void write(FriendlyByteBuf buf) {
		MarkerPacketCodec.writeMarkerSnapshot(buf, snapshot);
		MarkerPacketCodec.writeTargetNameJson(buf, targetName);
		MarkerPacketCodec.writeOwnerName(buf, ownerName);
	}

	public boolean isCorrupt() {
		return snapshot == null
			|| targetName == null
			|| ownerName == null
			|| ownerName.isBlank()
			|| ownerName.length() > MarkerPacketCodec.MAX_OWNER_NAME_LENGTH
			|| ownerName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
				> MarkerPacketCodec.MAX_OWNER_NAME_LENGTH * 4;
	}

	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static MarkerCreatedS2CPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, MarkerCreatedS2CPacket.class);
	}
}
