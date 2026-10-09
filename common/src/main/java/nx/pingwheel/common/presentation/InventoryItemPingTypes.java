package nx.pingwheel.common.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Code-defined allowed ping types for inventory item selections: the property
 * ping type policy for the same target context, plus a deduplicated
 * {@code take} only when the target's actual tags carry {@code #c:chests}.
 * Regular properties never acquire the inventory-only type.
 */
public final class InventoryItemPingTypes {
	private static final PresentationTargetSelector CHESTS = PresentationTargetSelector.of("#c:chests");
	private static final String TAKE = "take";
	private static final InventoryItemPingTypes BUILT_IN = new InventoryItemPingTypes();

	private InventoryItemPingTypes() {}

	public static InventoryItemPingTypes builtIn() {
		return BUILT_IN;
	}

	/** The ordered effective set for an inventory item's target context. */
	public List<String> effective(String registryId, Set<String> tagIds) {
		List<String> result = new ArrayList<>(PresentationPropertyPingTypes.builtIn().effective(registryId, tagIds));
		if (CHESTS.matches(registryId, tagIds) && !result.contains(TAKE)) result.add(TAKE);
		return List.copyOf(result);
	}

	public boolean allows(String pingTypeId, String registryId, Set<String> tagIds) {
		return effective(registryId, tagIds).contains(pingTypeId);
	}
}
