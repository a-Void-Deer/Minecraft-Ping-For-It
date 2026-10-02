package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;

/** Canonical non-negative v2 administration numbers, checked before any narrowing shift. */
final class ServerConfigVarNumbers {
	private ServerConfigVarNumbers() {}

	static int readInt(FriendlyByteBuf buf) {
		return (int) read(buf, 31);
	}

	static long readLong(FriendlyByteBuf buf) {
		return read(buf, 63);
	}

	private static long read(FriendlyByteBuf buf, int bits) {
		long value = 0;
		int maximumBytes = (bits + 6) / 7;
		for (int index = 0; index < maximumBytes; index++) {
			int encoded = buf.readUnsignedByte();
			int payload = encoded & 0x7f;
			int remainingBits = bits - index * 7;
			if (remainingBits < 7 && payload >= (1 << remainingBits)) {
				throw new IllegalArgumentException("overflowing server configuration number");
			}
			value |= (long) payload << (index * 7);
			if ((encoded & 0x80) == 0) {
				if (index > 0 && payload == 0) {
					throw new IllegalArgumentException("non-canonical server configuration number");
				}
				return value;
			}
		}
		throw new IllegalArgumentException("overlong server configuration number");
	}
}
