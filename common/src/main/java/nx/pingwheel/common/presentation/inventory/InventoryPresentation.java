package nx.pingwheel.common.presentation.inventory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationAuthorization;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationSettings;

/**
 * Shared inventory manifest descriptor, registered unchanged on both endpoints
 * so the adapter and its field are negotiated and enter the policy catalogue.
 * Inventory values are delivered by the dedicated inventory route: it is a
 * {@link DeliveryMode#DEDICATED} adapter and never takes part in the framed
 * SECTION mask, capture, publication or rendering, so its collector is never
 * reached from that machinery.
 */
public final class InventoryPresentation implements PresentationAdapter {
	public static final String ADAPTER_ID = "pingforit:inventory";
	public static final String ITEMS = "pingforit:inventory.items";
	public static final int SCHEMA = 1;
	public static final InventoryPresentation INSTANCE = new InventoryPresentation();

	private static final List<PresentationField> FIELDS = List.of(
		new PresentationField(ITEMS, PresentationField.Kind.RECORD, true, 0, "inventory items"));

	private InventoryPresentation() {}

	/**
	 * The inventory field IDs allowed for one recipient and target type under the
	 * shared per-field authorization rule. The dedicated inventory delivery path
	 * uses this helper directly instead of the SECTION projection mask.
	 */
	public static Set<String> allowed(PresentationSettings settings, UUID recipient, int actualLevel,
		String targetTypeId) {
		Set<String> result = new LinkedHashSet<>();
		for (PresentationField field : FIELDS) {
			if (PresentationAuthorization.fieldAllowed(settings, recipient, actualLevel, field, targetTypeId))
				result.add(field.id());
		}
		return Set.copyOf(result);
	}

	@Override public String adapterId() { return ADAPTER_ID; }
	@Override public String modId() { return "pingforit"; }
	@Override public int schema() { return SCHEMA; }
	@Override public int minUpdateIntervalTicks() { return 20; }
	@Override public List<PresentationField> fields() { return FIELDS; }

	@Override public PresentationAdapter.DeliveryMode deliveryMode() {
		return PresentationAdapter.DeliveryMode.DEDICATED;
	}

	/** Dedicated delivery owns inventory collection; the SECTION machinery never calls this. */
	@Override public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
		return null;
	}
}
