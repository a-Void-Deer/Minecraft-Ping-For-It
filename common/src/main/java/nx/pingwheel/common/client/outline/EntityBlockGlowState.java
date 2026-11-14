package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.domain.Target;

import static nx.pingwheel.common.Global.LOGGER;

/**
 * M7c C2 (scheme A): the ping colour a block-entity dispatch currently renders with, and
 * proof that the colour was actually consumed.
 *
 * <p>26.3 has no outline-only buffer source, so a block-entity model can only be given a
 * glow by writing {@code outlineColor} on {@code submitModel} — and that value is chosen
 * <em>inside</em> {@code BlockEntityRenderer.submit}, one level below
 * {@code BlockEntityRenderDispatcher.submit}, where the only block identity
 * ({@code BlockEntityRenderState.blockPos}) is still available. The dispatch hook
 * therefore publishes the colour resolved for that dispatch here and the
 * {@code submitModel} hook consumes it; see {@code BlockEntityRenderDispatcherMixin}.
 *
 * <p><strong>Why a thread-local, and why save/restore.</strong> The value is implicit
 * state that has to survive between two injected points of one dispatch without changing
 * any signature, and the render thread is its only writer, so a {@link ThreadLocal} keeps
 * it isolated from worker threads. A dispatch pushes whatever was active before it and
 * pops it afterwards, so a nested dispatch can neither inherit nor leak the outer colour.
 * {@link #resetFrame()} clears colour, stacks and the frame's position set once per frame:
 * a dispatch that never reached its RETURN (aborted) can then not tint unrelated geometry
 * later.
 *
 * <p><strong>Why the wireframe suppression reads a per-frame position set instead of
 * "is the hook installed".</strong> Arming a dispatch and actually drawing a glow are not
 * the same event: the renderer may be missing (dispatch returns early), the block entity
 * may only submit text or custom geometry (no {@code outlineColor} channel), or the
 * {@code submitModel} hook may never have been woven. Suppressing the M7a wireframe on
 * "the dispatch hook ran" would then delete the outline without adding a glow — a silent
 * visual regression. {@link #isGlowing(BlockPos)} is only true once this frame's
 * {@code submitModel} call for that exact position <em>consumed</em> a colour, so
 * "wireframe suppressed" and "glow drawn" are the same fact by construction.
 */
public final class EntityBlockGlowState {

	/** Frames with a dispatch candidate but no dispatch hook before warning. */
	private static final int DIAG_AFTER_FRAMES = 120;

	/** Glowing dispatches whose colour no model submit consumed before warning. */
	private static final int MISSED_DISPATCH_WARN_AT = 120;

	private static final String TARGET_TYPE_ENTITY_BLOCK = "entity_block";

	/** Current colour of this thread; {@code null} while the dispatch is not glowing. */
	private static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();

	/** Colours saved by enclosing dispatches, innermost last. */
	private static final ThreadLocal<List<Integer>> SAVED = ThreadLocal.withInitial(ArrayList::new);

	/** Position whose colour may still be consumed; {@code null} while nothing is armed. */
	private static final ThreadLocal<BlockPos> PENDING_POS = new ThreadLocal<>();

	/** Enclosing dispatch states saved by begin/restore, innermost last. */
	private static final ThreadLocal<List<Saved>> SAVED_STATE = ThreadLocal.withInitial(ArrayList::new);

	/** Positions whose model consumed a colour this frame, as {@link BlockPos#asLong}. */
	private static final ThreadLocal<Set<Long>> GLOWING = ThreadLocal.withInitial(HashSet::new);

	/** The dispatch hook ran at least once (proves the dispatcher injector applied). */
	private static volatile boolean dispatchHookApplied = false;

	/** A model submit consumed a colour at least once (proves the submitModel hook applied). */
	private static volatile boolean modelHookApplied = false;

	/**
	 * Whether the dispatch currently open resolved a colour, and whether its model submit
	 * consumed it.  Dispatch-local rather than per frame: a dispatch that armed a colour and
	 * then finished without any {@code submitModel} consuming it is exactly the evidence that
	 * the model hook is missing (or that this block entity draws no model at all).
	 */
	private static boolean pendingGlow = false;
	private static boolean pendingConsumed = false;

	/** Glowing dispatches whose colour was never consumed; logs once at the threshold. */
	private static int missedDispatches = 0;

	/** Dispatches that armed a colour this session, and how many a model submit consumed. */
	private static int glowingDispatches = 0;
	private static int consumedDispatches = 0;

	/** One-shot DEBUG proving the model-submit hook actually ran. */
	private static boolean firstConsumptionLogged = false;

	private static volatile boolean disabled = false;
	private static boolean activationLogged = false;
	private static boolean failureLogged = false;
	private static boolean dispatchHookWarned = false;
	private static int framesWithDispatchCandidates = 0;

	/**
	 * This frame's {@code entity_block} winner snapshot.  The dispatcher runs once per block
	 * entity, and {@code ClientMarkerStore#visibleWinnersInDimension} builds a fresh map, so
	 * filtering it per dispatch would allocate hundreds of maps per frame; the snapshot is
	 * therefore built once per frame and dropped by {@link #resetFrame()}.
	 */
	private static ClientMarkerStore cachedStore;
	private static String cachedDimension;
	private static Map<BlockPos, ClientMarker> cachedEntityBlockWinners;

	private EntityBlockGlowState() {}

	/**
	 * Pushes the enclosing state and arms {@code pos} with {@code color} for this dispatch.
	 *
	 * <p>Called exactly once per dispatch, also when nothing glows
	 * ({@code color == null}), so the matching {@link #restore()} is unambiguous.
	 */
	public static void begin(BlockPos pos, Integer color) {
		SAVED.get().add(CURRENT.get());
		SAVED_STATE.get().add(new Saved(PENDING_POS.get(), pendingGlow, pendingConsumed));

		if (color == null) {
			CURRENT.remove();
			PENDING_POS.remove();
			pendingGlow = false;
			pendingConsumed = false;
			return;
		}

		CURRENT.set(color);
		PENDING_POS.set(pos);
		pendingGlow = true;
		pendingConsumed = false;
	}

	/** Pops the state pushed by the matching {@link #begin(BlockPos, Integer)} call. */
	public static void restore() {
		// Judge the dispatch that is ending before its parent's state comes back.
		checkMissedDispatch();

		List<Integer> savedColors = SAVED.get();

		if (!savedColors.isEmpty()) {
			Integer previousColor = savedColors.remove(savedColors.size() - 1);

			if (previousColor == null) {
				CURRENT.remove();
			} else {
				CURRENT.set(previousColor);
			}
		}

		List<Saved> savedStates = SAVED_STATE.get();

		if (!savedStates.isEmpty()) {
			Saved previous = savedStates.remove(savedStates.size() - 1);

			if (previous.pos() == null) {
				PENDING_POS.remove();
			} else {
				PENDING_POS.set(previous.pos());
			}

			pendingGlow = previous.glow();
			pendingConsumed = previous.consumed();
		}
	}

	/**
	 * Counts a dispatch that armed a colour but finished without a single model submit
	 * consuming it.  Counter is per session, not per frame: the note is logged once on the
	 * 120th such dispatch.
	 *
	 * <p>This is deliberately DEBUG, not WARN: a text-only or item-only BER (campfire, sign)
	 * legitimately never calls {@code submitModel}, so accumulating misses is normal and the
	 * baked-model or wireframe fallback still decides what is drawn.  Only an actual absence
	 * of a glow while a model submit exists would be a regression, and that is observable from
	 * the screen instead.
	 */
	private static void checkMissedDispatch() {
		if (!pendingGlow) {
			return;
		}

		// Counters are per session and never reset: they let a support log distinguish
		// "this target simply draws no model" from "the model hook is dead".
		glowingDispatches++;

		if (pendingConsumed) {
			consumedDispatches++;
			return;
		}

		// Once the model hook has been observed working, a dispatch that legitimately draws no
		// model (renderer null, text-only) must not keep accumulating misses.
		if (modelHookApplied) {
			return;
		}

		missedDispatches++;

		if (missedDispatches == MISSED_DISPATCH_WARN_AT) {
			LOGGER.debug("entity-block glow: no model submit consumed the colour on {} glowing dispatches "
					+ "(expected for text/item-only BERs such as campfire or sign); the baked-model or "
					+ "wireframe fallback decides what is drawn "
					+ "(dispatchHookApplied={}, modelHookApplied={}, glowingDispatches={}, consumedDispatches={})",
				MISSED_DISPATCH_WARN_AT, dispatchHookApplied, modelHookApplied,
				glowingDispatches, consumedDispatches);
		}
	}

	/**
	 * The active colour, or {@code fallback} when this model submit is not glowing.
	 *
	 * <p>Consuming an armed colour is what proves the glow is real for that position, so the
	 * position is recorded here and nowhere else.
	 */
	public static int currentColorOr(int fallback) {
		Integer color = CURRENT.get();

		if (color == null) {
			return fallback;
		}

		BlockPos pos = PENDING_POS.get();

		if (pos != null) {
			GLOWING.get().add(pos.asLong());
			pendingConsumed = true;
			modelHookApplied = true;

			// One-shot proof that the submitModel hook is woven and consuming colours; without
			// it a "no model submit consumed ..." debug line is ambiguous.
			if (!firstConsumptionLogged) {
				firstConsumptionLogged = true;
				LOGGER.debug("entity-block glow: model submit hook active (first colour consumed)");
			}
		}

		return color;
	}

	/** True when this frame's model submit consumed a colour for {@code pos}. */
	public static boolean isGlowing(BlockPos pos) {
		return pos != null && GLOWING.get().contains(pos.asLong());
	}

	/** Drops this thread's colour, stacks, this frame's glowing positions and the snapshot. */
	public static void resetFrame() {
		CURRENT.remove();
		SAVED.get().clear();
		PENDING_POS.remove();
		SAVED_STATE.get().clear();
		pendingGlow = false;
		pendingConsumed = false;
		GLOWING.get().clear();
		cachedStore = null;
		cachedDimension = null;
		cachedEntityBlockWinners = null;
	}

	/**
	 * The {@code entity_block} winners of this frame's snapshot, keyed by block position.
	 *
	 * <p>Built lazily on the first dispatch (or diagnostic) of a frame and reused for the
	 * rest, so N block-entity dispatches cost one filter pass instead of N. The snapshot is
	 * immutable and callers only read it. It is dropped by {@link #resetFrame()}, so a marker
	 * that disappears or changes dimension cannot survive into the next frame.
	 */
	public static Map<BlockPos, ClientMarker> entityBlockWinners(
		ClientMarkerStore store,
		String dimensionId
	) {
		if (cachedEntityBlockWinners != null
			&& cachedStore == store
			&& Objects.equals(cachedDimension, dimensionId)) {
			return cachedEntityBlockWinners;
		}

		Map<BlockPos, ClientMarker> winners = new HashMap<>();

		if (store != null && dimensionId != null) {
			ClientLevel level = currentLevel(dimensionId);

			for (ClientMarker marker : store.visibleWinnersInDimension(dimensionId).values()) {
				if (marker == null || !TARGET_TYPE_ENTITY_BLOCK.equals(marker.targetTypeId())) {
					continue;
				}

				if (!(marker.target() instanceof Target.BlockTarget block)) {
					continue;
				}

				if (!block.dimensionId().equals(dimensionId)) {
					continue;
				}

				BlockPos canonicalPos = new BlockPos(block.x(), block.y(), block.z());
				winners.put(canonicalPos, marker);

				// A composite entity_block presentation (door, bed) renders one
				// model per subject, and each of those dispatches looks up its own position.
				// Mapping every subject position to the same marker is what lets the partner
				// half glow; the canonical position is always present, and the subject
				// positions are added when the resolver can expand the pair.
				BlockPresentationLookup.presentation(level, marker).ifPresent(presentation -> {
					for (BlockRenderSubject subject : presentation.renderSubjects()) {
						winners.put(subject.blockPos(), marker);
					}
				});
			}
		}

		cachedStore = store;
		cachedDimension = dimensionId;
		cachedEntityBlockWinners = Map.copyOf(winners);

		return cachedEntityBlockWinners;
	}

	/** The live client level, but only while it is still the requested dimension. */
	private static ClientLevel currentLevel(String dimensionId) {
		Minecraft game = Minecraft.getInstance();

		if (game == null || game.level == null) {
			return null;
		}

		if (!dimensionId.equals(game.level.dimension().identifier().toString())) {
			return null;
		}

		return game.level;
	}

	/** Called by the dispatch hook on every successful HEAD injection. */
	public static void markDispatchHookApplied() {
		dispatchHookApplied = true;
	}

	public static boolean isDisabled() {
		return disabled;
	}

	/**
	 * Logs the first dispatch whose colour was resolved (INFO, once).
	 *
	 * <p>Worded as "colour resolved" on purpose: resolving a colour only arms the outline
	 * channel.  A text-only or item-only BER never submits a model, so this line alone does
	 * not prove that anything was drawn — the baked-model source or the wireframe fallback
	 * may still be what the player sees.
	 */
	public static void logActivationOnce() {
		if (activationLogged) {
			return;
		}

		activationLogged = true;
		LOGGER.info("entity-block glow colour resolved (outlineColor via dispatch hook); "
			+ "a model submit, the baked-model source, or the wireframe fallback decides what is drawn");
	}

	/** Latches the pass off for this session and reports the failure once. */
	public static void failOnce(String message, Throwable failure) {
		disabled = true;

		if (failureLogged) {
			return;
		}

		failureLogged = true;

		if (failure == null) {
			LOGGER.warn(message);
		} else {
			LOGGER.warn("{} ({})", message, failure.getClass().getSimpleName());
		}
	}

	/**
	 * One-shot diagnostic for the case where the dispatcher injector never applied.
	 *
	 * <p>Without the dispatch hook no colour is ever resolved, so there is no glow and
	 * nothing is suppressed — the M7a wireframe simply stays, exactly like a build without
	 * C2. That failure has no false-positive surface, unlike the model-hook case: the latter
	 * is judged per dispatch in {@link #restore()} (a glowing dispatch whose colour no model
	 * submit consumed), because "some other block entity dispatched" or "the marked block
	 * entity was not submitted this frame" would make any frame-based counter lie.
	 */
	public static void diagnoseDispatchHook(ClientMarkerStore store, String dimensionId) {
		if (disabled || dispatchHookWarned || dispatchHookApplied) {
			return;
		}

		if (!hasDispatchCandidate(store, dimensionId)) {
			framesWithDispatchCandidates = 0;
			return;
		}

		framesWithDispatchCandidates++;

		if (framesWithDispatchCandidates < DIAG_AFTER_FRAMES) {
			return;
		}

		dispatchHookWarned = true;
		LOGGER.warn("entity-block glow: dispatch hook not observed after {} frames while entity_block "
				+ "targets were present; entity_block stays on the wireframe",
			DIAG_AFTER_FRAMES);
	}

	/** One enclosing dispatch, saved so a nested dispatch can restore it. */
	private record Saved(BlockPos pos, boolean glow, boolean consumed) {}

	private static boolean hasDispatchCandidate(ClientMarkerStore store, String dimensionId) {
		Minecraft game = Minecraft.getInstance();

		if (game == null || game.level == null) {
			return false;
		}

		for (BlockPos pos : entityBlockWinners(store, dimensionId).keySet()) {
			if (!game.level.isLoaded(pos)) {
				continue;
			}

			BlockEntity blockEntity = game.level.getBlockEntity(pos);

			if (blockEntity != null) {
				return true;
			}
		}

		return false;
	}
}
