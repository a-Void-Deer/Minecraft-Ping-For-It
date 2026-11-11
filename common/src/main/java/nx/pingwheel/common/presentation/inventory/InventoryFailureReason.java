package nx.pingwheel.common.presentation.inventory;

import nx.pingwheel.common.marker.MarkerRejectReason;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stable inventory failure vocabulary shared by diagnostics, decisions and the
 * inventory wire codec. Wire ids are explicit and never derived from declaration
 * order; an unknown id is a malformed value, not a fallback reason.
 */
@NotNullByDefault
public enum InventoryFailureReason {
	// Target and source admission.
	TARGET_GONE(1, "target-gone", "pingforit.inventory.reason.target_gone"),
	OUT_OF_RANGE(2, "out-of-range", "pingforit.inventory.reason.out_of_range"),
	FIELD_DENIED(3, "field-denied", "pingforit.inventory.reason.field_denied"),
	SOURCE_AUTHORITY_DENIED(4, "source-authority-denied", "pingforit.inventory.reason.source_authority_denied"),
	UNSUPPORTED_CONTEXT(5, "unsupported-context", "pingforit.inventory.reason.unsupported_context"),
	UNSUPPORTED_LANE(6, "unsupported-lane", "pingforit.inventory.reason.unsupported_lane"),
	SOURCE_UNAVAILABLE(7, "source-unavailable", "pingforit.inventory.reason.source_unavailable"),
	SOURCE_CHANGED(8, "source-changed", "pingforit.inventory.reason.source_changed"),
	PROVIDER_FAILURE(9, "provider-failure", "pingforit.inventory.reason.provider_failure"),

	// Request, target and publisher capacities.
	SESSION_CAPACITY(10, "session-capacity", "pingforit.inventory.reason.session_capacity"),
	REQUEST_CAPACITY(11, "request-capacity", "pingforit.inventory.reason.request_capacity"),
	TARGET_CAPACITY(12, "target-capacity", "pingforit.inventory.reason.target_capacity"),
	TRACKING_CAPACITY(13, "tracking-capacity", "pingforit.inventory.reason.tracking_capacity"),
	CONSUMER_CAPACITY(14, "consumer-capacity", "pingforit.inventory.reason.consumer_capacity"),
	ROUND_CAPACITY(15, "round-capacity", "pingforit.inventory.reason.round_capacity"),
	PUBLISHER_CAPACITY(16, "publisher-capacity", "pingforit.inventory.reason.publisher_capacity"),
	CONTROL_CAPACITY(17, "control-capacity", "pingforit.inventory.reason.control_capacity"),

	// Work, storage, encoding and queue bounds.
	MEMORY_LIMIT(18, "memory-limit", "pingforit.inventory.reason.memory_limit"),
	PROBE_LIMIT(19, "probe-limit", "pingforit.inventory.reason.probe_limit"),
	PROVIDER_WORK_LIMIT(20, "provider-work-limit", "pingforit.inventory.reason.provider_work_limit"),
	ITEM_WORK_LIMIT(21, "item-work-limit", "pingforit.inventory.reason.item_work_limit"),
	PHYSICAL_SLOT_LIMIT(22, "physical-slot-limit", "pingforit.inventory.reason.physical_slot_limit"),
	LOGICAL_SLOT_LIMIT(23, "logical-slot-limit", "pingforit.inventory.reason.logical_slot_limit"),
	VARIANT_LIMIT(24, "variant-limit", "pingforit.inventory.reason.variant_limit"),
	CATALOG_BYTES_LIMIT(25, "catalog-bytes-limit", "pingforit.inventory.reason.catalog_bytes_limit"),
	JOURNAL_LIMIT(26, "journal-limit", "pingforit.inventory.reason.journal_limit"),
	SNAPSHOT_LIMIT(27, "snapshot-limit", "pingforit.inventory.reason.snapshot_limit"),
	ENTRY_LIMIT(28, "entry-limit", "pingforit.inventory.reason.entry_limit"),
	DISPLAY_LIMIT(29, "display-limit", "pingforit.inventory.reason.display_limit"),
	FRAME_LIMIT(30, "frame-limit", "pingforit.inventory.reason.frame_limit"),
	QUEUE_LIMIT(31, "queue-limit", "pingforit.inventory.reason.queue_limit"),
	WIRE_LIMIT(32, "wire-limit", "pingforit.inventory.reason.wire_limit"),

	// Native traversal bounds.
	NATIVE_LIST_LIMIT(33, "native-list-limit", "pingforit.inventory.reason.native_list_limit"),
	NATIVE_NODE_LIMIT(34, "native-node-limit", "pingforit.inventory.reason.native_node_limit"),
	NATIVE_ARRAY_LIMIT(35, "native-array-limit", "pingforit.inventory.reason.native_array_limit"),
	NATIVE_MAP_LIMIT(36, "native-map-limit", "pingforit.inventory.reason.native_map_limit"),
	NATIVE_WORK_LIMIT(37, "native-work-limit", "pingforit.inventory.reason.native_work_limit"),
	NATIVE_DEPTH_LIMIT(38, "native-depth-limit", "pingforit.inventory.reason.native_depth_limit"),

	// Selection and quantity integrity.
	INVALID_SELECTION(39, "invalid-selection", "pingforit.inventory.reason.invalid_selection"),
	RETIRED_SELECTION(40, "retired-selection", "pingforit.inventory.reason.retired_selection"),
	AMOUNT_OVERFLOW(41, "amount-overflow", "pingforit.inventory.reason.amount_overflow"),

	// Lifecycle, transport and request admission.
	SESSION_TIMEOUT(42, "session-timeout", "pingforit.inventory.reason.session_timeout"),
	OPEN_TIMEOUT(43, "open-timeout", "pingforit.inventory.reason.open_timeout"),
	STALL_TIMEOUT(44, "stall-timeout", "pingforit.inventory.reason.stall_timeout"),
	TRANSPORT_FAILURE(45, "transport-failure", "pingforit.inventory.reason.transport_failure"),
	RATE_LIMITED(46, "rate-limited", "pingforit.inventory.reason.rate_limited"),
	CHANNEL_DISABLED(47, "channel-disabled", "pingforit.inventory.reason.channel_disabled"),
	INVALID_REQUEST(48, "invalid-request", "pingforit.inventory.reason.invalid_request"),
	NOT_OWNER(49, "not-owner", "pingforit.inventory.reason.not_owner"),
	NOT_FOUND(50, "not-found", "pingforit.inventory.reason.not_found"),
	INVALID_PING_TYPE(51, "invalid-ping-type", "pingforit.inventory.reason.invalid_ping_type"),
	PUBLICATION_WORK_LIMIT(52, "publication-work-limit", "pingforit.inventory.reason.publication_work_limit"),
	CAPTURE_READ_LIMIT(53, "capture-read-limit", "pingforit.inventory.reason.capture_read_limit");

	private static final @Unmodifiable Map<Integer, InventoryFailureReason> BY_WIRE_ID = wireIds();

	private final int wireId;
	private final String code;
	private final String translationKey;

	InventoryFailureReason(int wireId, String code, String translationKey) {
		if (wireId <= 0) throw new IllegalArgumentException("Inventory failure reason wire ids are positive");
		this.wireId = wireId;
		this.code = Objects.requireNonNull(code, "code");
		this.translationKey = Objects.requireNonNull(translationKey, "translationKey");
	}

	public int wireId() {
		return wireId;
	}

	/** Stable lower-case diagnostic code; never provider text. */
	public String code() {
		return code;
	}

	public String translationKey() {
		return translationKey;
	}

	public static InventoryFailureReason fromWireId(int wireId) {
		@Nullable InventoryFailureReason reason = BY_WIRE_ID.get(wireId);
		if (reason == null) throw new IllegalArgumentException("Unknown inventory failure reason wire id: " + wireId);
		return reason;
	}

	/**
	 * Bridge for generic marker rejections. Inventory-specific causes remain
	 * authoritative without changing the global marker rejection vocabulary.
	 */
	public static InventoryFailureReason from(MarkerRejectReason reason) {
		return switch (Objects.requireNonNull(reason, "reason")) {
			case TARGET_GONE -> TARGET_GONE;
			case OUT_OF_RANGE -> OUT_OF_RANGE;
			case CHANNEL_DISABLED -> CHANNEL_DISABLED;
			case RATE_LIMITED -> RATE_LIMITED;
			case INVALID_REQUEST -> INVALID_REQUEST;
			case NOT_OWNER -> NOT_OWNER;
			case NOT_FOUND -> NOT_FOUND;
			case INVALID_PING_TYPE -> INVALID_PING_TYPE;
		};
	}

	private static @Unmodifiable Map<Integer, InventoryFailureReason> wireIds() {
		Map<Integer, InventoryFailureReason> byId = new HashMap<>();
		for (InventoryFailureReason reason : values()) {
			if (byId.put(reason.wireId, reason) != null) {
				throw new IllegalStateException("Duplicate inventory failure reason wire id: " + reason.wireId);
			}
		}
		return Map.copyOf(byId);
	}
}
