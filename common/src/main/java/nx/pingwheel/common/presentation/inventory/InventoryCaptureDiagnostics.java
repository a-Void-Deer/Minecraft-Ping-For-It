package nx.pingwheel.common.presentation.inventory;

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
import nx.pingwheel.common.diagnostics.DiagnosticSide;

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

	private InventoryCaptureDiagnostics() {}

	/** Limit evidence is supplied by the deciding branch, never inferred from exception text. */
	static void limit(@Nullable InventorySourceInput input, InventoryDiagnosticSite site, InventoryOperation operation,
		InventoryFailureReason reason, InventoryOutcome outcome, String stage, long requested, long used, long limit,
		@Nullable Throwable failure) {
		InventoryDiagnostics.report(DiagnosticSide.SERVER, site, operation, reason, InventoryImpact.DEGRADED, outcome, true,
			() -> new InventoryDiagnosticDetail(stage, -1, -1, input == null ? null : input.resourceKind().name(), requested, used, limit), failure);
	}

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
		if (providerLimit(failure) != null) return true;
		if (failure instanceof InventorySharedRead.ReadFailure typed) {
			@Nullable Throwable cause = typed.failure();
			if (cause == null || cause instanceof nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.CapacityExceededException) return true;
			failure = cause;
		}
		@Nullable Throwable reason = failure instanceof InventorySourceAccess.CursorlessReadFailure ? failure.getCause() : failure;
		return reason instanceof InventoryItemBudget.CaptureLimitException
			|| reason instanceof InventoryObservationSnapshot.LimitException
			|| reason instanceof nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnsupportedException
			|| reason instanceof InventorySourceUnavailableException;
	}

	/** Narrower than {@link #expectedFailure}: only the expected unavailability boundary changes disclosure. */
	static boolean expectedSourceUnavailable(@Nullable Throwable failure) {
		if (providerLimit(failure) != null) return true;
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
		// Native guard sites already reported this event with its exact finite site.
		if (providerLimit(failure) != null) return;
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
		InventoryFailureReason reason = switch (detail) {
			case "negative-memory-bound", "snapshot-slots", "retained-bytes", "capture-work-or-structure", "snapshot-discovery" -> InventoryFailureReason.SNAPSHOT_LIMIT;
			case "native-journal-capacity" -> InventoryFailureReason.JOURNAL_LIMIT;
			default -> InventoryFailureReason.NATIVE_WORK_LIMIT;
		};
		limit(input, InventoryDiagnosticSite.CAPTURE, InventoryOperation.READ, reason,
			InventoryOutcome.TRUNCATED, detail, -1, -1, -1, null);
	}

	static void boundExceeded(InventorySourceInput input, Supplier<String> detail) {
		InventoryDiagnostics.report(DiagnosticSide.SERVER, InventoryDiagnosticSite.SNAPSHOT, InventoryOperation.SNAPSHOT,
			InventoryFailureReason.SNAPSHOT_LIMIT, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, true,
			() -> new InventoryDiagnosticDetail(detail.get(), -1, -1, input.resourceKind().name(), -1, -1, -1), null);
	}

	/** Only audited wrappers, each unwrapped at most once; never search an arbitrary cause graph. */
	static nx.pingwheel.common.presentation.inventory.minecraft.@Nullable InventoryProviderLimitException providerLimit(@Nullable Throwable failure) {
		boolean sharedSeen = false, cursorlessSeen = false, invocationSeen = false;
		for (int wrapper = 0; wrapper < 3; wrapper++) {
			if (!sharedSeen && failure instanceof InventorySharedRead.ReadFailure shared) { sharedSeen = true; failure = shared.failure(); }
			else if (!cursorlessSeen && failure instanceof InventorySourceAccess.CursorlessReadFailure) { cursorlessSeen = true; failure = failure.getCause(); }
			else if (!invocationSeen && failure instanceof java.lang.reflect.InvocationTargetException) { invocationSeen = true; failure = failure.getCause(); }
			else break;
		}
		return failure instanceof nx.pingwheel.common.presentation.inventory.minecraft.InventoryProviderLimitException limit ? limit : null;
	}
	static InventoryFailureReason sourceReason(Throwable failure) {
		@Nullable var limit = providerLimit(failure);
		return limit != null ? limit.reason() : expectedSourceUnavailable(failure)
			? InventoryFailureReason.SOURCE_UNAVAILABLE : InventoryFailureReason.PROVIDER_FAILURE;
	}

	static void boundExceeded(InventorySourceInput input, String detail, Throwable failure) {
		InventoryFailureReason reason = boundReason(failure);
		limit(input, InventoryDiagnosticSite.CAPTURE, InventoryOperation.READ, reason,
			InventoryOutcome.TRUNCATED, detail, -1, -1, -1, null);
	}

	/** Typed evidence only; wrappers preserve their actual refusing operation. */
	static InventoryFailureReason boundReason(Throwable failure) {
		@Nullable var provider = providerLimit(failure);
		if (provider != null) return provider.reason();
		if (failure instanceof InventorySourceAccess.CursorlessReadFailure) {
			@Nullable Throwable cause = failure.getCause();
			if (cause != null) failure = cause;
		}
		return failure instanceof InventorySlotJournal.CapacityException ? InventoryFailureReason.JOURNAL_LIMIT
			: failure instanceof InventoryObservationSnapshot.LimitException ? InventoryFailureReason.SNAPSHOT_LIMIT
			: failure instanceof nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.CapacityExceededException item ? item.reason()
			: failure instanceof InventoryFluidCodec.CapacityException fluid ? fluid.reason()
			: failure instanceof InventoryItemBudget.CaptureLimitException budget ? budget.reason() : InventoryFailureReason.SNAPSHOT_LIMIT;
	}

	static void memoryDeferred(InventorySourceInput input) {
		limit(input, InventoryDiagnosticSite.SNAPSHOT, InventoryOperation.SNAPSHOT, InventoryFailureReason.MEMORY_LIMIT,
			InventoryOutcome.DEFERRED, "retained-capacity", -1, -1, -1, null);
	}

}
