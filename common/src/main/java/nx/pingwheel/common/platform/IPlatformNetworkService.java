package nx.pingwheel.common.platform;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.network.IPacket;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ServiceLoader;

@NotNullByDefault
public interface IPlatformNetworkService {

	IPlatformNetworkService INSTANCE = ServiceLoader.load(IPlatformNetworkService.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No IPlatformNetworkService implementation found!"));

	void sendToServer(IPacket packet);
	void sendToClient(IPacket packet, ServerPlayer player);

	/**
	 * Observes only transport handoff, not receipt or business success. Legacy
	 * implementations retain their one send attempt without inventing evidence.
	 * Exceptions propagate unchanged; the result must not trigger a retry or fallback.
	 */
	default TransportHandoff sendToClientObserved(IPacket packet, ServerPlayer player) {
		sendToClient(packet, player);
		return TransportHandoff.UNOBSERVED;
	}

	/** Current C2S route support on the client connection, independent of S2C support. */
	default RouteSupport serverboundSupport(ResourceLocation routeId) {
		return RouteSupport.UNKNOWN;
	}

	/** Current S2C route support for this recipient, independent of C2S support. */
	default RouteSupport clientboundSupport(ServerPlayer player, ResourceLocation routeId) {
		return RouteSupport.UNKNOWN;
	}
}
