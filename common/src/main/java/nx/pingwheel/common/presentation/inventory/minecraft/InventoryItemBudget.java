package nx.pingwheel.common.presentation.inventory.minecraft;

import nx.pingwheel.common.config.InventoryLimits;

/**
 * Admission envelope and attempted-work ledger for sequential item freezes.
 * Modeled storage is not a measured JVM heap total. Every native encode path must
 * separately admit its call-graph vector BEFORE it runs; this ledger is not a
 * certificate for an arbitrary codec. Refused roots do not refund attempted work.
 */
public final class InventoryItemBudget {
	// Supported conservative layout weights: 32-byte object/array header, 8-byte
	// references, two-byte UTF-16 units, and seven bytes alignment per object.
	static final long OBJECT_BYTES = 40;
	static final long REFERENCE_BYTES = 8;
	static final int MAX_DEPTH = 48;
	static final int MAX_NODES = 4096;
	static final int MAX_UTF16 = 131072;
	static final int MAX_ARRAY_BYTES = 49152;
	static final int MAX_COLLECTION = 256;
	static final long MAX_NATIVE_TRAVERSAL = 262144;
	// Fixed-cap scratch envelope. Writers/certificates must charge into it; a
	// worst-case codec that cannot fit refuses identity rather than exceeding it.
	private static final long IDENTITY_ALLOCATION_BYTES = 2L * 1024 * 1024;
	private static final long DISPLAY_ALLOCATION_BYTES = 1024L * 1024;
	// E1 c256 alone contributes16*c*t=4,194,304 work before Details,
	// collector and native leaf rows. Four Mi cannot admit that single fixture.
	// The20k literal floor includes strict quoting + both-coder String helper
	// loops, canonical UTF8 and raw registry lookups. Twelve Mi leaves headroom;
	// this changes WORK only, not the3Mi modeled-allocation workspace.
	private static final long IDENTITY_WORK = 12L * 1024 * 1024;
	private static final long DISPLAY_WORK = 12L * 1024 * 1024;
	private static final int MAX_ROOTS = 65536;

	private final int displayBytes;
	private final int maxRoots;
	private final long retainedLimit;
	private final long workLimit;
	private long attemptedWork;
	private long retainedBytes;
	private int roots;
	private boolean active;
	private long rootWork;
	private long rootAllocated;
	private long phaseAllocated;
	private long phaseWork;
	private boolean display;
	private int nodes;
	private int utf16;
	private int arrayBytes;
	private int containers;

	private InventoryItemBudget(int displayBytes, int maxRoots, long retainedLimit, long workLimit) {
		this.displayBytes = displayCap(displayBytes);
		if (maxRoots < 1 || maxRoots > MAX_ROOTS || retainedLimit < 1 || workLimit < 1)
			throw new IllegalArgumentException("inventory capture budget");
		this.maxRoots = maxRoots;
		this.retainedLimit = retainedLimit;
		this.workLimit = workLimit;
	}

	/** Fixed attempted-allocation envelope plus bounded output overlaps and ledger state. */
	public static long workspaceUpperBound(int displayBytes) {
		int cap = displayCap(displayBytes);
		// Both cumulative phase caps dominate live scratch+retained outputs;
		// outside-phase ledger/input/refusals use <512B (94B/7R/4O).
		// The separate sequential Catalog pass uses its own admission bound;
		// it must not be added as simultaneous phase scratch or hidden in here.
		return add(IDENTITY_ALLOCATION_BYTES + DISPLAY_ALLOCATION_BYTES, add(32768, multiply(6, cap)));
	}

	/** Canonical array, display UTF-16, bounded id/label and metadata, including headers. */
	public static long retainedUpperBound(int displayBytes) {
		return add(InventoryItemCodec.MAX_CANONICAL_BYTES, add(multiply(2, displayCap(displayBytes)), 4096));
	}

	/** Freeze only; Catalog read processing must be admitted separately or via U. */
	public static long freezeWorkUpperBound(int displayBytes) {
		int cap = displayCap(displayBytes);
		return add(IDENTITY_WORK + DISPLAY_WORK, add(32768, multiply(16, cap)));
	}

	/** One freeze plus one sequential admitted Catalog read, never a phase debit. */
	public static long workUpperBound(int displayBytes) {
		return add(freezeWorkUpperBound(displayBytes), InventoryCatalogAdmission.workUpperBound(displayBytes));
	}

	/** OLD-input span arrays, decoded keys, output and final String; no Gson parse tree. */
	public static long reprojectionWorkspaceUpperBound(int retainedUtf8Bytes) {
		if (retainedUtf8Bytes < 0 || retainedUtf8Bytes > InventoryLimits.MAX_DISPLAY_BYTES)
			throw new IllegalArgumentException("retained display bound");
		// m=floor(B/4)+1, Q<=B+1024, L<=Q. Actual vector:
		// (25m+3L+48, B+Q+20, 12, 15), including possible compact-String
		// attempt and UTF-16 backing. Componentwise 25% padding fits this bound.
		return add(8192, multiply(18, retainedUtf8Bytes));
	}

	/** Independent OLD-input work, including semantic key merge sorting. */
	public static long reprojectionWorkUpperBound(int retainedUtf8Bytes) {
		reprojectionWorkspaceUpperBound(retainedUtf8Bytes);
		long members = retainedUtf8Bytes / 4L;
		long keyUnits = add(retainedUtf8Bytes, multiply(10, members));
		int levels = 0; for (long width = 1; width < members; width *= 2) levels++;
		// Eight input scans (<=24 local events/unit), closed-name recognition
		// (172/member), normalized sort character/descriptor/copy events,
		// adjacent duplicates, and three output attempts/descriptor tests.
		long scans = multiply(192, retainedUtf8Bytes);
		long sort = multiply(levels, add(multiply(8, keyUnits), multiply(12, members)));
		long emit = add(multiply(72, add(retainedUtf8Bytes, 1024)), multiply(300, members));
		long initialize = add(reprojectionWorkspaceUpperBound(retainedUtf8Bytes), multiply(2, keyUnits));
		return add(16384, add(scans, add(multiply(172, members), add(sort, add(emit, initialize)))));
	}

	public static InventoryItemBudget root(int displayBytes) {
		return new InventoryItemBudget(displayBytes, 1, retainedUpperBound(displayBytes), workUpperBound(displayBytes));
	}

	public static InventoryItemBudget capture(int displayBytes, int maxRoots,
		long retainedLimitBytes, long workLimitUnits) {
		return new InventoryItemBudget(displayBytes, maxRoots, retainedLimitBytes, workLimitUnits);
	}

	void begin(int requestedDisplayBytes) {
		if (active || displayCap(requestedDisplayBytes) != displayBytes)
			throw new IllegalStateException("inventory root budget lifecycle");
		if (roots >= maxRoots) throw new CaptureLimitException("inventory capture root limit");
		roots++;
		active = true;
		rootWork = 0;
		rootAllocated = 0;
		phaseAllocated = phaseWork = 0; display = false;
		nodes = utf16 = arrayBytes = 0;
		containers = 0;
	}

	void work(long units) {
		requireActive();
		if (units < 0) throw new IllegalArgumentException("negative inventory work");
		long nextPhase = add(phaseWork, units);
		if (nextPhase > (display ? DISPLAY_WORK : IDENTITY_WORK)) throw new RootLimitException("inventory root work limit");
		long next = add(attemptedWork, units);
		if (next > workLimit) throw new CaptureLimitException("inventory capture work limit");
		attemptedWork = next;
		rootWork = add(rootWork, units);
		phaseWork = nextPhase;
	}

	void allocate(long bytes, long chars, long references, long objects) {
		requireActive();
		long cost = allocationCost(bytes, chars, references, objects);
		long next = add(phaseAllocated, cost);
		if (next > (display ? DISPLAY_ALLOCATION_BYTES : IDENTITY_ALLOCATION_BYTES)) throw new RootLimitException("inventory root allocation limit");
		// Allocation also consumes cumulative attempted work: zero-initialized
		// array cells and object construction sites, not a memory-ledger refund.
		// Repeated discarded roots therefore cannot hide allocation attempts.
		work(add(add(bytes, chars), add(references, objects)));
		phaseAllocated = next;
		rootAllocated = add(rootAllocated, cost);
	}
	/** Identity/canonical bytes are final; display attempts have independent caps. */
	void beginDisplay() {
		requireActive(); if (display) throw new IllegalStateException("inventory display lifecycle");
		display = true; phaseAllocated = phaseWork = 0;
	}

	static long allocationCost(long bytes, long chars, long references, long objects) {
		if (bytes < 0 || chars < 0 || references < 0 || objects < 0)
			throw new IllegalArgumentException("negative inventory allocation vector");
		return add(add(bytes, multiply(2, chars)), add(multiply(REFERENCE_BYTES, references), multiply(OBJECT_BYTES, objects)));
	}

	void node(int depth) {
		work(1);
		if (depth > MAX_DEPTH || ++nodes > MAX_NODES) throw new RootLimitException("inventory native tree bound");
	}

	void container() { work(1); if (++containers > MAX_NODES) throw new RootLimitException("inventory container occurrences"); }

	void string(String value) {
		requireActive();
		if (value == null) throw new RootLimitException("null inventory string");
		int length = value.length();
		if (length > MAX_UTF16 - utf16) throw new RootLimitException("inventory string bound");
		utf16 += length;
		work(length);
	}

	void array(long bytes) {
		requireActive();
		if (bytes < 0 || bytes > MAX_ARRAY_BYTES - arrayBytes) throw new RootLimitException("inventory array bound");
		arrayBytes += (int) bytes;
		work(bytes);
	}

	long remainingTraversalWork() { return Math.min(MAX_NATIVE_TRAVERSAL, Math.min((display ? DISPLAY_WORK : IDENTITY_WORK) - phaseWork, workLimit - attemptedWork)); }
	int displayBytes() { return displayBytes; }

	void retain(long bytes) {
		requireActive();
		if (bytes < 0 || bytes > retainedUpperBound(displayBytes)) throw new RootLimitException("inventory retained root bound");
		long next = add(retainedBytes, bytes);
		if (next > retainedLimit) throw new CaptureLimitException("inventory capture retention limit");
		retainedBytes = next;
	}

	/** Releases root-local scratch only; attempted work and committed retention survive. */
	void end() { requireActive(); active = false; }
	long attemptedWork() { return attemptedWork; }
	long retainedBytes() { return retainedBytes; }
	long rootAllocatedBytes() { return rootAllocated; }
	private void requireActive() { if (!active) throw new IllegalStateException("no admitted inventory root"); }

	static int displayCap(int value) {
		if (value < InventoryItemCodec.minimumDisplayBytes() || value > InventoryLimits.MAX_DISPLAY_BYTES)
			throw new IllegalArgumentException("display byte cap is out of range");
		return value;
	}

	static long add(long left, long right) {
		// Refusal must not first construct Math.addExact's stack-bearing error.
		long result = left + right;
		if (((left ^ result) & (right ^ result)) < 0) throw new RootLimitException("inventory bound overflow");
		return result;
	}

	static long multiply(long left, long right) {
		long result = left * right;
		if ((left != 0 && result / left != right) || (left == -1 && right == Long.MIN_VALUE))
			throw new RootLimitException("inventory bound overflow");
		return result;
	}

	/** Invalid input is not an identity/capture refusal and never builds a trace. */
	static final class InvalidInputException extends IllegalArgumentException {
		InvalidInputException(String reason) { super(reason); }
		@Override public synchronized Throwable fillInStackTrace() { return this; }
	}

	/** Root identity refusal, distinct from aborting the entire atomic capture. */
	static final class RootLimitException extends IllegalStateException {
		RootLimitException(String reason) { super(reason); }
		@Override public synchronized Throwable fillInStackTrace() { return this; }
	}

	/** Caller must abandon the complete capture, never publish a mixed partial payload. */
	public static final class CaptureLimitException extends IllegalStateException {
		CaptureLimitException(String reason) { super(reason); }
		@Override public synchronized Throwable fillInStackTrace() { return this; }
	}
}
