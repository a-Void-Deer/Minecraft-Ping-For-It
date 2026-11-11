package nx.pingwheel.common.presentation.inventory;

import org.jetbrains.annotations.NotNullByDefault;

/** Stable inventory diagnostic site. Channels are partitioned by this value. */
@NotNullByDefault
public enum InventoryDiagnosticSite {
	REQUEST("request"),
	SESSION("session"),
	CAPTURE("capture"),
	SNAPSHOT("snapshot"),
	PROVIDER("provider"),
	NATIVE("native"),
	VALIDATION("validation"),
	PUBLICATION("publication"),
	TRANSPORT("transport"),
	CLIENT("client"),
	DISPLAY("display"),
	CLIENT_LOCAL_PREVIEW_REQUEST("client-local-preview-request"),
	CLIENT_LOGICAL_PREVIEW_HANDLE("client-logical-preview-handle"),
	CLIENT_KNOWN_MARKER_METADATA("client-known-marker-metadata"),
	CLIENT_TRACKING_CHANNELS("client-tracking-channels"),
	CLIENT_CREATION_METADATA("client-creation-metadata"),
	CLIENT_PENDING_SELECTIONS("client-pending-selections"),
	CLIENT_EFFECTIVE_FRAME("client-effective-frame"),
	CLIENT_EFFECTIVE_CAP_FENCE("client-effective-cap-fence"),
	CLIENT_EFFECTIVE_METADATA_FENCE("client-effective-metadata-fence"),
	CLIENT_EFFECTIVE_PENDING_FENCE("client-effective-pending-fence"),
	CLIENT_EFFECTIVE_BATCH_FENCE("client-effective-batch-fence"),
	CLIENT_EFFECTIVE_CLOSED_DISPLAY_FENCE("client-effective-closed-display-fence"),
	CLIENT_EFFECTIVE_HISTORY_FENCE("client-effective-history-fence"),
	CLIENT_EFFECTIVE_UNKNOWN_FENCE("client-effective-unknown-fence"),
	CLIENT_PREVIEW_PARTS("client-preview-parts"),
	CLIENT_PREVIEW_RETENTION_BYTES("client-preview-retention-bytes"),
	CLIENT_PREVIEW_RETENTION_ENTRIES("client-preview-retention-entries"),
	CLIENT_TRACKING_PARTS("client-tracking-parts"),
	CLIENT_TRACKING_ASSEMBLY_CONFLICT("client-tracking-assembly-conflict"),
	CLIENT_TRACKING_PART_CONFLICT("client-tracking-part-conflict"),
	CLIENT_TRACKING_OPEN_BATCHES("client-tracking-open-batches"),
	CLIENT_TRACKING_BATCH_BYTES("client-tracking-batch-bytes"),
	CLIENT_TRACKING_METADATA_ENTRIES("client-tracking-metadata-entries"),
	CLIENT_TRACKING_METADATA_BYTES("client-tracking-metadata-bytes"),
	CLIENT_TRACKING_PROJECTED_CUT_BYTES("client-tracking-projected-cut-bytes"),
	CLIENT_TRACKING_BATCH_CONFLICT("client-tracking-batch-conflict"),
	CLIENT_TRACKING_REPLAY_CONFLICT("client-tracking-replay-conflict"),
	CLIENT_TRACKING_DUPLICATE_KEY("client-tracking-duplicate-key"),
	CLIENT_TRACKING_ENTRY_CONFLICT("client-tracking-entry-conflict"),
	CLIENT_TRACKING_PART_RETENTION_ENTRIES("client-tracking-part-retention-entries"),
	CLIENT_TRACKING_PART_RETENTION_BYTES("client-tracking-part-retention-bytes"),
	CLIENT_TRACKING_PART_PROJECTED_BYTES("client-tracking-part-projected-bytes"),
	CLIENT_TRACKING_CLOSED_CUT_ENTRIES("client-tracking-closed-cut-entries"),
	CLIENT_TRACKING_CLOSED_CUT_BYTES("client-tracking-closed-cut-bytes"),
	CLIENT_UNKNOWN_SESSIONS("client-unknown-sessions"),
	CLIENT_UNKNOWN_OVERFLOW_ENTRIES("client-unknown-overflow-entries"),
	CLIENT_UNKNOWN_OVERFLOW_BYTES("client-unknown-overflow-bytes"),
	CLIENT_UNKNOWN_SINGLETON_BYTES("client-unknown-singleton-bytes"),
	CLIENT_STORE_SNAPSHOT_CHANNELS("client-store-snapshot-channels"),
	CLIENT_STORE_SNAPSHOT_ENTRIES("client-store-snapshot-entries"),
	CLIENT_STORE_UPDATE_ENTRIES("client-store-update-entries"),
	CLIENT_STORE_INVALIDATE_CHANNELS("client-store-invalidate-channels"),
	CLIENT_STORE_REBASE_CHANNELS("client-store-rebase-channels"),
	CLIENT_STORE_SNAPSHOT_PARTS("client-store-snapshot-parts"),
	CLIENT_STORE_ASSEMBLY_CHANNELS("client-store-assembly-channels"),
	CLIENT_STORE_PART_INDEX("client-store-part-index"),
	CLIENT_STORE_ASSEMBLY_ENTRIES("client-store-assembly-entries");

	private final String code;

	InventoryDiagnosticSite(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}
}
