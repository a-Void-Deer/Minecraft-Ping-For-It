package nx.pingwheel.common.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.renderer.RenderPipelines;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;

/**
 * Accessor for the private {@code RenderPipelines.register(RenderPipeline)}
 * (M7-prep E2, decision D1).
 */
@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {

	@Invoker("register")
	static RenderPipeline pingforit$register(RenderPipeline pipeline) {
		throw new AssertionError();
	}
}
