package nx.pingwheel.common.network;

import java.nio.charset.StandardCharsets;
import net.minecraft.network.FriendlyByteBuf;

/** Strict primitives for bounded versioned routes; does not alter legacy packet decoding. */
public final class StrictPacketCodec {
	private StrictPacketCodec() {}

	public static int readVarInt(FriendlyByteBuf buf) {
		return ServerConfigVarNumbers.readInt(buf);
	}

	public static long readVarLong(FriendlyByteBuf buf) {
		return ServerConfigVarNumbers.readLong(buf);
	}

	public static boolean readBoolean(FriendlyByteBuf buf) {
		int value = buf.readUnsignedByte();
		if (value > 1) throw new IllegalArgumentException("invalid boolean");
		return value == 1;
	}

	public static String readUtf(FriendlyByteBuf buf, int maxChars) {
		int length = readVarInt(buf);
		if (length < 0 || length > maxChars * 3 || length > buf.readableBytes())
			throw new IllegalArgumentException("invalid UTF length");
		String value = buf.readCharSequence(length, StandardCharsets.UTF_8).toString();
		if (value.length() > maxChars) throw new IllegalArgumentException("invalid UTF size");
		return value;
	}

	public static <E extends Enum<E>> E readEnum(FriendlyByteBuf buf, Class<E> type) {
		return Enum.valueOf(type, readUtf(buf, MarkerPacketCodec.MAX_ID_LENGTH));
	}
}
