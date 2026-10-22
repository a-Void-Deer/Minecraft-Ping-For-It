package nx.pingwheel.common.presentation.inventory.client;

import java.io.IOException;
import java.io.StringReader;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.UnsafeDisplay;
import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.Validation;

/** Parser-specific admission before native code allocates a recursive tree. */
final class ClientDisplaySyntax {

	private ClientDisplaySyntax() {}

	static void json(String text, int depth, Validation budget) {
		// JsonReader's fixed buffer and all decoded names/strings (including
		// temporary growing builders) are bounded by this input length. Reserve
		// before peek/nextName/nextString, not after they allocate their tokens.
		budget.characters(text);
		budget.characterUnits(4L * text.length() + 1024);
		budget.visit(depth);
		try (JsonReader reader = new JsonReader(new StringReader(text))) {
			reader.setLenient(true); // Exactly JsonParser.parseReader's tokenization.
			int nesting = depth;
			boolean root = false;
			while (true) {
				budget.visit(nesting);
				JsonToken token = reader.peek();
				if (token == JsonToken.END_DOCUMENT) {
					if (!root || nesting != depth) throw new UnsafeDisplay();
					return;
				}
				if (root && nesting == depth) throw new UnsafeDisplay();
				switch (token) {
					case BEGIN_ARRAY -> { budget.visit(nesting + 1); reader.beginArray(); nesting++; root = true; }
					case BEGIN_OBJECT -> { budget.visit(nesting + 1); reader.beginObject(); nesting++; root = true; }
					case END_ARRAY -> { reader.endArray(); nesting--; }
					case END_OBJECT -> { reader.endObject(); nesting--; }
					case NAME -> reader.nextName();
					case STRING, NUMBER -> { reader.nextString(); root = true; }
					case BOOLEAN -> { reader.nextBoolean(); root = true; }
					case NULL -> { reader.nextNull(); root = true; }
					default -> throw new UnsafeDisplay();
				}
			}
		} catch (IOException malformed) { throw new UnsafeDisplay(); }
	}

	/**
	 * TagParser/Brigadier use only quote-or-backslash escapes, no comments, and
	 * exclude either quote from unquoted tokens. NbtPathArgument likewise ends
	 * unquoted names at either quote. Thus quoted spans here match every span
	 * that can precede an embedded recursive SNBT parse; malformed input remains
	 * the native parser's concern. No JSON tokenization is applied to SNBT/paths.
	 */
	static void nbt(String text, int depth, Validation budget) {
		budget.characters(text);
		// Covers path nodes, SNBT nodes, lexemes, parser workspace and the scan,
		// before either the scan or native path recompilation can allocate.
		budget.cells(text.length());
		budget.characterUnits(2L * text.length());
		structure(text, 0, depth, false, budget);
	}

	/**
	 * EntitySelectorParser reads a name/UUID unless the first character is @,
	 * and parses options only at the '[' immediately after the selector type.
	 * Inside options, Brigadier readString/readUnquotedString, resource names,
	 * numeric ranges, score/advancement maps and TagParser all exclude quotes
	 * from unquoted lexemes. No comments or JSON escapes are recognized. Thus
	 * no native parse can reach recursive SNBT hidden by a non-native quoted
	 * span here: it either sees the same span or fails before that SNBT.
	 * Count actual unquoted structures conservatively, not selector punctuation
	 * as an SNBT grammar. A literal score name is not a selector or SNBT input.
	 */
	static void selector(String text, int depth, Validation budget) {
		budget.characters(text);
		if (text.length() < 3 || text.charAt(0) != '@' || text.charAt(2) != '[') return;
		budget.cells(text.length());
		budget.characterUnits(2L * text.length());
		structure(text, 2, depth, true, budget);
	}

	private static void structure(String text, int start, int depth, boolean selector, Validation budget) {
		char quote = 0; boolean escape = false; int nesting = depth;
		for (int i = start; i < text.length(); i++) {
			char c = text.charAt(i);
			if (quote != 0) {
				if (escape) {
					if (c != quote && c != '\\') throw new UnsafeDisplay();
					escape = false;
				} else if (c == '\\') escape = true;
				else if (c == quote) quote = 0;
			} else if (com.mojang.brigadier.StringReader.isQuotedStringStart(c)) quote = c;
			else if (c == '{' || c == '[') budget.visit(++nesting);
			else if (c == '}' || c == ']') {
				if (nesting <= depth) throw new UnsafeDisplay();
				nesting--;
				if (selector && nesting == depth) return; // Native selector parse ignores the trailing suffix.
			}
		}
		if (quote != 0 || nesting != depth) throw new UnsafeDisplay();
	}
}
