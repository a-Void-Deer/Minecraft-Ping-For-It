package nx.pingwheel.common.mixin;

import net.minecraft.client.renderer.block.ModelBlockRenderer;
import nx.pingwheel.common.client.outline.BlockModelRenderSeed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

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
 * chunk-oriented {@code tesselateBlock} path. The vanilla worker hard-codes
 * its variant-selection seed as the long constant {@code 42}, which appears
 * in exactly three {@code 42L} long constants class-wide: one never-read
 * local, and one per {@code RandomSource#setSeed} call (inside the per-face
 * quad loop and before the face-less quad list), so the substitution happens
 * on the constants themselves via
 * {@link BlockModelRenderSeed#resolve(long)}.
 *
 * <p>The injection is deliberately fully remap-independent: it matches the
 * bytecode {@code ldc2_w 42} constants, which are identical under every
 * loader mapping (Fabric intermediary, Forge/NeoForge official-dev, and the
 * production SRG names), it uses the wildcard method selector
 * {@code method = "*"} so no target-method name has to resolve, and it never
 * references an invocation owner/member name. Name-based selection proved
 * unreliable in practice: on the NeoForge 21.1.248 dev runtime the
 * name-only {@code renderModel} selector matched zero methods even though
 * the target class carries the worker, and on the NeoForge SRG production
 * runtime an {@code INVOKE} selector written with the official
 * {@code RandomSource#setSeed} name resolves zero sites
 * ({@code setSeed} is {@code m_188584_} there), failing the mixin apply with
 * {@code (0/2)}. A constant-plus-wildcard selector has neither dependency:
 * on every supported loader the only {@code 42L} long constants in the whole
 * class are exactly those three — the two behaviorally relevant
 * {@code RandomSource#setSeed} seed values and the never-read dead local —
 * so matching every method by constant value is equivalent to matching the
 * worker.
 *
 * <p>{@code require = 2} is a minimum guard, not a per-site proof: it does
 * not separately verify each of the two {@code setSeed} constants, only that
 * at least two of the three class-wide {@code 42L} constants still match. It
 * therefore fails hard at mixin apply time only when fewer than two total
 * matching sites remain (for example, if both {@code setSeed} constants
 * drifted away and only the dead local or nothing was left) instead of
 * silently rendering the wrong weighted model variant; a drift that removed
 * just one seed site would still leave two matching constants and is beyond
 * this guard's reach. Matching the dead local too is harmless —
 * it is never read, and outside the render-thread scope
 * {@link BlockModelRenderSeed#resolve(long)} returns its argument unchanged
 * anyway.
 *
 * <p>{@link BlockModelRenderSeed#resolve(long)} returns its argument
 * unchanged whenever the render-thread scope is inactive, so real block
 * displays, chunk building, and item renders stay vanilla — including the
 * fallback constant 42, which is exactly what remains outside the virtual
 * display scope; only the virtual display dispatch runs inside
 * {@code runWithSeed(...)}.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {

	@ModifyConstant(
		method = "*",
		constant = @Constant(longValue = 42L),
		require = 2
	)
	private long pingForItResolveRenderSeed(long original) {
		return BlockModelRenderSeed.resolve(original);
	}
}
