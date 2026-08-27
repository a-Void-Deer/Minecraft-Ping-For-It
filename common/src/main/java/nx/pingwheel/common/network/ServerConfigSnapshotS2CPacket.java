package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ServerConfigBounds;
import nx.pingwheel.common.config.ServerConfigSnapshot;
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
	int syncDuration
) implements IPacket {
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(
		S2C_NAMESPACE,
		"server-config-snapshot");
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

	/** Invalid values are used only by safe-decoding fallback. */
	public ServerConfigSnapshotS2CPacket() {
		this(-1L, false, null, false, -1, -1, -1);
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
			snapshot.syncDuration());
	}

	public ServerConfigSnapshotS2CPacket(FriendlyByteBuf buf) {
		this(
			buf.readVarLong(),
			buf.readBoolean(),
			readChannelMode(buf),
			buf.readBoolean(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt());
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
	}

	@Override
	public boolean isCorrupt() {
		return requestId <= 0L
			|| defaultChannelMode == null
			|| msToRegenerate < 0
			|| rateLimit < 0
			|| syncDuration < ServerConfigBounds.MIN_PING_DURATION
			|| syncDuration > ServerConfigBounds.MAX_PING_DURATION;
	}

	public ServerConfigSnapshot snapshot() {
		return new ServerConfigSnapshot(
			canEdit,
			defaultChannelMode,
			playerTrackingEnabled,
			msToRegenerate,
			rateLimit,
			syncDuration);
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
		int ordinal = buf.readVarInt();
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
