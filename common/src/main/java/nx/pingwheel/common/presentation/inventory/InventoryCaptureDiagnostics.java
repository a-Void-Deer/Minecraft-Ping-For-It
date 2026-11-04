package nx.pingwheel.common.presentation.inventory;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;

import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryObservationSnapshot;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.diagnostics.Diagnostics;

/**
 * Low-frequency inventory source diagnostics. Unexpected failures retain their
 * original bounded exception report; ordinary bounds and memory scheduling keep
 * their independent reason gate. Logging never resolves a source or initializes
 * the platform services.
 */
@NotNullByDefault
final class InventoryCaptureDiagnostics {
	@NotNullByDefault
	private enum FailureSite {
		CAPTURE, SNAPSHOT_STEP, NATIVE_STEP, LIVE_STEP, RESOLVE, SELECTION_WITNESS, SOURCE_CLOSE,
		SNAPSHOT_CLOSE, MEMORY_CLOSE, SELECTION_WITNESS_CLOSE, RESOLVE_CLOSE, SELECT_CREATE, PREVIEW_COUNT, TRACKING_COUNT,
		RUNTIME_SELECTION, INPUT_PROBE, SOURCE_PROJECTION, SNAPSHOT_PREPARE, EVIDENCE_PROBE,
		BINDING_PROBE, HANDOFF_PROBE, POOLED_PAGE;
		final nx.pingwheel.common.diagnostics.DiagnosticChannel channel =
			nx.pingwheel.common.diagnostics.DiagnosticChannel.interval("inventory-" + name(), java.time.Duration.ofSeconds(10));
	}

	private static nx.pingwheel.common.diagnostics.DiagnosticLogger failureLogger(String operation) {
		@Nullable FailureSite site = switch (operation) {
			case "capture-failed" -> FailureSite.CAPTURE;
			case "snapshot-step-failed" -> FailureSite.SNAPSHOT_STEP;
			case "native-step" -> FailureSite.NATIVE_STEP;
			case "live-step" -> FailureSite.LIVE_STEP;
			case "resolve" -> FailureSite.RESOLVE;
			case "selection-witness" -> FailureSite.SELECTION_WITNESS;
			case "source-close" -> FailureSite.SOURCE_CLOSE;
			case "snapshot-close" -> FailureSite.SNAPSHOT_CLOSE;
			case "snapshot-memory-close" -> FailureSite.MEMORY_CLOSE;
			case "selection-witness-close" -> FailureSite.SELECTION_WITNESS_CLOSE;
			case "resolve-close" -> FailureSite.RESOLVE_CLOSE;
			case "select-create" -> FailureSite.SELECT_CREATE;
			case "preview-count" -> FailureSite.PREVIEW_COUNT;
			case "tracking-count" -> FailureSite.TRACKING_COUNT;
			case "runtime/selection" -> FailureSite.RUNTIME_SELECTION;
			case "runtime/input-probe" -> FailureSite.INPUT_PROBE;
			case "runtime/source-projection" -> FailureSite.SOURCE_PROJECTION;
			case "runtime/snapshot-prepare" -> FailureSite.SNAPSHOT_PREPARE;
			case "runtime/evidence-probe" -> FailureSite.EVIDENCE_PROBE;
			case "runtime/binding-probe" -> FailureSite.BINDING_PROBE;
			case "runtime/handoff-probe" -> FailureSite.HANDOFF_PROBE;
			case "runtime/pooled-page" -> FailureSite.POOLED_PAGE;
			default -> null;
		};
		var logger = Diagnostics.server().atDetailed();
		return site == null ? logger : logger.rateLimited(site.channel);
	}

	private enum Reason {
		BOUND_EXCEEDED("bound-exceeded"),
		MEMORY_DEFERRED("memory-deferred");

		private final String token;

		Reason(String token) {
			this.token = token;
		}
	}

	/** Ordinary reason gates advance even while reporting is disabled. */
	private static final long MIN_INTERVAL_NANOS = 10_000_000_000L;
	private static final AtomicLongArray LAST_LOGGED = new AtomicLongArray(Reason.values().length);

	private InventoryCaptureDiagnostics() {}

	static void captureFailed(InventorySourceInput input, Throwable failure) {
		warn("capture-failed", input, null, () -> "stage=snapshot-prepare", failure);
	}

	static void snapshotStepFailed(InventorySourceInput input, Throwable failure) {
		warn("snapshot-step-failed", input, null, () -> "stage=snapshot-step", failure);
	}

	static void warn(String operation, InventorySourceInput input, @Nullable SourceKey source, Supplier<String> detail, Throwable failure) {
		if (expectedFailure(failure)) return;
		failureLogger(operation).failure(Level.WARN, "inventory/" + operation, "source-unavailable",
			() -> context(input) + (source == null ? "" : " source=" + source) + " " + detail.get()
				+ (input.target() instanceof Target.ExternalBlockTarget external ? " locator=" + external.providerLocator() : ""), failure);
	}

	static boolean expectedFailure(Throwable failure) {
		@Nullable Throwable reason = failure instanceof InventorySourceAccess.CursorlessReadFailure ? failure.getCause() : failure;
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
		boundExceeded(input, () -> detail);
	}

	static void boundExceeded(InventorySourceInput input, Supplier<String> detail) {
		log(Reason.BOUND_EXCEEDED, input, detail, null);
	}

	static void boundExceeded(InventorySourceInput input, String detail, Throwable failure) {
		log(Reason.BOUND_EXCEEDED, input, () -> detail, failure);
	}

	static void memoryDeferred(InventorySourceInput input) {
		log(Reason.MEMORY_DEFERRED, input, () -> "retained-capacity", null);
	}

	private static void log(Reason reason, InventorySourceInput input, Supplier<String> detail, @Nullable Throwable failure) {
		long now = System.nanoTime();
		int index = reason.ordinal();
		long last = LAST_LOGGED.get(index);
		if (last != 0L && now - last < MIN_INTERVAL_NANOS) return;
		if (!LAST_LOGGED.compareAndSet(index, last, now)) return;
		Level level = reason == Reason.MEMORY_DEFERRED ? Level.DEBUG : Level.WARN;
		var logger = reason == Reason.MEMORY_DEFERRED ? Diagnostics.server().atDebug() : Diagnostics.server().atDetailed();
		if (failure == null) logger.event(level, "inventory/snapshot", reason.token,
			() -> context(input) + " detail=" + detail.get());
		else logger.failure(level, "inventory/snapshot", reason.token,
			() -> "reason=" + reason.token + " " + context(input) + " detail=" + detail.get(), failure);
	}
}
