package nx.pingwheel.common.presentation.inventory.minecraft;

import net.minecraft.nbt.*;

/** Native StringTagVisitor grammar over an admitted owned graph, without subtree strings/regex. */
final class InventorySnbtWriter {
	private final InventoryItemBudget budget;
	private final char[] buffer;
	private final char[] digits;
	private int cursor;

	private InventorySnbtWriter(InventoryItemBudget budget, int capacity) {
		this.budget = budget;
		budget.allocate(0, capacity + 20L, 0, 2);
		buffer = new char[capacity]; digits = new char[20];
	}

	static String write(InventoryComponentBounds.NbtValue value, InventoryItemBudget budget) {
		int capacity = length(value, budget);
		budget.allocate(4, 0, 3, 1);
		var writer = new InventorySnbtWriter(budget, capacity); writer.value(value);
		// String(char[]) can attempt Latin-1 before its UTF-16 backing.
		budget.allocate(3L * writer.cursor + 16, 0, 1, 3); budget.work(2L * writer.cursor);
		return new String(writer.buffer, 0, writer.cursor);
	}

	/** No formatting/allocation during length admission; decimal tokens use their JDK21 ceiling. */
	private static int length(InventoryComponentBounds.NbtValue value, InventoryItemBudget budget) {
		budget.work(1);
		long length = 0;
		if (value.fields() != null) {
			length = 2 + Math.max(0, value.fields().size() - 1);
			for (int index = 0; index < value.fields().size(); index++) {
				var field = value.fields().get(index);
				length += (simple(field.key(), budget) ? field.key().length() : quotedLength(field.key(), budget))
					+ 1L + length(field.value(), budget);
				checkLength(length);
			}
		} else if (value.children() != null) {
			length = 2 + Math.max(0, value.children().size() - 1);
			for (int index = 0; index < value.children().size(); index++) {
				length += length(value.children().get(index), budget); checkLength(length);
			}
		} else {
			length = switch (value.scalar()) {
				case StringTag tag -> quotedLength(tag.getAsString(), budget);
				case ByteTag tag -> integerLength(tag.getAsByte(), budget) + 1;
				case ShortTag tag -> integerLength(tag.getAsShort(), budget) + 1;
				case IntTag tag -> integerLength(tag.getAsInt(), budget);
				case LongTag tag -> integerLength(tag.getAsLong(), budget) + 1;
				case FloatTag ignored -> 25;
				case DoubleTag ignored -> 25;
				case ByteArrayTag tag -> arrayLength(tag.getAsByteArray(), null, null, budget);
				case IntArrayTag tag -> arrayLength(null, tag.getAsIntArray(), null, budget);
				case LongArrayTag tag -> arrayLength(null, null, tag.getAsLongArray(), budget);
				default -> throw new InventoryItemBudget.RootLimitException("unsupported SNBT tag");
			};
		}
		checkLength(length); return (int) length;
	}
	private static long arrayLength(byte[] bytes, int[] ints, long[] longs, InventoryItemBudget budget) {
		int size = bytes != null ? bytes.length : ints != null ? ints.length : longs.length;
		long length = 4L + Math.max(0, size - 1);
		for (int index = 0; index < size; index++) {
			long number = bytes != null ? bytes[index] : ints != null ? ints[index] : longs[index];
			length += integerLength(number, budget) + (ints == null ? 1 : 0); checkLength(length);
		}
		return length;
	}
	private static int integerLength(long value, InventoryItemBudget budget) {
		int length = value < 0 ? 1 : 0; if (value > 0) value = -value;
		do { budget.work(1); length++; value /= 10; } while (value != 0);
		return length;
	}
	private static long quotedLength(String value, InventoryItemBudget budget) {
		char quote = quoteCharacter(value, budget);
		long length = value.length() + 2L;
		for (int index = 0; index < value.length(); index++) {
			budget.work(1); char c = value.charAt(index);
			if (c == '\\' || c == quote) length++;
		}
		return length;
	}
	private static void checkLength(long length) {
		if (length > InventoryItemBudget.MAX_UTF16) InventoryComponentCertificate.refuse();
	}

	private void value(InventoryComponentBounds.NbtValue value) {
		budget.work(1);
		if (value.fields() != null) {
			int size = value.fields().size(); budget.allocate(0, 0, size, 1);
			var fields = new InventoryComponentBounds.NbtField[size];
			for (int index = 0; index < size; index++) { budget.work(1); fields[index] = value.fields().get(index); }
			for (int index = 1; index < size; index++) {
				var field = fields[index]; int prior = index - 1;
				while (prior >= 0 && compare(fields[prior].key(), field.key()) > 0) { budget.work(1); fields[prior + 1] = fields[prior--]; }
				fields[prior + 1] = field;
			}
			unit('{');
			for (int index = 0; index < size; index++) {
				if (index != 0) unit(',');
				String key = fields[index].key();
				if (simple(key, budget)) text(key); else quote(key);
				unit(':'); value(fields[index].value());
			}
			unit('}'); return;
		}
		if (value.children() != null) {
			unit('[');
			for (int index = 0; index < value.children().size(); index++) {
				if (index != 0) unit(','); value(value.children().get(index));
			}
			unit(']'); return;
		}
		switch (value.scalar()) {
			case StringTag tag -> quote(tag.getAsString());
			case ByteTag tag -> { integer(tag.getAsByte()); unit('b'); }
			case ShortTag tag -> { integer(tag.getAsShort()); unit('s'); }
			case IntTag tag -> integer(tag.getAsInt());
			case LongTag tag -> { integer(tag.getAsLong()); unit('L'); }
			case FloatTag tag -> { decimal(); text(Float.toString(tag.getAsFloat())); unit('f'); }
			case DoubleTag tag -> { decimal(); text(Double.toString(tag.getAsDouble())); unit('d'); }
			case ByteArrayTag tag -> {
				text("[B;"); byte[] array = tag.getAsByteArray();
				for (int index = 0; index < array.length; index++) { if (index != 0) unit(','); integer(array[index]); unit('B'); } unit(']');
			}
			case IntArrayTag tag -> {
				text("[I;"); int[] array = tag.getAsIntArray();
				for (int index = 0; index < array.length; index++) { if (index != 0) unit(','); integer(array[index]); } unit(']');
			}
			case LongArrayTag tag -> {
				text("[L;"); long[] array = tag.getAsLongArray();
				for (int index = 0; index < array.length; index++) { if (index != 0) unit(','); integer(array[index]); unit('L'); } unit(']');
			}
			default -> InventoryComponentCertificate.refuse();
		}
	}
	private void decimal() {
		// Shared JDK21/JDK25 Schubfach ceiling, including either String coder.
		// Allocation initialization (98) is added once by allocate.
		budget.allocate(92, 0, 2, 4); budget.work(13523);
	}
	private void integer(long number) {
		boolean negative = number < 0; if (!negative) number = -number;
		int index = digits.length;
		do { budget.work(4); digits[--index] = (char) ('0' - number % 10); number /= 10; } while (number != 0);
		if (negative) unit('-'); for (; index < digits.length; index++) unit(digits[index]);
	}
	private static boolean simple(String value, InventoryItemBudget budget) {
		if (value.isEmpty()) return false;
		for (int index = 0; index < value.length(); index++) {
			budget.work(1); char c = value.charAt(index);
			if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')
				&& c != '.' && c != '_' && c != '+' && c != '-') return false;
		}
		return true;
	}
	private void quote(String value) {
		char quote = quoteCharacter(value, budget);
		unit(quote);
		for (int index = 0; index < value.length(); index++) { char c = value.charAt(index); if (c == '\\' || c == quote) unit('\\'); unit(c); }
		unit(quote);
	}
	private static char quoteCharacter(String value, InventoryItemBudget budget) {
		for (int index = 0; index < value.length(); index++) {
			budget.work(1); char c = value.charAt(index);
			if (c == '"' || c == '\'') return c == '"' ? '\'' : '"';
		}
		return '"';
	}
	private int compare(String left, String right) {
		for (int index = 0; index < Math.min(left.length(), right.length()); index++) {
			budget.work(1); int compared = left.charAt(index) - right.charAt(index); if (compared != 0) return compared;
		}
		budget.work(1); return left.length() - right.length();
	}
	private void text(String value) { for (int index = 0; index < value.length(); index++) unit(value.charAt(index)); }
	private void unit(char value) {
		budget.work(1); if (cursor == buffer.length) InventoryComponentCertificate.refuse(); buffer[cursor++] = value;
	}
}
