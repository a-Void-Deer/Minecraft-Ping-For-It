package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.util.RateLimitedDiagnostics;

/** Low-frequency snapshot discovery/failure diagnostics; never captures or serializes snapshot data for logging. */
final class InventorySnapshotDiagnostics {
	private enum Reason { UNSUPPORTED }
	private static final AtomicLongArray LAST_LOGGED = new AtomicLongArray(Reason.values().length);
	private static final long MIN_INTERVAL_NANOS = 10_000_000_000L;

	private InventorySnapshotDiagnostics() {}

	/** Only the boundary converting an exception into unavailable/fallback reports it. */
	static void failure(String operation, InventorySourceInput input, Object provider, Supplier<String> detail, Throwable failure) {
		if (failure instanceof InventorySourceUnavailableException || failure instanceof InventoryItemBudget.CaptureLimitException) return;
		var target = input.target();
		String scope = target instanceof Target.ExternalBlockTarget external ? external.providerId() : ((Target.BlockTarget) target).blockRegistryId();
		String providerClass = provider == null ? "minecraft" : provider.getClass().getName();
		RateLimitedDiagnostics.warn(operation, scope + "/" + providerClass,
			() -> "owner=" + input.readOwner() + " face=" + input.face() + " providerClass=" + providerClass
				+ " " + detail.get() + " target=" + target, failure);
	}

	static void unsupported(InventorySourceInput input, String detail) {
		int index = Reason.UNSUPPORTED.ordinal();
		long now = System.nanoTime();
		long previous = LAST_LOGGED.get(index);
		if (previous != 0L && now - previous < MIN_INTERVAL_NANOS) return;
		if (!LAST_LOGGED.compareAndSet(index, previous, now)) return;
		try {
			var target = input.target();
			if (target instanceof Target.ExternalBlockTarget external) {
				// External coordinates are placeholders; the provider identity is the diagnostic context.
				logger().debug("inventory snapshot unsupported at {} provider={} registry={} face={} ({})",
					external.dimensionId(), external.providerId(), external.expectedBlockRegistryId(), input.face(), detail);
			} else {
				var block = (Target.BlockTarget) target;
				logger().debug("inventory snapshot unsupported at {} {} {} face={} ({})", block.dimensionId(),
					block.x() + "," + block.y() + "," + block.z(), block.blockRegistryId(), input.face(), detail);
			}
		} catch (RuntimeException | LinkageError ignored) { /* diagnostics must not affect source resolution */ }
	}

	private static Logger logger() { return LoggerHolder.LOGGER; }
	private static final class LoggerHolder {
		private static final Logger LOGGER = LogManager.getLogger("pingforit");
	}
}
