package nx.pingwheel.common.client.marker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.client.Minecraft;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.render.WorldRenderContext;
import org.jetbrains.annotations.Nullable;

import static nx.pingwheel.common.CommonClient.Game;

/**
 * Main-thread-confined cache of per-marker {@link MarkerView}s backing the
 * world overlay renderers.
 *
 * <p>Every world render frame {@link #prepare} synchronizes the cached views
 * with the authoritative {@link ClientMarkerStore}:
 * <ul>
 *   <li>a view is created for every newly seen {@link MarkerId};</li>
 *   <li>an existing view's payload is replaced in place when the same id is
 *       re-applied, so view instances survive across frames;</li>
 *   <li>views whose marker disappeared from the store are removed;</li>
 *   <li>only views whose target dimension is the current dimension are
 *       updated (the renderers skip other dimensions anyway);</li>
 *   <li>the exposed render list is immutable and sorted by descending
 *       distance, exactly like the legacy ping repository.</li>
 * </ul>
 *
 * <p>Calling {@link #prepare} with a missing world or a {@code null} store
 * (for example after leaving the server) clears every cached view. No logging
 * happens here at all: the store and the client runtime already own all
 * mutation logging, so nothing in this class can produce per-frame log spam.
 *
 * <p>Thread safety: main-thread-confined, same as the {@link ClientMarkerStore}
 * it mirrors. Concurrent access is unsupported.
 */
public final class MarkerOverlayState {

	public static final MarkerOverlayState INSTANCE = new MarkerOverlayState();

	private final Map<MarkerId, MarkerView> views = new LinkedHashMap<>();
	private List<MarkerView> renderViews = List.of();

	private MarkerOverlayState() {}

	/**
	 * Synchronizes the cached views with {@code store} and recomputes the
	 * current-dimension views for this render frame.
	 *
	 * <p>With a {@code null} store or context — or without a live level — the
	 * state is cleared instead.
	 */
	public void prepare(@Nullable WorldRenderContext ctx, @Nullable ClientMarkerStore store) {
		Minecraft game = Game;

		if (ctx == null || store == null || game == null || game.level == null) {
			clear();
			return;
		}

		List<ClientMarker> markers = store.allMarkers();
		SyncPlan plan = syncPlan(views.keySet(), markers);

		Map<MarkerId, ClientMarker> markersById = new LinkedHashMap<>();

		for (ClientMarker marker : markers) {
			markersById.put(marker.id(), marker);
		}

		for (MarkerId id : plan.toRemove()) {
			views.remove(id);
		}

		for (MarkerId id : plan.toAdd()) {
			views.put(id, new MarkerView(markersById.get(id)));
		}

		for (MarkerId id : plan.toReplace()) {
			views.get(id).replacePayload(markersById.get(id));
		}

		String currentDimension = game.level.dimension().location().toString();

		for (MarkerView view : views.values()) {
			if (view.getDimension().equals(currentDimension)) {
				view.update(ctx);
			}
		}

		List<MarkerView> sorted = new ArrayList<>(views.values());
		sorted.sort((left, right) -> Double.compare(right.getDistance(), left.getDistance()));

		renderViews = List.copyOf(sorted);
	}

	/**
	 * The immutable render list for this frame, sorted by descending distance.
	 */
	public List<MarkerView> renderViews() {
		return renderViews;
	}

	/**
	 * Drops every cached view and the render list.
	 */
	public void clear() {
		views.clear();
		renderViews = List.of();
	}

	/**
	 * Computes the pure id-level synchronization plan between the cached view
	 * ids and the markers currently present in the store:
	 * <ul>
	 *   <li>{@code toAdd}: marker ids not yet cached;</li>
	 *   <li>{@code toReplace}: marker ids already cached whose payload must be
	 *       refreshed;</li>
	 *   <li>{@code toRemove}: cached ids whose marker no longer exists.</li>
	 * </ul>
	 *
	 * <p>Pure JDK logic with no Minecraft or config access, so it is unit
	 * tested directly.
	 */
	static SyncPlan syncPlan(Collection<MarkerId> knownIds, Collection<ClientMarker> markers) {
		Objects.requireNonNull(knownIds, "knownIds");
		Objects.requireNonNull(markers, "markers");

		Set<MarkerId> toAdd = new HashSet<>();
		Set<MarkerId> toReplace = new HashSet<>();
		Set<MarkerId> markerIds = new HashSet<>();

		for (ClientMarker marker : markers) {
			markerIds.add(marker.id());

			if (knownIds.contains(marker.id())) {
				toReplace.add(marker.id());
			} else {
				toAdd.add(marker.id());
			}
		}

		Set<MarkerId> toRemove = new HashSet<>(knownIds);
		toRemove.removeAll(markerIds);

		return new SyncPlan(toAdd, toReplace, toRemove);
	}

	/**
	 * The id-level changes required to sync cached views with the store.
	 */
	public record SyncPlan(Set<MarkerId> toAdd, Set<MarkerId> toReplace, Set<MarkerId> toRemove) {}
}
