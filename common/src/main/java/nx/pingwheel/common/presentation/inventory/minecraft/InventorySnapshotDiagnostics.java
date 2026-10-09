package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.concurrent.atomic.AtomicLongArray;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;

/** Low-frequency snapshot discovery diagnostics; never logs captured NBT. */
final class InventorySnapshotDiagnostics {
	private enum Reason { UNSUPPORTED }
	private static final AtomicLongArray LAST_LOGGED = new AtomicLongArray(Reason.values().length);
	private static final long MIN_INTERVAL_NANOS = 10_000_000_000L;

	private InventorySnapshotDiagnostics() {}

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
				logger().warn("inventory snapshot unsupported at {} provider={} registry={} face={} ({})",
					external.dimensionId(), external.providerId(), external.expectedBlockRegistryId(), input.face(), detail);
			} else {
				var block = (Target.BlockTarget) target;
				logger().warn("inventory snapshot unsupported at {} {} {} face={} ({})", block.dimensionId(),
					block.x() + "," + block.y() + "," + block.z(), block.blockRegistryId(), input.face(), detail);
			}
		} catch (RuntimeException | LinkageError ignored) { /* diagnostics must not affect source resolution */ }
	}

	private static Logger logger() { return LoggerHolder.LOGGER; }
	private static final class LoggerHolder {
		private static final Logger LOGGER = LogManager.getLogger("pingforit");
	}
}
