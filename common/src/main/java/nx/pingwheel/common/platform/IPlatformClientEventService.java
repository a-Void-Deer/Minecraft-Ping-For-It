package nx.pingwheel.common.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import org.apache.logging.log4j.util.TriConsumer;

import java.util.ServiceLoader;
import java.util.function.BiConsumer;

public interface IPlatformClientEventService {

	IPlatformClientEventService INSTANCE = ServiceLoader.load(IPlatformClientEventService.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No IPlatformClientEventService implementation found!"));

	void registerTickStartEvent(Runnable callback);
	void registerJoinServerEvent(Runnable callback);
	void registerLeaveServerEvent(Runnable callback);
	void registerRenderWorldEvent(TriConsumer<Matrix4f, Matrix4f, Float> callback);
	void registerRenderGUIEvent(BiConsumer<PoseStack, Float> callback);
}
