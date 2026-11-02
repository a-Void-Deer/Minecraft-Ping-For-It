package nx.pingwheel.common.presentation.inventory;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Closed detached observation boundary for the shared Runtime's lane dispatch. Identity and
 * quantity are independent of display. An absent observation is an observed empty address only after
 * a successful getter; source failure is carried separately. No native data or second budget lives here.
 */
@NotNullByDefault
public sealed interface InventoryResourceValue permits InventoryDomainCodec.Item, InventoryFluidValue {
	InventoryScanner.Key key();
	/** ITEM count or FLUID U (1/81000 bucket), exact nonnegative long. */
	long amount();
	String label();
	@Nullable String displayJson();
	boolean aggregateRequired();
	/** Constant-cost amount replacement over already detached display and identity. */
	InventoryResourceValue withAmount(long amount);
	/** Source migration aliases; units remain defined by resourceKind, never by the accessor name. */
	default long count() { return amount(); }
	default boolean stripped() { return aggregateRequired(); }
	default InventoryResourceKind resourceKind() { return key().kind(); }
	default String resourceId() { return key().resourceId(); }
}
