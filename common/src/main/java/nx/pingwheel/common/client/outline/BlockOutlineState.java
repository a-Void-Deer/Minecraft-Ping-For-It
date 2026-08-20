package nx.pingwheel.common.client.outline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.marker.TargetKey;

/**
 * Main-thread-confined snapshot of the blocks that currently control a
 * visible outline, prepared once per world render frame before the block
 * outline pass.
 *
 * <p>Every frame {@link #prepare} re-selects the block outline specs from the
 * authoritative {@link ClientMarkerStore} visible-winner map of the current
 * dimension and swaps the immutable block-key→spec snapshot, preserving
 * {@link BlockOutlineSelection#select}'s deterministic ascending marker-id
 * iteration order. The snapshot carries no live shape: a {@code BlockState}
 * change with the same block type keeps the same block identity, and the
 * block outline renderer resolves the current shape itself. A block type
 * replacement changes the key's registry id and therefore the identity.
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

	private BlockOutlineState() {}

	/**
	 * Re-selects the block outline specs for {@code dimensionId} from
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

		Map<TargetKey.BlockKey, BlockOutlineSpec> next = BlockOutlineSelection.select(
			store.visibleWinnersInDimension(dimensionId), BUILT_IN_CATALOG);

		if (next.equals(specs)) {
			return;
		}

		int added = 0;
		int changed = 0;

		for (Map.Entry<TargetKey.BlockKey, BlockOutlineSpec> entry : next.entrySet()) {
			BlockOutlineSpec previous = specs.get(entry.getKey());

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

		specs = Collections.unmodifiableMap(new LinkedHashMap<>(next));
		logger.transition(added, removed, changed, next.size());
	}

	/**
	 * Whether the current snapshot contains at least one block outline. The
	 * render pass checks this before acquiring or flushing the custom block
	 * outline batch, so a frame without block outlines never creates or
	 * flushes an empty batch.
	 */
	public boolean hasOutlines() {
		return !specs.isEmpty();
	}

	/**
	 * Whether every key in the current snapshot is contained in
	 * {@code succeeded} — the per-frame set of keys whose model-outline pass
	 * emitted geometry (see {@link BlockModelOutlineState}). When true, the
	 * late VoxelShape pass has nothing to draw, so the caller can skip
	 * acquiring and flushing the custom block outline batch entirely. An
	 * empty snapshot counts as fully covered.
	 */
	public boolean allCoveredBy(Set<TargetKey.BlockKey> succeeded) {
		Objects.requireNonNull(succeeded, "succeeded");

		for (TargetKey.BlockKey blockKey : specs.keySet()) {
			if (!succeeded.contains(blockKey)) {
				return false;
			}
		}

		return true;
	}

	/**
	 * The outline spec for {@code blockKey}, or {@code null} when the block
	 * currently has no outline.
	 */
	public BlockOutlineSpec specFor(TargetKey.BlockKey blockKey) {
		return specs.get(blockKey);
	}

	/**
	 * The fully opaque ARGB outline color for {@code blockKey}, or {@code 0}
	 * when the block currently has no outline.
	 */
	public int colorFor(TargetKey.BlockKey blockKey) {
		BlockOutlineSpec spec = specs.get(blockKey);

		return spec == null ? 0 : spec.argbColor();
	}

	/**
	 * The current snapshot as an unmodifiable map whose iteration order is the
	 * ascending {@link nx.pingwheel.common.domain.MarkerId} order produced by
	 * {@link BlockOutlineSelection#select}.
	 *
	 * <p>Mainly a test seam: the renderer iterates the snapshot in this exact
	 * order; production callers query per block via {@link #specFor} and
	 * {@link #colorFor}.
	 */
	public Map<TargetKey.BlockKey, BlockOutlineSpec> snapshot() {
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
