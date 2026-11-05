package nx.pingwheel.common.presentation.inventory;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;

import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryObservationSnapshot;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
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
		BINDING_PROBE, HANDOFF_PROBE, POOLED_PAGE, CATALOG_CREATE, METADATA_VALIDATE, PUBLICATION_VALIDATE;
		final nx.pingwheel.common.diagnostics.DiagnosticChannel channel =
			nx.pingwheel.common.diagnostics.DiagnosticChannel.interval("inventory-" + name(), java.time.Duration.ofSeconds(10));
	}

	/** DEBUG-only expected unavailability sites; terminal lane failures cannot suppress a sibling source. */
	@NotNullByDefault
	private enum ExpectedSourceSite {
		INPUT_PROBE("inventory/runtime/input-probe"),
		EVIDENCE_PROBE("inventory/runtime/evidence-probe"),
		SNAPSHOT_PREPARE("inventory/runtime/snapshot-prepare"),
		RESOLVE("inventory/resolve"),
		CAPTURE_FAILED("inventory/capture-failed"),
		PREPARE("inventory/runtime/prepare");

		private final String site;
		ExpectedSourceSite(String site) {
			this.site = site;
		}

		private static @Nullable ExpectedSourceSite forOperation(String operation) {
			return switch (operation) {
				case "runtime/input-probe" -> INPUT_PROBE;
				case "runtime/evidence-probe" -> EVIDENCE_PROBE;
				case "runtime/snapshot-prepare" -> SNAPSHOT_PREPARE;
				case "resolve" -> RESOLVE;
				case "capture-failed" -> CAPTURE_FAILED;
				case "runtime/prepare" -> PREPARE;
				default -> null;
			};
		}
	}

	/** Repeatable tick-level lane admission failures keep independent bounded history. */
	@NotNullByDefault
	private enum PreviewLaneFailure {
		MEMORY, ATTACH, PUBLISHER;

		private final nx.pingwheel.common.diagnostics.DiagnosticChannel channel =
			nx.pingwheel.common.diagnostics.DiagnosticChannel.interval("inventory-preview-lane-" + name(), java.time.Duration.ofSeconds(10));

		private static @Nullable PreviewLaneFailure forReason(String reason) {
			return switch (reason) {
				case "memory" -> MEMORY;
				case "attach" -> ATTACH;
				case "publisher" -> PUBLISHER;
				default -> null;
			};
		}
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
			case "runtime/pooled-read" -> FailureSite.POOLED_PAGE;
			case "runtime/catalog-create" -> FailureSite.CATALOG_CREATE;
			case "runtime/metadata-validate" -> FailureSite.METADATA_VALIDATE;
			case "runtime/publication-validate" -> FailureSite.PUBLICATION_VALIDATE;
			default -> null;
		};
		var logger = Diagnostics.server().atNormal();
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

	/** Ordinary reason gates advance after profile eligibility even while backend output is disabled. */
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
		warn(operation, handling(operation), input, source, detail, failure);
	}

	static void warn(String operation, String handling, InventorySourceInput input, @Nullable SourceKey source, Supplier<String> detail, Throwable failure) {
		if (expectedFailure(failure)) return;
		failureLogger(operation).failure(Level.WARN, "inventory/" + operation, handling,
			() -> context(input) + (source == null ? "" : " source=" + source) + " " + detail.get()
				+ (input.target() instanceof Target.ExternalBlockTarget external ? " locator=" + external.providerLocator() : ""), failure);
	}

	static boolean expectedFailure(Throwable failure) {
		if (failure instanceof InventorySharedRead.ReadFailure typed) {
			@Nullable Throwable cause = typed.failure();
			return cause == null || cause instanceof nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.CapacityExceededException
				|| expectedFailure(cause);
		}
		@Nullable Throwable reason = failure instanceof InventorySourceAccess.CursorlessReadFailure ? failure.getCause() : failure;
		return reason instanceof InventoryItemBudget.CaptureLimitException
			|| reason instanceof InventoryObservationSnapshot.LimitException
			|| reason instanceof InventorySourceUnavailableException;
	}

	/** Narrower than {@link #expectedFailure}: only the expected unavailability boundary changes disclosure. */
	static boolean expectedSourceUnavailable(@Nullable Throwable failure) {
		if (failure instanceof InventorySharedRead.ReadFailure typed) failure = typed.failure();
		if (failure instanceof InventorySourceAccess.CursorlessReadFailure) failure = failure.getCause();
		return failure instanceof InventorySourceUnavailableException;
	}

	/**
	 * One DEBUG-only expected-unavailability report at the boundary that actually
	 * consumes the source failure. The bounded graph is formatted by the shared
	 * pipeline; callers must not also warn for the same expected failure.
	 */
	static void sourceUnavailable(String operation, InventorySourceInput input, @Nullable Supplier<String> source,
		String stage, @Nullable Throwable failure) {
		@Nullable ExpectedSourceSite site = ExpectedSourceSite.forOperation(operation);
		if (site == null) return;
		DiagnosticLogger logger = Diagnostics.server().atDebug();
		@Nullable Throwable reportedFailure = failure;
		Supplier<@Nullable String> detail = () -> {
			@Nullable String context = diagnosticContext(reportedFailure);
			return "operation=" + operation + " stage=" + stage + " source=" + (source == null ? "unresolved" : source.get())
				+ " " + context(input) + (context == null ? "" : " context=" + context);
		};
		if (reportedFailure == null) logger.event(Level.DEBUG, site.site, "source-unavailable", detail);
		else logger.failure(Level.DEBUG, site.site, "source-unavailable", detail, reportedFailure);
	}

	private static @Nullable String diagnosticContext(@Nullable Throwable failure) {
		if (failure instanceof InventorySharedRead.ReadFailure typed) failure = typed.failure();
		if (failure instanceof InventorySourceAccess.CursorlessReadFailure) failure = failure.getCause();
		return failure instanceof InventorySourceUnavailableException unavailable ? unavailable.diagnosticContext() : null;
	}

	static void previewOpen(String reason, String stage, long request, Target target, BlockFace face) {
		Diagnostics.server().atDebug().event(Level.DEBUG, "inventory/preview/open", reason,
			() -> "request=" + request + " stage=" + stage + " face=" + face + " " + targetContext(target));
	}

	static void previewLaneOpened(long request, InventoryResourceKind kind, Target target, BlockFace face) {
		Diagnostics.server().atDebug().event(Level.DEBUG, "inventory/preview/lane", "opened",
			() -> "request=" + request + " kind=" + kind + " face=" + face + " " + targetContext(target));
	}

	static void previewLaneRejected(String reason, String stage, long request, InventoryResourceKind kind, Target target, BlockFace face) {
		DiagnosticLogger logger = Diagnostics.server().atDebug();
		@Nullable PreviewLaneFailure bounded = PreviewLaneFailure.forReason(reason);
		if (bounded != null) logger = logger.rateLimited(bounded.channel);
		logger.event(Level.DEBUG, "inventory/preview/lane", reason,
			() -> "request=" + request + " stage=" + stage + " kind=" + kind + " face=" + face + " " + targetContext(target));
	}

	static void previewInvalidated(long request, InventoryResourceKind kind, Target target, BlockFace face) {
		Diagnostics.server().atDebug().event(Level.DEBUG, "inventory/preview/invalidate", "invalidated",
			() -> "request=" + request + " kind=" + kind + " face=" + face + " " + targetContext(target));
	}

	static void publicationFailed(Supplier<String> detail, Throwable failure) {
		if (expectedFailure(failure)) return;
		failureLogger("runtime/publication-validate").failure(Level.WARN, "inventory/runtime/publication-validate", "deny-publication", detail::get, failure);
	}

	private static String handling(String operation) {
		return switch (operation) {
			case "source-close", "snapshot-close", "snapshot-memory-close", "selection-witness-close", "resolve-close" -> "cleanup-continued";
			case "selection-witness", "select-create", "runtime/selection" -> "reject-selection";
			case "preview-count", "tracking-count" -> "reject-quantity";
			case "runtime/input-probe", "runtime/evidence-probe", "runtime/binding-probe", "runtime/handoff-probe" -> "deny-publication";
			default -> "invalidate-read";
		};
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
		boolean debug = reason == Reason.MEMORY_DEFERRED;
		Level level = debug ? Level.DEBUG : Level.WARN;
		DiagnosticLogger logger = debug ? Diagnostics.server().atDebug() : Diagnostics.server().atDetailed();
		logger.domainBeforeBackend(level, "inventory/snapshot", () -> {
			long now = System.nanoTime();
			int index = reason.ordinal();
			long last = LAST_LOGGED.get(index);
			if (last != 0L && now - last < MIN_INTERVAL_NANOS) return null;
			if (!LAST_LOGGED.compareAndSet(index, last, now)) return null;
			return new DiagnosticLogger.DomainReport(reason.token, failure,
				() -> context(input) + " detail=" + detail.get());
		});
	}
}
