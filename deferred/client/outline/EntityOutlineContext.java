package nx.pingwheel.common.client.outline;

import java.util.Objects;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable, per-attempt data passed to one {@link EntityOutlineSource} for a
 * live selected entity.
 *
 * <p>This is an internal compatibility seam for the loader-neutral entity
 * outline infrastructure, not a stable public plugin API; no API stability is
 * guaranteed. It contains only the live entity and the render data a source
 * needs: the client level, the canonical live entity whose outline should be
 * drawn, the selected {@link EntityOutlineSpec} (marker, locator, ping type,
 * opaque ARGB color), the camera position for camera-relative geometry, the
 * frame partial tick, the monotonic frame id, and the shared vanilla
 * {@link OutlineBufferSource} the source writes silhouette geometry into
 * (flushed by the vanilla {@code endOutlineBatch()} call). It deliberately
 * contains no marker ownership, network, or other protocol state, and has no
 * dependency on Create, Flywheel, or another optional mod.</p>
 *
 * <p>Live game references may be {@code null} when a source is being tested;
 * production passes a live level and the shared outline buffer. The runner
 * creates this value only for a resolved entity and passes it for the duration
 * of one invocation; sources must not retain it.</p>
 */
public record EntityOutlineContext(
	ClientLevel level,
	Entity entity,
	EntityOutlineSpec spec,
	Vec3 cameraPosition,
	float partialTick,
	long frameId,
	OutlineBufferSource outlineBuffer
) {
	public EntityOutlineContext {
		Objects.requireNonNull(entity, "entity");
		Objects.requireNonNull(spec, "spec");
		cameraPosition = cameraPosition == null ? Vec3.ZERO : cameraPosition;
	}

	/** Minimal context useful to headless tests that only track invocation policy. */
	public static EntityOutlineContext empty(Entity entity, EntityOutlineSpec spec) {
		return new EntityOutlineContext(null, entity, spec, Vec3.ZERO, 0.0F, 0L, null);
	}
}
