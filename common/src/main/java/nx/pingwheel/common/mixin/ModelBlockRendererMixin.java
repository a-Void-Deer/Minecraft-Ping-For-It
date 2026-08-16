package nx.pingwheel.common.mixin;

import net.minecraft.client.renderer.block.ModelBlockRenderer;
import nx.pingwheel.common.client.outline.BlockModelRenderSeed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Registered client mixin that substitutes the position-derived model render
 * seed for the vanilla fallback seed inside the virtual {@code BlockDisplay}
 * model-glow pass.
 *
 * <p>In 1.21.1 the block display render route
 * ({@code DisplayRenderer.BlockDisplayRenderer#renderSingleBlock} ->
 * {@code BlockRenderDispatcher#renderSingleBlock}) funnels into
 * {@code ModelBlockRenderer#renderModel}; it never reaches the AO helpers
 * {@code tesselateWithAO}/{@code tesselateWithoutAO}, which only serve the
 * chunk-oriented {@code tesselateBlock} path. This mixin therefore targets
 * {@code renderModel} — never the tesselate methods. The vanilla caller
 * additionally passes a fixed constant seed (42) as the {@code setSeed}
 * argument; that constant is deliberately never rewritten (no
 * {@code ModifyConstant} targets it), because the substitution happens at the
 * {@code RandomSource#setSeed(long)} invocation sites inside
 * {@code renderModel} via {@link BlockModelRenderSeed#resolve(long)}.
 *
 * <p>The method selector is intentionally name-only
 * ({@code method = "renderModel"}, no descriptor) so every {@code renderModel}
 * overload is covered regardless of loader-specific re-signaturing: on the
 * Fabric-mapped loader a single {@code renderModel} worker carries exactly 2
 * {@code RandomSource#setSeed(long)} invocation sites, while the
 * Forge/NeoForge-patched loaders add a 0-site delegating overload in front of
 * that same 2-site worker. {@code require = 2} is therefore the strict total
 * across all name-matched methods — satisfied on every supported loader, never
 * less — so a future mapping or structure drift (a lost or extra seed call
 * site) fails hard at mixin apply time instead of silently rendering the wrong
 * weighted model variant.
 *
 * <p>{@link BlockModelRenderSeed#resolve(long)} returns its argument
 * unchanged whenever the render-thread scope is inactive, so real block
 * displays, chunk building, and item renders stay vanilla — including the
 * caller's fallback constant 42, which is exactly what remains outside the
 * virtual display scope; only the virtual display dispatch runs inside
 * {@code runWithSeed(...)}.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {

	@ModifyArg(
		method = "renderModel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;setSeed(J)V"),
		index = 0,
		require = 2
	)
	private long pingForItResolveRenderSeed(long original) {
		return BlockModelRenderSeed.resolve(original);
	}
}
