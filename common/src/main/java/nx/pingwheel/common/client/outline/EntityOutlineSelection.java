package nx.pingwheel.common.client.outline;

import java.util.HashMap;
import java.util.Map;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;

/**
 * M7b: turns the visible entity-marker winners of one dimension into a
 * {@code locator -> ARGB colour} snapshot.
 *
 * <p>Mirrors the upstream selection rules:
 * <ul>
 *   <li>only {@link Target.EntityTarget} winners whose target dimension matches;</li>
 *   <li>only target types that are rendered as entities ({@code entity},
 *       {@code dropped_item}) — {@code entity_block} is a block target kind and is
 *       excluded;</li>
 *   <li>duplicate locators (canonicalisation can merge several parts of one entity)
 *       keep the marker with the <em>largest</em> marker id;</li>
 *   <li>colour is {@code 0xFF000000 | pingType.outlineColor()}, unknown ping types fall
 *       back to opaque white.</li>
 * </ul>
 *
 * <p>Pure JDK + domain types: no {@code net.minecraft} references, so the rules stay
 * testable without a game client.
 */
public final class EntityOutlineSelection {

	private static final PingTypeCatalog PING_TYPES = PingTypeCatalog.builtIn();
	private static final int FALLBACK_COLOR = 0xFFFFFFFF;

	private static final String TARGET_TYPE_ENTITY = "entity";
	private static final String TARGET_TYPE_DROPPED_ITEM = "dropped_item";

	private EntityOutlineSelection() {}

	/** Builds an immutable snapshot from the store's winners for {@code dimensionId}. */
	public static Map<EntityLocator, Integer> select(Iterable<ClientMarker> winners, String dimensionId) {
		if (winners == null || dimensionId == null) {
			return Map.of();
		}

		Map<EntityLocator, ClientMarker> best = new HashMap<>();

		for (ClientMarker marker : winners) {
			if (marker == null || !acceptsForEntityRendering(marker.targetTypeId())) {
				continue;
			}

			if (!(marker.target() instanceof Target.EntityTarget target)) {
				continue;
			}

			if (!target.dimensionId().equals(dimensionId)) {
				continue;
			}

			ClientMarker previous = best.get(target.locator());

			if (previous == null || marker.id().compareTo(previous.id()) > 0) {
				best.put(target.locator(), marker);
			}
		}

		if (best.isEmpty()) {
			return Map.of();
		}

		Map<EntityLocator, Integer> colors = new HashMap<>(best.size());

		for (Map.Entry<EntityLocator, ClientMarker> entry : best.entrySet()) {
			colors.put(entry.getKey(), resolveColor(entry.getValue().pingTypeId()));
		}

		return Map.copyOf(colors);
	}

	private static boolean acceptsForEntityRendering(String targetTypeId) {
		return TARGET_TYPE_ENTITY.equals(targetTypeId) || TARGET_TYPE_DROPPED_ITEM.equals(targetTypeId);
	}

	private static int resolveColor(String pingTypeId) {
		return PING_TYPES.findById(pingTypeId)
			.map(pingType -> 0xFF000000 | pingType.outlineColor())
			.orElse(FALLBACK_COLOR);
	}
}
