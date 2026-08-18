package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

/**
 * Server-authoritative client marker-create rate-limit policy.
 */
public record RateLimitPolicyS2CPacket(int rateLimit, int msToRegenerate) implements IPacket {

	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(S2C_NAMESPACE, "rate-limit-policy");
	public static final Type<RateLimitPolicyS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	/**
	 * A negative field is the intentionally corrupt fallback used by safe
	 * packet decoding.
	 */
	public RateLimitPolicyS2CPacket() {
		this(-1, -1);
	}

	public RateLimitPolicyS2CPacket(FriendlyByteBuf buf) {
		this(buf.readInt(), buf.readInt());
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeInt(rateLimit);
		buf.writeInt(msToRegenerate);
	}

	@Override
	public boolean isCorrupt() {
		return rateLimit < 0 || msToRegenerate < 0;
	}

	@Override
	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static RateLimitPolicyS2CPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, RateLimitPolicyS2CPacket.class);
	}

	@Override
	public @NotNull Type<RateLimitPolicyS2CPacket> type() {
		return PACKET_TYPE;
	}
}
