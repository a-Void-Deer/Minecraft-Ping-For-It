package nx.pingwheel.common.network;

import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@NotNullByDefault
public class PacketHandler {
	/**
	 * Finite, class-keyed decode routes. The actual packet class selects its own
	 * channel, so one protocol family cannot suppress another and malformed input
	 * cannot create channels. Unknown caller classes share one fallback route.
	 */
	private enum DecodeRoute {
		PRESENTATION_C2S(PresentationC2SPacket.class, "presentation-c2s"),
		PRESENTATION_S2C(PresentationS2CPacket.class, "presentation-s2c"),
		PRESENTATION_PREVIEW_C2S(PresentationPreviewC2SPacket.class, "presentation-preview-c2s"),
		PRESENTATION_PREVIEW_S2C(PresentationPreviewS2CPacket.class, "presentation-preview-s2c"),
		INVENTORY_C2S(InventoryC2SPacket.class, "inventory-c2s"),
		INVENTORY_S2C(InventoryS2CPacket.class, "inventory-s2c"),
		SERVER_CONFIG_REQUEST(ServerConfigRequestC2SPacket.class, "server-config-request-c2s"),
		SERVER_CONFIG_UPDATE(ServerConfigUpdateC2SPacket.class, "server-config-update-c2s"),
		SERVER_CONFIG_SNAPSHOT(ServerConfigSnapshotS2CPacket.class, "server-config-snapshot-s2c"),
		SERVER_PRESENTATION_POLICY_C2S(ServerPresentationPolicyC2SPacket.class, "server-presentation-policy-c2s"),
		SERVER_PRESENTATION_POLICY_S2C(ServerPresentationPolicyS2CPacket.class, "server-presentation-policy-s2c"),
		RATE_LIMIT_POLICY_S2C(RateLimitPolicyS2CPacket.class, "rate-limit-policy-s2c"),
		UPDATE_CHANNEL_C2S(UpdateChannelC2SPacket.class, "update-channel-c2s"),
		OTHER(null, "other");

		private final @Nullable Class<?> packetClass;
		private final DiagnosticChannel channel;

		DecodeRoute(@Nullable Class<?> packetClass, String route) {
			this.packetClass = packetClass;
			this.channel = DiagnosticChannel.interval("packet-decode/" + route, Duration.ofSeconds(10));
		}
	}

	private static final Map<Class<?>, DecodeRoute> ROUTES = routes();

	PacketHandler() {}

	public static <T> T readSafe(FriendlyByteBuf buf, Class<T> packetClass, DiagnosticLogger consumer) {
		try {
			try {
				return packetClass.getDeclaredConstructor(FriendlyByteBuf.class).newInstance(buf);
			} catch (ReflectiveOperationException | RuntimeException e) {
				consumer.atDebug().rateLimited(channel(packetClass)).debug("packet-decode", "corrupt-fallback", e,
					() -> "packetClass=" + packetClass.getName());
				return corruptFallback(packetClass);
			}
		} finally {
			if (buf.readableBytes() > 0) {
				buf.readerIndex(buf.readerIndex() + buf.readableBytes());
			}
		}
	}

	private static DiagnosticChannel channel(Class<?> packetClass) {
		DecodeRoute route = ROUTES.get(packetClass);
		return (route == null ? DecodeRoute.OTHER : route).channel;
	}

	private static Map<Class<?>, DecodeRoute> routes() {
		Map<Class<?>, DecodeRoute> routes = new HashMap<>();
		for (DecodeRoute route : DecodeRoute.values())
			if (route.packetClass != null) routes.put(route.packetClass, route);
		return Map.copyOf(routes);
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
