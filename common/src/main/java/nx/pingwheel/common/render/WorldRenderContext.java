package nx.pingwheel.common.render;

import lombok.AllArgsConstructor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;

@AllArgsConstructor(staticName = "of")
public class WorldRenderContext {
	public final Matrix4f modelViewMatrix;
	public final Matrix4f projectionMatrix;
	public final Float tickDelta;
	public final CameraRenderState camera;
}
