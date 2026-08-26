package nx.pingwheel.common.client.outline;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;

/**
 * Pure, deterministic selection of block outline specs from the store's
 * authoritative visible-winner map.
 *
 * <p>This is deliberately a pure JDK function: it consumes only the
 * {@link Map} produced by
 * {@link nx.pingwheel.common.client.marker.ClientMarkerStore#visibleWinnersInDimension}
 * (never the store directly) and the {@link PingTypeCatalog}, so it can be
 * unit tested without any game client.
 *
 * <ul>
 *   <li>Only {@link TargetKey.BlockKey} entries whose marker target is a
 *       {@link Target.BlockTarget} with the exact same dimension, x/y/z
 *       position, and block registry id are selected; entity, location, and
 *       any mismatched entries are excluded even when the store already
 *       filtered them.</li>
 *   <li>Only markers whose authoritative {@code targetTypeId} is a block
 *       rendering participant ({@code block} or {@code entity_block}, see
 *       {@link BlockModelOutlineRoute#acceptsForBlockRendering}) are selected;
 *       a block-shaped marker that was classified as an entity or location
 *       cannot drive the block outline.</li>
 *   <li>Entries are processed in ascending {@link nx.pingwheel.common.domain.MarkerId}
 *       order, so the result map's iteration order is deterministic and, if
 *       two entries ever resolve to the same block key, the larger marker id
 *       wins — exactly one spec per block key.</li>
 *   <li>The spec retains the marker's authoritative {@code targetTypeId} (the
 *       renderer routes on it) and the color is the ping type's 24-bit outline
 *       color forced opaque; an unknown ping type id falls back to opaque
 *       white.</li>
 * </ul>
 */
public final class BlockOutlineSelection {

	private BlockOutlineSelection() {}

	/**
	 * Selects one {@link BlockOutlineSpec} per block key from {@code
	 * visibleWinners}.
	 *
	 * @param visibleWinners the store's authoritative visible-winner map for
	 *                       the current dimension
	 * @param catalog        the catalog used to resolve ping type outline
	 *                       colors
	 * @return an unmodifiable map from block key to spec, ordered by ascending
	 *         marker id
	 */
	public static Map<TargetKey.BlockKey, BlockOutlineSpec> select(
		Map<TargetKey, ClientMarker> visibleWinners,
		PingTypeCatalog catalog
	) {
		Objects.requireNonNull(visibleWinners, "visibleWinners");
		Objects.requireNonNull(catalog, "catalog");

		List<Map.Entry<TargetKey, ClientMarker>> ordered = visibleWinners.entrySet().stream()
			.sorted(Map.Entry.comparingByValue(Comparator.comparing(ClientMarker::id)))
			.toList();

		Map<TargetKey.BlockKey, BlockOutlineSpec> selected = new LinkedHashMap<>();

		for (Map.Entry<TargetKey, ClientMarker> entry : ordered) {
			TargetKey key = entry.getKey();
			ClientMarker marker = entry.getValue();

			if (!(key instanceof TargetKey.BlockKey blockKey)
				|| !(marker.target() instanceof Target.BlockTarget blockTarget)
				|| !blockKey.dimensionId().equals(blockTarget.dimensionId())
				|| blockKey.x() != blockTarget.x()
				|| blockKey.y() != blockTarget.y()
				|| blockKey.z() != blockTarget.z()
				|| !blockKey.blockRegistryId().equals(blockTarget.blockRegistryId())
				|| !BlockModelOutlineRoute.acceptsForBlockRendering(marker.targetTypeId())) {
				continue;
			}

			int argbColor = 0xFF000000 | catalog.findById(marker.pingTypeId())
				.map(pingType -> pingType.outlineColor())
				.orElse(0xFFFFFF);

			selected.put(
				blockKey,
				new BlockOutlineSpec(
					marker.id(), blockKey, marker.targetTypeId(), marker.pingTypeId(), argbColor));
		}

		return Collections.unmodifiableMap(selected);
	}

	/**
	 * Selects provider-owned external block winners. Their renderer resolves the
	 * current native state, baked-model eligibility, and transformed shape
	 * through the provider at render time; unsuccessful model attempts retain
	 * the native VoxelShape fallback.
	 */
	public static Map<TargetKey.ExternalBlockKey, ExternalBlockOutlineSpec> selectExternal(
		Map<TargetKey, ClientMarker> visibleWinners,
		PingTypeCatalog catalog
	) {
		Objects.requireNonNull(visibleWinners, "visibleWinners");
		Objects.requireNonNull(catalog, "catalog");

		List<Map.Entry<TargetKey, ClientMarker>> ordered = visibleWinners.entrySet().stream()
			.sorted(Map.Entry.comparingByValue(Comparator.comparing(ClientMarker::id)))
			.toList();

		Map<TargetKey.ExternalBlockKey, ExternalBlockOutlineSpec> selected = new LinkedHashMap<>();

		for (Map.Entry<TargetKey, ClientMarker> entry : ordered) {
			TargetKey key = entry.getKey();
			ClientMarker marker = entry.getValue();

			if (!(key instanceof TargetKey.ExternalBlockKey externalKey)
				|| !(marker.target() instanceof Target.ExternalBlockTarget externalTarget)
				|| !externalKey.equals(marker.targetKey())
				|| !BlockModelOutlineRoute.acceptsForBlockRendering(marker.targetTypeId())) {
				continue;
			}

			int argbColor = 0xFF000000 | catalog.findById(marker.pingTypeId())
				.map(pingType -> pingType.outlineColor())
				.orElse(0xFFFFFF);

			selected.put(
				externalKey,
				new ExternalBlockOutlineSpec(
					marker.id(), externalKey, externalTarget,
					marker.targetTypeId(), marker.pingTypeId(), argbColor));
		}

		return Collections.unmodifiableMap(selected);
	}
}
