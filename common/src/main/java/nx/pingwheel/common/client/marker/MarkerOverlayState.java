package nx.pingwheel.common.client.marker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.name.ClientTargetNameDecoder;
import nx.pingwheel.common.name.ClientTargetNameStore;
import nx.pingwheel.common.name.TargetNameComposer;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.render.WorldRenderContext;
import org.jetbrains.annotations.Nullable;

import static nx.pingwheel.common.CommonClient.Game;

/**
 * Main-thread-confined cache of per-marker {@link MarkerView}s backing the
 * world overlay renderers.
 *
 * <p>Every world render frame {@link #prepare} synchronizes the cached views
 * with the authoritative {@link ClientMarkerStore} and the authoritative
 * {@link ClientTargetNameStore}:
 * <ul>
 *   <li>a view is created for every newly seen {@link MarkerId};</li>
 *   <li>an existing view's payload is replaced in place when the same id is
 *       re-applied, so view instances survive across frames;</li>
 *   <li>views whose marker disappeared from the store are removed;</li>
 *   <li>every view's displayed target name is kept in step with the name
 *       store by marker id: the name JSON is decoded with the current level's
 *       registry access and applied without rebuilding the view, falling back
 *       to the unknown name when the store has no entry or the payload is
 *       malformed. The per-id {@code appliedNames} cache owns the
 *       reset/update decision: a name is only re-decoded when the stored
 *       payload for that id actually changed — the every-frame sync is a
 *       compare, never a forced re-decode — and replacing a view's payload
 *       in place never resets its displayed name;</li>
 *   <li>only views whose target dimension is the current dimension are
 *       updated (the renderers skip other dimensions anyway);</li>
 *   <li>the exposed render list is immutable and sorted by descending
 *       distance, exactly like the legacy ping repository.</li>
 * </ul>
 *
 * <p>Calling {@link #prepare} with a missing world or a {@code null} store or
 * name store (for example after leaving the server) clears every cached view.
 * No logging happens here at all: the store and the client runtime already own
 * all mutation logging, so nothing in this class can produce per-frame log
 * spam.
 *
 * <p>Thread safety: main-thread-confined, same as the {@link ClientMarkerStore}
 * it mirrors. Concurrent access is unsupported.
 */
public final class MarkerOverlayState {

	public static final MarkerOverlayState INSTANCE = new MarkerOverlayState();

	private final Map<MarkerId, MarkerView> views = new LinkedHashMap<>();
	private final Map<MarkerId, TargetNameJson> appliedNames = new LinkedHashMap<>();
	private List<MarkerView> renderViews = List.of();

	private MarkerOverlayState() {}

	/**
	 * Synchronizes the cached views with {@code store} and their displayed
	 * names with {@code nameStore}, then recomputes the current-dimension
	 * views for this render frame.
	 *
	 * <p>With a {@code null} store, name store, or context — or without a live
	 * level — the state is cleared instead.
	 */
	public void prepare(
		@Nullable WorldRenderContext ctx,
		@Nullable ClientMarkerStore store,
		@Nullable ClientTargetNameStore nameStore
	) {
		Minecraft game = Game;

		if (ctx == null || store == null || nameStore == null || game == null || game.level == null) {
			clear();
			return;
		}

		// The store retains hidden synchronized records and stale records for
		// packet/lifecycle handling. Only records inside their independent
		// display lifetime belong in the visual cache.
		List<ClientMarker> markers = store.renderMarkers();
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

		syncNames(game.level.registryAccess(), nameStore);

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
	 * Keeps every cached view's displayed target name in step with the name
	 * store. The per-id {@code appliedNames} cache owns the reset/update
	 * decision: the name JSON for a marker id is decoded (with the current
	 * level's registry access) and applied only when the stored payload for
	 * that id changed since the last sync, so running every frame merely
	 * compares and never forces a re-decode; a missing store entry or a
	 * malformed payload yields the unknown fallback. A payload replacement
	 * ({@link MarkerView#replacePayload}) never resets a name — this cache is
	 * the single authority for it. Views whose marker disappeared are
	 * forgotten, mirroring the marker-id-driven removal.
	 */
	private void syncNames(RegistryAccess registryAccess, ClientTargetNameStore nameStore) {
		appliedNames.keySet().removeIf(id -> !views.containsKey(id));

		for (Map.Entry<MarkerId, MarkerView> entry : views.entrySet()) {
			MarkerId id = entry.getKey();
			TargetNameJson current = nameStore.find(id).orElse(null);

			if (Objects.equals(current, appliedNames.get(id))) {
				continue;
			}

			appliedNames.put(id, current);

			if (current != null) {
				entry.getValue().replaceTargetName(ClientTargetNameDecoder.decode(id, current, registryAccess));
			} else {
				entry.getValue().replaceTargetName(TargetNameComposer.unknown());
			}
		}
	}

	/**
	 * The immutable render list for this frame, sorted by descending distance.
	 */
	public List<MarkerView> renderViews() {
		return renderViews;
	}

	/**
	 * Looks up the position presented by a marker view, if the cached payload is
	 * still the expected target in the expected dimension and the view has been
	 * rendered at least once.
	 *
	 * <p>This is main-thread-confined like {@link #prepare}. The target guard is
	 * intentional: a view is retained by marker id, so a same-id payload
	 * replacement must never expose the old entity's frozen position to
	 * cancellation.
	 */
	public Optional<WorldVector> lookupPresentationPosition(
		MarkerId markerId,
		Target expectedTarget,
		String expectedDimension
	) {
		Objects.requireNonNull(markerId, "markerId");
		Objects.requireNonNull(expectedTarget, "expectedTarget");
		Objects.requireNonNull(expectedDimension, "expectedDimension");

		MarkerView view = views.get(markerId);

		if (view == null) {
			return Optional.empty();
		}

		return matchingPresentationPosition(view, expectedTarget, expectedDimension);
	}

	/**
	 * Applies the payload/dimension guard before exposing a cached view position.
	 * Kept package-private so the identity rule can be tested without a live
	 * client render context.
	 */
	static Optional<WorldVector> matchingPresentationPosition(
		MarkerView view,
		Target expectedTarget,
		String expectedDimension
	) {
		Objects.requireNonNull(view, "view");
		Objects.requireNonNull(expectedTarget, "expectedTarget");
		Objects.requireNonNull(expectedDimension, "expectedDimension");

		if (!view.matchesTarget(expectedTarget, expectedDimension)) {
			return Optional.empty();
		}

		return view.presentationPosition()
			.map(position -> new WorldVector(position.x, position.y, position.z));
	}

	/**
	 * Drops every cached view and the render list.
	 */
	public void clear() {
		views.clear();
		appliedNames.clear();
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
