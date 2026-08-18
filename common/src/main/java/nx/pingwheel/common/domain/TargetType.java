package nx.pingwheel.common.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A code-defined classification rule with a fixed numeric priority and an
 * explicitly ordered set of ping types.
 *
 * <p>Lower numeric priority means higher precedence. Equal priorities resolve
 * by declaration order (see {@link TargetTypeCatalog#resolutionOrder()}).
 *
 * <p>{@link #kind()} is only a coarse category (entity/block/location), not a
 * matching rule: the phase-3 matchers must still distinguish finer
 * specializations such as {@code dropped_item} from the generic {@code entity}
 * target type. No kind-only {@code matches} implementation or matcher seam is
 * introduced in this phase.
 */
public record TargetType(
	String id,
	int priority,
	TargetKind kind,
	List<PingType> pingTypes,
	PingType defaultPingType
) {

	public TargetType {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(defaultPingType, "defaultPingType");

		if (id.isBlank()) {
			throw new IllegalArgumentException("id must not be blank");
		}

		pingTypes = List.copyOf(Objects.requireNonNull(pingTypes, "pingTypes"));

		if (pingTypes.isEmpty()) {
			throw new IllegalArgumentException("pingTypes must not be empty");
		}

		Set<String> seen = new HashSet<>();

		for (PingType pingType : pingTypes) {
			if (!seen.add(pingType.id())) {
				throw new IllegalArgumentException("duplicate ping type in list: " + pingType.id());
			}
		}

		if (!pingTypes.contains(defaultPingType)) {
			throw new IllegalArgumentException(
				"defaultPingType must be a member of pingTypes: " + defaultPingType.id());
		}
	}
}
