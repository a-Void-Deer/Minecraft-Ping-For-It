package nx.pingwheel.common.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable catalog of {@link PingType}s in explicit declaration order.
 */
public final class PingTypeCatalog {

	private final List<PingType> entries;
	private final Map<String, PingType> byId;

	public PingTypeCatalog(List<PingType> entries) {
		Objects.requireNonNull(entries, "entries");

		List<PingType> copied = List.copyOf(entries);
		Map<String, PingType> map = new LinkedHashMap<>();

		for (PingType pingType : copied) {
			if (map.putIfAbsent(pingType.id(), pingType) != null) {
				throw new IllegalArgumentException("duplicate ping type id: " + pingType.id());
			}
		}

		this.entries = copied;
		this.byId = Map.copyOf(map);
	}

	/**
	 * The confirmed built-in ping type catalog, in declaration order.
	 */
	public static PingTypeCatalog builtIn() {
		return new PingTypeCatalog(List.of(
			new PingType("attention", "pingforit.ping_type.attention.phrase", "pingforit.ping_type.attention", 0xFFC247, 0xFFAA00, Optional.empty()),
			new PingType("danger", "pingforit.ping_type.danger.phrase", "pingforit.ping_type.danger", 0xFF4D4D, 0xFF5555, Optional.empty()),
			new PingType("go_to", "pingforit.ping_type.go_to.phrase", "pingforit.ping_type.go_to", 0x4DB8FF, 0x55FFFF, Optional.empty()),
			new PingType("loot", "pingforit.ping_type.loot.phrase", "pingforit.ping_type.loot", 0x52D273, 0x55FF55, Optional.empty()),
			new PingType("destroy", "pingforit.ping_type.destroy.phrase", "pingforit.ping_type.destroy", 0xE66BDD, 0xF0A0EA, Optional.empty()),
			new PingType("take", "pingforit.ping_type.take.phrase", "pingforit.ping_type.take", 0x52D273, 0x55FF55, Optional.empty()),
			new PingType("request", "pingforit.ping_type.request.phrase", "pingforit.ping_type.request", 0x8C8CFF, 0xB8B8FF, Optional.empty())
		));
	}

	/**
	 * The ping types in explicit declaration order.
	 */
	public List<PingType> entries() {
		return entries;
	}

	/**
	 * Looks up a ping type by id; empty if absent.
	 */
	public Optional<PingType> findById(String id) {
		return Optional.ofNullable(byId.get(id));
	}
}
