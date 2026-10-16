package nx.pingwheel.common.presentation.inventory;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryObservationSnapshot;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.util.RateLimitedDiagnostics;

/**
 * Low-frequency inventory source diagnostics. Unexpected failures retain their
 * original bounded exception report; ordinary bounds and memory scheduling keep
 * their independent reason gate. Logging never resolves a source or initializes
 * the platform services.
 */
final class InventoryCaptureDiagnostics {

	private enum Reason {
		BOUND_EXCEEDED("bound-exceeded"),
		MEMORY_DEFERRED("memory-deferred");

		private final String token;

		Reason(String token) {
			this.token = token;
		}
	}

	/** At most one line per ordinary reason per interval; failures use the shared bounded gate. */
	private static final long MIN_INTERVAL_NANOS = 10_000_000_000L;
	private static final AtomicLongArray LAST_LOGGED = new AtomicLongArray(Reason.values().length);

	private InventoryCaptureDiagnostics() {}

	static void captureFailed(InventorySourceInput input, Throwable failure) {
		warn("capture-failed", input, null, () -> "stage=snapshot-prepare", failure);
	}

	static void snapshotStepFailed(InventorySourceInput input, Throwable failure) {
		warn("snapshot-step-failed", input, null, () -> "stage=snapshot-step", failure);
	}

	static void warn(String operation, InventorySourceInput input, SourceKey source, Supplier<String> detail, Throwable failure) {
		if (expectedFailure(failure)) return;
		RateLimitedDiagnostics.warn("inventory/" + operation, scope(input.target()),
			() -> context(input) + (source == null ? "" : " source=" + source) + " " + detail.get()
				+ (input.target() instanceof Target.ExternalBlockTarget external ? " locator=" + external.providerLocator() : ""), failure);
	}

	static boolean expectedFailure(Throwable failure) {
		Throwable reason = failure instanceof InventorySourceAccess.CursorlessReadFailure ? failure.getCause() : failure;
		return reason instanceof InventoryItemBudget.CaptureLimitException
			|| reason instanceof InventoryObservationSnapshot.LimitException
			|| reason instanceof InventorySourceUnavailableException;
	}

	static String scope(Target target) {
		return target instanceof Target.ExternalBlockTarget external
			? external.providerId() + "/" + external.expectedBlockRegistryId()
			: target instanceof Target.BlockTarget block ? "block/" + block.blockRegistryId() : target.getClass().getName();
	}

	static String context(InventorySourceInput input) {
		return "face=" + input.face() + " readOwner=" + input.readOwner() + " " + targetContext(input.target());
	}

	static String targetContext(Target target) {
		if (target instanceof Target.ExternalBlockTarget external) {
			// Detached external coordinates are placeholders, not a physical read position.
			return "dimension=" + external.dimensionId() + " provider=" + external.providerId()
				+ " stableId=" + external.stableTargetId() + " registry=" + external.expectedBlockRegistryId();
		}
		if (target instanceof Target.BlockTarget block) {
			return "dimension=" + block.dimensionId() + " position=" + block.x() + "," + block.y() + "," + block.z()
				+ " registry=" + block.blockRegistryId();
		}
		return "target=" + target;
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
			if (reason == Reason.MEMORY_DEFERRED) {
				if (logger().isDebugEnabled()) logger().debug("inventory snapshot {} {} ({})", reason.token, context(input), detail);
			} else if (logger().isWarnEnabled()) logger().warn("inventory snapshot {} {} ({})", reason.token, context(input), detail);
		} catch (RuntimeException | LinkageError ignored) { /* diagnostics never affect capture */ }
	}

	private static Logger logger() {
		return LoggerHolder.LOGGER;
	}

	private static final class LoggerHolder {
		private static final Logger LOGGER = LogManager.getLogger("pingforit");
	}
}
