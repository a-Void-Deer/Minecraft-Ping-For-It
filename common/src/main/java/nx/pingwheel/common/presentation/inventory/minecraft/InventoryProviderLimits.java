package nx.pingwheel.common.presentation.inventory.minecraft;

import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.SafeDiagnosticValue;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;

/** Native guard reporting only; no provider reads, fallback decisions or retained exception graph. */
@NotNullByDefault
public final class InventoryProviderLimits {
	private InventoryProviderLimits() {}
	public static InventoryProviderLimitException exceeded(ProviderLimitSite site, long required, long limit) {
		report(site, required, limit);
		return new InventoryProviderLimitException(site, required, limit);
	}
	/** For checks that intentionally retain their existing false/null/UNCLASSIFIED fallback. */
	public static void report(ProviderLimitSite site, long required, long limit) {
		Diagnostics.server().atDetailed().rateLimited(site.channel()).reportByProfile(Level.DEBUG,
			"inventory-provider/" + site.code(), "limit-degraded",
			new DiagnosticLogger.NormalSummary("Inventory provider limit reason={} required={} limit={} limitHit={}",
				new SafeDiagnosticValue[] {SafeDiagnosticValue.code(site.reason().code()),
					SafeDiagnosticValue.number(required), SafeDiagnosticValue.number(limit), SafeDiagnosticValue.bool(true)}),
			null, null, true);
	}
}
