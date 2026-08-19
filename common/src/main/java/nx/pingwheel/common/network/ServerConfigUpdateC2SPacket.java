package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ServerConfigUpdate;
import org.jetbrains.annotations.NotNull;

import static nx.pingwheel.common.Global.C2S_NAMESPACE;

/** Sends one dirty-field-only server settings update. */
public record ServerConfigUpdateC2SPacket(
	int changedFields,
	ChannelMode defaultChannelMode,
	boolean playerTrackingEnabled,
	int msToRegenerate,
	int rateLimit
) implements IPacket {
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(
		C2S_NAMESPACE,
		"server-config-update");
	public static final Type<ServerConfigUpdateC2SPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	public static final int DEFAULT_CHANNEL_MODE = ServerConfigUpdate.DEFAULT_CHANNEL_MODE;
	public static final int PLAYER_TRACKING_ENABLED = ServerConfigUpdate.PLAYER_TRACKING_ENABLED;
	public static final int MS_TO_REGENERATE = ServerConfigUpdate.MS_TO_REGENERATE;
	public static final int RATE_LIMIT = ServerConfigUpdate.RATE_LIMIT;
	public static final int ALL_FIELDS = ServerConfigUpdate.ALL_FIELDS;

	/** Invalid values are used only by safe-decoding fallback. */
	public ServerConfigUpdateC2SPacket() {
		this(0, null, false, -1, -1);
	}

	public ServerConfigUpdateC2SPacket(FriendlyByteBuf buf) {
		this(
			buf.readVarInt(),
			ServerConfigSnapshotS2CPacket.readChannelMode(buf),
			buf.readBoolean(),
			buf.readVarInt(),
			buf.readVarInt());
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeVarInt(changedFields);
		ServerConfigSnapshotS2CPacket.writeChannelMode(buf, defaultChannelMode);
		buf.writeBoolean(playerTrackingEnabled);
		buf.writeVarInt(msToRegenerate);
		buf.writeVarInt(rateLimit);
	}

	@Override
	public boolean isCorrupt() {
		return !new ServerConfigUpdate(
			changedFields,
			defaultChannelMode,
			playerTrackingEnabled,
			msToRegenerate,
			rateLimit).isValid();
	}

	public ServerConfigUpdate update() {
		return new ServerConfigUpdate(
			changedFields,
			defaultChannelMode,
			playerTrackingEnabled,
			msToRegenerate,
			rateLimit);
	}

	public int changedMask() {
		return changedFields;
	}

	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static ServerConfigUpdateC2SPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, ServerConfigUpdateC2SPacket.class);
	}

	@Override
	public @NotNull Type<ServerConfigUpdateC2SPacket> type() {
		return PACKET_TYPE;
	}
}
