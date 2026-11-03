package nx.pingwheel.common.platform;

import java.util.ServiceLoader;

public interface IPlatformClientEventService {

	IPlatformClientEventService INSTANCE = ServiceLoader.load(IPlatformClientEventService.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No IPlatformClientEventService implementation found!"));

	void registerTickStartEvent(Runnable callback);
	void registerJoinServerEvent(Runnable callback);
	void registerLeaveServerEvent(Runnable callback);

	/**
	 * Registers a callback invoked exactly once per rendered frame while a level
	 * is present, at the end of the level-extraction phase (before the GUI/HUD is
	 * extracted for the same frame).
	 *
	 * <p>This is the 26.3 frame boundary for presentation/interaction timing. It
	 * must be independent of HUD visibility: {@code F1} hides the HUD but never
	 * stops level extraction, so frame timing must not live in a HUD element.
	 */
	void registerRenderFrameEvent(Runnable callback);
}
