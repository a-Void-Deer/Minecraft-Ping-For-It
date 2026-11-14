package nx.pingwheel.common.platform;

import nx.pingwheel.common.network.IPacket;

import java.util.ServiceLoader;

public interface IClientNetworkService {

	IClientNetworkService INSTANCE = ServiceLoader.load(IClientNetworkService.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No IClientNetworkService implementation found!"));

	void sendToServer(IPacket packet);
}
