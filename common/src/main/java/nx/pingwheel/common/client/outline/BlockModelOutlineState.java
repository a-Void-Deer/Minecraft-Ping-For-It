package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import nx.pingwheel.common.client.outline.ExternalBlockFramePresentation.SuccessKey;
import nx.pingwheel.common.integration.sable.client.SableClientProvider;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Main-thread-confined, per-frame record of ordinary and external block presentations
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
 * render-thread accumulators are published as immutable snapshots only when
 * queried, and are only ever read by the main-thread render passes.
 */
public final class BlockModelOutlineState {
	/*
	 * A shared OutlineBufferSource cannot roll back a recoverable partial
	 * commit. Such a key is retained as rendered for this frame to suppress a
	 * partial-mask plus VoxelShape overlay; beginFrame clears it so the source
	 * is retried normally on the next frame.
	 */

	public static final BlockModelOutlineState INSTANCE = new BlockModelOutlineState();

	private final Set<BlockPresentationSuccessKey> successAccumulator = new LinkedHashSet<>();
	private final Set<SuccessKey> externalSuccessAccumulator = new LinkedHashSet<>();
	private @Nullable @Unmodifiable Set<BlockPresentationSuccessKey> successSnapshot = Set.of();
	private @Nullable @Unmodifiable Set<SuccessKey> externalSuccessSnapshot = Set.of();
	private @Unmodifiable List<BlockPresentation> presentations = List.of();
	private @Unmodifiable List<ExternalBlockFramePresentation> externalPresentations = List.of();
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
		successAccumulator.clear();
		externalSuccessAccumulator.clear();
		successSnapshot = Set.of();
		externalSuccessSnapshot = Set.of();
		presentations = List.of();
		externalPresentations = List.of();
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

		if (successAccumulator.add(successKey)) {
			successSnapshot = null;
		}
	}

	/**
	 * Records geometry success for one external subject, not its entire marker.
	 */
	void addExternalSuccess(SuccessKey subjectKey) {
		Objects.requireNonNull(subjectKey, "subjectKey");

		if (externalSuccessAccumulator.add(subjectKey)) {
			externalSuccessSnapshot = null;
		}
	}

	/**
	 * Whether at least one block emitted model-outline geometry this frame.
	 * Drives the vanilla entity-outline post-process handoff.
	 */
	public boolean emitted() {
		return !successAccumulator.isEmpty() || !externalSuccessAccumulator.isEmpty();
	}

	/**
	 * The immutable set of source/subject keys that succeeded this frame, in
	 * first-success order. Main-thread render passes only.
	 */
	public @Unmodifiable Set<BlockPresentationSuccessKey> successKeys() {
		if (successSnapshot == null) {
			successSnapshot = immutableSnapshot(successAccumulator);
		}
		return successSnapshot;
	}

	/**
	 * Resolves and stores ordinary and external presentations for this frame. The
	 * resolution is deliberately performed here, after {@link #beginFrame()},
	 * rather than lazily in either render pass, so both passes consume the same
	 * immutable subject snapshot.
	 *
	 * <p>The source chunk is checked before invoking the registry. A missing
	 * chunk therefore produces an intentional empty presentation without asking
	 * the client level to load it.</p>
	 */
	public void preparePresentations(ClientLevel level, BlockOutlineState outlineState, float renderPartialTick) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(outlineState, "outlineState");
		preparePresentations(outlineState.snapshot().values(), outlineState.externalSnapshot().values(),
			sourceSpec -> resolveOrdinary(level, sourceSpec),
			spec -> SableClientProvider.resolveFramePresentation(level, spec, renderPartialTick));
	}

	void preparePresentations(Collection<BlockOutlineSpec> ordinarySpecs, Collection<ExternalBlockOutlineSpec> externalSpecs,
		Function<BlockOutlineSpec, BlockPresentation> ordinaryResolver,
		Function<ExternalBlockOutlineSpec, Optional<ExternalBlockFramePresentation>> externalResolver) {
		if (presentationsPrepared) {
			return;
		}

		List<BlockPresentation> next = new ArrayList<>(ordinarySpecs.size());
		for (BlockOutlineSpec sourceSpec : ordinarySpecs) {
			next.add(ordinaryResolver.apply(sourceSpec));
		}
		List<ExternalBlockFramePresentation> nextExternal = new ArrayList<>(externalSpecs.size());
		for (ExternalBlockOutlineSpec spec : externalSpecs) {
			externalResolver.apply(spec).ifPresent(nextExternal::add);
		}
		externalPresentations = List.copyOf(nextExternal);

		setPresentations(next);
	}

	private static BlockPresentation resolveOrdinary(ClientLevel level, BlockOutlineSpec sourceSpec) {
		BlockPos sourcePos = new BlockPos(sourceSpec.blockKey().x(), sourceSpec.blockKey().y(), sourceSpec.blockKey().z());
		return level.hasChunkAt(sourcePos) ? BlockPresentationResolverRegistry.INSTANCE.resolve(level, sourceSpec)
			: new BlockPresentation(sourceSpec, List.of());
	}

	/** The immutable ordinary presentation snapshot for the current frame. */
	public @Unmodifiable List<BlockPresentation> presentations() {
		return presentations;
	}

	@Unmodifiable List<ExternalBlockFramePresentation> externalPresentations() {
		return externalPresentations;
	}

	/**
	 * Installs the immutable ordinary presentation snapshot for the current
	 * frame. This is also a focused-test seam; production uses
	 * {@link #preparePresentations(ClientLevel, BlockOutlineState, float)}.
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
		Set<BlockPresentationSuccessKey> succeeded = successKeys();
		for (BlockPresentation presentation : presentations) {
			for (BlockRenderSubject subject : presentation.renderSubjects()) {
				if (!succeeded.contains(subject.successKey(presentation.sourceSpec()))) {
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
	public boolean allCoveredByModels() {
		if (!allPresentationsCovered()) return false;
		for (ExternalBlockFramePresentation external : externalPresentations) {
			if (!external.allCoveredBy(externalSuccessAccumulator)) return false;
		}
		return true;
	}

	/**
	 * Clears only model successes while retaining the already-resolved
	 * presentations. This is used when the model pipeline is unavailable: all
	 * non-empty subjects must then reach the late native-shape pass.
	 */
	public void clearSuccesses() {
		successAccumulator.clear();
		externalSuccessAccumulator.clear();
		successSnapshot = Set.of();
		externalSuccessSnapshot = Set.of();
	}

	/**
	 * The immutable set of external source/subject keys that emitted a
	 * successful model glow this frame.
	 */
	@Unmodifiable Set<SuccessKey> externalSuccessKeys() {
		if (externalSuccessSnapshot == null) {
			externalSuccessSnapshot = immutableSnapshot(externalSuccessAccumulator);
		}
		return externalSuccessSnapshot;
	}

	private static <T> @Unmodifiable Set<T> immutableSnapshot(Set<T> source) {
		return Collections.unmodifiableSet(new LinkedHashSet<>(source));
	}

	/**
	 * Drops the per-frame success record (used when leaving a server); the
	 * next frame starts fresh via {@link #beginFrame()}.
	 */
	public void clear() {
		beginFrame();
	}
}
