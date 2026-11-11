package nx.pingwheel.common.presentation.inventory;

import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import nx.pingwheel.common.diagnostics.DiagnosticLogger.NormalSummary;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.SafeDiagnosticValue;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;

/**
 * One diagnostic entry point for inventory limits, deferrals, truncation and
 * quality degradation. NORMAL emits the fixed summary, DEEP adds short handling,
 * and DEBUG adds numeric associations. A limit event bypasses the interval
 * channel only at DEBUG; other reports share one fixed channel per
 * site/operation/reason tuple. Impact selects the minimum profile while backend
 * severity is selected independently by the caller or the impact default.
 */
@NotNullByDefault
public final class InventoryDiagnostics {
	private static final Duration CHANNEL_INTERVAL = Duration.ofSeconds(10);
	private static final String SUMMARY_TEMPLATE = "operation={} reason={} impact={} limitHit={}";
	private static final int OPERATION_COUNT = InventoryOperation.values().length;
	private static final int REASON_COUNT = InventoryFailureReason.values().length;
	private static final AtomicReferenceArray<DiagnosticChannel> CHANNELS =
		new AtomicReferenceArray<>(InventoryDiagnosticSite.values().length * OPERATION_COUNT * REASON_COUNT);

	private InventoryDiagnostics() {}

	/**
	 * Reports one already-decided inventory outcome with the impact's default
	 * severity: {@link InventoryImpact#PING_UNAVAILABLE} is WARN and the other
	 * impacts are DEBUG. The detail supplier runs only under DEEP/DEBUG, once,
	 * after profile and channel admission; NORMAL does not ask for it. The
	 * original throwable uses the shared bounded formatter.
	 */
	public static void report(DiagnosticSide side, InventoryDiagnosticSite site, InventoryOperation operation,
		InventoryFailureReason reason, InventoryImpact impact, InventoryOutcome outcome, boolean limitHit,
		Supplier<@Nullable InventoryDiagnosticDetail> detail, @Nullable Throwable failure) {
		report(side, defaultSeverity(Objects.requireNonNull(impact, "impact")), site, operation, reason, impact,
			outcome, limitHit, detail, failure);
	}

	/** Explicit backend severity for a caller that owns a different domain level. */
	public static void report(DiagnosticSide side, Level severity, InventoryDiagnosticSite site,
		InventoryOperation operation, InventoryFailureReason reason, InventoryImpact impact,
		InventoryOutcome outcome, boolean limitHit, Supplier<@Nullable InventoryDiagnosticDetail> detail,
		@Nullable Throwable failure) {
		Objects.requireNonNull(side, "side");
		Level level = Objects.requireNonNull(severity, "severity");
		Objects.requireNonNull(site, "site");
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(impact, "impact");
		Objects.requireNonNull(outcome, "outcome");
		Supplier<@Nullable InventoryDiagnosticDetail> source = Objects.requireNonNull(detail, "detail");

		NormalSummary summary = new NormalSummary(SUMMARY_TEMPLATE, new SafeDiagnosticValue[] {
			SafeDiagnosticValue.code(operation.code()),
			SafeDiagnosticValue.code(reason.code()),
			SafeDiagnosticValue.code(impact.code()),
			SafeDiagnosticValue.bool(limitHit)
		});
		DiagnosticLogger logger = logger(side, impact).rateLimited(channel(site, operation, reason));
		logger.reportByProfile(level, site.code(), outcome.code(), summary, profile -> {
			@Nullable InventoryDiagnosticDetail value = source.get();
			return value == null ? null : value.format(profile);
		}, failure, limitHit);
	}

	private static Level defaultSeverity(InventoryImpact impact) {
		return switch (impact) {
			case PING_UNAVAILABLE -> Level.WARN;
			case DEGRADED, DETAIL_ONLY -> Level.DEBUG;
		};
	}

	private static DiagnosticLogger logger(DiagnosticSide side, InventoryImpact impact) {
		DiagnosticLogger logger = switch (side) {
			case CLIENT -> Diagnostics.client();
			case SERVER -> Diagnostics.server();
			case BOOTSTRAP -> Diagnostics.bootstrap();
		};
		return switch (impact.minimumProfile()) {
			case NORMAL -> logger.atNormal();
			case DEEP -> logger.atDetailed();
			case DEBUG -> logger.atDebug();
		};
	}

	private static DiagnosticChannel channel(InventoryDiagnosticSite site, InventoryOperation operation,
		InventoryFailureReason reason) {
		int index = index(site, operation, reason);
		@Nullable DiagnosticChannel existing = CHANNELS.get(index);
		if (existing != null) return existing;
		DiagnosticChannel created = DiagnosticChannel.interval(
			"inventory-diag-" + site.code() + "-" + operation.code() + "-" + reason.code(), CHANNEL_INTERVAL);
		if (CHANNELS.compareAndSet(index, null, created)) return created;
		return Objects.requireNonNull(CHANNELS.get(index), "channel");
	}

	private static int index(InventoryDiagnosticSite site, InventoryOperation operation, InventoryFailureReason reason) {
		return (site.ordinal() * OPERATION_COUNT + operation.ordinal()) * REASON_COUNT + reason.ordinal();
	}
}
