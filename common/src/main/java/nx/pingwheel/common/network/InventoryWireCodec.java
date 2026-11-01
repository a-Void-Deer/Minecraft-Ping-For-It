package nx.pingwheel.common.network;

import java.nio.charset.StandardCharsets;
import io.netty.buffer.ByteBufUtil;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNullByDefault;

/** Inventory-v2 text rejects malformed UTF-8 before decoding; other versioned routes keep their own contract. */
@NotNullByDefault
final class InventoryWireCodec {
	private InventoryWireCodec() {}
	static String readUtf(FriendlyByteBuf buf, int maxChars) { return readUtf(buf, maxChars, maxChars * 3); }
	static String readUtf(FriendlyByteBuf buf, int maxChars, int maxBytes) {
		int length = StrictPacketCodec.readVarInt(buf);
		if (length < 0 || length > maxBytes || length > buf.readableBytes()
			|| !ByteBufUtil.isText(buf, buf.readerIndex(), length, StandardCharsets.UTF_8))
			throw new IllegalArgumentException("invalid inventory UTF");
		String text = buf.readCharSequence(length, StandardCharsets.UTF_8).toString();
		if (text.length() > maxChars) throw new IllegalArgumentException("inventory UTF size");
		return text;
	}
	static <E extends Enum<E>> E readEnum(FriendlyByteBuf buf, Class<E> type) {
		return Enum.valueOf(type, readUtf(buf, MarkerPacketCodec.MAX_ID_LENGTH));
	}
}
