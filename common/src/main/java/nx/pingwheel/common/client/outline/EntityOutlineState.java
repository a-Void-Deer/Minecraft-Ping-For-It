package nx.pingwheel.common.client.outline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.domain.PingTypeCatalog;

/**
 * Main-thread-confined snapshot of the entities that currently control a
 * visible outline, prepared once per world render frame before the vanilla
 * entity pass.
 *
 * <p>Every frame {@link #prepare} re-selects the entity outline specs from the
 * authoritative {@link ClientMarkerStore} visible-winner map of the current
 * dimension and swaps the immutable UUID→spec snapshot, preserving
 * {@link EntityOutlineSelection#select}'s deterministic ascending marker-id
 * iteration order. The snapshot carries no live position, so movement and
 * same-dimension teleports keep the same entity identity. The vanilla outline
 * renderer resolves the entity position and shape itself.
 *
 * <p>The render redirects in {@code LevelRendererMixin} query this state per
 * entity via {@link #shouldOutline} and {@link #colorFor}; no per-entity
 * render logging happens anywhere in this class.
 *
 * <p>Logging: only snapshot transitions are debug logged — the aggregate
 * counts {@code added/removed/changed/total} — never any UUID, dimension,
 * color, position, or name. A repeated identical prepare emits no log. The
 * default logger is a noop; production installs the lazy global logger via
 * {@link #setLogger}.
 *
 * <p>Thread safety: main-thread-confined, same as the {@link ClientMarkerStore}
 * it mirrors. Concurrent access is unsupported.
 */
public final class EntityOutlineState {

	public static final EntityOutlineState INSTANCE = new EntityOutlineState();

	private static final PingTypeCatalog BUILT_IN_CATALOG = PingTypeCatalog.builtIn();

	private static volatile EntityOutlineLogger logger = EntityOutlineLogger.noop();

	private Map<UUID, EntityOutlineSpec> specs = Map.of();

	private EntityOutlineState() {}

	/**
	 * Re-selects the entity outline specs for {@code dimensionId} from
	 * {@code store} and, when the snapshot changed, replaces it and logs the
	 * transition counts.
	 *
	 * <p>With a {@code null} store or dimension (no live runtime/level, or
	 * after leaving the server) the state is cleared instead.
	 */
	public void prepare(ClientMarkerStore store, String dimensionId) {
		if (store == null || dimensionId == null) {
			clear();
			return;
		}

		Map<UUID, EntityOutlineSpec> next = EntityOutlineSelection.select(
			store.visibleWinnersInDimension(dimensionId), BUILT_IN_CATALOG);

		if (next.equals(specs)) {
			return;
		}

		int added = 0;
		int changed = 0;

		for (Map.Entry<UUID, EntityOutlineSpec> entry : next.entrySet()) {
			EntityOutlineSpec previous = specs.get(entry.getKey());

			if (previous == null) {
				added++;
			} else if (!previous.equals(entry.getValue())) {
				changed++;
			}
		}

		int removed = 0;

		for (UUID entityId : specs.keySet()) {
			if (!next.containsKey(entityId)) {
				removed++;
			}
		}

		specs = Collections.unmodifiableMap(new LinkedHashMap<>(next));
		logger.transition(added, removed, changed, next.size());
	}

	/**
	 * Whether the entity with {@code entityId} currently controls a visible
	 * outline.
	 */
	public boolean shouldOutline(UUID entityId) {
		return specs.containsKey(entityId);
	}

	/**
	 * The fully opaque ARGB outline color for {@code entityId}, or {@code 0}
	 * when the entity currently has no outline.
	 */
	public int colorFor(UUID entityId) {
		EntityOutlineSpec spec = specs.get(entityId);

		return spec == null ? 0 : spec.argbColor();
	}

	/**
	 * The current snapshot as an unmodifiable map whose iteration order is the
	 * ascending {@link nx.pingwheel.common.domain.MarkerId} order produced by
	 * {@link EntityOutlineSelection#select}.
	 *
	 * <p>Mainly a test seam: production callers query per entity via
	 * {@link #shouldOutline} and {@link #colorFor} instead of iterating the
	 * snapshot.
	 */
	Map<UUID, EntityOutlineSpec> snapshot() {
		return specs;
	}

	/**
	 * Drops the whole snapshot; logs a single transition when it was
	 * non-empty and does nothing (and logs nothing) otherwise.
	 */
	public void clear() {
		if (specs.isEmpty()) {
			return;
		}

		int removed = specs.size();
		specs = Map.of();
		logger.transition(0, removed, 0, 0);
	}

	/**
	 * Replaces the transition logger; mainly a test seam. Production installs
	 * the lazy global logger once during client initialization.
	 */
	public static void setLogger(EntityOutlineLogger newLogger) {
		logger = Objects.requireNonNull(newLogger, "newLogger");
	}

	/**
	 * Resets the transition logger to the noop default; mainly a test seam.
	 */
	public static void resetLogger() {
		logger = EntityOutlineLogger.noop();
	}
}
