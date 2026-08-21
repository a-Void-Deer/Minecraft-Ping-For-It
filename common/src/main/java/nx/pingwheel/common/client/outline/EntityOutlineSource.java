package nx.pingwheel.common.client.outline;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.world.entity.Entity;

/**
 * Internal entity-outline extension seam.
 *
 * <p>The source id must be a stable, namespaced Minecraft
 * {@code ResourceLocation} and unique within the modded entity-outline source
 * registry. This is an internal compatibility seam for a future separately
 * loaded optional adapter package (and for the loader-neutral entity outline
 * infrastructure), not a stable public plugin API; no API stability is
 * guaranteed. A source must not retain the supplied context.</p>
 *
 * <p>Each source decides independently whether it owns a given entity via
 * {@link #handles(Entity)}; the runner iterates the registry in registration
 * order, skips sources that do not handle the entity, and stops at the first
 * {@link EntityBlockGeometryOutcome#RENDERED} result. {@link #attempt} renders
 * into the shared {@code OutlineBufferSource} carried by the context using the
 * outline color of the selected {@link EntityOutlineSpec}.</p>
 */
public interface EntityOutlineSource {
	/** Stable unique id used for deterministic registration and diagnostics. */
	String id();

	/**
	 * Whether this source owns the outline for {@code entity}. Called on every
	 * entity the runner considers, before any geometry attempt; a throwing
	 * implementation fails soft (full diagnostic, treated as not-handling) in
	 * both the registry's {@code handlesAny} and the runner.
	 */
	boolean handles(Entity entity);

	/**
	 * Attempts to render this entity's outline into the shared outline buffer.
	 * The source must not retain the supplied context. A {@link
	 * EntityBlockGeometryOutcome#RENDERED} result is the source's honest
	 * responsibility: it is returned when geometry was emitted for the current
	 * frame. A shared-buffer commit may have emitted a partial mask that cannot
	 * be rolled back; that result still counts as rendered for this frame and
	 * is retried on the next frame.
	 */
	/**
	 * Source contract for shared-buffer commits: a source that catches a
	 * recoverable budget/commit exception after writing one or more vertices
	 * must return {@link EntityBlockGeometryOutcome#RENDERED}. With zero writes
	 * it may return {@link EntityBlockGeometryOutcome#FAILED}. The runner never
	 * inspects source buffers to reconstruct this decision.
	 */
	EntityBlockGeometryOutcome attempt(EntityOutlineContext context);

	/** Alias that makes the uniqueness contract explicit at call sites. */
	default String uniqueId() {
		return id();
	}

	/**
	 * Small internal adapter for built-ins and focused tests; it does not create
	 * a lifecycle or registration API of its own.
	 */
	static EntityOutlineSource of(
		String id,
		Predicate<Entity> handles,
		Function<EntityOutlineContext, EntityBlockGeometryOutcome> attempt
	) {
		String stableId = EntityBlockGeometrySourceIds.require(id);
		Objects.requireNonNull(handles, "handles");
		Objects.requireNonNull(attempt, "attempt");

		return new EntityOutlineSource() {
			@Override
			public String id() {
				return stableId;
			}

			@Override
			public boolean handles(Entity entity) {
				return handles.test(entity);
			}

			@Override
			public EntityBlockGeometryOutcome attempt(EntityOutlineContext context) {
				return attempt.apply(context);
			}
		};
	}
}
