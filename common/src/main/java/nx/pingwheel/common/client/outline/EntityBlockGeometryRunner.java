package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import nx.pingwheel.common.Global;
import nx.pingwheel.common.config.EntityBlockRenderMode;

/**
 * Runs the fixed and optional entity-block geometry sources for one outline
 * attempt.
 *
 * <p>The built-in list is always exactly BER first, then baked model. In
 * {@link EntityBlockRenderMode#ALL}, one immutable modded-registry snapshot is
 * appended after those built-ins. {@link EntityBlockRenderMode#COMPATIBLE}
 * skips that snapshot, and {@link EntityBlockRenderMode#VOXEL_SHAPE_ONLY}
 * returns before constructing a context or invoking any source. Attempts are
 * deliberately non-short-circuiting: only {@link
 * EntityBlockGeometryOutcome#RENDERED} contributes to the return value.</p>
 *
 * <p>This runner is an internal compatibility seam for a future
 * separately-loaded optional adapter package, not a stable public API; no API
 * stability is guaranteed. Sources must not retain the per-attempt context.
 * Generic runner failures are logged once per source and failure category with
 * the complete available context and original throwable; the once-set keeps a
 * bad source from producing per-frame log spam.</p>
 */
public final class EntityBlockGeometryRunner {
	public static final String BLOCK_ENTITY_RENDERER_SOURCE_ID =
		"pingforit:block_entity_renderer";
	public static final String BAKED_MODEL_SOURCE_ID = "pingforit:baked_model";

	private final EntityBlockGeometrySourceRegistry registry;
	private final List<EntityBlockGeometrySource> builtInSources;
	private final Set<String> warnedFailureKeys = new HashSet<>();
	private final WarningSink warningSink;

	@FunctionalInterface
	interface WarningSink {
		void warn(String message, Throwable failure);
	}

	/**
	 * Creates a runner whose fixed sources are supplied explicitly. The
	 * explicit constructor keeps built-ins out of the modded registry and also
	 * makes the orchestration seam headlessly testable.
	 */
	public EntityBlockGeometryRunner(
		EntityBlockGeometrySourceRegistry registry,
		EntityBlockGeometrySource blockEntityRendererSource,
		EntityBlockGeometrySource bakedModelSource
	) {
		this(registry, blockEntityRendererSource, bakedModelSource,
			EntityBlockGeometryRunner::warnGlobally);
	}

	EntityBlockGeometryRunner(
		EntityBlockGeometrySourceRegistry registry,
		EntityBlockGeometrySource blockEntityRendererSource,
		EntityBlockGeometrySource bakedModelSource,
		WarningSink warningSink
	) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.builtInSources = List.of(
			Objects.requireNonNull(blockEntityRendererSource, "blockEntityRendererSource"),
			Objects.requireNonNull(bakedModelSource, "bakedModelSource"));
		this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
	}

	/**
	 * Runs one mode-selected attempt. The supplier is intentionally evaluated
	 * only after the VOXEL_SHAPE_ONLY early return.
	 */
	public boolean run(
		EntityBlockRenderMode mode,
		Supplier<EntityBlockGeometryContext> contextFactory
	) {
		EntityBlockRenderMode effectiveMode = EntityBlockRenderMode.effective(mode);

		if (effectiveMode == EntityBlockRenderMode.VOXEL_SHAPE_ONLY) {
			return false;
		}

		List<EntityBlockGeometrySource> allowedSources = new ArrayList<>(builtInSources);
		if (effectiveMode == EntityBlockRenderMode.ALL) {
			// Acquire exactly one immutable snapshot for this invocation, after
			// deciding that modded sources are allowed.
			allowedSources.addAll(registry.snapshot());
		}

		Objects.requireNonNull(contextFactory, "contextFactory");

		boolean rendered = false;
		for (EntityBlockGeometrySource source : allowedSources) {
			EntityBlockGeometryContext context = null;
			try {
				// A fresh context is important: a source must observe one immutable
				// attempt snapshot and must not retain live render state across calls.
				context = contextFactory.get();
			} catch (Exception | LinkageError | AssertionError failure) {
				String sourceId = safeSourceId(source);
				warnOnce(
					"context-creation:" + sourceId,
					() -> "entity block geometry attempt failed; id=" + sourceId
						+ "; category=context-creation; context=<unavailable>",
					failure);
				continue;
			}

			if (context == null) {
				String sourceId = safeSourceId(source);
				warnOnce(
					"null-context:" + sourceId,
					() -> "entity block geometry attempt failed; id=" + sourceId
						+ "; category=null-context; context=null",
					null);
				continue;
			}

			EntityBlockGeometryOutcome outcome = attempt(source, context);
			if (outcome == EntityBlockGeometryOutcome.RENDERED) {
				rendered = true;
			}
		}

		return rendered;
	}

	private EntityBlockGeometryOutcome attempt(
		EntityBlockGeometrySource source,
		EntityBlockGeometryContext context
	) {
		try {
			EntityBlockGeometryOutcome outcome = source.attempt(context);
			return outcome == null ? EntityBlockGeometryOutcome.FAILED : outcome;
		} catch (Exception | LinkageError | AssertionError failure) {
			String sourceId = safeSourceId(source);
			warnOnce(
				"attempt:" + sourceId,
				() -> "entity block geometry source failed; id=" + sourceId
					+ "; category=attempt; context=" + context,
				failure);
			return EntityBlockGeometryOutcome.FAILED;
		}
	}

	void warnOnce(String key, Supplier<String> message, Throwable failure) {
		if (!warnedFailureKeys.add(key)) {
			return;
		}
		warningSink.warn(message.get(), failure);
	}

	private static void warnGlobally(String message, Throwable failure) {
		if (failure == null) {
			Global.LOGGER.warn(message);
		} else {
			Global.LOGGER.warn(message, failure);
		}
	}

	private static String safeSourceId(EntityBlockGeometrySource source) {
		try {
			String id = EntityBlockGeometrySourceIds.validate(source.id());
			return id == null ? EntityBlockGeometrySourceIds.INVALID : id;
		} catch (Exception | LinkageError | AssertionError failure) {
			return EntityBlockGeometrySourceIds.UNAVAILABLE;
		}
	}
}
