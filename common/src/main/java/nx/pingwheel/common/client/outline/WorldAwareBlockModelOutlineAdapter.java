package nx.pingwheel.common.client.outline;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Internal loader-compatibility seam for world-aware baked-model outlines.
 *
 * <p>An adapter must perform its claim check in {@link #handles} before
 * dispatching anything from {@link #render}. The common renderer owns the
 * attempt-local buffer, vertex accounting, commit, and exception isolation;
 * an adapter only dispatches its world-aware model into the supplied outline
 * buffer and must not flush it. This is not an external plugin API.</p>
 */
public interface WorldAwareBlockModelOutlineAdapter {
	/** Stable namespaced id used for deterministic registration and diagnostics. */
	String id();

	/**
	 * Returns whether this adapter claims the supplied live target. This is
	 * called before the adapter receives an outline buffer.
	 */
	boolean handles(EntityBlockGeometryContext context);

	/**
	 * Dispatches the complete model into the common attempt-local outline
	 * buffer. The adapter must not flush the buffer or retain the context/buffer.
	 */
	void render(EntityBlockGeometryContext context, OutlineOnlyBufferSource buffer);

	/** Alias that makes the uniqueness contract explicit at call sites. */
	default String uniqueId() {
		return id();
	}

	/**
	 * Small internal adapter for focused tests and loader bridges. It does not
	 * create a lifecycle or registration API of its own.
	 */
	static WorldAwareBlockModelOutlineAdapter of(
		String id,
		Predicate<EntityBlockGeometryContext> handles,
		BiConsumer<EntityBlockGeometryContext, OutlineOnlyBufferSource> render
	) {
		String stableId = EntityBlockGeometrySourceIds.require(id);
		Objects.requireNonNull(handles, "handles");
		Objects.requireNonNull(render, "render");

		return new WorldAwareBlockModelOutlineAdapter() {
			@Override
			public String id() {
				return stableId;
			}

			@Override
			public boolean handles(EntityBlockGeometryContext context) {
				return handles.test(context);
			}

			@Override
			public void render(EntityBlockGeometryContext context, OutlineOnlyBufferSource buffer) {
				render.accept(context, buffer);
			}
		};
	}
}
