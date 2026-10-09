package nx.pingwheel.common.presentation.inventory;

import java.util.concurrent.atomic.AtomicLongArray;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import nx.pingwheel.common.domain.Target;

/**
 * Low-frequency inventory snapshot-capture diagnostics. One bounded line per
 * reason and interval carries the target, the frozen face and a fixed reason
 * token; raw NBT, item data and arbitrary exception text never reach the log.
 * The logger is resolved lazily so pure tests never trigger a platform
 * service lookup just by exercising a capture.
 */
final class InventoryCaptureDiagnostics {

	private enum Reason {
		CAPTURE_FAILED("capture-failed"),
		SNAPSHOT_STEP_FAILED("snapshot-step-failed"),
		BOUND_EXCEEDED("bound-exceeded"),
		MEMORY_DEFERRED("memory-deferred");

		private final String token;

		Reason(String token) {
			this.token = token;
		}
	}

	/** At most one line per reason per interval; capture failures never log per slot. */
	private static final long MIN_INTERVAL_NANOS = 10_000_000_000L;
	private static final AtomicLongArray LAST_LOGGED = new AtomicLongArray(Reason.values().length);

	private InventoryCaptureDiagnostics() {}

	static void captureFailed(InventorySourceInput input, Throwable failure) {
		log(Reason.CAPTURE_FAILED, input, failure == null ? "unknown" : failure.getClass().getSimpleName());
	}

	static void snapshotStepFailed(InventorySourceInput input, Throwable failure) {
		log(Reason.SNAPSHOT_STEP_FAILED, input, failure == null ? "unknown" : failure.getClass().getSimpleName());
	}

	static void boundExceeded(InventorySourceInput input, String detail) {
		log(Reason.BOUND_EXCEEDED, input, detail);
	}

	static void memoryDeferred(InventorySourceInput input) {
		log(Reason.MEMORY_DEFERRED, input, "retained-capacity");
	}

	private static void log(Reason reason, InventorySourceInput input, String detail) {
		long now = System.nanoTime();
		int index = reason.ordinal();
		long last = LAST_LOGGED.get(index);
		if (last != 0L && now - last < MIN_INTERVAL_NANOS) return;
		if (!LAST_LOGGED.compareAndSet(index, last, now)) return;
		try {
			var target = input.target();
			if (target instanceof Target.ExternalBlockTarget external) {
				// External coordinates are placeholders; the provider identity is the diagnostic context.
				logger().warn("inventory snapshot {} at {} provider={} registry={} face={} ({})", reason.token,
					external.dimensionId(), external.providerId(), external.expectedBlockRegistryId(), input.face(), detail);
			} else {
				var block = (Target.BlockTarget) target;
				logger().warn("inventory snapshot {} at {} {} {} face={} ({})", reason.token,
					block.dimensionId(), block.x() + "," + block.y() + "," + block.z(), block.blockRegistryId(), input.face(), detail);
			}
		} catch (RuntimeException | LinkageError ignored) { /* diagnostics never affect capture */ }
	}

	private static Logger logger() {
		return LoggerHolder.LOGGER;
	}

	private static final class LoggerHolder {
		private static final Logger LOGGER = LogManager.getLogger("pingforit");
	}
}
