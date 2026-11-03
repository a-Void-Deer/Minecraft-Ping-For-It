package nx.pingwheel.common.client.outline;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.marker.TargetKey;

/**
 * Immutable, per-attempt data passed to an entity-block geometry source.
 *
 * <p>This is an internal compatibility seam for a future separately-loaded
 * optional adapter package, not a stable public plugin API; no API stability
 * is guaranteed. It contains only the live
 * target and render data such an adapter may need: level, position, state,
 * block entity when available, public outline color, camera/timing/light
 * values, the level renderer used for exact frame-time parity, the frozen
 * target key, render target type, current frame id, and the vanilla render dispatchers. It deliberately contains no
 * marker ownership, network, or other protocol state, and has no dependency
 * on Create, Flywheel, or another optional mod.</p>
 *
 * <p>Live game references may be {@code null} when a source is being tested or
 * when a target component is unavailable. The runner creates this value only
 * for modes that allow geometry attempts and passes it for the duration of
 * one invocation; sources must not retain it.</p>
 */
public record EntityBlockGeometryContext(
	ClientLevel level,
	BlockPos blockPos,
	BlockState blockState,
	BlockEntity blockEntity,
	int argbColor,
	Vec3 cameraPosition,
	float partialTick,
	float flywheelPartialTick,
	int packedLight,
	EntityRenderDispatcher entityRenderDispatcher,
	BlockEntityRenderDispatcher blockEntityRenderDispatcher,
	LevelRenderer levelRenderer,
	TargetKey targetKey,
	String renderTargetTypeId,
	long frameId,
	EntityBlockGeometryTransform transform
) {
	public EntityBlockGeometryContext {
		cameraPosition = cameraPosition == null ? Vec3.ZERO : cameraPosition;
		argbColor = 0xFF000000 | (argbColor & 0x00FFFFFF);
		if (renderTargetTypeId != null && renderTargetTypeId.isBlank()) {
			throw new IllegalArgumentException("renderTargetTypeId must not be blank");
		}
	}

	/**
	 * Minimal context useful to headless tests and to sources that only track
	 * invocation policy.
	 */
	public static EntityBlockGeometryContext empty() {
		return new EntityBlockGeometryContext(
			null,
			null,
			null,
			null,
			0xFFFFFFFF,
		Vec3.ZERO,
		0.0F,
		0.0F,
		0,
			null,
			null,
			null,
			null,
			null,
		0L,
			null);
	}
}
