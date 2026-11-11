package nx.pingwheel.common.network;

import java.nio.charset.StandardCharsets;
import io.netty.buffer.ByteBufUtil;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.network.InventoryWireLimitException.Bound;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Inventory-v2 text rejects malformed UTF-8 before decoding; other versioned routes keep their own contract. */
@NotNullByDefault
final class InventoryWireCodec {
	private InventoryWireCodec() {}
	static String readUtf(FriendlyByteBuf buf, int maxChars) { return readUtf(buf, maxChars, maxChars * 3, null); }
	static String readUtf(FriendlyByteBuf buf, int maxChars, int maxBytes) { return readUtf(buf, maxChars, maxBytes, null); }
	static String readUtf(FriendlyByteBuf buf, int maxChars, Bound bound) { return readUtf(buf, maxChars, maxChars * 3, bound); }
	/** The bound classifies only a positive declared byte or decoded character overrun; malformed or truncated text stays ordinary. */
	static String readUtf(FriendlyByteBuf buf, int maxChars, int maxBytes, @Nullable Bound bound) {
		int length = StrictPacketCodec.readVarInt(buf);
		if (length < 0) throw new IllegalArgumentException("invalid inventory UTF");
		if (length > maxBytes) {
			throw bound == null ? new IllegalArgumentException("invalid inventory UTF")
				: new InventoryWireLimitException(bound, length, maxBytes);
		}
		if (length > buf.readableBytes() || !ByteBufUtil.isText(buf, buf.readerIndex(), length, StandardCharsets.UTF_8))
			throw new IllegalArgumentException("invalid inventory UTF");
		String text = buf.readCharSequence(length, StandardCharsets.UTF_8).toString();
		if (text.length() > maxChars) {
			throw bound == null ? new IllegalArgumentException("inventory UTF size")
				: new InventoryWireLimitException(bound, text.length(), maxChars);
		}
		return text;
	}
	static <E extends Enum<E>> E readEnum(FriendlyByteBuf buf, Class<E> type) {
		return Enum.valueOf(type, readUtf(buf, MarkerPacketCodec.MAX_ID_LENGTH, Bound.ID_BYTES));
	}
}
