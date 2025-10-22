package nx.pingwheel.common.platform;

import java.util.ServiceLoader;

public interface IPlatformClientEventService {

	IPlatformClientEventService INSTANCE = ServiceLoader.load(IPlatformClientEventService.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No IPlatformClientEventService implementation found!"));

	void registerTickStartEvent(Runnable callback);
	void registerJoinServerEvent(Runnable callback);
	void registerLeaveServerEvent(Runnable callback);
}
