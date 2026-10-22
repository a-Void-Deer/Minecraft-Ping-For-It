package nx.pingwheel.common.presentation.inventory;

import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;

/**
 * Cached costs of lowering already-owned display data. Each actual parser
 * invocation is counted, even when two owners retain the same String. Readers
 * use detached metadata only; constructing/updating a summary belongs inside
 * the owner's admitted capture, intern or tightening operation.
 */
public record InventoryReprojectionSummary(long entries, long parserCalls, long inputBytes,
	long parserWorkUnits, int maxInputBytes, long replacementBytes) {
	public static final InventoryReprojectionSummary EMPTY = new InventoryReprojectionSummary(0, 0, 0, 0, 0, 0);
	/** An extension with an opaque tightening callback has supplied no cost contract. */
	public static final InventoryReprojectionSummary UNKNOWN = new InventoryReprojectionSummary(-1, 0, 0, 0, 0, 0);
	public static final long ENTRY_WORK = 8192L, FIXED_WORK = 4096L;
	/** Fixed room for cached records and handle combination, not parser scratch. */
	public static final long METADATA_BYTES = 1024L;

	public InventoryReprojectionSummary {
		if (entries < -1 || parserCalls < 0 || inputBytes < 0 || parserWorkUnits < 0 || maxInputBytes < 0 || replacementBytes < 0
			|| (entries >= 0 && parserCalls > entries)
			|| (entries == -1 && (parserCalls != 0 || inputBytes != 0 || parserWorkUnits != 0 || maxInputBytes != 0 || replacementBytes != 0)))
			throw new IllegalArgumentException("inventory reprojection summary");
	}
	public boolean known() { return entries >= 0; }
	public InventoryReprojectionSummary plus(InventoryReprojectionSummary other) {
		if (!known() || !other.known()) return UNKNOWN;
		if (this == EMPTY) return other;
		if (other == EMPTY) return this;
		return new InventoryReprojectionSummary(Math.addExact(entries, other.entries), Math.addExact(parserCalls, other.parserCalls),
			Math.addExact(inputBytes, other.inputBytes), Math.addExact(parserWorkUnits, other.parserWorkUnits),
			Math.max(maxInputBytes, other.maxInputBytes), Math.addExact(replacementBytes, other.replacementBytes));
	}
	public long workUnits() {
		if (!known()) throw new IllegalStateException("unknown inventory reprojection cost");
		return Math.addExact(FIXED_WORK, Math.addExact(Math.multiplyExact(ENTRY_WORK, entries), parserWorkUnits));
	}
	public long workspaceBytes() {
		if (!known()) throw new IllegalStateException("unknown inventory reprojection cost");
		return Math.addExact(METADATA_BYTES, Math.addExact(replacementBytes,
			parserCalls == 0 ? 0 : InventoryItemBudget.reprojectionWorkspaceUpperBound(maxInputBytes)));
	}
	/** Includes caller UTF counts, validation/equality and replacement metadata. */
	public static long parserWork(int oldUtf8Bytes) {
		return Math.addExact(InventoryItemBudget.reprojectionWorkUpperBound(oldUtf8Bytes), Math.multiplyExact(64L, oldUtf8Bytes));
	}
	/** New display/string/frozen wrapper overlap; OLD ownership remains charged. */
	public static long replacementBytes(int oldUtf8Bytes) { return Math.addExact(1024L, Math.multiplyExact(2L, oldUtf8Bytes)); }
	/** One fixed-size scratch accumulator, never an array proportional to the input. */
	public static final class Builder {
		private long entries, calls, bytes, work, replacements;
		private int max;
		public Builder entries(long count) {
			if (count < 0) throw new IllegalArgumentException("negative reprojection entries");
			entries = Math.addExact(entries, count); return this;
		}
		/** One actual non-null parser input; null invokes no parser. */
		public Builder input(String json) {
			if (json == null) return this;
			int length = utf8Bytes(json);
			calls = Math.addExact(calls, 1); bytes = Math.addExact(bytes, length);
			work = Math.addExact(work, parserWork(length)); max = Math.max(max, length);
			replacements = Math.addExact(replacements, replacementBytes(length)); return this;
		}
		public InventoryReprojectionSummary build() {
			if (calls > entries) throw new IllegalStateException("parser inputs exceed visited entries");
			return entries == 0 ? EMPTY : new InventoryReprojectionSummary(entries, calls, bytes, work, max, replacements);
		}
	}
	/** Allocation-free UTF-8 length, with CharsetEncoder's one-byte surrogate replacement. */
	public static int utf8Bytes(String value) {
		if (value == null) return 0;
		int bytes = 0;
		for (int index = 0; index < value.length(); index++) {
			char unit = value.charAt(index);
			int width;
			if (unit < 0x80) width = 1;
			else if (unit < 0x800) width = 2;
			else if (Character.isHighSurrogate(unit) && index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) { width = 4; index++; }
			else width = Character.isSurrogate(unit) ? 1 : 3;
			bytes = Math.addExact(bytes, width);
		}
		return bytes;
	}
}
