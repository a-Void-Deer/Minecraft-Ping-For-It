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
 * stability is guaranteed. Sources must not retain the per-attempt context.</p>
 */
public final class EntityBlockGeometryRunner {
	public static final String BLOCK_ENTITY_RENDERER_SOURCE_ID =
		"pingforit:block_entity_renderer";
	public static final String BAKED_MODEL_SOURCE_ID = "pingforit:baked_model";

	private final EntityBlockGeometrySourceRegistry registry;
	private final List<EntityBlockGeometrySource> builtInSources;
	private final Set<String> warnedSourceIds = new HashSet<>();

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
		this.registry = Objects.requireNonNull(registry, "registry");
		this.builtInSources = List.of(
			Objects.requireNonNull(blockEntityRendererSource, "blockEntityRendererSource"),
			Objects.requireNonNull(bakedModelSource, "bakedModelSource"));
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
		final EntityBlockGeometryContext context;
		try {
			context = contextFactory.get();
		} catch (Exception | LinkageError | AssertionError failure) {
			Global.warnException(
				"entity block geometry attempt failed; category=context-creation",
				failure);
			return false;
		}

		if (context == null) {
			Global.LOGGER.warn("entity block geometry attempt failed; category=null-context");
			return false;
		}

		boolean rendered = false;
		for (EntityBlockGeometrySource source : allowedSources) {
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
			if (warnedSourceIds.add(sourceId)) {
				Global.warnException(
					"entity block geometry source failed; id=" + sourceId + "; category=attempt",
					failure);
			}
			return EntityBlockGeometryOutcome.FAILED;
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
