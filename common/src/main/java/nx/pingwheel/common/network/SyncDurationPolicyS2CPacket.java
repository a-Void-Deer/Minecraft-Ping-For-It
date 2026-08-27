package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import nx.pingwheel.common.config.ServerConfigBounds;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

/**
 * Server-authoritative marker sync-duration policy.  It is separate from the
 * create-rate policy because duration affects newly-created marker lifetime,
 * not request admission.
 */
public record SyncDurationPolicyS2CPacket(int syncDuration) implements IPacket {
	public static final ResourceLocation PACKET_ID =
		ResourceLocation.fromNamespaceAndPath(S2C_NAMESPACE, "sync-duration-policy");
	public static final Type<SyncDurationPolicyS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	/** A negative duration is the intentionally corrupt decode fallback. */
	public SyncDurationPolicyS2CPacket() {
		this(-1);
	}

	public SyncDurationPolicyS2CPacket(FriendlyByteBuf buf) {
		this(buf.readInt());
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeInt(syncDuration);
	}

	@Override
	public boolean isCorrupt() {
		return syncDuration < ServerConfigBounds.MIN_PING_DURATION
			|| syncDuration > ServerConfigBounds.MAX_PING_DURATION;
	}

	@Override
	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static SyncDurationPolicyS2CPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, SyncDurationPolicyS2CPacket.class);
	}

	@Override
	public @NotNull Type<SyncDurationPolicyS2CPacket> type() {
		return PACKET_TYPE;
	}
}
