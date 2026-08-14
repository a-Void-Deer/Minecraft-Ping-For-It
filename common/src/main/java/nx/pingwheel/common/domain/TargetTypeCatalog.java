package nx.pingwheel.common.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable catalog of {@link TargetType}s in explicit declaration order,
 * plus a deterministic resolution order (ascending priority, then declaration
 * order) computed explicitly.
 */
public final class TargetTypeCatalog {

	private final List<TargetType> entries;
	private final Map<String, TargetType> byId;
	private final List<TargetType> resolutionOrder;

	public TargetTypeCatalog(List<TargetType> entries) {
		Objects.requireNonNull(entries, "entries");

		List<TargetType> copied = List.copyOf(entries);
		Map<String, TargetType> map = new LinkedHashMap<>();

		for (TargetType targetType : copied) {
			if (map.putIfAbsent(targetType.id(), targetType) != null) {
				throw new IllegalArgumentException("duplicate target type id: " + targetType.id());
			}
		}

		this.entries = copied;
		// Lookup-only map: resolution/declaration ordering is carried by the
		// `entries` and `resolutionOrder` lists, never by this map's iteration order.
		this.byId = Map.copyOf(map);
		this.resolutionOrder = computeResolutionOrder(copied);
	}

	/**
	 * The confirmed built-in target type catalog, in declaration order.
	 */
	public static TargetTypeCatalog builtIn() {
		PingTypeCatalog pingTypes = PingTypeCatalog.builtIn();

		return new TargetTypeCatalog(List.of(
			new TargetType(
				"dropped_item",
				100,
				TargetKind.ENTITY,
				List.of(pingType(pingTypes, "loot"), pingType(pingTypes, "attention"), pingType(pingTypes, "danger")),
				pingType(pingTypes, "loot")
			),
			new TargetType(
				"entity",
				200,
				TargetKind.ENTITY,
				List.of(pingType(pingTypes, "attention"), pingType(pingTypes, "danger"), pingType(pingTypes, "go_to")),
				pingType(pingTypes, "attention")
			),
			new TargetType(
				"block",
				300,
				TargetKind.BLOCK,
				List.of(pingType(pingTypes, "attention"), pingType(pingTypes, "go_to"), pingType(pingTypes, "danger")),
				pingType(pingTypes, "attention")
			),
			new TargetType(
				"location",
				Integer.MAX_VALUE,
				TargetKind.LOCATION,
				List.of(pingType(pingTypes, "go_to"), pingType(pingTypes, "attention"), pingType(pingTypes, "danger")),
				pingType(pingTypes, "go_to")
			)
		));
	}

	/**
	 * The target types in explicit declaration order.
	 */
	public List<TargetType> entries() {
		return entries;
	}

	/**
	 * The deterministic resolution order: ascending priority, then original
	 * declaration order for equal priorities.
	 *
	 * <p>Implemented explicitly by inserting each type (in declaration order)
	 * before the first already-inserted type with a strictly greater priority,
	 * so the result never depends on map iteration order or on any library sort
	 * stability guarantee.
	 */
	public List<TargetType> resolutionOrder() {
		return resolutionOrder;
	}

	/**
	 * Looks up a target type by id; empty if absent.
	 */
	public Optional<TargetType> findById(String id) {
		return Optional.ofNullable(byId.get(id));
	}

	private static List<TargetType> computeResolutionOrder(List<TargetType> declared) {
		List<TargetType> ordered = new ArrayList<>(declared.size());

		for (TargetType candidate : declared) {
			int insertAt = ordered.size();

			for (int i = 0; i < ordered.size(); i++) {
				if (candidate.priority() < ordered.get(i).priority()) {
					insertAt = i;
					break;
				}
			}

			ordered.add(insertAt, candidate);
		}

		return List.copyOf(ordered);
	}

	private static PingType pingType(PingTypeCatalog catalog, String id) {
		return catalog.findById(id)
			.orElseThrow(() -> new IllegalStateException("built-in ping type missing: " + id));
	}
}
