package nx.pingwheel.common.presentation.inventory.client;

import java.util.function.Supplier;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.inventory.InventoryDiagnosticDetail;
import nx.pingwheel.common.presentation.inventory.InventoryDiagnostics;
import nx.pingwheel.common.presentation.inventory.InventoryDiagnosticSite;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import nx.pingwheel.common.presentation.inventory.InventoryImpact;
import nx.pingwheel.common.presentation.inventory.InventoryOperation;
import nx.pingwheel.common.presentation.inventory.InventoryOutcome;
import nx.pingwheel.common.render.SpatialInventoryView;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Inventory outcome reports carry explicit consequences and fixed predicate sites.
 * Open, lane and preview-state traces remain DEBUG-only; target/provider text stays lazy.
 */
@NotNullByDefault
public final class ClientInventoryDiagnostics {
	private ClientInventoryDiagnostics() {}

	/** Shared DEBUG-only entry point so call-site suppression and laziness stay testable. */
	public static void debug(String site, String reason, Supplier<@Nullable String> detail) {
		Diagnostics.client().atDebug().event(Level.DEBUG, site, reason, detail);
	}

	public static void openRejected(String reason, @Nullable Target target, @Nullable BlockFace face,
		@Nullable String targetType) {
		if (reason.equals("preview-capacity")) limit(InventoryDiagnosticSite.CLIENT_LOCAL_PREVIEW_REQUEST,
			InventoryOperation.PREVIEW, InventoryFailureReason.REQUEST_CAPACITY, InventoryImpact.PING_UNAVAILABLE, InventoryOutcome.REJECTED,
			-1, -1, -1, ClientInventory.MAX_PREVIEW_CHANNELS);
		debug("inventory/preview-open", reason,
			() -> "targetType=" + targetType + " target=" + target + " face=" + face);
	}

	public static void limit(InventoryDiagnosticSite site, InventoryOperation operation, InventoryFailureReason reason,
		InventoryImpact impact, InventoryOutcome outcome, long request, long requested, long used, long limit) {
		report(site, operation, reason, impact, outcome, true, request, requested, used, limit);
	}
	public static void nonLimit(InventoryDiagnosticSite site, InventoryOperation operation, InventoryFailureReason reason,
		InventoryImpact impact, InventoryOutcome outcome, long request, long requested, long used, long limit) {
		report(site, operation, reason, impact, outcome, false, request, requested, used, limit);
	}
	private static void report(InventoryDiagnosticSite site, InventoryOperation operation, InventoryFailureReason reason,
		InventoryImpact impact, InventoryOutcome outcome, boolean limitHit, long request, long requested, long used, long limit) {
		InventoryDiagnostics.report(DiagnosticSide.CLIENT, site, operation, reason, impact, outcome, limitHit,
			() -> new InventoryDiagnosticDetail(site.code(), request, -1, null, requested, used, limit), null);
	}
	public static void transportFailed(String stage, RuntimeException failure) {
		InventoryDiagnostics.report(DiagnosticSide.CLIENT, InventoryDiagnosticSite.TRANSPORT,
			InventoryOperation.ROUTE, InventoryFailureReason.TRANSPORT_FAILURE, InventoryImpact.PING_UNAVAILABLE,
			InventoryOutcome.FAILED, false, () -> new InventoryDiagnosticDetail(stage, -1, -1, null, -1, -1, -1), failure);
	}
	public static void previewFailed(long localId, long wireId, InventoryFailureReason reason, int retry, boolean stopped) {
		InventoryDiagnostics.report(DiagnosticSide.CLIENT, InventoryDiagnosticSite.REQUEST,
			InventoryOperation.PREVIEW, reason, InventoryImpact.PING_UNAVAILABLE,
			InventoryOutcome.FAILED, false,
			() -> new InventoryDiagnosticDetail(stopped ? "preview-stopped" : "preview-retry", wireId, localId, null, retry, -1, 3), null);
	}
	public static void selectionRejected(long request, long commit, InventoryFailureReason reason) {
		boolean limit = reason == InventoryFailureReason.RATE_LIMITED || reason == InventoryFailureReason.TRACKING_CAPACITY
			|| reason == InventoryFailureReason.CONTROL_CAPACITY || reason == InventoryFailureReason.MEMORY_LIMIT
			|| reason == InventoryFailureReason.REQUEST_CAPACITY;
		InventoryDiagnostics.report(DiagnosticSide.CLIENT, InventoryDiagnosticSite.CLIENT, InventoryOperation.SELECT,
			reason, InventoryImpact.PING_UNAVAILABLE, InventoryOutcome.REJECTED, limit,
			() -> new InventoryDiagnosticDetail("selection-rejected", request, commit, null, -1, -1, -1), null);
	}

	public static void laneDecision(long requestId, InventoryResourceKind kind, String reason, String targetType) {
		debug("inventory/preview-lane", reason,
			() -> "request=" + requestId + " kind=" + kind + " targetType=" + targetType);
	}

	public static void openSent(long requestId, InventoryResourceKind kind, Target target, BlockFace face) {
		debug("inventory/preview-send", "open-sent",
			() -> "request=" + requestId + " kind=" + kind + " target=" + target + " face=" + face);
	}

	public static void lanePolicy(InventoryResourceKind kind, boolean allowed, long view, int allowedTypeCount) {
		debug("inventory/lane-policy", allowed ? "lane-allowed" : "lane-denied",
			() -> "kind=" + kind + " view=" + view + " allowedTypes=" + allowedTypeCount);
	}

	public static void laneSchemaDisabled(InventoryResourceKind kind, int removedPreviews, int removedTracking) {
		debug("inventory/lane-schema", "lane-schema-disabled",
			() -> "kind=" + kind + " previews=" + removedPreviews + " tracking=" + removedTracking);
	}

	public static void previewStatus(long requestId, InventoryResourceKind kind,
		InventoryS2CPacket.Status oldStatus, InventoryS2CPacket.Status newStatus, long statusRevision) {
		debug("inventory/preview-status", statusReason(newStatus),
			() -> "request=" + requestId + " kind=" + kind + " old=" + oldStatus
				+ " new=" + newStatus + " revision=" + statusRevision);
	}

	public static void laneRevoked(String scope, long requestId, InventoryResourceKind kind,
		InventoryS2CPacket.Status oldStatus, long baselineId) {
		debug("inventory/lane-revoked", "policy-revoked",
			() -> "scope=" + scope + " request=" + requestId + " kind=" + kind
				+ " old=" + oldStatus + " new=revoked baseline=" + baselineId);
	}

	public static void selectorContentDisabled(String reason, long requestId, long previewSession,
		SpatialInventoryView.Status status, boolean eligible, int allowedTypeCount) {
		debug("inventory/selector-content", reason,
			() -> "request=" + requestId + " previewSession=" + previewSession + " status=" + status
				+ " eligible=" + eligible + " allowedTypes=" + allowedTypeCount);
	}

	private static String statusReason(InventoryS2CPacket.Status status) {
		return switch (status) {
			case UPDATING -> "status-updating";
			case READY -> "status-ready";
			case UNCERTAIN -> "status-uncertain";
			case INCOMPLETE -> "status-incomplete";
			case INVALID -> "status-invalid";
			case COMPONENT_TOO_LONG -> "status-component-too-long";
		};
	}
}
