package nx.pingwheel.common.presentation.inventory.minecraft;

import com.mojang.serialization.DataResult;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.presentation.inventory.InventoryDiagnosticDetail;
import nx.pingwheel.common.presentation.inventory.InventoryDiagnosticSite;
import nx.pingwheel.common.presentation.inventory.InventoryDiagnostics;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import nx.pingwheel.common.presentation.inventory.InventoryImpact;
import nx.pingwheel.common.presentation.inventory.InventoryOperation;
import nx.pingwheel.common.presentation.inventory.InventoryOutcome;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Failure detail only: never walks, formats or retains a resident item graph. */
@NotNullByDefault
public final class InventoryItemDiagnostics {
	private InventoryItemDiagnostics() {}

	static <T> T complete(DataResult<T> result, String stage) {
		// Error suppliers can walk NBT before producing their first character.
		// Keep only the trusted stage; neither retain the result nor use a partial.
		return result.result().orElseThrow(() -> new CodecRefusal(stage));
	}

	static void refusal(String operation, String itemId, RuntimeException refusal) {
		boolean identity = operation.equals("inventory/item-identity");
		report(DiagnosticSide.SERVER, severity(refusal), identity ? InventoryDiagnosticSite.NATIVE : InventoryDiagnosticSite.DISPLAY,
			identity ? InventoryOperation.ENCODE : InventoryOperation.DISPLAY, reason(refusal), limitHit(refusal),
			refusal instanceof CodecRefusal ? "native-codec-rejected-all-variants"
				: identity ? "all-variants" : "empty-icon-registry-label", "item", refusal instanceof CodecRefusal ? null : refusal);
	}

	static void fluidIdentityRefused(String fluidId, RuntimeException refusal) {
		report(DiagnosticSide.SERVER, severity(refusal), InventoryDiagnosticSite.NATIVE, InventoryOperation.ENCODE,
			reason(refusal), limitHit(refusal), refusal instanceof CodecRefusal ? "native-codec-rejected-all-variants" : "all-variants",
			"fluid", refusal instanceof CodecRefusal ? null : refusal);
	}

	static void displayDegraded(InventoryFailureReason reason, boolean limitHit, String stage) {
		report(DiagnosticSide.SERVER, Level.DEBUG, InventoryDiagnosticSite.DISPLAY, InventoryOperation.DISPLAY,
			reason, limitHit, stage, "item", null);
	}

	/** One client operation owns its report; callers suppress nested duplicates. */
	public static void clientDisplayDegraded(InventoryFailureReason reason, boolean limitHit, String stage, @Nullable Throwable failure) {
		report(DiagnosticSide.CLIENT, limitHit ? Level.DEBUG : Level.WARN, InventoryDiagnosticSite.DISPLAY, InventoryOperation.DECODE,
			reason, limitHit, stage, "item", failure);
	}

	private static InventoryFailureReason reason(RuntimeException refusal) {
		if (refusal instanceof InventoryItemBudget.RootLimitException root) return root.reason();
		if (refusal instanceof InventoryItemCodec.IdentityUnavailableException identity) return identity.reason();
		return InventoryFailureReason.PROVIDER_FAILURE;
	}
	private static boolean limitHit(RuntimeException refusal) {
		return refusal instanceof InventoryItemBudget.RootLimitException root && root.limitHit()
			|| refusal instanceof InventoryItemCodec.IdentityUnavailableException identity && identity.limitHit();
	}
	private static Level severity(RuntimeException refusal) {
		return refusal instanceof CodecRefusal
			|| !(refusal instanceof InventoryItemBudget.RootLimitException || refusal instanceof InventoryItemCodec.IdentityUnavailableException)
			? Level.WARN : Level.DEBUG;
	}
	private static void report(DiagnosticSide side, Level severity, InventoryDiagnosticSite site, InventoryOperation operation,
		InventoryFailureReason reason, boolean limitHit, String stage, String lane, @Nullable Throwable failure) {
		InventoryDiagnostics.report(side, severity, site, operation, reason, InventoryImpact.DEGRADED, InventoryOutcome.DEGRADED, limitHit,
			() -> new InventoryDiagnosticDetail(stage, -1, -1, lane, -1, -1, -1), failure);
	}

	@NotNullByDefault
	private static final class CodecRefusal extends InventoryItemBudget.RootLimitException {
		CodecRefusal(String stage) { super(stage); }
	}
}
