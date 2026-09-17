package nx.pingwheel.common.math;

import java.util.Objects;

import net.minecraft.world.entity.Entity;

/**
 * The local native-geometry result selected for an owned entity.
 */
public record EntityLocalHit(Entity owner, String sourceId, LocalGeometryHit localGeometryHit) {

	public EntityLocalHit {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(sourceId, "sourceId");

		if (sourceId.isBlank()) {
			throw new IllegalArgumentException("sourceId must not be blank");
		}

		Objects.requireNonNull(localGeometryHit, "localGeometryHit");
	}

	public double t() {
		return localGeometryHit.t();
	}

	/** Transient local geometry is valid only for the exact entity instance it refined. */
	public boolean belongsTo(Entity entity) {
		return owner == entity;
	}
}
