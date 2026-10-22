package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;

import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.diagnostics.Diagnostics;

/** Low-frequency snapshot discovery/failure diagnostics; never captures or serializes snapshot data for logging. */
@NotNullByDefault
final class InventorySnapshotDiagnostics {
	private enum Reason { UNSUPPORTED }
	private static final AtomicLongArray LAST_LOGGED = new AtomicLongArray(Reason.values().length);
	private static final long MIN_INTERVAL_NANOS = 10_000_000_000L;

	private InventorySnapshotDiagnostics() {}

	/** Only the boundary converting an exception into unavailable/fallback reports it. */
	static void failure(String operation, InventorySourceInput input, @Nullable Object provider, Supplier<String> detail, Throwable failure) {
		if (failure instanceof InventorySourceUnavailableException || failure instanceof InventoryItemBudget.CaptureLimitException) return;
		var target = input.target();
		String scope = target instanceof Target.ExternalBlockTarget external ? external.providerId() : ((Target.BlockTarget) target).blockRegistryId();
		String providerClass = provider == null ? "minecraft" : provider.getClass().getName();
		Diagnostics.warn(operation, scope + "/" + providerClass,
			() -> "owner=" + input.readOwner() + " face=" + input.face() + " providerClass=" + providerClass
				+ " " + detail.get() + " target=" + target, failure);
	}

	static void unsupported(InventorySourceInput input, String detail) {
		int index = Reason.UNSUPPORTED.ordinal();
		long now = System.nanoTime();
		long previous = LAST_LOGGED.get(index);
		if (previous != 0L && now - previous < MIN_INTERVAL_NANOS) return;
		if (!LAST_LOGGED.compareAndSet(index, previous, now)) return;
		Diagnostics.domainApprovedEvent(Level.DEBUG, "inventory/snapshot-discovery", "minecraft", "unsupported",
			() -> {
				var target = input.target();
				String context = target instanceof Target.ExternalBlockTarget external
					? "dimension=" + external.dimensionId() + " provider=" + external.providerId() + " registry=" + external.expectedBlockRegistryId()
					: "target=" + target;
				return context + " face=" + input.face() + " detail=" + detail;
			});
	}
}
