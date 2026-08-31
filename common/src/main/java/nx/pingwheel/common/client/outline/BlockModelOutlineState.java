package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import nx.pingwheel.common.marker.TargetKey;

/**
 * Main-thread-confined, per-frame record of the ordinary block presentations
 * and subject keys whose model-outline pass successfully emitted geometry this
 * world render frame.
 *
 * <p>The vanilla model glow routes ({@code entity_block} BlockEntity geometry,
 * whitelisted {@code block} BlockDisplay geometry, and the compatible baked
 * model route for provider-owned blocks) run before the vanilla {@code
 * OutlineBufferSource.endOutlineBatch()} call. Every key that produced at
 * least one outline vertex is recorded here; the late custom VoxelShape
 * fallback pass skips exactly those source/subject keys, so a successful glow
 * pass is never doubled by the 3.75 px line outline. Subjects whose glow route
 * was unavailable, failed, or emitted zero vertices are simply absent, so they
 * fall back to the VoxelShape outline.
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

	private Set<BlockPresentationSuccessKey> successKeys = Set.of();
	private Set<TargetKey.ExternalBlockKey> externalSuccessKeys = Set.of();
	private List<BlockPresentation> presentations = List.of();
	private boolean presentationsPrepared;
	private long frameId;

	private BlockModelOutlineState() {}

	/**
	 * Starts a fresh frame: the presentation and success snapshots are empty
	 * until frame preparation and the model pass repopulate them. Called before
	 * the model pass on every world render frame.
	 */
	public void beginFrame() {
		frameId++;
		successKeys = Set.of();
		externalSuccessKeys = Set.of();
		presentations = List.of();
		presentationsPrepared = false;
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
	 * {@code successKey} this frame, so the VoxelShape fallback must skip only
	 * that exact source/subject pair.
	 */
	public void addSuccess(BlockPresentationSuccessKey successKey) {
		Objects.requireNonNull(successKey, "successKey");

		Set<BlockPresentationSuccessKey> next = new LinkedHashSet<>(successKeys);
		next.add(successKey);
		successKeys = Collections.unmodifiableSet(next);
	}

	/**
	 * Records that a provider-owned external block emitted baked-model outline
	 * geometry this frame, so its native VoxelShape fallback must skip it.
	 */
	public void addExternalSuccess(TargetKey.ExternalBlockKey blockKey) {
		Objects.requireNonNull(blockKey, "blockKey");

		Set<TargetKey.ExternalBlockKey> next = new LinkedHashSet<>(externalSuccessKeys);
		next.add(blockKey);
		externalSuccessKeys = Collections.unmodifiableSet(next);
	}

	/**
	 * Whether at least one block emitted model-outline geometry this frame.
	 * Drives the vanilla entity-outline post-process handoff.
	 */
	public boolean emitted() {
		return !successKeys.isEmpty() || !externalSuccessKeys.isEmpty();
	}

	/**
	 * The immutable set of source/subject keys that succeeded this frame, in
	 * first-success order. Main-thread render passes only.
	 */
	public Set<BlockPresentationSuccessKey> successKeys() {
		return successKeys;
	}

	/**
	 * Resolves and stores the ordinary presentations for this frame. The
	 * resolution is deliberately performed here, after {@link #beginFrame()},
	 * rather than lazily in either render pass, so both passes consume the same
	 * immutable subject snapshot.
	 *
	 * <p>The source chunk is checked before invoking the registry. A missing
	 * chunk therefore produces an intentional empty presentation without asking
	 * the client level to load it.</p>
	 */
	public void preparePresentations(ClientLevel level, BlockOutlineState outlineState) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(outlineState, "outlineState");

		if (presentationsPrepared) {
			return;
		}

		List<BlockPresentation> next = new ArrayList<>(outlineState.snapshot().size());
		for (BlockOutlineSpec sourceSpec : outlineState.snapshot().values()) {
			BlockPos sourcePos = new BlockPos(
				sourceSpec.blockKey().x(), sourceSpec.blockKey().y(), sourceSpec.blockKey().z());
			BlockPresentation presentation = level.hasChunkAt(sourcePos)
				? BlockPresentationResolverRegistry.INSTANCE.resolve(level, sourceSpec)
				: new BlockPresentation(sourceSpec, List.of());
			next.add(presentation);
		}

		setPresentations(next);
	}

	/** The immutable ordinary presentation snapshot for the current frame. */
	public List<BlockPresentation> presentations() {
		return presentations;
	}

	/**
	 * Installs the immutable ordinary presentation snapshot for the current
	 * frame. This is also a focused-test seam; production uses
	 * {@link #preparePresentations(ClientLevel, BlockOutlineState)}.
	 */
	public void setPresentations(List<BlockPresentation> nextPresentations) {
		Objects.requireNonNull(nextPresentations, "nextPresentations");
		presentations = List.copyOf(nextPresentations);
		presentationsPrepared = true;
	}

	/**
	 * Returns whether every subject in the current ordinary snapshot emitted
	 * model geometry this frame. Empty presentations and empty subject lists are
	 * intentionally vacuously covered.
	 */
	public boolean allPresentationsCovered() {
		for (BlockPresentation presentation : presentations) {
			for (BlockRenderSubject subject : presentation.renderSubjects()) {
				if (!successKeys.contains(subject.successKey(presentation.sourceSpec()))) {
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * Checks ordinary subject coverage and the external snapshot together. This
	 * is the early-out seam used by the late fallback caller.
	 */
	public boolean allCoveredBy(BlockOutlineState outlineState) {
		Objects.requireNonNull(outlineState, "outlineState");
		return outlineState.allCoveredBy(presentations, successKeys, externalSuccessKeys);
	}

	/**
	 * Clears only model successes while retaining the already-resolved
	 * presentations. This is used when the model pipeline is unavailable: all
	 * non-empty subjects must then reach the late native-shape pass.
	 */
	public void clearSuccesses() {
		successKeys = Set.of();
		externalSuccessKeys = Set.of();
	}

	/**
	 * The immutable set of provider-owned external keys that emitted a
	 * successful model glow this frame.
	 */
	public Set<TargetKey.ExternalBlockKey> externalSuccessKeys() {
		return externalSuccessKeys;
	}

	/**
	 * Drops the per-frame success record (used when leaving a server); the
	 * next frame starts fresh via {@link #beginFrame()}.
	 */
	public void clear() {
		beginFrame();
	}
}
