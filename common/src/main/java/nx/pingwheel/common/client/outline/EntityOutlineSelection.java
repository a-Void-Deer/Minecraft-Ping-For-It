package nx.pingwheel.common.client.outline;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;

/**
 * Pure, deterministic selection of entity outline specs from the store's
 * authoritative visible-winner map.
 *
 * <p>This is deliberately a pure JDK function: it consumes only the
 * {@link Map} produced by
 * {@link nx.pingwheel.common.client.marker.ClientMarkerStore#visibleWinnersInDimension}
 * (never the store directly) and the {@link PingTypeCatalog}, so it can be
 * unit tested without any game client.
 *
 * <ul>
 *   <li>Only {@link TargetKey.EntityKey} entries whose marker target is a
 *       {@link Target.EntityTarget} with the same dimension and UUID are
 *       selected; block, location, and mismatched entries are excluded even
 *       when the store already filtered them.</li>
 *   <li>Entries are processed in ascending {@link nx.pingwheel.common.domain.MarkerId}
 *       order, so the result map's iteration order is deterministic and, if
 *       two entries ever resolve to the same entity UUID, the larger marker
 *       id wins — exactly one spec per entity.</li>
 *   <li>The color is the ping type's 24-bit outline color forced opaque;
 *       an unknown ping type id falls back to opaque white.</li>
 * </ul>
 */
public final class EntityOutlineSelection {

	private EntityOutlineSelection() {}

	/**
	 * Selects one {@link EntityOutlineSpec} per entity from {@code
	 * visibleWinners}.
	 *
	 * @param visibleWinners the store's authoritative visible-winner map for
	 *                       the current dimension
	 * @param catalog        the catalog used to resolve ping type outline
	 *                       colors
	 * @return an unmodifiable map from entity UUID to spec, ordered by
	 *         ascending marker id
	 */
	public static Map<UUID, EntityOutlineSpec> select(
		Map<TargetKey, ClientMarker> visibleWinners,
		PingTypeCatalog catalog
	) {
		Objects.requireNonNull(visibleWinners, "visibleWinners");
		Objects.requireNonNull(catalog, "catalog");

		List<Map.Entry<TargetKey, ClientMarker>> ordered = visibleWinners.entrySet().stream()
			.sorted(Map.Entry.comparingByValue(Comparator.comparing(ClientMarker::id)))
			.toList();

		Map<UUID, EntityOutlineSpec> selected = new LinkedHashMap<>();

		for (Map.Entry<TargetKey, ClientMarker> entry : ordered) {
			TargetKey key = entry.getKey();
			ClientMarker marker = entry.getValue();

			if (!(key instanceof TargetKey.EntityKey entityKey)
				|| !(marker.target() instanceof Target.EntityTarget entityTarget)
				|| !entityKey.dimensionId().equals(entityTarget.dimensionId())
				|| !entityKey.entityId().equals(entityTarget.entityId())) {
				continue;
			}

			UUID entityId = entityTarget.entityId();
			int argbColor = 0xFF000000 | catalog.findById(marker.pingTypeId())
				.map(pingType -> pingType.outlineColor())
				.orElse(0xFFFFFF);

			selected.put(
				entityId,
				new EntityOutlineSpec(marker.id(), entityId, marker.pingTypeId(), argbColor));
		}

		return Collections.unmodifiableMap(selected);
	}
}
