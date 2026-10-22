package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
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
	private final @Unmodifiable List<EntityBlockGeometrySource> builtInSources;
	private final Set<String> warnedFailureKeys = new HashSet<>();
	private final @Nullable WarningSink warningSink;

	@FunctionalInterface
	interface WarningSink {
		void warn(String message, @Nullable Throwable failure);
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
			null);
	}

	EntityBlockGeometryRunner(
		EntityBlockGeometrySourceRegistry registry,
		EntityBlockGeometrySource blockEntityRendererSource,
		EntityBlockGeometrySource bakedModelSource,
		@Nullable WarningSink warningSink
	) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.builtInSources = List.of(
			Objects.requireNonNull(blockEntityRendererSource, "blockEntityRendererSource"),
			Objects.requireNonNull(bakedModelSource, "bakedModelSource"));
		this.warningSink = warningSink;
	}

	/**
	 * Runs one mode-selected attempt. The supplier is intentionally evaluated
	 * only after the VOXEL_SHAPE_ONLY early return.
	 */
	public boolean run(
		EntityBlockRenderMode mode,
		Supplier<EntityBlockGeometryContext> contextFactory
	) {
		return run(mode, contextFactory, (sourceId, outcome) -> {});
	}

	/**
	 * Runs one mode-selected attempt and reports the final outcome of every
	 * allowed source. Observer failures are intentionally fail-soft so optional
	 * presentation bookkeeping cannot suppress later permitted sources.
	 */
	public boolean run(
		EntityBlockRenderMode mode,
		Supplier<EntityBlockGeometryContext> contextFactory,
		BiConsumer<String, EntityBlockGeometryOutcome> outcomeObserver
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
		Objects.requireNonNull(outcomeObserver, "outcomeObserver");

		boolean rendered = false;
		for (EntityBlockGeometrySource source : allowedSources) {
			String sourceId = safeSourceId(source);
			@Nullable EntityBlockGeometryContext context = null;
			try {
				// A fresh context is important: a source must observe one immutable
				// attempt snapshot and must not retain live render state across calls.
				context = contextFactory.get();
			} catch (Exception | LinkageError | AssertionError failure) {
				warnOnce(
					"context-creation:" + sourceId,
					() -> "entity block geometry attempt failed; id=" + sourceId
						+ "; category=context-creation; context=<unavailable>",
					failure);
				reportOutcome(outcomeObserver, sourceId, EntityBlockGeometryOutcome.FAILED);
				continue;
			}

			if (context == null) {
				warnOnce(
					"null-context:" + sourceId,
					() -> "entity block geometry attempt failed; id=" + sourceId
						+ "; category=null-context; context=null",
					null);
				reportOutcome(outcomeObserver, sourceId, EntityBlockGeometryOutcome.FAILED);
				continue;
			}

			EntityBlockGeometryOutcome outcome = attempt(source, context);
			if (outcome == EntityBlockGeometryOutcome.RENDERED) {
				rendered = true;
			}
			reportOutcome(outcomeObserver, sourceId, outcome);
		}

		return rendered;
	}

	private void reportOutcome(
		BiConsumer<String, EntityBlockGeometryOutcome> outcomeObserver,
		String sourceId,
		EntityBlockGeometryOutcome outcome
	) {
		try {
			outcomeObserver.accept(sourceId, outcome);
		} catch (Exception | LinkageError | AssertionError failure) {
			// Presentation observers are auxiliary bookkeeping. A recoverable
			// observer fault must not alter source ordering or fallback behavior.
			warnOnce("observer:" + sourceId,
				() -> "entity block geometry observer failed; id=" + sourceId + "; outcome=" + outcome,
				failure);
		}
	}

	private EntityBlockGeometryOutcome attempt(
		EntityBlockGeometrySource source,
		EntityBlockGeometryContext context
	) {
		try {
			@Nullable EntityBlockGeometryOutcome outcome = source.attempt(context);
			if (outcome == null) {
				String sourceId = safeSourceId(source);
				warnOnce("null-outcome:" + sourceId,
					() -> "entity block geometry source returned null; id=" + sourceId + "; context=" + context, null);
				return EntityBlockGeometryOutcome.FAILED;
			}
			return outcome;
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

	void warnOnce(String key, Supplier<String> message, @Nullable Throwable failure) {
		if (warningSink == null && !OutlineDiagnostics.enabled()) {
			return;
		}
		if (!warnedFailureKeys.add(key)) {
			return;
		}
		if (warningSink == null) {
			OutlineDiagnostics.approved("entity-block-geometry", key, message, failure);
		} else {
			OutlineDiagnostics.deliver(text -> warningSink.warn(text, null), message, failure);
		}
	}

	private String safeSourceId(EntityBlockGeometrySource source) {
		try {
			@Nullable String id = EntityBlockGeometrySourceIds.validate(source.id());
			if (id == null) {
				warnOnce("invalid-id:" + source.getClass().getName(),
					() -> "entity block geometry source has invalid id; class=" + source.getClass().getName(), null);
			}
			return id == null ? EntityBlockGeometrySourceIds.INVALID : id;
		} catch (Exception | LinkageError | AssertionError failure) {
			warnOnce("source-id:" + source.getClass().getName(),
				() -> "entity block geometry source id failed; class=" + source.getClass().getName(), failure);
			return EntityBlockGeometrySourceIds.UNAVAILABLE;
		}
	}
}
