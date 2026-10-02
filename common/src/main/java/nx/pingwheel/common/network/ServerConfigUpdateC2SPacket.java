package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ServerConfigBounds;
import nx.pingwheel.common.config.ServerConfigUpdate;
import nx.pingwheel.common.config.InventoryConfigValues;
import org.jetbrains.annotations.NotNull;

import static nx.pingwheel.common.Global.C2S_NAMESPACE;

/** Sends one dirty-field-only server settings update. */
public record ServerConfigUpdateC2SPacket(
	int changedFields,
	ChannelMode defaultChannelMode,
	boolean playerTrackingEnabled,
	int msToRegenerate,
	int rateLimit,
	int syncDuration,
	InventoryConfigValues inventory
) implements IPacket {
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(
		C2S_NAMESPACE,
		"server-config-update-v2");
	public static final Type<ServerConfigUpdateC2SPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	/** Compatibility constructor for callers that do not edit sync duration. */
	public ServerConfigUpdateC2SPacket(
		int changedFields,
		ChannelMode defaultChannelMode,
		boolean playerTrackingEnabled,
		int msToRegenerate,
		int rateLimit) {
		this(changedFields, defaultChannelMode, playerTrackingEnabled, msToRegenerate, rateLimit,
			ServerConfigBounds.DEFAULT_SYNC_DURATION);
	}

	/** Source compatibility only; v2 wire updates always contain inventory. */
	public ServerConfigUpdateC2SPacket(int changedFields, ChannelMode defaultChannelMode,
		boolean playerTrackingEnabled, int msToRegenerate, int rateLimit, int syncDuration) {
		this(changedFields, defaultChannelMode, playerTrackingEnabled, msToRegenerate, rateLimit,
			syncDuration, InventoryConfigValues.defaults());
	}

	public ServerConfigUpdateC2SPacket(ServerConfigUpdate update) {
		this(update.changedFields(), update.defaultChannelMode(), update.playerTrackingEnabled(),
			update.msToRegenerate(), update.rateLimit(), update.syncDuration(), update.inventory());
	}

	public static final int DEFAULT_CHANNEL_MODE = ServerConfigUpdate.DEFAULT_CHANNEL_MODE;
	public static final int PLAYER_TRACKING_ENABLED = ServerConfigUpdate.PLAYER_TRACKING_ENABLED;
	public static final int MS_TO_REGENERATE = ServerConfigUpdate.MS_TO_REGENERATE;
	public static final int RATE_LIMIT = ServerConfigUpdate.RATE_LIMIT;
	public static final int SYNC_DURATION = ServerConfigUpdate.SYNC_DURATION;
	public static final int ALL_FIELDS = ServerConfigUpdate.ALL_FIELDS;

	/** Invalid values are used only by safe-decoding fallback. */
	public ServerConfigUpdateC2SPacket() {
		this(0, null, false, -1, -1, -1, null);
	}

	public ServerConfigUpdateC2SPacket(FriendlyByteBuf buf) {
		this(
			ServerConfigVarNumbers.readInt(buf),
			ServerConfigSnapshotS2CPacket.readChannelMode(buf),
			ServerInventoryConfigCodec.readBoolean(buf),
			ServerConfigVarNumbers.readInt(buf),
			ServerConfigVarNumbers.readInt(buf),
			ServerConfigVarNumbers.readInt(buf),
			ServerInventoryConfigCodec.readComplete(buf));
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeVarInt(changedFields);
		ServerConfigSnapshotS2CPacket.writeChannelMode(buf, defaultChannelMode);
		buf.writeBoolean(playerTrackingEnabled);
		buf.writeVarInt(msToRegenerate);
		buf.writeVarInt(rateLimit);
		buf.writeVarInt(syncDuration);
		ServerInventoryConfigCodec.write(buf, inventory);
	}

	@Override
	public boolean isCorrupt() {
		return !new ServerConfigUpdate(
			changedFields,
			defaultChannelMode,
			playerTrackingEnabled,
			msToRegenerate,
			rateLimit,
			syncDuration,
			inventory).isValid();
	}

	public ServerConfigUpdate update() {
		return new ServerConfigUpdate(
			changedFields,
			defaultChannelMode,
			playerTrackingEnabled,
			msToRegenerate,
			rateLimit,
			syncDuration,
			inventory);
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
