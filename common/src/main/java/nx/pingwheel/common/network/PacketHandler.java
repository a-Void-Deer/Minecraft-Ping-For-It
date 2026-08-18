package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PacketHandler {
	PacketHandler() {}

	// Dedicated logger for packet decode failures. This intentionally uses
	// Log4j directly instead of Global.LOGGER: Global's static initializer
	// resolves the platform context and must never run from packet decoding,
	// and tests must be able to exercise readSafe without platform wiring.
	private static final Logger LOGGER = LogManager.getLogger(PacketHandler.class);

	public static <T> T readSafe(FriendlyByteBuf buf, Class<T> packetClass) {
		try {
			try {
				return packetClass.getDeclaredConstructor(FriendlyByteBuf.class).newInstance(buf);
			} catch (ReflectiveOperationException | RuntimeException e) {
				// Only the packet class name and the exception class name are
				// logged. Payload bytes, exception messages, and any other
				// user-controlled data never reach the log.
				LOGGER.debug("Failed to decode {} packet, using corrupt fallback ({}).",
					packetClass.getSimpleName(), e.getClass().getSimpleName());
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
