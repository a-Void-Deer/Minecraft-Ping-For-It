package nx.pingwheel.common.client.outline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot diagnostic flag shared between {@code SubmitNodeCollectionMixin} and
 * {@link BlockOutlinePass}.
 *
 * <p>{@code SubmitNodeCollectionMixin} is declared with {@code require = 0}, so if its
 * target descriptor ever changes it silently stops intercepting and our opaque-alpha
 * outline falls back to the vanilla solid bucket. That is not a crash and the outline
 * still draws (it simply loses the "always on top" behaviour), so the pass must not be
 * disabled — it only needs to say so once.
 *
 * <p>It also records the MC 26.3 "improved transparency" fallback: vanilla only executes
 * the {@code afterTerrain} phase from {@code LevelRenderer.executeClassicTransparency},
 * which is skipped when {@code Options.improvedTransparency} is on, so in that mode the
 * routing mixin sends the outline to the {@code outline} phase instead (see
 * {@link #noteImprovedTransparencyFallback()}).
 */
public final class OutlineRoutingState {

	private static final Logger LOGGER = LoggerFactory.getLogger("PingForIt");

	private static volatile boolean routedSeen = false;
	private static volatile boolean fallbackLogged = false;

	private OutlineRoutingState() {}

	/** Called by the routing mixin after it redirects one of our submissions. */
	public static void markRouted() {
		routedSeen = true;
	}

	/** True once the routing mixin has actually intercepted an outline submission. */
	public static boolean hasRouted() {
		return routedSeen;
	}

	/** Called once per session when the improved-transparency fallback is taken. */
	public static void noteImprovedTransparencyFallback() {
		if (fallbackLogged) {
			return;
		}
		fallbackLogged = true;
		LOGGER.info("[PingForIt] improved transparency is ON: MC 26.3 never executes the afterTerrain phase, "
			+ "routing the outline to the outline phase instead (see docs/reviews/24-fidelity-constraint-audit.md #19)");
	}
}