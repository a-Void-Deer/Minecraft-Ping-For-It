package nx.pingwheel.common.math;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * An exact native-shape hit in an owned entity's local coordinate system.
 *
 * <p>{@code t} is normalized against the owner adapter's local segment. The
 * adapter must use the same affine segment parameter as the original request;
 * the common raycast validates it and reconstructs the selected world point
 * from the original segment.</p>
 */
public record LocalGeometryHit(
	double t,
	BlockPos localPos,
	LocalGeometryKind kind,
	BlockState state,
	String blockRegistryId,
	Optional<String> fluidRegistryId,
	Vec3 localPoint
) {

	public LocalGeometryHit {
		if (!Double.isFinite(t) || t < 0.0 || t >= 1.0) {
			throw new IllegalArgumentException("t must be finite and in [0, 1)");
		}

		Objects.requireNonNull(localPos, "localPos");
		localPos = new BlockPos(localPos.getX(), localPos.getY(), localPos.getZ());
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(state, "state");
		requireIdentifier(blockRegistryId, "blockRegistryId");
		Objects.requireNonNull(fluidRegistryId, "fluidRegistryId");
		fluidRegistryId.ifPresent(id -> requireIdentifier(id, "fluidRegistryId"));
		requireFinite(localPoint, "localPoint");

		if ((kind == LocalGeometryKind.FLUID) != fluidRegistryId.isPresent()) {
			throw new IllegalArgumentException("fluid registry id must be present if and only if the local hit kind is FLUID");
		}
	}

	private static void requireIdentifier(String value, String name) {
		Objects.requireNonNull(value, name);

		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

	private static void requireFinite(Vec3 value, String name) {
		Objects.requireNonNull(value, name);

		if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
			throw new IllegalArgumentException(name + " must contain only finite coordinates");
		}
	}
}
