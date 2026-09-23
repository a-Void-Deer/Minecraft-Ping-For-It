package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Operation;
import org.jetbrains.annotations.NotNull;

import static nx.pingwheel.common.Global.C2S_NAMESPACE;

/**
 * Carries one presentation policy rule-view read or one bounded selector
 * mutation. The route is versioned independently of the five-field
 * server-config request/update routes and never travels on their packet ids.
 */
public record ServerPresentationPolicyC2SPacket(
	long requestId,
	Operation operation,
	String selector,
	boolean whitelistOnly
) implements IPacket {
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(
		C2S_NAMESPACE,
		"server-presentation-policy-v1");
	public static final Type<ServerPresentationPolicyC2SPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	/** A correlated read request; any authenticated player may send one. */
	public static ServerPresentationPolicyC2SPacket read(long requestId) {
		return new ServerPresentationPolicyC2SPacket(requestId, Operation.READ, "", false);
	}

	/** Invalid values are used only by safe-decoding fallback. */
	public ServerPresentationPolicyC2SPacket() {
		this(-1L, null, null, false);
	}

	public ServerPresentationPolicyC2SPacket(FriendlyByteBuf buf) {
		this(
			buf.readVarLong(),
			readOperation(buf),
			buf.readUtf(ServerPresentationPolicyService.MAX_SELECTOR_LENGTH),
			buf.readBoolean());
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeVarLong(requestId);
		buf.writeVarInt(operation == null ? -1 : operation.ordinal());
		buf.writeUtf(selector == null ? "" : selector, ServerPresentationPolicyService.MAX_SELECTOR_LENGTH);
		buf.writeBoolean(whitelistOnly);
	}

	@Override
	public boolean isCorrupt() {
		if (requestId <= 0L || operation == null) {
			return true;
		}

		if (!operation.requiresSelector()) {
			return false;
		}

		return !ServerPresentationPolicyService.isValidSelector(selector);
	}

	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static ServerPresentationPolicyC2SPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, ServerPresentationPolicyC2SPacket.class);
	}

	@Override
	public @NotNull Type<ServerPresentationPolicyC2SPacket> type() {
		return PACKET_TYPE;
	}

	static Operation readOperation(FriendlyByteBuf buf) {
		int ordinal = buf.readVarInt();
		Operation[] values = Operation.values();
		if (ordinal < 0 || ordinal >= values.length) {
			throw new IllegalArgumentException("invalid presentation policy operation");
		}
		return values[ordinal];
	}
}
