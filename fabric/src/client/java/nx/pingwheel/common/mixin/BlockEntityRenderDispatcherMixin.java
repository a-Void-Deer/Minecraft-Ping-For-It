package nx.pingwheel.common.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.client.outline.EntityBlockGlowState;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.EntityBlockRenderMode;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;

/**
 * M7c C2 (scheme A): publishes the ping colour of one block-entity dispatch.
 *
 * <p>{@code BlockEntityRenderDispatcher.submit} is the last point that still sees the
 * {@link BlockEntityRenderState} — and therefore {@code blockPos} — immediately before
 * the renderer hands its model to {@code SubmitNodeCollector.submitModel}, which carries
 * no block identity at all. The colour is resolved here (winner + block-display policy +
 * ping-type colour, the same rules as the M7a wireframe and M7b entity pass) and
 * published through {@link EntityBlockGlowState}, whose model-submit hook writes it into
 * {@code outlineColor}.
 *
 * <p>Upstream reached the same geometry through an outline-only buffer source, so the
 * coverage boundary is unchanged: model submits glow, {@code submitText} (sign text),
 * {@code submitItem} and {@code submitCustomGeometry} do not — those have no outline
 * channel, and sign text not glowing is the upstream look.
 *
 * <p>The {@code entity_block} render mode gates this source per attempt: under
 * {@code VOXEL_SHAPE_ONLY} no colour is resolved here, so the BER contributes nothing and
 * the baked-model source is gated identically.  This is one of several permitted sources,
 * not a short-circuit for the others.
 *
 * <p>{@code require = 0} on both injectors: if either descriptor changes, nothing is
 * applied, {@code entity_block} keeps its M7a wireframe and
 * {@link EntityBlockGlowState} reports the missing hook once instead of crashing.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

	private static final String TARGET_TYPE_ENTITY_BLOCK = "entity_block";

	private static final PingTypeCatalog PING_TYPES = PingTypeCatalog.builtIn();
	private static final int FALLBACK_COLOR = 0xFFFFFFFF;

	@Inject(method = "submit", at = @At("HEAD"), require = 0)
	private void pingforit$beginEntityBlockGlow(
		BlockEntityRenderState state,
		PoseStack poseStack,
		SubmitNodeCollector collector,
		CameraRenderState cameraRenderState,
		CallbackInfo ci
	) {
		EntityBlockGlowState.markDispatchHookApplied();

		Integer color = null;

		try {
			if (!EntityBlockGlowState.isDisabled()) {
				color = pingforit$resolveGlowColor(state);
			}
		} catch (RuntimeException | LinkageError failure) {
			EntityBlockGlowState.failOnce(
				"entity-block glow: dispatch hook failed; falling back to the wireframe for this session",
				failure);
		}

		// Always paired with restore() below, including the no-glow case, so a nested
		// dispatch can never be mistaken for the outer one.  The position is armed with the
		// colour: only a model submit that consumes it records a glow for that position.
		EntityBlockGlowState.begin(state == null ? null : state.blockPos, color);

		if (color != null) {
			EntityBlockGlowState.logActivationOnce();
		}
	}

	@Inject(method = "submit", at = @At("RETURN"), require = 0)
	private void pingforit$endEntityBlockGlow(
		BlockEntityRenderState state,
		PoseStack poseStack,
		SubmitNodeCollector collector,
		CameraRenderState cameraRenderState,
		CallbackInfo ci
	) {
		EntityBlockGlowState.restore();
	}

	/**
	 * Resolves the glow colour for one dispatch, or {@code null} when the block is not an
	 * {@code entity_block} winner, no longer matches the captured block, or the
	 * block-display policy routes it elsewhere.
	 */
	private static Integer pingforit$resolveGlowColor(BlockEntityRenderState state) {
		// The entity-block mode is client-local and read for every attempt
		// (geometry_sources.md): VOXEL_SHAPE_ONLY constructs no source context and attempts
		// no normal source, so the BER source publishes no colour and the position never
		// counts as glowing.  ALL and COMPATIBLE run the same built-in sources here; ALL's
		// optional-source registry is not wired until M9, so no extra branch for it.
		if (ClientConfig.HANDLER.getConfig().getEntityBlockRenderMode() == EntityBlockRenderMode.VOXEL_SHAPE_ONLY) {
			return null;
		}

		if (state == null || state.blockPos == null) {
			return null;
		}

		Minecraft game = Minecraft.getInstance();

		if (game == null || game.level == null) {
			return null;
		}

		var runtime = CommonClient.INSTANCE.getPingRuntime();

		if (runtime == null) {
			return null;
		}

		String dimensionId = game.level.dimension().identifier().toString();
		BlockPos pos = state.blockPos;
		ClientMarker marker = pingforit$findWinner(runtime.store(), dimensionId, pos);

		if (marker == null) {
			return null;
		}

		BlockState blockState = game.level.getBlockState(pos);

		if (blockState.isAir()) {
			return null;
		}

		if (!ClientConfig.HANDLER.getConfig().getBlockDisplayPolicy()
			.shouldUseNativeGlow(TARGET_TYPE_ENTITY_BLOCK, blockState)) {
			return null;
		}

		return PING_TYPES.findById(marker.pingTypeId())
			.map(pingType -> 0xFF000000 | pingType.outlineColor())
			.orElse(FALLBACK_COLOR);
	}

	/**
	 * The {@code entity_block} winner for {@code pos}. Mirrors the wireframe pass: the
	 * marker must still describe the block that is there, otherwise a replaced block would
	 * inherit an unrelated ping.
	 *
	 * <p>The winner snapshot itself is built once per frame by
	 * {@link EntityBlockGlowState#entityBlockWinners(ClientMarkerStore, String)}, so a frame
	 * with many block entities pays for one filter pass instead of one per dispatch.
	 */
	private static ClientMarker pingforit$findWinner(
		ClientMarkerStore store,
		String dimensionId,
		BlockPos pos
	) {
		if (store == null || pos == null) {
			return null;
		}

		Minecraft game = Minecraft.getInstance();

		if (game == null || game.level == null) {
			return null;
		}

		ClientMarker marker = EntityBlockGlowState.entityBlockWinners(store, dimensionId).get(pos);

		if (marker == null || !(marker.target() instanceof Target.BlockTarget block)) {
			return null;
		}

		String registryId = BuiltInRegistries.BLOCK
			.getKey(game.level.getBlockState(pos).getBlock()).toString();

		return registryId.equals(block.blockRegistryId()) ? marker : null;
	}
}
