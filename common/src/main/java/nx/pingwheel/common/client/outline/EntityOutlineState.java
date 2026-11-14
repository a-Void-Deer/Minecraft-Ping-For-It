package nx.pingwheel.common.client.outline;

import java.util.Map;

import net.minecraft.client.Minecraft;

import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.domain.EntityLocator;

import static nx.pingwheel.common.Global.LOGGER;

/**
 * M7b: per-frame {@code EntityLocator -> ARGB colour} snapshot for entity outlines.
 *
 * <p>The snapshot is rebuilt from the marker store at the start of every level
 * extraction, before render states are extracted, so the values a renderer reads are
 * always from the current frame. The map is immutable and replaced wholesale, so a
 * reader can never observe a half-built snapshot.
 *
 * <p>Failure policy: the hook warns once and then stays silent. Outlines are cosmetic,
 * so a broken snapshot must never throw into the render loop.
 */
public final class EntityOutlineState {

	/** Extractions with outlines pending but no entity hook before the warning fires. */
	private static final int DIAG_AFTER_PREPARES = 60;

	private static volatile Map<EntityLocator, Integer> outlines = Map.of();
	private static volatile String dimensionId = "";

	private static boolean activationLogged = false;
	private static boolean disabled = false;
	private static boolean failureLogged = false;
	private static boolean hookWarned = false;
	private static int preparesWithOutlines = 0;
	private static int lastSize = -1;

	private EntityOutlineState() {}

	/**
	 * Rebuilds the snapshot for {@code dimensionId}. Must run on the render thread and
	 * before entity render states are extracted.
	 */
	public static void prepare(ClientMarkerStore store, String currentDimensionId) {
		if (disabled) {
			return;
		}

		try {
			if (store == null || currentDimensionId == null) {
				clear();
				return;
			}

			Map<EntityLocator, Integer> next = EntityOutlineSelection.select(
				store.visibleWinnersInDimension(currentDimensionId).values(), currentDimensionId);

			diagnoseHook(next);

			if (next.equals(outlines)) {
				return;
			}

			outlines = next;
			dimensionId = currentDimensionId;

			if (!activationLogged && !next.isEmpty()) {
				activationLogged = true;
				LOGGER.info("entity outline pass active (native glow channel via outlineColor)");
			}

			if (next.size() != lastSize) {
				lastSize = next.size();
				LOGGER.debug("entity outline: {} entit{} active", next.size(), next.size() == 1 ? "y" : "ies");
			}
		} catch (RuntimeException | LinkageError | AssertionError failure) {
			disabled = true;
			outlines = Map.of();

			if (!failureLogged) {
				failureLogged = true;
				LOGGER.warn("entity outline: pass failed, disabled for this session ({})",
					failure.getClass().getSimpleName());
			}
		}
	}

	/** ARGB colour for {@code locator}, or {@code null} when it must not be outlined. */
	public static Integer colorFor(EntityLocator locator) {
		return locator == null ? null : outlines.get(locator);
	}

	public static boolean shouldOutline(EntityLocator locator) {
		return locator != null && outlines.containsKey(locator);
	}

	/** Dimension the current snapshot was built for. */
	public static String dimensionId() {
		return dimensionId;
	}

	public static boolean hasOutlines() {
		return !outlines.isEmpty();
	}

	/** Drops the snapshot; used when leaving a world or when no runtime is present. */
	public static void clear() {
		if (!outlines.isEmpty()) {
			outlines = Map.of();
			lastSize = -1;
		}

		dimensionId = "";
	}

	private static void diagnoseHook(Map<EntityLocator, Integer> next) {
		if (hookWarned || next.isEmpty() || EntityOutlineDiagnostics.extractionHookApplied()) {
			return;
		}

		// extractRenderState only runs for entities that survive culling. When the level
		// holds no entities at all there is nothing that could ever have been extracted
		// this frame, so an absent hook is not observable yet — stay quiet instead of
		// reporting a hook that is in fact fine.
		if (!hasRenderableEntities()) {
			return;
		}

		preparesWithOutlines++;

		if (preparesWithOutlines >= DIAG_AFTER_PREPARES) {
			hookWarned = true;
			LOGGER.warn("entity-outline extraction hook not observed after {} frames "
					+ "(mixin inactive, or the outlined entities were never extracted); "
					+ "entity outlines may be missing",
				DIAG_AFTER_PREPARES);
		}
	}

	/** Cheap precondition: does the current level hold any entity at all? */
	private static boolean hasRenderableEntities() {
		Minecraft game = Minecraft.getInstance();

		if (game == null || game.level == null) {
			return false;
		}

		return game.level.entitiesForRendering().iterator().hasNext();
	}
}
