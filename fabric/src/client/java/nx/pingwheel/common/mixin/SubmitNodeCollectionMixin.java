package nx.pingwheel.common.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.ShapeOutlineFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.world.phys.shapes.VoxelShape;

import nx.pingwheel.common.client.outline.EntityBlockGlowState;
import nx.pingwheel.common.client.outline.OutlineRoutingState;
import nx.pingwheel.common.client.outline.PingOutlineRenderType;

/**
 * M7-prep E2, decision D2: bucket routing for the custom outline render type.
 *
 * <p>{@code SubmitNodeCollection.submitShapeOutline} sends every opaque-alpha colour
 * (alpha == 255) to the {@code solid} phase and ignores the {@code afterTerrain}
 * argument entirely, so a custom render type can never reach the after-terrain
 * bucket through the vanilla path. This mixin intercepts only our own render type
 * (identity comparison) and submits it to the requested bucket directly; every other
 * submission is left untouched and keeps vanilla routing.
 *
 * <p>{@code require = 0}: if the target method is absent or its descriptor changes in
 * a future version, the mixin silently does nothing and the outline simply falls back
 * to the vanilla solid bucket instead of crashing the client.
 */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionMixin {

	@Shadow
	@Final
	public SimpleFeatureRenderPhase solid;

	@Shadow
	@Final
	public SimpleFeatureRenderPhase afterTerrain;

	@Shadow
	@Final
	public SimpleFeatureRenderPhase outline;

	@Inject(method = "submitShapeOutline", at = @At("HEAD"), cancellable = true, require = 0)
	private void pingforit$routeCustomOutline(
		PoseStack poseStack,
		VoxelShape shape,
		RenderType renderType,
		int color,
		float width,
		boolean afterTerrainFlag,
		CallbackInfo ci
	) {
		boolean customRenderType = PingOutlineRenderType.isCustom(renderType);

		if (!customRenderType) {
			return;
		}

		ShapeOutlineFeatureRenderer.Submit submit =
			new ShapeOutlineFeatureRenderer.Submit(poseStack.last().copy(), shape, renderType, color, width);

		if (!afterTerrainFlag) {
			this.solid.submit(submit);
		} else if (pingforit$classicTransparencyActive()) {
			this.afterTerrain.submit(submit);
		} else {
			// MC 26.3 只在“经典半透明”路径里执行 afterTerrain 相位：PreparedFrame.executeTranslucentAfterTerrain
			// 的唯一调用者是 LevelRenderer.executeClassicTransparency，而它在 improvedTransparency 打开时
			// 整个被跳过（lambda$addMainPass$0 的 178/187 分支）。此时退到 outline 相位 —— 它排在 OIT
			// 之后、由 hasAnyOutline() && shouldShowEntityOutlines 触发，语义最接近上游“最后画、穿过方块”。
			this.outline.submit(submit);
			OutlineRoutingState.noteImprovedTransparencyFallback();
		}
		OutlineRoutingState.markRouted();
		ci.cancel();
	}

	/**
	 * M7c C2: writes the colour resolved by {@code BlockEntityRenderDispatcherMixin}
	 * into the model's outline channel.
	 *
	 * <p>Every {@code submitModel} overload collapses into this 9-argument method (the
	 * {@code default} overloads invoke it directly and {@code submitModelPart} builds a
	 * {@code Model.Simple} for it), so one variable hook covers both ordinary models and
	 * model parts. The last argument is {@code outlineColor} at local slot 9 of an
	 * instance method ({@code this} occupies slot 0), which is exactly the value vanilla
	 * tests at bytecode offset 100 ({@code iload 9 / ifeq}).
	 *
	 * <p>{@code require = 0}: should the slot ever move, the hook silently stops applying
	 * and block entities keep their un-glowing model, never a crash.
	 */
	@ModifyVariable(method = "submitModel", at = @At("HEAD"), argsOnly = true, index = 9, require = 0)
	private int pingforit$applyEntityBlockGlow(int outlineColor) {
		return EntityBlockGlowState.currentColorOr(outlineColor);
	}

	/**
	 * True when vanilla will run the classic translucent path this frame, i.e. when the
	 * {@code afterTerrain} phase is actually executed.
	 *
	 * <p>{@code improvedTransparency} is read from {@link Minecraft#options}, which is the
	 * authoritative value and available at any time. Only {@code renderWireframeTerrain} is
	 * read from the render state, and a missing render state is treated as {@code false}
	 * (i.e. "follow the option") instead of degrading the whole decision to classic — the
	 * previous version treated a not-yet-populated {@code optionsRenderState} as classic,
	 * which could silently keep rerouting off even when the option was on.
	 *
	 * <p>Vanilla's rule ({@code GameRenderer#useImprovedTransparency}) still needs both:
	 * improved transparency on <em>and</em> terrain wireframe off. With wireframe terrain on,
	 * vanilla keeps executing the classic translucent path, so the outline must stay in the
	 * {@code afterTerrain} bucket. When the client itself is gone we assume classic (the safer
	 * bucket, it also runs with improved transparency off).
	 */
	private static boolean pingforit$classicTransparencyActive() {
		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft == null || minecraft.gameRenderer == null) {
			return true;
		}

		boolean improvedTransparency = minecraft.options != null
			&& minecraft.options.improvedTransparency().get();
		GameRenderState renderState = minecraft.gameRenderer.gameRenderState();
		boolean renderWireframeTerrain = renderState != null
			&& renderState.levelRenderState != null
			&& renderState.levelRenderState.renderWireframeTerrain;

		return !improvedTransparency || renderWireframeTerrain;
	}
}