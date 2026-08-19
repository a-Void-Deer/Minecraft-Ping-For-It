package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import static nx.pingwheel.common.Global.C2S_NAMESPACE;

/** Requests one authoritative server-settings snapshot using a positive correlation id. */
public record ServerConfigRequestC2SPacket(long requestId) implements IPacket {
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(
		C2S_NAMESPACE,
		"server-config-request");
	public static final Type<ServerConfigRequestC2SPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	/** Invalid values are used only by safe-decoding fallback. */
	public ServerConfigRequestC2SPacket() {
		this(-1L);
	}

	public ServerConfigRequestC2SPacket(FriendlyByteBuf buf) {
		this(buf.readVarLong());
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeVarLong(requestId);
	}

	@Override
	public boolean isCorrupt() {
		return requestId <= 0L;
	}

	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static ServerConfigRequestC2SPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, ServerConfigRequestC2SPacket.class);
	}

	@Override
	public @NotNull Type<ServerConfigRequestC2SPacket> type() {
		return PACKET_TYPE;
	}
}
