package nx.pingwheel.common.client.outline;

import java.util.Objects;
import java.util.function.Function;

/**
 * Internal entity-block geometry extension seam.
 *
 * <p>The source id must be a stable, namespaced Minecraft
 * {@code ResourceLocation} and unique within the modded source registry. This
 * is an internal compatibility seam for a future separately-loaded optional
 * adapter package, not a stable public plugin API; no API stability is
 * guaranteed. A source must not retain the supplied context.</p>
 */
public interface EntityBlockGeometrySource {
	/** Stable unique id used for deterministic registration and diagnostics. */
	String id();

	/**
	 * Attempts to render this target into the source's own isolated geometry
	 * route. The source must not retain the supplied context. A {@link
	 * EntityBlockGeometryOutcome#RENDERED} result is the source's honest
	 * responsibility: a source returns it when geometry was emitted for the
	 * current frame. A shared-buffer commit may have emitted a partial mask that
	 * cannot be rolled back; that result still suppresses the VoxelShape fallback
	 * for this frame and is retried on the next frame. A source may render
	 * through any backend allowed by the current render pass.
	 */
	EntityBlockGeometryOutcome attempt(EntityBlockGeometryContext context);

	/** Alias that makes the uniqueness contract explicit at call sites. */
	default String uniqueId() {
		return id();
	}

	/**
	 * Small internal adapter for built-ins and focused tests; it does not create
	 * a lifecycle or registration API of its own.
	 */
	static EntityBlockGeometrySource of(
		String id,
		Function<EntityBlockGeometryContext, EntityBlockGeometryOutcome> attempt
	) {
		String stableId = EntityBlockGeometrySourceIds.require(id);
		Objects.requireNonNull(attempt, "attempt");

		return new EntityBlockGeometrySource() {
			@Override
			public String id() {
				return stableId;
			}

			@Override
			public EntityBlockGeometryOutcome attempt(EntityBlockGeometryContext context) {
				return attempt.apply(context);
			}
		};
	}
}
