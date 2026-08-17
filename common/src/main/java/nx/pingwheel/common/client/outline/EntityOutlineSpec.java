package nx.pingwheel.common.client.outline;

import java.util.UUID;

import java.util.Objects;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.EntityLocator;

/**
 * An immutable outline specification for one pinged entity.
 *
 * <p>A spec carries the authoritative marker that currently controls the
	 * visible outline, the entity {@link EntityLocator}; movement and
	 * same-dimension teleports never change it), the ping type id the color
 * was resolved from, and the fully opaque ARGB outline color.
 *
 * <p>The compact constructor is strict: every reference must be non-null and
 * the ping type id must not be blank. The color is forced opaque via
 * {@code 0xFF000000 | (color & 0x00FFFFFF)}, discarding any caller-supplied
 * alpha, so a call site can never produce a transparent outline.
 */
public record EntityOutlineSpec(
	MarkerId markerId,
	EntityLocator locator,
	String pingTypeId,
	int argbColor
) {

	public EntityOutlineSpec {
		Objects.requireNonNull(markerId, "markerId");
		Objects.requireNonNull(locator, "locator");
		Objects.requireNonNull(pingTypeId, "pingTypeId");

		if (pingTypeId.isBlank()) {
			throw new IllegalArgumentException("pingTypeId must not be blank");
		}

		argbColor = 0xFF000000 | (argbColor & 0x00FFFFFF);
	}

	/** UUID convenience constructor for the existing UUID-only render edge. */
	public EntityOutlineSpec(MarkerId markerId, UUID entityId, String pingTypeId, int argbColor) {
		this(markerId, EntityLocator.uuid(entityId), pingTypeId, argbColor);
	}
}
