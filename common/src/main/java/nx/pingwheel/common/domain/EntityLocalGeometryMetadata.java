package nx.pingwheel.common.domain;

import java.util.Objects;
import java.util.Optional;

import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.math.LocalGeometryKind;

/**
 * Immutable capture metadata for a refined local block or fluid hit on an
 * entity owned by an optional loader integration.
 *
 * <p>This is deliberately a plain domain value. It does not retain an entity,
 * block state, level, shape, or optional-mod object, and it does not alter the
 * entity target's existing stable dimension/locator identity.</p>
 */
public record EntityLocalGeometryMetadata(
	String sourceId,
	LocalGeometryKind kind,
	int localX,
	int localY,
	int localZ,
	String expectedBlockRegistryId,
	Optional<String> expectedFluidRegistryId,
	WorldVector localWorldVector,
	WorldVector worldWorldVector
) {

	public EntityLocalGeometryMetadata {
		requireIdentifier(sourceId, "sourceId");
		Objects.requireNonNull(kind, "kind");
		requireIdentifier(expectedBlockRegistryId, "expectedBlockRegistryId");
		Objects.requireNonNull(expectedFluidRegistryId, "expectedFluidRegistryId");
		expectedFluidRegistryId.ifPresent(id -> requireIdentifier(id, "expectedFluidRegistryId"));
		Objects.requireNonNull(localWorldVector, "localWorldVector");
		Objects.requireNonNull(worldWorldVector, "worldWorldVector");

		if ((kind == LocalGeometryKind.FLUID) != expectedFluidRegistryId.isPresent()) {
			throw new IllegalArgumentException("only a fluid local hit carries an expected fluid registry id");
		}
	}

	private static void requireIdentifier(String value, String name) {
		Objects.requireNonNull(value, name);

		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}
