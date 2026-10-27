package nx.pingwheel.common.config;

/**
 * Code-owned engineering bounds and wire-byte bases for the authoritative
 * inventory budget model in {@link InventorySettings}.
 *
 * <p>Finite configured ranges apply to files and remote updates alike; UI
 * adjustment steps do not restrict legal integers within those ranges.
 * Unlimited guards are separate engineering bounds, never numeric sentinels.
 */
public final class InventoryLimits {

	/** Default shared physical scan allowance in slots per tick. */
	public static final int DEFAULT_PHYSICAL_SLOTS_PER_TICK = ConfigDefaults.Inventory.PHYSICAL_SLOTS_PER_TICK;

	/**
	 * Finite hard guard used when the physical scan cap is in explicit
	 * unlimited mode. This is a bounded work-loop guard, never a sentinel.
	 */
	public static final int INTERNAL_PHYSICAL_SLOT_GUARD = 65536;

	/**
	 * Finite hard guard used when a variant, slot or target count cap is in
	 * explicit unlimited mode. Kept far below the integer wire maximum so an
	 * unlimited collection never loses its finite structural bound.
	 */
	public static final int INTERNAL_COUNT_LIMIT_GUARD = 1_048_576;
	/** Tracking server-wide slots have their own guard, not the shared collection guard. */
	public static final int INTERNAL_TRACKING_SERVER_SLOT_GUARD = 1 << 30;

	/**
	 * Finite hard guard used when a send-byte multiplier is in explicit
	 * unlimited mode: 256 MiB per period. The runtime keeps its own structural
	 * and memory guard; this value only prevents an unbounded byte allowance.
	 */
	public static final long INTERNAL_BYTE_BUDGET_GUARD = 256L * 1024L * 1024L;

	/** A finite cap is always positive; zero is not a disable sentinel for these caps. */
	public static final int MIN_FINITE_LIMIT = 1;

	/** Finite configured slot and ordinary variant-count range. */
	public static final int MIN_ORDINARY_COUNT = 16;
	public static final int MAX_ORDINARY_COUNT = 1024;
	public static final int MAX_PREVIEW_TARGETS_PER_CLIENT = 8;
	public static final int MAX_TRACKING_VARIANTS_PER_TARGET = 32;
	public static final int MIN_TRACKING_SLOTS_SERVER = 128;
	public static final int MAX_TRACKING_SLOTS_SERVER = 16384;

	/** Default server-wide pending-memory bound in MiB; explicit unlimited stays finite. */
	public static final int DEFAULT_PENDING_MEMORY_MIB = ConfigDefaults.Inventory.PENDING_MEMORY_MIB;
	public static final int MIN_PENDING_MEMORY_MIB = 16;
	public static final int MAX_PENDING_MEMORY_MIB = 1024;
	public static final int INTERNAL_PENDING_MEMORY_MIB_GUARD = 4096;

	/** Finite accounting periods shared by preview and tracking. */
	public static final int MIN_PERIOD_TICKS = 1;
	public static final int MAX_PERIOD_TICKS = 10;

	/** Zero skips the resynchronization cooldown check; no unlimited mode. */
	public static final int MIN_RESYNC_PERIODS = 0;
	public static final int MAX_RESYNC_PERIODS = 10;

	/** Heartbeat periods: zero is the explicit "disabled" value, not an unlimited sentinel. */
	public static final int MIN_HEARTBEAT_PERIODS = 0;
	public static final int MAX_HEARTBEAT_PERIODS = 32;

	/** Finite rolling excess-byte smoothing window in tracking periods. */
	public static final int MIN_GRACE_PERIODS = 1;
	public static final int MAX_GRACE_PERIODS = 32;

	/** Preview per-client period byte base, scaled by the client byte multiplier. */
	public static final int PREVIEW_CLIENT_BASE_BYTES = 4 * 1024;
	/** Preview global period byte base, scaled by the global byte multiplier. */
	public static final int PREVIEW_GLOBAL_BASE_BYTES = 32 * 1024;
	/** Tracking stream byte base per client+target period, scaled by the client multiplier. */
	public static final int TRACKING_STREAM_BASE_BYTES = 2 * 1024;
	/** Tracking snapshot byte base per fragment and per client period, scaled by the client multiplier. */
	public static final int TRACKING_SNAPSHOT_BASE_BYTES = 16 * 1024;
	/** Tracking global period byte base, scaled by the global byte multiplier. */
	public static final int TRACKING_GLOBAL_BASE_BYTES = 16 * 1024;

	/**
	 * Display byte base scaled by the finite display size multiplier. The
	 * legal display values are the confirmed KiB grid, so this base always
	 * yields an exact integer byte amount.
	 */
	public static final int DISPLAY_BASE_BYTES = 4 * 1024;
	/**
	 * Frame byte base scaled by the finite frame size multiplier; frame values
	 * step by a quarter multiplier, which is exactly 8 KiB on this base.
	 */
	public static final int FRAME_BASE_BYTES = 32 * 1024;
	/**
	 * Fixed metadata reserve the frame budget must keep above the display
	 * budget: {@code displayBytes + reserve <= frameBytes}.
	 */
	public static final int FRAME_METADATA_RESERVE_BYTES = 2 * 1024;
	/** Largest legal display budget in bytes (128 KiB). */
	public static final int MAX_DISPLAY_BYTES = 128 * 1024;
	/** Largest legal frame budget in bytes (512 KiB). */
	public static final int MAX_FRAME_BYTES = 512 * 1024;

	/** The preview send queue holds four global server period byte amounts. */
	public static final int PREVIEW_QUEUE_GLOBAL_PERIODS = 4;
	/** The tracking rolling queue holds four average bounds of two global period amounts. */
	public static final int TRACKING_ROLLING_QUEUE_GLOBAL_PERIODS = 8;

	private InventoryLimits() {}
}
