package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Arrays;
import net.minecraft.nbt.*;

/** Fixed-cap owned canonical output, with direct UTF-8 and bounded insertion sorting. */
final class InventoryCanonicalWriter {
	private static final String BYTE_BOUND_REASON = "canonical byte bound=" + InventoryItemCodec.MAX_CANONICAL_BYTES;
	private final byte[] bytes;
	private final InventoryItemBudget budget;
	private int cursor;
	private int nodes;

	private InventoryCanonicalWriter(InventoryItemBudget budget) {
		this.budget = budget;
		allocate(InventoryItemCodec.MAX_CANONICAL_BYTES, 0, 1);
		bytes = new byte[InventoryItemCodec.MAX_CANONICAL_BYTES];
	}

	static byte[] write(Tag tag, InventoryItemBudget budget) {
		if (budget != null) budget.allocate(8, 0, 2, 1);
		var writer = new InventoryCanonicalWriter(budget);
		writer.tag(tag, 0);
		writer.allocate(writer.cursor, 0, 1);
		writer.work(writer.cursor);
		return Arrays.copyOf(writer.bytes, writer.cursor);
	}

	private void tag(Tag tag, int depth) {
		if (tag == null || depth > InventoryItemBudget.MAX_DEPTH || ++nodes > InventoryItemBudget.MAX_NODES) refuse();
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
				byte[] data = value.getAsByteArray(); room(4L + data.length); number(data.length, 4);
				work(data.length); System.arraycopy(data, 0, bytes, cursor, data.length); cursor += data.length;
			}
			case IntArrayTag value -> {
				int[] data = value.getAsIntArray(); room(4L + 4L * data.length); number(data.length, 4);
				for (int element : data) number(element, 4);
			}
			case LongArrayTag value -> {
				long[] data = value.getAsLongArray(); room(4L + 8L * data.length); number(data.length, 4);
				for (long element : data) number(element, 8);
			}
			case ListTag value -> {
				if (value.size() > InventoryItemBudget.MAX_COLLECTION) refuse();
				number(value.getElementType(), 1); number(value.size(), 4);
				for (int index = 0; index < value.size(); index++) tag(value.get(index), depth + 1);
			}
			case CompoundTag value -> {
				int count = value.size(); if (count > InventoryItemBudget.MAX_COLLECTION) refuse();
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
		if (value.length() > InventoryItemBudget.MAX_UTF16) refuse();
		work(32L * value.length()); long length = InventoryDisplayWriter.utf8(value); room(4 + length); number(length, 4);
		for (int index = 0; index < value.length(); index++) {
			work(32); char c = value.charAt(index);
			if (c < 0x80) number(c, 1);
			else if (c < 0x800) { number(0xC0 | c >> 6, 1); number(0x80 | c & 63, 1); }
			else if (Character.isHighSurrogate(c) && index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
				int code = Character.toCodePoint(c, value.charAt(++index));
				number(0xF0 | code >> 18, 1); number(0x80 | code >> 12 & 63, 1);
				number(0x80 | code >> 6 & 63, 1); number(0x80 | code & 63, 1);
			} else if (Character.isSurrogate(c)) number('?', 1);
			else { number(0xE0 | c >> 12, 1); number(0x80 | c >> 6 & 63, 1); number(0x80 | c & 63, 1); }
		}
	}

	private void number(long value, int width) {
		room(width); work(8L * width);
		for (int shift = (width - 1) * 8; shift >= 0; shift -= 8) bytes[cursor++] = (byte) (value >>> shift);
	}
	private void room(long additional) {
		if (additional > bytes.length - cursor) throw new InventoryItemCodec.IdentityUnavailableException(BYTE_BOUND_REASON);
	}
	private void allocate(long payload, long references, long objects) {
		if (budget != null) budget.allocate(payload, 0, references, objects);
	}
	private void work(long units) { if (budget != null) budget.work(units); }
	private static void refuse() { throw new InventoryItemCodec.IdentityUnavailableException("canonical component bound"); }
}
