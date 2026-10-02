package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ServerConfigBounds;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.InventoryConfigValues;
import org.jetbrains.annotations.NotNull;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

/** Carries a request-correlated server-authoritative settings response. */
public record ServerConfigSnapshotS2CPacket(
	/** The positive request id echoed by the server. */
	long requestId,
	boolean canEdit,
	ChannelMode defaultChannelMode,
	boolean playerTrackingEnabled,
	int msToRegenerate,
	int rateLimit,
	int syncDuration,
	InventoryConfigValues inventory
) implements IPacket {
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(
		S2C_NAMESPACE,
		"server-config-snapshot-v2");
	public static final Type<ServerConfigSnapshotS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	/** Compatibility constructor for snapshots from the pre-duration settings model. */
	public ServerConfigSnapshotS2CPacket(
		long requestId,
		boolean canEdit,
		ChannelMode defaultChannelMode,
		boolean playerTrackingEnabled,
		int msToRegenerate,
		int rateLimit) {
		this(requestId, canEdit, defaultChannelMode, playerTrackingEnabled, msToRegenerate, rateLimit,
			ServerConfigBounds.DEFAULT_SYNC_DURATION);
	}

	/** Source compatibility only; v2 wire snapshots always contain inventory. */
	public ServerConfigSnapshotS2CPacket(long requestId, boolean canEdit, ChannelMode defaultChannelMode,
		boolean playerTrackingEnabled, int msToRegenerate, int rateLimit, int syncDuration) {
		this(requestId, canEdit, defaultChannelMode, playerTrackingEnabled, msToRegenerate, rateLimit,
			syncDuration, InventoryConfigValues.defaults());
	}

	/** Invalid values are used only by safe-decoding fallback. */
	public ServerConfigSnapshotS2CPacket() {
		this(-1L, false, null, false, -1, -1, -1, null);
	}

	/** Builds an expansion response with the request id echoed by the server. */
	public ServerConfigSnapshotS2CPacket(long requestId, ServerConfigSnapshot snapshot) {
		this(
			requestId,
			snapshot.canEdit(),
			snapshot.defaultChannelMode(),
			snapshot.playerTrackingEnabled(),
			snapshot.msToRegenerate(),
			snapshot.rateLimit(),
			snapshot.syncDuration(),
			snapshot.inventory());
	}

	public ServerConfigSnapshotS2CPacket(FriendlyByteBuf buf) {
		this(
			ServerConfigVarNumbers.readLong(buf),
			ServerInventoryConfigCodec.readBoolean(buf),
			readChannelMode(buf),
			ServerInventoryConfigCodec.readBoolean(buf),
			ServerConfigVarNumbers.readInt(buf),
			ServerConfigVarNumbers.readInt(buf),
			ServerConfigVarNumbers.readInt(buf),
			ServerInventoryConfigCodec.readComplete(buf));
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeVarLong(requestId);
		buf.writeBoolean(canEdit);
		writeChannelMode(buf, defaultChannelMode);
		buf.writeBoolean(playerTrackingEnabled);
		buf.writeVarInt(msToRegenerate);
		buf.writeVarInt(rateLimit);
		buf.writeVarInt(syncDuration);
		ServerInventoryConfigCodec.write(buf, inventory);
	}

	@Override
	public boolean isCorrupt() {
		return requestId <= 0L
			|| defaultChannelMode == null
			|| msToRegenerate < 0
			|| rateLimit < 0
			|| syncDuration < ServerConfigBounds.MIN_PING_DURATION
			|| syncDuration > ServerConfigBounds.MAX_PING_DURATION
			|| inventory == null || !inventory.isSafe();
	}

	public ServerConfigSnapshot snapshot() {
		return new ServerConfigSnapshot(
			canEdit,
			defaultChannelMode,
			playerTrackingEnabled,
			msToRegenerate,
			rateLimit,
			syncDuration,
			inventory);
	}

	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static ServerConfigSnapshotS2CPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, ServerConfigSnapshotS2CPacket.class);
	}

	@Override
	public @NotNull Type<ServerConfigSnapshotS2CPacket> type() {
		return PACKET_TYPE;
	}

	static ChannelMode readChannelMode(FriendlyByteBuf buf) {
		int ordinal = ServerConfigVarNumbers.readInt(buf);
		ChannelMode[] values = ChannelMode.values();
		if (ordinal < 0 || ordinal >= values.length) {
			throw new IllegalArgumentException("invalid server config enum ordinal");
		}
		return values[ordinal];
	}

	static void writeChannelMode(FriendlyByteBuf buf, ChannelMode mode) {
		buf.writeVarInt(mode == null ? -1 : mode.ordinal());
	}
}
