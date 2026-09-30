package nx.pingwheel.common.config;

/**
 * Code-owned engineering bounds and wire-byte bases for the authoritative
 * inventory budget model in {@link InventorySettings}.
 *
 * <p>These constants are implementation values, not a second product
 * catalogue. Product ranges that the settings UI and documentation own are the
 * multiplier grids in {@link ByteMultiplierGrid}, the finite memory range and
 * the heartbeat range; the guards below exist only so an explicit unlimited
 * mode can never become {@code Integer.MAX_VALUE} arithmetic, an unbounded
 * array or an unbounded work loop.
 */
public final class InventoryLimits {

	/** Default shared physical scan allowance in slots per tick. */
	public static final int DEFAULT_PHYSICAL_SLOTS_PER_TICK = 1024;

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

	/**
	 * Finite hard guard used when a send-byte multiplier is in explicit
	 * unlimited mode: 256 MiB per period. The runtime keeps its own structural
	 * and memory guard; this value only prevents an unbounded byte allowance.
	 */
	public static final long INTERNAL_BYTE_BUDGET_GUARD = 256L * 1024L * 1024L;

	/** A finite cap is always positive; zero is not a disable sentinel for these caps. */
	public static final int MIN_FINITE_LIMIT = 1;

	/** Default server-wide pending-memory bound in MiB; finite only. */
	public static final int DEFAULT_PENDING_MEMORY_MIB = 16;
	public static final int MIN_PENDING_MEMORY_MIB = 1;
	public static final int MAX_PENDING_MEMORY_MIB = 512;

	/** Shared period bound for preview and tracking; matches the established interval bound. */
	public static final int MIN_PERIOD_TICKS = 1;
	public static final int MAX_PERIOD_TICKS = 72000;

	/** Resynchronization cooldown stays positive and has no unlimited mode. */
	public static final int MIN_RESYNC_PERIODS = 1;

	/** Heartbeat periods: zero is the explicit "disabled" value, not an unlimited sentinel. */
	public static final int MIN_HEARTBEAT_PERIODS = 0;
	public static final int MAX_HEARTBEAT_PERIODS = 32;

	/** Bounded grace periods before an unknown-baseline stream is dropped. */
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

	/** The preview send queue holds four global server period byte amounts. */
	public static final int PREVIEW_QUEUE_GLOBAL_PERIODS = 4;
	/** The tracking rolling queue holds four average bounds of two global period amounts. */
	public static final int TRACKING_ROLLING_QUEUE_GLOBAL_PERIODS = 8;

	private InventoryLimits() {}
}
