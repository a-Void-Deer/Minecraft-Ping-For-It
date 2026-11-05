package nx.pingwheel.common.presentation.inventory.client;

import java.util.function.Supplier;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.render.SpatialInventoryView;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * DEBUG-only inventory open, lane and preview-state evidence. Sites and reasons
 * are fixed codes; target/provider text remains lazy and never reaches a lower
 * profile or a disabled backend.
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
		debug("inventory/preview-open", reason,
			() -> "targetType=" + targetType + " target=" + target + " face=" + face);
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
