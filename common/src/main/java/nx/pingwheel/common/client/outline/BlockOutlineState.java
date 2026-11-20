package nx.pingwheel.common.client.outline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.marker.TargetKey;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Main-thread-confined snapshot of the blocks that currently control a
 * visible outline, prepared once per world render frame before the block
 * outline pass.
 *
 * <p>Every frame {@link #prepare} re-selects ordinary and provider-owned block
 * outline specs from the {@link ClientMarkerStore} outline projection of the
 * current dimension and swaps immutable snapshots,
 * preserving deterministic ascending marker-id iteration order. The
 * snapshots carry no live shape: a {@code BlockState} change with the same
 * block type keeps the same ordinary block identity, while an external
 * provider refresh replaces only its opaque locator/anchor payload. Ordinary
 * specs are resolved into the immutable per-frame presentation snapshot held
 * by {@link BlockModelOutlineState} before either block render pass runs.
 *
 * <p>Logging: only snapshot transitions are debug logged — the aggregate
 * counts {@code added/removed/changed/total} — never any block position,
 * registry id, dimension, color, or name. A repeated identical prepare emits
 * no log. The default logger is a noop; production installs the lazy global
 * logger via {@link #setLogger}.
 *
 * <p>Thread safety: main-thread-confined, same as the {@link ClientMarkerStore}
 * it mirrors. Concurrent access is unsupported.
 */
public final class BlockOutlineState {

	public static final BlockOutlineState INSTANCE = new BlockOutlineState();

	private static final PingTypeCatalog BUILT_IN_CATALOG = PingTypeCatalog.builtIn();

	private static volatile BlockOutlineLogger logger = BlockOutlineLogger.noop();

	private Map<TargetKey.BlockKey, BlockOutlineSpec> specs = Map.of();
	private Map<TargetKey.ExternalBlockKey, ExternalBlockOutlineSpec> externalSpecs = Map.of();

	private BlockOutlineState() {}

	/**
	 * Re-selects the block outline specs for {@code dimensionId} from
	 * {@code store} and, when the snapshot changed, replaces it and logs the
	 * transition counts.
	 *
	 * <p>With a {@code null} store or dimension (no live runtime/level, or
	 * after leaving the server) the state is cleared instead.
	 */
	public void prepare(@Nullable ClientMarkerStore store, @Nullable String dimensionId) {
		if (store == null || dimensionId == null) {
			clear();
			return;
		}

		@Unmodifiable Map<TargetKey, ClientMarker> winners = store.outlineWinnersInDimension(dimensionId);
		@Unmodifiable Map<TargetKey.BlockKey, BlockOutlineSpec> next = BlockOutlineSelection.select(
			winners, BUILT_IN_CATALOG);
		@Unmodifiable Map<TargetKey.ExternalBlockKey, ExternalBlockOutlineSpec> nextExternal =
			BlockOutlineSelection.selectExternal(winners, BUILT_IN_CATALOG);

		if (next.equals(specs) && nextExternal.equals(externalSpecs)) {
			return;
		}

		int added = 0;
		int changed = 0;

		for (Map.Entry<TargetKey.BlockKey, BlockOutlineSpec> entry : next.entrySet()) {
			@Nullable BlockOutlineSpec previous = specs.get(entry.getKey());

			if (previous == null) {
				added++;
			} else if (!previous.equals(entry.getValue())) {
				changed++;
			}
		}

		int removed = 0;

		for (TargetKey.BlockKey blockKey : specs.keySet()) {
			if (!next.containsKey(blockKey)) {
				removed++;
			}
		}

		int externalAdded = 0;
		int externalChanged = 0;
		int externalRemoved = 0;

		for (Map.Entry<TargetKey.ExternalBlockKey, ExternalBlockOutlineSpec> entry : nextExternal.entrySet()) {
			@Nullable ExternalBlockOutlineSpec previous = externalSpecs.get(entry.getKey());

			if (previous == null) {
				externalAdded++;
			} else if (!previous.equals(entry.getValue())) {
				externalChanged++;
			}
		}

		for (TargetKey.ExternalBlockKey key : externalSpecs.keySet()) {
			if (!nextExternal.containsKey(key)) {
				externalRemoved++;
			}
		}

		specs = Collections.unmodifiableMap(new LinkedHashMap<>(next));
		externalSpecs = Collections.unmodifiableMap(new LinkedHashMap<>(nextExternal));
		logger.transition(
			added + externalAdded,
			removed + externalRemoved,
			changed + externalChanged,
			next.size() + nextExternal.size());
	}

	/**
	 * Whether the current snapshot contains at least one block outline. The
	 * render pass checks this before acquiring or flushing the custom block
	 * outline batch, so a frame without block outlines never creates or
	 * flushes an empty batch.
	 */
	public boolean hasOutlines() {
		return !specs.isEmpty() || !externalSpecs.isEmpty();
	}

	/**
	 * The current snapshot as an unmodifiable map whose iteration order is the
	 * ascending {@link nx.pingwheel.common.domain.MarkerId} order produced by
	 * {@link BlockOutlineSelection#select}. Each spec retains its winner's
	 * fully opaque ARGB outline color.
	 *
	 * <p>This immutable snapshot is the presentation-resolution and test seam;
	 * the render passes consume it through the prepared frame snapshot.
	 */
	public Map<TargetKey.BlockKey, BlockOutlineSpec> snapshot() {
		return specs;
	}

	/** The current provider-owned external block outline snapshot. */
	public Map<TargetKey.ExternalBlockKey, ExternalBlockOutlineSpec> externalSnapshot() {
		return externalSpecs;
	}

	/**
	 * Drops the whole snapshot; logs a single transition when it was
	 * non-empty and does nothing (and logs nothing) otherwise.
	 */
	public void clear() {
		if (specs.isEmpty() && externalSpecs.isEmpty()) {
			return;
		}

		int removed = specs.size();
		int externalRemoved = externalSpecs.size();
		specs = Map.of();
		externalSpecs = Map.of();
		logger.transition(0, removed + externalRemoved, 0, 0);
	}

	/**
	 * Replaces the transition logger; mainly a test seam. Production installs
	 * the lazy global logger once during client initialization.
	 */
	public static void setLogger(BlockOutlineLogger newLogger) {
		logger = Objects.requireNonNull(newLogger, "newLogger");
	}

	/**
	 * Resets the transition logger to the noop default; mainly a test seam.
	 */
	public static void resetLogger() {
		logger = BlockOutlineLogger.noop();
	}
}
