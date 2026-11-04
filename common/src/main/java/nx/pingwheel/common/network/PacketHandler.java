package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Duration;

@NotNullByDefault
public class PacketHandler {
	private static final DiagnosticChannel DECODE_FAILURE = DiagnosticChannel.interval("packet-decode", Duration.ofSeconds(10));
	PacketHandler() {}

	public static <T> T readSafe(FriendlyByteBuf buf, Class<T> packetClass, DiagnosticLogger consumer) {
		try {
			try {
				return packetClass.getDeclaredConstructor(FriendlyByteBuf.class).newInstance(buf);
			} catch (ReflectiveOperationException | RuntimeException e) {
				consumer.atDebug().rateLimited(DECODE_FAILURE).debug("packet-decode", "corrupt-fallback", e,
					() -> "packetClass=" + packetClass.getName());
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
