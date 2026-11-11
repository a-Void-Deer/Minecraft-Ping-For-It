package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Arrays;
import net.minecraft.nbt.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;

/** Bounded growable owned canonical scratch, with direct UTF-8 and insertion sorting. */
@NotNullByDefault
final class InventoryCanonicalWriter {
	private static final String BYTE_BOUND_REASON = "canonical byte bound=" + InventoryItemCodec.MAX_CANONICAL_BYTES;
	private byte[] bytes;
	private final @Nullable InventoryItemBudget budget;
	private int cursor;
	private int nodes;
	private int arrayBytes;
	private boolean strictUtf;

	private InventoryCanonicalWriter(@Nullable InventoryItemBudget budget) {
		this.budget = budget;
		allocate(256, 0, 1);
		bytes = new byte[256];
	}

	static byte @Unmodifiable [] write(@Nullable Tag tag, @Nullable InventoryItemBudget budget) {
		return write(tag, budget, false);
	}
	/** Fluid identity cannot collapse malformed UTF-16 to the same replacement byte. ITEM remains unchanged. */
	static byte @Unmodifiable [] writeFluid(Tag tag, InventoryItemBudget budget) { return write(tag, budget, true); }
	private static byte @Unmodifiable [] write(@Nullable Tag tag, @Nullable InventoryItemBudget budget, boolean strictUtf) {
		if (budget != null) budget.allocate(17, 0, 2, 1);
		var writer = new InventoryCanonicalWriter(budget);
		writer.strictUtf = strictUtf;
		writer.tag(tag, 0);
		writer.allocate(writer.cursor, 0, 1);
		writer.work(writer.cursor);
		return Arrays.copyOf(writer.bytes, writer.cursor);
	}

	private void tag(@Nullable Tag tag, int depth) {
		work(1); // Refused depth/node/type attempts remain paid before output allocation.
		if (tag == null) refuse();
		if (depth > InventoryItemBudget.MAX_DEPTH) refuse(InventoryFailureReason.NATIVE_DEPTH_LIMIT);
		if (++nodes > InventoryItemBudget.MAX_NODES) refuse(InventoryFailureReason.NATIVE_NODE_LIMIT);
		Class<?> type = tag.getClass();
		if (type != ByteTag.class && type != ShortTag.class && type != IntTag.class && type != LongTag.class
			&& type != FloatTag.class && type != DoubleTag.class && type != StringTag.class && type != ByteArrayTag.class
			&& type != IntArrayTag.class && type != LongArrayTag.class && type != ListTag.class && type != CompoundTag.class) refuse();
		work(64); number(tag.getId(), 1);
		switch (tag) {
			case ByteTag value -> number(value.getAsByte(), 1);
			case ShortTag value -> number(value.getAsShort(), 2);
			case IntTag value -> number(value.getAsInt(), 4);
			case LongTag value -> number(value.getAsLong(), 8);
			case FloatTag value -> number(Float.floatToIntBits(value.getAsFloat()), 4);
			case DoubleTag value -> number(Double.doubleToLongBits(value.getAsDouble()), 8);
			case StringTag value -> string(value.getAsString());
			case ByteArrayTag value -> {
				byte[] data = value.getAsByteArray(); array(data.length); room(4L + data.length); number(data.length, 4);
				work(data.length); System.arraycopy(data, 0, bytes, cursor, data.length); cursor += data.length;
			}
			case IntArrayTag value -> {
				int[] data = value.getAsIntArray(); array(4L * data.length); room(4L + 4L * data.length); number(data.length, 4);
				for (int element : data) number(element, 4);
			}
			case LongArrayTag value -> {
				long[] data = value.getAsLongArray(); array(8L * data.length); room(4L + 8L * data.length); number(data.length, 4);
				for (long element : data) number(element, 8);
			}
			case ListTag value -> {
				if (value.size() > InventoryItemBudget.MAX_LIST) refuse(InventoryFailureReason.NATIVE_LIST_LIMIT);
				number(value.getElementType(), 1); number(value.size(), 4);
				for (int index = 0; index < value.size(); index++) tag(value.get(index), depth + 1);
			}
			case CompoundTag value -> {
				int count = value.size(); if (count > InventoryItemBudget.MAX_MAP) refuse(InventoryFailureReason.NATIVE_MAP_LIMIT);
				if (budget != null) budget.allocate(8, 0, 4, 2); // key view + HashIterator's two ints/three refs
				// Owned outputs start16, may collision-resize to64, and load-grow.
				// No resident sparse capacity is licensed by this output-only pass.
				int capacity = count == 0 ? 0 : 16;
				while (capacity < 64 && count > 8 || count * 4L > capacity * 3L) capacity *= 2;
				work(16L * (capacity + count));
				allocate(0, count, 1); String[] keys = new String[count]; int index = 0;
				for (String key : value.getAllKeys()) { work(1); keys[index++] = key; }
				for (int next = 1; next < count; next++) {
					String key = keys[next]; int previous = next - 1;
					while (previous >= 0 && compare(keys[previous], key) > 0) { work(1); keys[previous + 1] = keys[previous--]; }
					keys[previous + 1] = key;
				}
				number(count, 4);
				for (String key : keys) {
					string(key);
					// Owned output keys may collide. Pay the finite full-collision
					// string comparison/search bound before HashMap.get.
					work((long) (count + 1) * (512L + 8L * (key.length() + 1)));
					tag(value.get(key), depth + 1);
				}
			}
			default -> refuse();
		}
	}

	private int compare(String left, String right) {
		int count = Math.min(left.length(), right.length());
		for (int index = 0; index < count; index++) {
			work(16); int difference = left.charAt(index) - right.charAt(index); if (difference != 0) return difference;
		}
		work(1); return left.length() - right.length();
	}

	private void string(String value) {
		if (value.length() > InventoryItemBudget.MAX_UTF16) refuse(InventoryFailureReason.NATIVE_WORK_LIMIT);
		work(32L * value.length()); long length = InventoryDisplayWriter.utf8(value); room(4 + length); number(length, 4);
		for (int index = 0; index < value.length(); index++) {
			work(32); char c = value.charAt(index);
			if (c < 0x80) number(c, 1);
			else if (c < 0x800) { number(0xC0 | c >> 6, 1); number(0x80 | c & 63, 1); }
			else if (Character.isHighSurrogate(c) && index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
				int code = Character.toCodePoint(c, value.charAt(++index));
				number(0xF0 | code >> 18, 1); number(0x80 | code >> 12 & 63, 1);
				number(0x80 | code >> 6 & 63, 1); number(0x80 | code & 63, 1);
			} else if (Character.isSurrogate(c)) { if (strictUtf) refuse(); number('?', 1); }
			else { number(0xE0 | c >> 12, 1); number(0x80 | c >> 6 & 63, 1); number(0x80 | c & 63, 1); }
		}
	}

	private void number(long value, int width) {
		room(width); work(8L * width);
		for (int shift = (width - 1) * 8; shift >= 0; shift -= 8) bytes[cursor++] = (byte) (value >>> shift);
	}
	private void room(long additional) {
		// The logical limit is independent of physical storage. Reject before
		// growth, including a negative/overflowed request; never allocate over-cap.
		if (additional < 0 || additional > InventoryItemCodec.MAX_CANONICAL_BYTES - cursor)
			throw new InventoryItemCodec.IdentityUnavailableException(InventoryFailureReason.ENTRY_LIMIT, BYTE_BOUND_REASON, true);
		if (additional <= bytes.length - cursor) return;
		work(128L + cursor); // at most eight doublings/control and written-byte copy
		int capacity = bytes.length;
		while (capacity < cursor + additional) capacity = Math.min(InventoryItemCodec.MAX_CANONICAL_BYTES, capacity * 2);
		// Pay the entire new array, including initialization, not its delta.
		// Skipped capacities are not allocated; discarded attempts stay paid.
		allocate(capacity, 0, 1);
		byte[] grown = new byte[capacity];
		System.arraycopy(bytes, 0, grown, 0, cursor);
		bytes = grown;
	}
	private void allocate(long payload, long references, long objects) {
		if (budget != null) budget.allocate(payload, 0, references, objects);
	}
	private void work(long units) { if (budget != null) budget.work(units); }
	private void array(long bytes) {
		work(1);
		if (bytes > InventoryItemBudget.MAX_ARRAY_BYTES - arrayBytes) refuse(InventoryFailureReason.NATIVE_ARRAY_LIMIT);
		arrayBytes += (int) bytes;
	}
	private static void refuse() { throw new InventoryItemCodec.IdentityUnavailableException("canonical component bound"); }
	private static void refuse(InventoryFailureReason reason) { throw new InventoryItemCodec.IdentityUnavailableException(reason, "canonical component bound", true); }
}
