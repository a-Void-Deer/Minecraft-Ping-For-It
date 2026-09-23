package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.presentation.PresentationPolicy;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Status;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static nx.pingwheel.common.Global.S2C_NAMESPACE;

/**
 * Carries the current presentation selector rule view, the server in-memory
 * revision, and the status of the correlated request. Only the three
 * presentation selector values travel on this route; no other persisted server
 * setting is disclosed. A {@code requestId} of zero marks an unsolicited
 * broadcast that keeps every connected client's known rule view current.
 *
 * <p>The canonical constructor defensively copies both selector lists, and
 * {@link #write(FriendlyByteBuf)} refuses to encode a snapshot that violates
 * the selector capacity, selector length, selector grammar, or the total
 * encoded selector byte bound, so a malformed in-memory packet can never reach
 * the wire. Decoding keeps failing closed through
 * {@link #readSafe(FriendlyByteBuf)}.
 */
public record ServerPresentationPolicyS2CPacket(
	long requestId,
	long revision,
	Status status,
	boolean canEdit,
	List<String> white,
	List<String> black,
	boolean whitelistOnly
) implements IPacket {
	public static final ResourceLocation PACKET_ID = ResourceLocation.fromNamespaceAndPath(
		S2C_NAMESPACE,
		"server-presentation-policy-v1");
	public static final Type<ServerPresentationPolicyS2CPacket> PACKET_TYPE = new Type<>(PACKET_ID);

	/**
	 * Maximum encoded size of one selector. The accepted grammar is ASCII, so
	 * this matches both {@link ServerPresentationPolicyService#MAX_SELECTOR_LENGTH}
	 * and the encoded byte count of every grammar-valid selector.
	 */
	public static final int MAX_ENCODED_SELECTOR_BYTES = ServerPresentationPolicyService.MAX_SELECTOR_LENGTH;

	/**
	 * Upper bound of the encoded selector payload, derived from the capacity and
	 * selector limits plus the list count varints and a small fixed header
	 * allowance. A grammar-valid maximum-capacity snapshot always fits; a
	 * multibyte list that would smuggle more bytes than the character limits
	 * permit is rejected even if it somehow passed the grammar check.
	 */
	public static final int MAX_ENCODED_PAYLOAD_BYTES =
		2 * ServerPresentationPolicyService.MAX_SELECTORS * (MAX_ENCODED_SELECTOR_BYTES + 3) + 64;

	/** Invalid values are used only by safe-decoding fallback. */
	public ServerPresentationPolicyS2CPacket() {
		this(-1L, -1L, null, false, List.of(), List.of(), false);
	}

	public ServerPresentationPolicyS2CPacket {
		white = white == null ? List.of() : List.copyOf(white);
		black = black == null ? List.of() : List.copyOf(black);
	}

	public ServerPresentationPolicyS2CPacket(FriendlyByteBuf buf) {
		this(
			buf.readVarLong(),
			buf.readVarLong(),
			readStatus(buf),
			buf.readBoolean(),
			readSelectors(buf),
			readSelectors(buf),
			buf.readBoolean());
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		if (isCorrupt()) {
			throw new IllegalArgumentException("refusing to encode an invalid presentation policy snapshot");
		}

		buf.writeVarLong(requestId);
		buf.writeVarLong(revision);
		buf.writeVarInt(status.ordinal());
		buf.writeBoolean(canEdit);
		writeSelectors(buf, white);
		writeSelectors(buf, black);
		buf.writeBoolean(whitelistOnly);
	}

	@Override
	public boolean isCorrupt() {
		if (requestId < 0L || revision < 0L || status == null) {
			return true;
		}

		if (!validSelectorList(white) || !validSelectorList(black)) {
			return true;
		}

		if (encodedSelectorBytes() > MAX_ENCODED_PAYLOAD_BYTES) {
			return true;
		}

		try {
			new PresentationPolicy(white, black, whitelistOnly);
			return false;
		} catch (RuntimeException ex) {
			return true;
		}
	}

	private static boolean validSelectorList(List<String> selectors) {
		if (selectors == null || selectors.size() > ServerPresentationPolicyService.MAX_SELECTORS) {
			return false;
		}

		for (String selector : selectors) {
			if (selector == null
				|| selector.isEmpty()
				|| selector.length() > MAX_ENCODED_SELECTOR_BYTES) {
				return false;
			}
		}

		return true;
	}

	private int encodedSelectorBytes() {
		return encodedListBytes(white) + encodedListBytes(black);
	}

	private static int encodedListBytes(List<String> selectors) {
		int size = varIntSize(selectors.size());

		for (String selector : selectors) {
			int bytes = selector.getBytes(StandardCharsets.UTF_8).length;
			size += varIntSize(bytes) + bytes;
		}

		return size;
	}

	/** Encoded size of a non-negative varint; mirrors {@code FriendlyByteBuf#writeVarInt}. */
	private static int varIntSize(int value) {
		return (value & -128) == 0 ? 1
			: (value & -16384) == 0 ? 2
			: (value & -2097152) == 0 ? 3
			: (value & -268435456) == 0 ? 4
			: 5;
	}

	public ResourceLocation getId() {
		return PACKET_ID;
	}

	public static ServerPresentationPolicyS2CPacket readSafe(FriendlyByteBuf buf) {
		return PacketHandler.readSafe(buf, ServerPresentationPolicyS2CPacket.class);
	}

	@Override
	public @NotNull Type<ServerPresentationPolicyS2CPacket> type() {
		return PACKET_TYPE;
	}

	static List<String> readSelectors(FriendlyByteBuf buf) {
		int count = buf.readVarInt();
		if (count < 0 || count > ServerPresentationPolicyService.MAX_SELECTORS) {
			throw new IllegalArgumentException("invalid presentation selector count");
		}

		List<String> selectors = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			selectors.add(buf.readUtf(ServerPresentationPolicyService.MAX_SELECTOR_LENGTH));
		}

		return List.copyOf(selectors);
	}

	static void writeSelectors(FriendlyByteBuf buf, List<String> selectors) {
		buf.writeVarInt(selectors.size());
		for (String selector : selectors) {
			buf.writeUtf(selector, ServerPresentationPolicyService.MAX_SELECTOR_LENGTH);
		}
	}

	static Status readStatus(FriendlyByteBuf buf) {
		int ordinal = buf.readVarInt();
		Status[] values = Status.values();
		if (ordinal < 0 || ordinal >= values.length) {
			throw new IllegalArgumentException("invalid presentation policy status");
		}
		return values[ordinal];
	}
}
