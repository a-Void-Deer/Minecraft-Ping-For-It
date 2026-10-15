package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;

import nx.pingwheel.common.config.InventoryLimits;

/** Owned display data only. A null projection means suppression cannot be represented safely. */
final class InventoryDisplayWriter {

	private static final int DEPTH = 48;
	private static final String LORE = "minecraft:lore";
	private static final String CONTAINER = "minecraft:container";
	private static final String CUSTOM_NAME = "minecraft:custom_name";
	private static final String ITEM_NAME = "minecraft:item_name";
	// Sorted, closed persistent component grammar. Unknown removals remain legal;
	// an unknown addition is not permission to decode a resident component codec.
	private static final String[] COMPONENTS = {
		"attribute_modifiers", "banner_patterns", "base_color", "bees", "block_entity_data", "block_state",
		"bucket_entity_data", "bundle_contents", "can_break", "can_place_on", "charged_projectiles", "container",
		"container_loot", "custom_data", "custom_model_data", "custom_name", "damage", "debug_stick_state",
		"dyed_color", "enchantment_glint_override", "enchantments", "entity_data", "fire_resistant",
		"firework_explosion", "fireworks", "food", "hide_additional_tooltip", "hide_tooltip", "instrument",
		"intangible_projectile", "item_name", "jukebox_playable", "lock", "lodestone_tracker", "lore", "map_color",
		"map_decorations", "map_id", "max_damage", "max_stack_size", "note_block_sound", "ominous_bottle_amplifier",
		"pot_decorations", "potion_contents", "profile", "rarity", "recipes", "repair_cost", "stored_enchantments",
		"suspicious_stew_effects", "tool", "trim", "unbreakable", "writable_book_content", "written_book_content"
	};
	private static final Set<String> SHULKERS = Set.of("minecraft:shulker_box", "minecraft:white_shulker_box",
		"minecraft:orange_shulker_box", "minecraft:magenta_shulker_box", "minecraft:light_blue_shulker_box",
		"minecraft:yellow_shulker_box", "minecraft:lime_shulker_box", "minecraft:pink_shulker_box",
		"minecraft:gray_shulker_box", "minecraft:light_gray_shulker_box", "minecraft:cyan_shulker_box",
		"minecraft:purple_shulker_box", "minecraft:blue_shulker_box", "minecraft:brown_shulker_box",
		"minecraft:green_shulker_box", "minecraft:red_shulker_box", "minecraft:black_shulker_box");

	private InventoryDisplayWriter() {}
	static boolean shulker(String id) { return SHULKERS.contains(id); }

	static long utf8(String value) {
		long bytes = 0;
		for (int index = 0; index < value.length(); index++) {
			char c = value.charAt(index);
			if (c < 0x80) bytes++;
			else if (c < 0x800) bytes += 2;
			else if (Character.isHighSurrogate(c) && index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) { bytes += 4; index++; }
			else if (Character.isSurrogate(c)) bytes++; // Java's wire encoder replaces an isolated unit with '?'.
			else bytes += 3;
		}
		return bytes;
	}

	/** Only the known mandatory redactions; this does not suppress arbitrary item defaults. */
	static String bare(String id) {
		return project(null, id, 1024);
	}

	/** Fixed aggregate grammar; never inspects resident values or builds a JSON tree. */
	static String aggregate(String id) {
		char[] buffer = new char[327];
		int cursor = copy("{\"id\":\"", buffer, 0);
		cursor = copy(id, buffer, cursor);
		cursor = copy("\",\"components\":{\"!minecraft:lore\":{}", buffer, cursor);
		if (shulker(id)) cursor = copy(",\"!minecraft:container\":{}", buffer, cursor);
		cursor = copy("}}", buffer, cursor);
		return new String(buffer, 0, cursor);
	}

	private static int copy(String text, char[] buffer, int cursor) {
		text.getChars(0, text.length(), buffer, cursor); return cursor + text.length();
	}

	/** Does not mutate the owned tree, and allocates a result String only after a pass fits. */
	static String project(JsonElement owned, String id, int cap) {
		return project(owned, id, cap, null);
	}

	static String project(JsonElement owned, String id, int cap, InventoryItemBudget budget) {
		cap(cap);
		work(budget, 512);
		if (id == null || id.length() > 256 || utf8(id) > 256) return null;
		JsonObject components = null;
		int count = 0;
		if (owned != null) {
			if (!owned.isJsonObject()) return null;
			JsonObject object = owned.getAsJsonObject();
			if (!object.has("id") || !object.get("id").isJsonPrimitive()
				|| !object.get("id").getAsJsonPrimitive().isString() || !id.equals(object.get("id").getAsString())) return null;
			if (object.has("components")) {
				if (!object.get("components").isJsonObject()) return null;
				components = object.getAsJsonObject("components");
			}
			if (object.has("count")) {
				JsonElement value = object.get("count");
				if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
				Number number = value.getAsNumber();
				if (!(number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long)) return null;
				long amount = number.longValue(); if (amount < 1 || amount > 99) return null;
				count = (int) amount;
			}
			allocate(budget, 4, 0, 3, 2);
			for (String key : object.keySet()) {
				work(budget, 16L * (key.length() + 1));
				if (!key.equals("id") && !key.equals("components") && !key.equals("count")) return null;
			}
		}
		int members = components == null ? 0 : components.size();
		// Even a shortest legal removal member consumes eight bytes including
		// its separator. No pass can fit more; refuse before allocating key refs.
		if (members > cap / 8) return null;
		allocate(budget, 0, 0, members, 1); String[] keys = new String[members];
		if (components != null) {
			allocate(budget, 4, 0, 3, 2);
			int index = 0;
			for (String key : components.keySet()) {
				work(budget, 16);
				if (key.length() > cap) return null;
				keys[index++] = key;
			}
			for (int i = members / 2 - 1; i >= 0; i--) sift(keys, i, members, budget);
			for (int n = members - 1; n > 0; n--) {
				work(budget, 3); String temp = keys[0]; keys[0] = keys[n]; keys[n] = temp; sift(keys, 0, n, budget);
			}
			for (int i = 0; i < members; i++) {
				String key = keys[i]; boolean removed = key.startsWith("!");
				work(budget, 8L * key.length() + 256); // resource/namespace/closed-name/get checks
				if (!resource(key, removed ? 1 : 0) || (!removed && !known(key))) return null;
				JsonElement value = components.get(key);
				if (removed && (!value.isJsonObject() || value.getAsJsonObject().size() != 0)) return null;
				if (i != 0 && compareKeys(keys[i - 1], key, budget) == 0) return null;
			}
		}
		Output out = new Output(cap, budget);
		for (int pass = 0; pass < 3; pass++) {
			out.reset();
			if (tree(out, components, keys, id, count, pass)) return out.result();
		}
		return null;
	}

	private static boolean tree(Output out, JsonObject components, String[] keys, String id, int count, int pass) {
		if (!out.text("{\"id\":") || !out.quote(id)) return false;
		if (count != 0 && (!out.text(",\"count\":") || !out.integer(count))) return false;
		if (!out.text(",\"components\":{")) return false;
		boolean comma = false, lore = false, container = false, customName = false, itemName = false;
		for (String key : keys) {
			work(out.budget, 8L * key.length() + 128);
			boolean removed = key.startsWith("!");
			int start = removed ? 1 : 0;
			boolean isLore = named(key, start, LORE), isContainer = named(key, start, CONTAINER);
			boolean isCustom = named(key, start, CUSTOM_NAME), isItem = named(key, start, ITEM_NAME);
			lore |= isLore; container |= isContainer; customName |= isCustom; itemName |= isItem;
			boolean omit = removed || isLore || (isContainer && shulker(id)) || pass == 2 || (pass == 1 && (isCustom || isItem));
			if (comma && !out.unit(',')) return false;
			if (!(omit ? out.removal(key, start) : out.quote(key)) || !out.unit(':')) return false;
			if (omit ? !out.text("{}") : !out.value(components.get(key), 2)) return false;
			comma = true;
		}
		if (!lore) { if (!tombstone(out, comma, LORE)) return false; comma = true; }
		if (shulker(id) && !container) { if (!tombstone(out, comma, CONTAINER)) return false; comma = true; }
		if (pass != 0 && !customName) { if (!tombstone(out, comma, CUSTOM_NAME)) return false; comma = true; }
		if (pass != 0 && !itemName && !tombstone(out, comma, ITEM_NAME)) return false;
		return out.text("}}");
	}

	private static boolean tombstone(Output out, boolean comma, String name) {
		return (!comma || out.unit(',')) && out.removal(name) && out.text(":{}");
	}

	/** OLD bytes and complete syntax/depth are checked without allocating parser scratch. */
	static String reproject(String ownedJson, String id, int cap) {
		cap(cap);
		if (ownedJson == null || ownedJson.length() > InventoryLimits.MAX_DISPLAY_BYTES
			|| id == null || id.length() > 256 || utf8(id) > 256) return null;
		long bytes = utf8(ownedJson);
		if (bytes > InventoryLimits.MAX_DISPLAY_BYTES) return null;
		int end = scan(ownedJson, white(ownedJson, 0), 0);
		if (end < 0 || white(ownedJson, end) != ownedJson.length()) return null;
		Spans spans = new Spans(ownedJson, (int) bytes);
		if (!spans.read(id)) return null;
		// Tightening needs at most OLD bytes; the fixed minimum also accommodates
		// mandatory markers on a small, otherwise valid envelope.
		Output out = new Output(Math.min(cap, Math.max(1024, (int) bytes + 128)));
		for (int pass = 0; pass < 3; pass++) {
			out.reset();
			if (spans.emit(out, id, pass)) return out.result();
		}
		return null;
	}

	private static void cap(int cap) {
		if (cap < 1024 || cap > InventoryLimits.MAX_DISPLAY_BYTES) throw new IllegalArgumentException("inventory display cap");
	}

	private static boolean resource(String key, int start) {
		if (key.length() == start) return false;
		int colon = key.indexOf(':', start);
		if (colon == start || colon == key.length() - 1) return false;
		for (int i = start; i < key.length(); i++) {
			char c = key.charAt(i);
			if (c == ':' && i == colon) continue;
			if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '.' && !(c == '/' && i > colon)) return false;
		}
		return true;
	}
	private static boolean named(String key, int start, String name) {
		int nameOffset = key.indexOf(':', start) < 0 ? 10 : 0;
		return key.length() - start == name.length() - nameOffset
			&& key.regionMatches(start, name, nameOffset, name.length() - nameOffset);
	}
	private static int compareKeys(String a, String b, InventoryItemBudget budget) {
		work(budget, 3L * (a.length() + b.length() + 10));
		int startA = a.startsWith("!") ? 1 : 0, startB = b.startsWith("!") ? 1 : 0;
		boolean prefixA = a.indexOf(':', startA) < 0, prefixB = b.indexOf(':', startB) < 0;
		int na = a.length() - startA + (prefixA ? 10 : 0), nb = b.length() - startB + (prefixB ? 10 : 0);
		for (int i = 0; i < Math.min(na, nb); i++) {
			char ca = prefixA && i < 10 ? "minecraft:".charAt(i) : a.charAt(startA + i - (prefixA ? 10 : 0));
			char cb = prefixB && i < 10 ? "minecraft:".charAt(i) : b.charAt(startB + i - (prefixB ? 10 : 0));
			if (ca != cb) return ca - cb;
		}
		return na - nb;
	}
	private static void sift(String[] keys, int root, int length, InventoryItemBudget budget) {
		while (root < length / 2) {
			int child = root * 2 + 1;
			if (child + 1 < length && compareKeys(keys[child], keys[child + 1], budget) < 0) child++;
			if (compareKeys(keys[root], keys[child], budget) >= 0) return;
			work(budget, 3);
			String temp = keys[root]; keys[root] = keys[child]; keys[child] = temp; root = child;
		}
	}

	private static boolean known(String key) {
		int offset = key.indexOf(':') < 0 ? 0 : 10;
		if (offset != 0 && !key.startsWith("minecraft:")) return false;
		int low = 0, high = COMPONENTS.length - 1;
		while (low <= high) {
			int middle = (low + high) >>> 1;
			String candidate = COMPONENTS[middle];
			int compared = 0, length = Math.min(key.length() - offset, candidate.length());
			for (int i = 0; i < length && compared == 0; i++) compared = key.charAt(offset + i) - candidate.charAt(i);
			if (compared == 0) compared = key.length() - offset - candidate.length();
			if (compared == 0) return true;
			if (compared < 0) high = middle - 1; else low = middle + 1;
		}
		return false;
	}

	/** Six int arrays, one flag byte array and decoded-key char array; no object per token. */
	private static final class Spans {
		private final String json;
		private final char[] keys;
		private final int[] keyStart, keyLength, valueStart, valueEnd, order, merge;
		private final byte[] flags;
		private int size, used, countStart = -1, countEnd;

		Spans(String json, int bytes) {
			this.json = json;
			keys = new char[bytes];
			int members = bytes / 4 + 1;
			keyStart = new int[members]; keyLength = new int[members];
			valueStart = new int[members]; valueEnd = new int[members]; order = new int[members]; merge = new int[members];
			flags = new byte[members];
		}

		boolean read(String id) {
			int at = white(json, 0), seen = 0;
			if (at >= json.length() || json.charAt(at++) != '{') return false;
			at = white(json, at);
			while (at < json.length() && json.charAt(at) != '}') {
				int key = at, afterKey = string(json, key);
				if (afterKey < 0) return false;
				at = white(json, afterKey);
				if (at >= json.length() || json.charAt(at++) != ':') return false;
				at = white(json, at);
				int after = scan(json, at, 1), bit;
				if (after < 0) return false;
				if (equal(json, key, "id")) { bit = 1; if (json.charAt(at) != '"' || !equal(json, at, id)) return false; }
				else if (equal(json, key, "components")) { bit = 2; if (!components(at)) return false; }
				else if (equal(json, key, "count")) {
					bit = 4;
					if (after - at < 1 || after - at > 2 || json.charAt(at) < '1' || json.charAt(at) > '9') return false;
					if (after - at == 2 && !digit(json.charAt(at + 1))) return false;
					countStart = at; countEnd = after;
				} else return false;
				if ((seen & bit) != 0) return false;
				seen |= bit;
				at = white(json, after);
				if (at >= json.length()) return false;
				if (json.charAt(at) == '}') break;
				if (json.charAt(at++) != ',') return false;
				at = white(json, at);
			}
			if ((seen & 1) == 0 || at >= json.length() || json.charAt(at) != '}') return false;
			// Merge passes bound compared characters by the total decoded key
			// length per pass: each comparison consumes one of its two entries.
			// Include virtual minecraft: prefixes in that independently charged sum.
			for (int width = 1; width < size; width *= 2) {
				for (int start = 0; start < size; start += 2 * width) {
					int middle = Math.min(start + width, size), endIndex = Math.min(start + 2 * width, size);
					int left = start, right = middle;
					for (int target = start; target < endIndex; target++) {
						merge[target] = right == endIndex || (left < middle && compare(order[left], order[right]) <= 0)
							? order[left++] : order[right++];
					}
				}
				System.arraycopy(merge, 0, order, 0, size);
			}
			for (int i = 1; i < size; i++) if (compare(order[i - 1], order[i]) == 0) return false;
			return true;
		}

		private boolean components(int at) {
			if (json.charAt(at) != '{') return false;
			at = white(json, at + 1);
			while (at < json.length() && json.charAt(at) != '}') {
				if (size == keyStart.length) return false;
				int end = string(json, at), start = used;
				if (end < 0) return false;
				for (int i = at + 1; i < end - 1;) {
					char c = json.charAt(i++);
					if (c == '\\') {
						c = json.charAt(i++);
						if (c == 'u') { c = unicode(json, i); i += 4; }
						else c = unescape(c);
					}
					keys[used++] = c;
				}
				boolean removal = used > start && keys[start] == '!';
				keyStart[size] = start + (removal ? 1 : 0); keyLength[size] = used - keyStart[size];
				flags[size] = (byte) (removal ? 1 : 0);
				if (!keyResource(size) || (!removal && !knownKey(size))) return false;
				if (colon(size) < 0) keyLength[size] = -keyLength[size];
				at = white(json, end);
				if (at >= json.length() || json.charAt(at++) != ':') return false;
				at = white(json, at);
				valueStart[size] = at; valueEnd[size] = scan(json, at, 2); if (valueEnd[size] < 0) return false; order[size] = size;
				if (removal && (json.charAt(at) != '{' || json.charAt(white(json, at + 1)) != '}')) return false;
				at = white(json, valueEnd[size++]);
				if (at >= json.length()) return false;
				if (json.charAt(at) == '}') break;
				if (json.charAt(at++) != ',') return false;
				at = white(json, at);
			}
			return at < json.length() && json.charAt(at) == '}';
		}

		private int colon(int entry) {
			for (int i = 0; i < length(entry); i++) if (keys[keyStart[entry] + i] == ':') return i;
			return -1;
		}
		private int length(int entry) { return Math.abs(keyLength[entry]); }
		private boolean keyResource(int entry) {
			int length = keyLength[entry], colon = colon(entry);
			if (length == 0 || colon == 0 || colon == length - 1) return false;
			for (int i = 0; i < length; i++) {
				char c = keys[keyStart[entry] + i];
				if (c == ':' && i == colon) continue;
				if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '.' && !(c == '/' && i > colon)) return false;
			}
			return true;
		}
		private boolean knownKey(int entry) {
			int colon = colon(entry), offset = colon < 0 ? 0 : 10;
			if (colon >= 0 && (colon != 9 || !prefix(entry, "minecraft:"))) return false;
			int low = 0, high = COMPONENTS.length - 1;
			while (low <= high) {
				int middle = (low + high) >>> 1;
				int compared = compareLiteral(entry, offset, COMPONENTS[middle]);
				if (compared == 0) return true;
				if (compared < 0) high = middle - 1; else low = middle + 1;
			}
			return false;
		}
		private boolean prefix(int entry, String prefix) {
			if (length(entry) < prefix.length()) return false;
			for (int i = 0; i < prefix.length(); i++) if (keys[keyStart[entry] + i] != prefix.charAt(i)) return false;
			return true;
		}
		private int compareLiteral(int entry, int offset, String literal) {
			int length = length(entry) - offset, n = Math.min(length, literal.length());
			for (int i = 0; i < n; i++) { int c = keys[keyStart[entry] + offset + i] - literal.charAt(i); if (c != 0) return c; }
			return length - literal.length();
		}
		private boolean named(int entry, String name) {
			int offset = keyLength[entry] < 0 ? 10 : 0;
			int length = name.length() - offset;
			if (length(entry) != length) return false;
			for (int i = 0; i < length; i++) if (keys[keyStart[entry] + i] != name.charAt(i + offset)) return false;
			return true;
		}
		private int compare(int a, int b) {
			boolean prefixA = keyLength[a] < 0, prefixB = keyLength[b] < 0;
			int na = length(a) + (prefixA ? 10 : 0), nb = length(b) + (prefixB ? 10 : 0);
			for (int i = 0; i < Math.min(na, nb); i++) {
				char ca = prefixA && i < 10 ? "minecraft:".charAt(i) : keys[keyStart[a] + i - (prefixA ? 10 : 0)];
				char cb = prefixB && i < 10 ? "minecraft:".charAt(i) : keys[keyStart[b] + i - (prefixB ? 10 : 0)];
				if (ca != cb) return ca - cb;
			}
			return na - nb;
		}

		boolean emit(Output out, String id, int pass) {
			if (!out.text("{\"id\":") || !out.quote(id)) return false;
			if (countStart >= 0 && (!out.text(",\"count\":") || !out.span(json, countStart, countEnd))) return false;
			if (!out.text(",\"components\":{")) return false;
			boolean comma = false, lore = false, container = false, customName = false, itemName = false;
			for (int i = 0; i < size; i++) {
				boolean isLore = named(i, LORE), isContainer = named(i, CONTAINER);
				boolean isCustom = named(i, CUSTOM_NAME), isItem = named(i, ITEM_NAME);
				lore |= isLore; container |= isContainer; customName |= isCustom; itemName |= isItem;
				boolean removed = flags[i] != 0;
				boolean omit = removed || isLore || (isContainer && shulker(id)) || pass == 2 || (pass == 1 && (isCustom || isItem));
				if (comma && !out.unit(',')) return false;
				if (omit) {
					if (!out.text("\"!") || !out.quotedUnits(keys, keyStart[i], length(i)) || !out.text("\":{}")) return false;
				} else {
					if (!out.unit('"') || !out.quotedUnits(keys, keyStart[i], length(i)) || !out.text("\":")
						|| !out.span(json, valueStart[i], valueEnd[i])) return false;
				}
				comma = true;
			}
			if (!lore) { if (!tombstone(out, comma, LORE)) return false; comma = true; }
			if (shulker(id) && !container) { if (!tombstone(out, comma, CONTAINER)) return false; comma = true; }
			if (pass != 0 && !customName) { if (!tombstone(out, comma, CUSTOM_NAME)) return false; comma = true; }
			if (pass != 0 && !itemName && !tombstone(out, comma, ITEM_NAME)) return false;
			return out.text("}}");
		}
	}

	/** Reused across all three attempts; UTF-8 counts exactly String.getBytes(UTF_8). */
	private static final class Output {
		private final InventoryItemBudget budget;
		private final char[] buffer;
		private final char[] digits;
		private int length, bytes;
		private boolean high;
		Output(int cap) { this(cap, null); }
		Output(int cap, InventoryItemBudget budget) {
			this.budget = budget; allocate(budget, 12, cap + 20L, 3, 3);
			buffer = new char[cap]; digits = new char[20];
		}
		void reset() { length = 0; bytes = 0; high = false; }
		boolean unit(char c) {
			work(budget, 1);
			int extra = c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isLowSurrogate(c) && high ? 3 : Character.isSurrogate(c) ? 1 : 3;
			if (length == buffer.length || bytes + extra > buffer.length) return false;
			buffer[length++] = c; bytes += extra; high = Character.isHighSurrogate(c);
			return true;
		}
		boolean text(String text) { return span(text, 0, text.length()); }
		boolean span(String text, int start, int end) { for (int i = start; i < end; i++) if (!unit(text.charAt(i))) return false; return true; }
		boolean quote(String value) {
			if (!unit('"')) return false;
			for (int i = 0; i < value.length(); i++) { work(budget, 1); if (!quoted(value.charAt(i))) return false; }
			return unit('"');
		}
		boolean removal(String value) { return removal(value, 0); }
		boolean removal(String value, int start) {
			if (!text("\"!")) return false;
			for (int i = start; i < value.length(); i++) { work(budget, 1); if (!quoted(value.charAt(i))) return false; }
			return unit('"');
		}
		boolean quotedUnits(char[] value, int start, int length) {
			for (int i = start; i < start + length; i++) if (!quoted(value[i])) return false;
			return true;
		}
		private boolean quoted(char c) {
			return switch (c) {
				case '"' -> text("\\\""); case '\\' -> text("\\\\"); case '\b' -> text("\\b"); case '\f' -> text("\\f");
				case '\n' -> text("\\n"); case '\r' -> text("\\r"); case '\t' -> text("\\t");
				default -> {
					if (c < 0x20 || c == '\u2028' || c == '\u2029') yield text("\\u")
						&& unit("0123456789abcdef".charAt(c >>> 12)) && unit("0123456789abcdef".charAt((c >>> 8) & 15))
						&& unit("0123456789abcdef".charAt((c >>> 4) & 15)) && unit("0123456789abcdef".charAt(c & 15));
					yield unit(c);
				}
			};
		}
		private boolean integer(long number) {
			boolean negative = number < 0;
			if (!negative) number = -number;
			int cursor = digits.length;
			do { work(budget, 4); digits[--cursor] = (char) ('0' - number % 10); number /= 10; } while (number != 0);
			if (negative && !unit('-')) return false;
			for (; cursor < digits.length; cursor++) if (!unit(digits[cursor])) return false;
			return true;
		}
		boolean value(JsonElement value, int depth) {
			work(budget, 1);
			if (value == null || value.isJsonNull()) return text("null");
			if (depth > DEPTH) return false;
			if (value.isJsonPrimitive()) {
				JsonPrimitive primitive = value.getAsJsonPrimitive();
				if (primitive.isString()) return quote(primitive.getAsString());
				if (primitive.isBoolean()) return text(primitive.getAsBoolean() ? "true" : "false");
				Number number = primitive.getAsNumber();
				if (number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long) return integer(number.longValue());
				if (number instanceof Float f) { decimal(); return Float.isFinite(f) && text(Float.toString(f)); }
				if (number instanceof Double d) { decimal(); return Double.isFinite(d) && text(Double.toString(d)); }
				if (number instanceof LazilyParsedNumber) { String token = number.toString(); return number(token, 0) == token.length() && text(token); }
				return false;
			}
			if (value.isJsonArray()) {
				if (depth == DEPTH) return false;
				if (!unit('[')) return false;
				var array = value.getAsJsonArray();
				for (int i = 0; i < array.size(); i++) if ((i != 0 && !unit(',')) || !value(array.get(i), depth + 1)) return false;
				return unit(']');
			}
			if (depth == DEPTH || !unit('{')) return false;
			allocate(budget, 4, 0, 3, 2); // Gson entrySet view + iterator, each attempt
			boolean comma = false;
			for (var member : value.getAsJsonObject().entrySet()) {
				work(budget, 32);
				if ((comma && !unit(',')) || !quote(member.getKey()) || !unit(':') || !value(member.getValue(), depth + 1)) return false;
				comma = true;
			}
			return unit('}');
		}
		private void decimal() {
			if (budget == null) return;
			allocate(budget, 92, 0, 2, 4); work(budget, 13523);
		}
		String result() {
			allocate(budget, 3L * length + 16, 0, 1, 3); work(budget, 2L * length);
			return new String(buffer, 0, length);
		}
	}

	private static void work(InventoryItemBudget budget, long units) { if (budget != null) budget.work(units); }
	private static void allocate(InventoryItemBudget budget, long bytes, long chars, long references, long objects) {
		if (budget != null) budget.allocate(bytes, chars, references, objects);
	}

	// Strict JSON syntax only. Nested values are opaque: no nested item/FLAT interpretation.
	// JSON's escaped UTF-16 units may be isolated, as Gson/Java permit; raw isolated
	// units use the existing one-byte wire replacement. A pair is counted as four bytes.
	private static int scan(String json, int at, int depth) {
		if (at >= json.length() || depth > DEPTH) return -1;
		char c = json.charAt(at);
		if (c == '"') return string(json, at);
		if (c == '{' || c == '[') {
			if (depth == DEPTH) return -1;
			boolean object = c == '{'; char close = object ? '}' : ']';
			at = white(json, at + 1);
			if (at < json.length() && json.charAt(at) == close) return at + 1;
			while (at < json.length()) {
				if (object) {
					at = string(json, at); if (at < 0) return -1;
					at = white(json, at); if (at == json.length() || json.charAt(at++) != ':') return -1;
					at = white(json, at);
				}
				at = scan(json, at, depth + 1); if (at < 0) return -1;
				at = white(json, at); if (at == json.length()) return -1;
				c = json.charAt(at++); if (c == close) return at;
				if (c != ',') return -1;
				at = white(json, at);
			}
			return -1;
		}
		if (c == 't') return json.startsWith("true", at) ? at + 4 : -1;
		if (c == 'f') return json.startsWith("false", at) ? at + 5 : -1;
		if (c == 'n') return json.startsWith("null", at) ? at + 4 : -1;
		return number(json, at);
	}
	private static int number(String json, int at) {
		int n = json.length();
		if (at < n && json.charAt(at) == '-') at++;
		if (at == n) return -1;
		if (json.charAt(at) == '0') at++;
		else {
			if (json.charAt(at) < '1' || json.charAt(at) > '9') return -1;
			do { at++; } while (at < n && digit(json.charAt(at)));
		}
		if (at < n && json.charAt(at) == '.') { int start = ++at; while (at < n && digit(json.charAt(at))) at++; if (at == start) return -1; }
		if (at < n && (json.charAt(at) == 'e' || json.charAt(at) == 'E')) {
			at++; if (at < n && (json.charAt(at) == '+' || json.charAt(at) == '-')) at++;
			int start = at; while (at < n && digit(json.charAt(at))) at++; if (at == start) return -1;
		}
		return at;
	}
	private static boolean digit(char c) { return c >= '0' && c <= '9'; }
	private static int string(String json, int at) {
		if (at >= json.length() || json.charAt(at++) != '"') return -1;
		while (at < json.length()) {
			char c = json.charAt(at++);
			if (c == '"') return at;
			if (c < 0x20) return -1;
			if (c == '\\') {
				if (at == json.length()) return -1;
				c = json.charAt(at++);
				if (c == 'u') {
					if (at + 4 > json.length()) return -1;
					for (int i = 0; i < 4; i++) if (hex(json.charAt(at++)) < 0) return -1;
				} else if (c != '"' && c != '\\' && c != '/' && c != 'b' && c != 'f' && c != 'n' && c != 'r' && c != 't') return -1;
			}
		}
		return -1;
	}
	private static int white(String json, int at) {
		while (at < json.length()) { char c = json.charAt(at); if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break; at++; }
		return at;
	}
	private static int hex(char c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1; }
	private static char unicode(String json, int at) { return (char) ((hex(json.charAt(at)) << 12) | (hex(json.charAt(at + 1)) << 8) | (hex(json.charAt(at + 2)) << 4) | hex(json.charAt(at + 3))); }
	private static char unescape(char c) { return switch (c) { case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> c; }; }
	private static boolean equal(String json, int at, String expected) {
		int index = 0;
		for (int i = at + 1; i < json.length();) {
			char c = json.charAt(i++);
			if (c == '"') return index == expected.length();
			if (c == '\\') { c = json.charAt(i++); if (c == 'u') { c = unicode(json, i); i += 4; } else c = unescape(c); }
			if (index == expected.length() || c != expected.charAt(index++)) return false;
		}
		return false;
	}
}
