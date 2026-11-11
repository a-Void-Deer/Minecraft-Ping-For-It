package nx.pingwheel.common.presentation.inventory.minecraft;

import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Scalar sub-allowance of reservations already held by the caller. This is not a
 * memory ledger or permission to manufacture a grant. Workspace is reusable for
 * sequential calls; attempted work is cumulative and is never refunded, even
 * when an intern refuses identity or retention. Lazy snapshot reads need their
 * own live scratch reservation, not the expired freeze reservation.
 */
@NotNullByDefault
public final class InventoryCatalogAdmission {

	static final int TABLE_CAPACITY = 2048;
	// Clone header40; digest/hex/both String coders408; Key+Encoded112;
	// nullable replacement Display65; reprojection result72; Builder+two summary records252;
	// one stackless capacity-refusal Throwable104 (typed limit/reason/flag).
	// No input-proportional slack.
	private static final long FIXED_BYTES = 40 + 408 + 112 + 65 + 72 + 252 + 104;
	private static final InventoryItemBudget.CaptureLimitException WORK_EXHAUSTED =
		new InventoryItemBudget.CaptureLimitException(InventoryFailureReason.ITEM_WORK_LIMIT, "inventory catalog work admission exhausted");
	private static final InventoryItemBudget.CaptureLimitException WORKSPACE_EXHAUSTED =
		new InventoryItemBudget.CaptureLimitException(InventoryFailureReason.MEMORY_LIMIT, "inventory catalog workspace admission exhausted");
	/** Four scalar longs; caller admits this metadata before constructing a grant. */
	public static final long METADATA_BYTES = 72;
	private final long workspaceBytes, workUnits;
	private long attemptedWork, peakWorkspace;

	public InventoryCatalogAdmission(long workspaceBytes, long workUnits) {
		if (workspaceBytes < 0 || workUnits < 0) throw new IllegalArgumentException("catalog allowance");
		this.workspaceBytes = workspaceBytes; this.workUnits = workUnits;
	}

	public long attemptedWork() { return attemptedWork; }
	public long peakWorkspaceBytes() { return peakWorkspace; }
	/** Fluid canonical/hash/index processing uses the same caller-owned global grant, never a second ledger. */
	public void admitFluid(int canonicalBytes, int idUnits, int labelUnits, int displayUnits) {
		admitFluid(canonicalBytes, idUnits, labelUnits, displayUnits, InventoryItemCodec.DEFAULT_MAX_ENTRIES);
	}
	/** Actual already-retained occupancy; the owning page separately admits possible growth before callbacks. */
	public void admitFluid(int canonicalBytes, int idUnits, int labelUnits, int displayUnits, int entries) {
		// Length metadata admits the subsequent scans and UTF allocation. Three
		// bytes per UTF-16 unit also dominates surrogate pairs; malformed input is
		// refused by the detached display contract, never silently canonicalized.
		if (displayUnits < 0 || displayUnits > InventoryLimits.MAX_DISPLAY_BYTES) throw new IllegalArgumentException("fluid display span");
		int bytes = (int) Math.min(InventoryLimits.MAX_DISPLAY_BYTES, 3L * displayUnits);
		admit(workspaceUpperBound(canonicalBytes, bytes, entries),
			scanWorkUpperBound(displayUnits, idUnits, labelUnits) + operationWorkUpperBound(canonicalBytes, bytes, entries));
	}
	/** Preflight a certified native read before its first provider callback. Intern still pays actual attempted work. */
	void requireNativeRead(int displayBytes) {
		requireNativeRead(displayBytes, InventoryItemCodec.DEFAULT_MAX_ENTRIES);
	}
	/** The caller's admitted per-attempt occupancy, never an authority or content cache. */
	void requireNativeRead(int displayBytes, int entries) {
		if (workspaceBytes < workspaceUpperBound(InventoryItemCodec.MAX_CANONICAL_BYTES, displayBytes, entries)) throw WORKSPACE_EXHAUSTED;
		if (workUnits - attemptedWork < workUpperBound(displayBytes, entries)) throw WORK_EXHAUSTED;
	}

	/** Checked, allocation-free metadata, before the actual UTF-8 length scan. */
	public static long scanWorkUpperBound(int oldUtf16Units, int itemIdUnits, int labelUnits) {
		if (oldUtf16Units < 0 || oldUtf16Units > InventoryLimits.MAX_DISPLAY_BYTES
			|| itemIdUnits < 1 || itemIdUnits > 256 || labelUnits < 0)
			throw new IllegalArgumentException("catalog input span");
		return Math.addExact(256, Math.multiplyExact(32L, Math.addExact((long) oldUtf16Units,
			Math.addExact((long) itemIdUnits, labelUnits))));
	}

	/** Exact SHA-256 compression count, including the length/padding block(s). */
	public static int sha256Blocks(int canonicalBytes) {
		canonical(canonicalBytes); return (canonicalBytes + 72) / 64;
	}

	/**
	 * Complete attempted allocation, not just peak parser output: OLD parser,
	 * canonical clone, digest32/hex64 (both String coders), fixed records, three
	 * lazy tables, three new tree nodes and worst-case bucket treeification.
	 * Existing retained maps/OLD input remain charged by their owning K/R tickets.
	 */
	public static long workspaceUpperBound(int canonicalBytes, int oldUtf8Bytes, int entries) {
		inputs(canonicalBytes, oldUtf8Bytes, entries);
		long parser = oldUtf8Bytes == 0 ? 0 : InventoryItemBudget.reprojectionWorkspaceUpperBound(oldUtf8Bytes);
		// With capacity2048 and no removal, a bin converts at exactly nine nodes;
		// it cannot grow a longer linked chain or later untreeify. Key is not
		// Comparable and String has HashMap's explicit fast path, so neither
		// comparableClassFor branch performs generic reflection or allocates.
		long nodes = 3L * 10 * 128;
		return Math.addExact(parser, Math.addExact(canonicalBytes, Math.addExact(FIXED_BYTES,
			Math.addExact(3L * (40 + 8L * TABLE_CAPACITY), nodes))));
	}

	/**
	 * Work AFTER the separately paid UTF scan. ALL_VARIANTS uses canonicalBytes=0
	 * conservatively (one SHA block allowed but not executed). At most six owned
	 * map operations, each with two complete collision searches; each comparison
	 * allows both 256-unit item id and 64-unit digest, the fixed {@code <=128}-unit
	 * bootstrap class names used by tieBreakOrder, and hash/control work.
	 */
	public static long operationWorkUpperBound(int canonicalBytes, int oldUtf8Bytes, int entries) {
		inputs(canonicalBytes, oldUtf8Bytes, entries);
		long parser = oldUtf8Bytes == 0 ? 0 : InventoryReprojectionSummary.parserWork(oldUtf8Bytes);
		long comparisons = 12L * (entries + 1) + (entries < 8 ? 0 : 3L * 9 * 9);
		long maps = comparisons * (128 + 8 * (320 + 128));
		// SHA: 16 big-endian loads*16, 48 schedule steps*32, 64 rounds*96,
		// 8 state merges*16 and block guards/control128 <=8192/block.
		// Fixed4096 = reset512 + wrapper/padding1024 + hex32*32 +
		// noncompact inflate64*8 + monitor/method/output control1024.
		long hashing = 4096L + 8192L * sha256Blocks(canonicalBytes) + 8L * canonicalBytes;
		// Includes allocation initialization, summary build/plus, output UTF scans,
		// reproject equality/Display validation and fixed success/refusal objects.
		return Math.addExact(parser, Math.addExact(maps, Math.addExact(hashing,
			Math.addExact(InventoryReprojectionSummary.ENTRY_WORK, workspaceUpperBound(canonicalBytes, oldUtf8Bytes, entries)))));
	}

	/** Whole ordinary native read envelope; actual-input callers can reserve less. */
	public static long workUpperBound(int displayBytes) {
		return workUpperBound(displayBytes, InventoryItemCodec.DEFAULT_MAX_ENTRIES);
	}
	/** One native read at a bounded occupancy; page callers include all possible prior insertions. */
	public static long workUpperBound(int displayBytes, int entries) {
		InventoryItemBudget.displayCap(displayBytes);
		return Math.addExact(scanWorkUpperBound(displayBytes, 256, 262),
			operationWorkUpperBound(InventoryItemCodec.MAX_CANONICAL_BYTES, displayBytes, entries));
	}

	void admit(long workspace, long work) {
		if (workspace < 0 || work < 0) throw new IllegalArgumentException("catalog charge");
		if (workspace > workspaceBytes) throw WORKSPACE_EXHAUSTED;
		if (work > workUnits - attemptedWork) throw WORK_EXHAUSTED;
		attemptedWork += work; peakWorkspace = Math.max(peakWorkspace, workspace);
	}

	private static void canonical(int bytes) {
		if (bytes < 0 || bytes > InventoryItemCodec.MAX_CANONICAL_BYTES) throw new IllegalArgumentException("catalog canonical span");
	}
	private static void inputs(int canonicalBytes, int oldUtf8Bytes, int entries) {
		canonical(canonicalBytes);
		if (oldUtf8Bytes < 0 || oldUtf8Bytes > InventoryLimits.MAX_DISPLAY_BYTES
			|| entries < 0 || entries > InventoryItemCodec.DEFAULT_MAX_ENTRIES)
			throw new IllegalArgumentException("catalog operation input");
	}
}
