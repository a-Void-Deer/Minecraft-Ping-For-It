package nx.pingwheel.common.client.outline;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import nx.pingwheel.common.Global;

/**
 * Runs the registered entity-outline sources for one resolved live entity.
 *
 * <p>The registry is iterated in its deterministic registration order. A
 * source that does not {@link EntityOutlineSource#handles} the entity is
 * skipped. The first {@link EntityBlockGeometryOutcome#RENDERED} result
 * short-circuits immediately (a modded source owning the entity fully
 * controls its outline for this frame); {@code EMPTY} and {@code FAILED}
 * results and recoverable throwing sources continue to the next source.
 * Fatal JVM {@link Error}s always propagate.</p>
 *
 * <p>This runner is an internal compatibility seam for the loader-neutral
 * entity-outline infrastructure, not a stable public API; no API stability is
 * guaranteed. Sources must not retain the per-attempt context. Every call
 * retries every source — a source that failed this frame is attempted again
 * on the next frame; only the warning log is rate-limited per source and
 * failure category, and the warning message supplier is evaluated lazily so a
 * suppressed warning never pays for building its detailed diagnostic.</p>
 */
public final class EntityOutlineRunner {
	private final EntityOutlineSourceRegistry registry;
	private final Set<String> warnedFailureKeys = ConcurrentHashMap.newKeySet();
	private final WarningSink warningSink;

	@FunctionalInterface
	interface WarningSink {
		void warn(String message, Throwable failure);
	}

	/**
	 * Creates a runner over the given registry, typically the production
	 * {@link EntityOutlineSourceRegistry#INSTANCE}.
	 */
	public EntityOutlineRunner(EntityOutlineSourceRegistry registry) {
		this(registry, EntityOutlineRunner::warnGlobally);
	}

	EntityOutlineRunner(EntityOutlineSourceRegistry registry, WarningSink warningSink) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
	}

	/**
	 * Runs one attempt for {@code context}'s live entity.
	 *
	 * @return {@link EntityBlockGeometryOutcome#RENDERED} when a source emitted
	 *         geometry (and the first such source short-circuited the rest);
	 *         {@link EntityBlockGeometryOutcome#FAILED} when no source rendered
	 *         but at least one failed; {@link EntityBlockGeometryOutcome#EMPTY}
	 *         when every handled source was empty or none handled the entity.
	 */
	public EntityBlockGeometryOutcome run(EntityOutlineContext context) {
		Objects.requireNonNull(context, "context");

		boolean anyFailed = false;
		for (EntityOutlineSource source : registry.snapshot()) {
			if (!handlesSafely(source, context)) {
				continue;
			}

			EntityBlockGeometryOutcome outcome = attemptSafely(source, context);
			if (outcome == EntityBlockGeometryOutcome.RENDERED) {
				return EntityBlockGeometryOutcome.RENDERED;
			}
			if (outcome == EntityBlockGeometryOutcome.FAILED) {
				anyFailed = true;
			}
		}

		return anyFailed ? EntityBlockGeometryOutcome.FAILED : EntityBlockGeometryOutcome.EMPTY;
	}

	private boolean handlesSafely(EntityOutlineSource source, EntityOutlineContext context) {
		try {
			return source.handles(context.entity());
		} catch (Exception | LinkageError | AssertionError failure) {
			String sourceId = safeSourceId(source);
			warnOnce(
				"handles:" + sourceId,
				() -> "entity outline source handles() failed; id=" + sourceId
					+ "; category=handles; context=" + context,
				failure);
			return false;
		}
	}

	private EntityBlockGeometryOutcome attemptSafely(
		EntityOutlineSource source,
		EntityOutlineContext context
	) {
		try {
			EntityBlockGeometryOutcome outcome = source.attempt(context);
			return outcome == null ? EntityBlockGeometryOutcome.FAILED : outcome;
		} catch (Exception | LinkageError | AssertionError failure) {
			String sourceId = safeSourceId(source);
			warnOnce(
				"attempt:" + sourceId,
				() -> "entity outline source attempt failed; id=" + sourceId
					+ "; category=attempt; context=" + context,
				failure);
			return EntityBlockGeometryOutcome.FAILED;
		}
	}

	/**
	 * Rate-limited warning: the message supplier is only evaluated for a
	 * category that has not been warned before, so a broken source cannot
	 * spam per-frame logs while still being retried on every call.
	 */
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

	private static String safeSourceId(EntityOutlineSource source) {
		try {
			String id = EntityBlockGeometrySourceIds.validate(source.id());
			return id == null ? EntityBlockGeometrySourceIds.INVALID : id;
		} catch (Exception | LinkageError | AssertionError failure) {
			return EntityBlockGeometrySourceIds.UNAVAILABLE;
		}
	}
}
