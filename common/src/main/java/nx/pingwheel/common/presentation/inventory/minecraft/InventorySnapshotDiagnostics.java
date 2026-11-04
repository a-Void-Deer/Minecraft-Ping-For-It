package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;

import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import nx.pingwheel.common.diagnostics.Diagnostics;

/** Low-frequency snapshot discovery/failure diagnostics; never captures or serializes snapshot data for logging. */
@NotNullByDefault
final class InventorySnapshotDiagnostics {
	@NotNullByDefault
	private enum FailureSite {
		SOURCE_RESOLVE, EXTERNAL_RESOLVE, RESIDENT_VALID, PROVIDER_LAYOUT, SOURCE_VALID, AUTHORITY, EXTERNAL_VALID;
		final nx.pingwheel.common.diagnostics.DiagnosticChannel channel = nx.pingwheel.common.diagnostics.DiagnosticChannel.interval("inventory-snapshot-" + name(), java.time.Duration.ofSeconds(10));
	}
	private static nx.pingwheel.common.diagnostics.DiagnosticLogger failureLogger(String operation) {
		@Nullable FailureSite site = switch (operation) {
			case "inventory-source-resolve" -> FailureSite.SOURCE_RESOLVE;
			case "inventory-external-resolve" -> FailureSite.EXTERNAL_RESOLVE;
			case "inventory-resident-scope-valid" -> FailureSite.RESIDENT_VALID;
			case "inventory-provider-snapshot-layout" -> FailureSite.PROVIDER_LAYOUT;
			case "inventory-source-valid" -> FailureSite.SOURCE_VALID;
			case "inventory-snapshot-authority" -> FailureSite.AUTHORITY;
			case "inventory-external-valid" -> FailureSite.EXTERNAL_VALID;
			default -> null;
		};
		var logger = Diagnostics.server().atDetailed();
		return site == null ? logger : logger.rateLimited(site.channel);
	}
	private enum Reason { UNSUPPORTED }
	private static final AtomicLongArray LAST_LOGGED = new AtomicLongArray(Reason.values().length);
	private static final long MIN_INTERVAL_NANOS = 10_000_000_000L;

	private InventorySnapshotDiagnostics() {}

	/** Only the boundary converting an exception into unavailable/fallback reports it. */
	static void failure(String operation, InventorySourceInput input, @Nullable Object provider, Supplier<String> detail, Throwable failure) {
		if (failure instanceof InventorySourceUnavailableException || failure instanceof InventoryItemBudget.CaptureLimitException) return;
		var target = input.target();
		failureLogger(operation).failure(Level.WARN, operation, "source-unavailable",
			() -> "owner=" + input.readOwner() + " face=" + input.face() + " providerClass=" + (provider == null ? "minecraft" : provider.getClass().getName())
				+ " " + detail.get() + " target=" + target, failure);
	}

	static void unsupported(InventorySourceInput input, String detail) {
		Diagnostics.server().atDebug().domainBeforeBackend(Level.DEBUG, "inventory/snapshot-discovery", () -> {
			int index = Reason.UNSUPPORTED.ordinal();
			long now = System.nanoTime();
			long previous = LAST_LOGGED.get(index);
			if (previous != 0L && now - previous < MIN_INTERVAL_NANOS) return null;
			if (!LAST_LOGGED.compareAndSet(index, previous, now)) return null;
			return new DiagnosticLogger.DomainReport("unsupported", null, () -> {
				var target = input.target();
				String context = target instanceof Target.ExternalBlockTarget external
					? "dimension=" + external.dimensionId() + " provider=" + external.providerId() + " registry=" + external.expectedBlockRegistryId()
					: "target=" + target;
				return context + " face=" + input.face() + " detail=" + detail;
			});
		});
	}
}
