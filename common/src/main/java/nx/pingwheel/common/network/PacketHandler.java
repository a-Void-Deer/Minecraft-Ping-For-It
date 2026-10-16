package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.util.RateLimitedDiagnostics;

public class PacketHandler {
	PacketHandler() {}

	public static <T> T readSafe(FriendlyByteBuf buf, Class<T> packetClass) {
		try {
			try {
				return packetClass.getDeclaredConstructor(FriendlyByteBuf.class).newInstance(buf);
			} catch (ReflectiveOperationException | RuntimeException e) {
				RateLimitedDiagnostics.debug("packet-decode", packetClass.getName(),
					() -> "packetClass=" + packetClass.getName() + " fallback=corrupt", e);
				return corruptFallback(packetClass);
			}
		} finally {
			if (buf.readableBytes() > 0) {
				buf.readerIndex(buf.readerIndex() + buf.readableBytes());
			}
		}
	}

	private static <T> T corruptFallback(Class<T> packetClass) {
		try {
			return packetClass.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new IllegalStateException(
				"Failed to create corrupt fallback packet for " + packetClass.getSimpleName()
					+ " (" + e.getClass().getSimpleName() + ")", e);
		}
	}
}
