package nx.pingwheel.common.client.outline;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import nx.pingwheel.common.marker.TargetKey;

/**
 * Main-thread-confined, per-frame record of the block keys whose model-outline
 * pass successfully emitted geometry this world render frame.
 *
 * <p>The vanilla model glow routes ({@code entity_block} BlockEntity geometry
 * and whitelisted {@code block} BlockDisplay geometry) run before the vanilla
 * {@code OutlineBufferSource.endOutlineBatch()} call. Every key that produced
 * at least one outline vertex is recorded here; the late custom VoxelShape
 * fallback pass skips exactly those keys, so a successful glow pass is never
 * doubled by the 3.75 px line outline. Keys whose glow route was unavailable,
 * failed, or emitted zero vertices are simply absent, so they fall back to the
 * VoxelShape outline.
 *
 * <p>{@link #beginFrame()} must be called at the start of every
 * {@code renderLevel} pass (before the model pass runs) and {@link #clear()}
 * when leaving a server, so successes never leak across frames or worlds. The
 * snapshot is immutable and replaced per mutation; it is only ever read by the
 * main-thread render passes.
 */
public final class BlockModelOutlineState {
	/*
	 * A shared OutlineBufferSource cannot roll back a recoverable partial
	 * commit. Such a key is retained as rendered for this frame to suppress a
	 * partial-mask plus VoxelShape overlay; beginFrame clears it so the source
	 * is retried normally on the next frame.
	 */

	public static final BlockModelOutlineState INSTANCE = new BlockModelOutlineState();

	private Set<TargetKey.BlockKey> successKeys = Set.of();
	private long frameId;

	private BlockModelOutlineState() {}

	/**
	 * Starts a fresh frame: the success set is empty until the model pass
	 * records keys. Called before the model pass on every world render frame.
	 */
	public void beginFrame() {
		frameId++;
		successKeys = Set.of();
	}

	/**
	 * Monotonic render-frame identity for sources that need a hard aggregate
	 * budget without retaining any live geometry or transform state.
	 */
	public long frameId() {
		return frameId;
	}

	/**
	 * Records that the model-outline pass successfully emitted geometry for
	 * {@code blockKey} this frame, so the VoxelShape fallback must skip it.
	 */
	public void addSuccess(TargetKey.BlockKey blockKey) {
		Objects.requireNonNull(blockKey, "blockKey");

		Set<TargetKey.BlockKey> next = new LinkedHashSet<>(successKeys);
		next.add(blockKey);
		successKeys = Collections.unmodifiableSet(next);
	}

	/**
	 * Whether at least one block emitted model-outline geometry this frame.
	 * Drives the vanilla entity-outline post-process handoff.
	 */
	public boolean emitted() {
		return !successKeys.isEmpty();
	}

	/**
	 * The immutable set of block keys that succeeded this frame, in
	 * first-success order. Main-thread render passes only.
	 */
	public Set<TargetKey.BlockKey> successKeys() {
		return successKeys;
	}

	/**
	 * Drops the per-frame success record (used when leaving a server); the
	 * next frame starts fresh via {@link #beginFrame()}.
	 */
	public void clear() {
		beginFrame();
	}
}
