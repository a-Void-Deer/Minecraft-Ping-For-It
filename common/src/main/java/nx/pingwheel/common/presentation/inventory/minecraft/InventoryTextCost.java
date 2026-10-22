package nx.pingwheel.common.presentation.inventory.minecraft;

import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.*;
import net.minecraft.resources.ResourceLocation;

/** E5 shape expansion for one FLAT occurrence, before the original text codec. */
final class InventoryTextCost {
	private final InventoryItemBudget budget;
	private final InventoryResidentAccess raw;
	private final InventoryEncodingCost cost;
	private long length, strings, quoted, values, fields, elements, objects, arrays, writes, decimals, integers;
	private boolean utf16Source;
	private int maximumDepth;

	InventoryTextCost(InventoryItemBudget budget, InventoryResidentAccess raw, InventoryEncodingCost cost) {
		this.budget = budget; this.raw = raw; this.cost = cost;
	}
	void flat(Component value) {
		boolean target = cost.output(true); // FLAT always constructs an inner JSON tree
		length = strings = quoted = values = fields = elements = objects = arrays = writes = decimals = integers = 0;
		utf16Source = false;
		maximumDepth = 0;
		component(value, 0);
		// E5 fixed writer, backing growth + possible coder inflation + final copy.
		// L bounds every write-end; all discarded arrays remain cumulative.
		int growth = 1; long capacity = 16;
		while (capacity < length) { capacity = InventoryEncodingCost.add(2 * capacity, 2); growth++; }
		// UTF16 final String may try a discarded latin1 compression array first.
		// Buffer<=12L+96, final attempted L plus retained2L are cumulative.
		cost.v(48 + 15 * length + 96, 0, 11, 4 + growth + 3);
		// One document cell plus maximumDepth+1 open containers.
		long stack = 32, stackSum = 32, stackArrays = 1;
		while (stack < maximumDepth + 2L) { stack *= 2; stackSum += stack; stackArrays++; }
		cost.v(4 * stackSum, 0, 0, stackArrays);
		// E5 dispatch. Valid-index charAt's selected scalar graph<=96 on21/25
		// (including assertions); whole-body tokens193 include mutually exclusive
		// exception constructions that the index invariant excludes. Not append
		// setup per char. See the named valid-branch census in the event matrix.
		cost.w(234 * (values + fields + elements + objects + arrays) + 49 * objects);
		cost.w((107 + 96 + 8) * strings + (76 + 8) * quoted);
		writerControl();
		// Selected acyclic append helper graph on21/25 (both untaken branches
		// included) plus copies/inflation. Each unescaped String is3 writes.
		// Inspected append fixed graph989/1283 tokens on21/25; at most two
		// occurrences of each helper plus range/Arrays wrappers fits4096/write.
		// Unicode enables appendChars scans/putCharsSB even on later latin1
		// sources (whole body239 + charAt96 + helper/control<=640 per unit).
		// ASCII bulk/copy bound128L; Unicode additionally pays inflation and
		// conservative final compression loops256L, separately from I.
		cost.w(4096 * writes + (utf16Source ? 256 : 128) * length
			+ (utf16Source ? 640 * strings : 0) + 4 * stackSum + 1024);
		cost.v(56 * integers, 0, integers, 2 * integers);
		// E5 integer expression: ALL selected int/long/coder bodies10288,
		// whole-body*19 covers width tests and digit-loop final tests.
		cost.w(200000 * integers);
		for (long index = 0; index < decimals; index++) cost.decimal();
		// Written-page FLAT can fail its string-length check AFTER inner writing.
		// The local empty-partial Error/supplier and every ancestor builder's
		// error-or-success apply2 row are paid. No message supplier is evaluated.
		cost.output(target);
		cost.flatResult();
	}
	private void writerControl() {
		long containers = objects + arrays;
		// Gson2.10.1 JsonWriter named method-body tokens; newline's indent-null
		// branch<=8. One beforeValue/value and one beforeName/field. Peek and
		// replaceTop each<=4*(N+E+C+1); push C+1 includes constructor root.
		cost.w(13 + (31 + 10 + 19 + 74) * containers + 33 * (containers + 1)
			+ 47 * fields + 22 * values + 49 * fields + 91 * values
			+ (24 + 9) * 4 * (values + fields + containers + 1)
			+ 8 * (values + fields + containers) + 137 * values);
		// Exact Gson scalar accessors/type checks, trusted-number predicate and
		// constant-length nonfinite comparisons<=256/value (formatters separate).
		// Iterator constructors, hasNext/next/checkForComodification<=128/edge.
		cost.w(256 * values + 128 * (fields + elements + containers + 1) + 1024);
	}
	private void component(Component component, int depth) {
		budget.work(32);
		var contents = component.getContents(); var style = component.getStyle(); var siblings = component.getSiblings();
		boolean literal = contents == PlainTextContents.EMPTY || contents.getClass() == PlainTextContents.LiteralContents.class;
		if (literal && style.isEmpty() && siblings.isEmpty()) {
			cost.either(); cost.either(); cost.xmap();
			String text = contents == PlainTextContents.EMPTY ? "" : ((PlainTextContents.LiteralContents) contents).text();
			stringValue(text); return;
		}
		cost.either(); cost.xmap(); cost.graph(3); cost.emptyListEquality(); cost.defaults(1, siblings.isEmpty() ? 0 : 1);
		int n = style(style, depth + 1);
		if (!siblings.isEmpty()) {
			key("extra"); array(siblings.size(), depth + 1); cost.checked(); cost.list(siblings.size(), 0);
			cost.planningList(siblings.size()); for (int index = 0; index < siblings.size(); index++) component(siblings.get(index), depth + 2); n++;
		}
		if (literal) {
			cost.graph(1); key("text"); stringValue(contents == PlainTextContents.EMPTY ? "" : ((PlainTextContents.LiteralContents) contents).text()); n++;
		} else if (contents instanceof TranslatableContents trans) {
			cost.graph(3); key("translate"); stringValue(trans.getKey()); n++;
			cost.optional(trans.getFallback() != null); cost.optionalFields(2);
			if (trans.getFallback() != null) { key("fallback"); stringValue(trans.getFallback()); n++; }
			Object[] args = trans.getArgs();
			cost.optional(args.length != 0);
			if (args.length != 0) {
				cost.v(0, 0, 1, 1); cost.w(8); // Arrays.asList view
				key("with"); array(args.length, depth + 1); cost.list(args.length, 0); n++;
				for (Object arg : args) {
					cost.either(); cost.xmap();
					if (arg instanceof Component c) component(c, depth + 2);
					else {
						cost.javaPrimitive(arg);
						if (arg instanceof String string) { quote(string); values++; }
						else if (arg instanceof Boolean) scalar(5);
						else numeric(arg);
					}
				}
			}
		} else if (contents instanceof KeybindContents keybind) {
			cost.graph(1); key("keybind"); stringValue(keybind.getName()); n++;
		} else if (contents instanceof ScoreContents score) {
			cost.graph(1); cost.graph(2); key("score"); key("name"); stringValue(score.getName()); key("objective"); stringValue(score.getObjective()); object(2, depth + 1); n++;
		} else if (contents instanceof SelectorContents selector) {
			cost.graph(2); cost.optionalFields(1); key("selector"); stringValue(selector.getPattern()); n++;
			if (selector.getSeparator().isPresent()) { key("separator"); component(selector.getSeparator().orElseThrow(), depth + 1); n++; }
		} else if (contents instanceof NbtContents nbt) {
			cost.graph(4); cost.defaults(1, nbt.isInterpreting() ? 1 : 0); cost.optionalFields(1);
			key("nbt"); stringValue(nbt.getNbtPath()); n++;
			if (nbt.isInterpreting()) { key("interpret"); cost.booleanLeaf(); scalar(5); n++; }
			if (nbt.getSeparator().isPresent()) { key("separator"); component(nbt.getSeparator().orElseThrow(), depth + 1); n++; }
			cost.graph(1);
			if (nbt.getDataSource() instanceof BlockDataSource block) { key("block"); stringValue(block.posPattern()); }
			else if (nbt.getDataSource() instanceof EntityDataSource entity) { key("entity"); stringValue(entity.selectorPattern()); }
			else { key("storage"); resource(((StorageDataSource) nbt.getDataSource()).id()); }
			n++;
		}
		object(n, depth);
	}
	private int style(Style style, int depth) {
		cost.graph(10); cost.optionalFields(10);
		int flags = raw.styleFlags(style);
		// Applicative.ap10's outer map.encode re-evaluates the FIRST FIVE
		// getters: color,bold,italic,underlined,strikethrough. Obfuscated is6th.
		cost.repeatedOptionals(Integer.bitCount(flags & 15) + (style.getColor() == null ? 0 : 1));
		int n = Integer.bitCount(flags);
		// All five native nullable fields preserve false as present.
		for (int index = 0; index < n; index++) { cost.optional(true); cost.booleanLeaf(); scalar(5); }
		if ((flags & 1) != 0) key("bold");
		if ((flags & 2) != 0) key("italic");
		if ((flags & 4) != 0) key("underlined");
		if ((flags & 8) != 0) key("strikethrough");
		if ((flags & 16) != 0) key("obfuscated");
		if (style.getColor() != null) {
			cost.optional(true); key("color"); cost.xmap();
			String name = raw.colorName(style.getColor());
			if (name == null) { cost.v(37, 7, 1, 4); cost.w(96); cost.stringLeaf(""); generatedString(7); }
			else stringValue(name);
			n++;
		}
		if (style.getInsertion() != null) { cost.optional(true); key("insertion"); stringValue(style.getInsertion()); n++; }
		ResourceLocation font = raw.styleFont(style);
		if (font != null) { cost.optional(true); key("font"); resource(font); n++; }
		if (style.getClickEvent() != null) {
			cost.optional(true); key("clickEvent"); var click = style.getClickEvent(); cost.graph(2);
			// OPEN_FILE may fail on dedicated servers (Neo's integrated exception
			// may instead succeed). Both paths are paid, never call a server probe.
			cost.mapValidation();
			key("action"); enumValue(click.getAction().getSerializedName()); key("value"); stringValue(click.getValue()); object(2, depth); n++;
		}
		if (style.getHoverEvent() != null) {
			cost.optional(true); key("hoverEvent"); var hover = style.getHoverEvent(); cost.either(); cost.xmap();
			// KeyDispatch type/result/encoder/flatMap wrappers, action validation.
			// getCodec: type Success, flatMap capture, encoder Success,map
			// replacement, final map replacement; builder.withErrorsFrom success
			//6R/4O; result Optional; type-again/flatMap action continuation.
			// Action.validate + enum resolver are separate at enumValue below.
			cost.keyDispatch();
			key("action"); cost.checked(); enumValue(hover.getAction() == HoverEvent.Action.SHOW_TEXT ? "show_text" : "show_entity"); key("contents");
			if (hover.getAction() == HoverEvent.Action.SHOW_TEXT) component(hover.getValue(HoverEvent.Action.SHOW_TEXT), depth + 1);
			else {
				var entity = hover.getValue(HoverEvent.Action.SHOW_ENTITY); cost.graph(3); cost.optionalFields(1);
				budget.work(InventoryRegistryContext.valueWork(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE, raw));
				cost.w(InventoryRegistryContext.valueWork(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE, raw)
					+ InventoryRegistryContext.registrationWork(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE, raw));
				// EntityType.CODEC is the built-in byName codec, not a bare resource.
				cost.checked(); cost.v(0, 0, 4, 3); cost.w(48);
				key("type"); resource(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.type));
				key("id"); cost.either(); cost.intStream(4); array(4, depth + 2);
				for (int index = 0; index < 4; index++) { integers++; scalar(20); }
				if (entity.name.isPresent()) { key("name"); component(entity.name.orElseThrow(), depth + 2); }
				object(entity.name.isPresent() ? 3 : 2, depth + 1);
			}
			object(2, depth); n++;
		}
		return n;
	}
	private void key(String key) { cost.key(); quote(key); fields++; }
	private void stringValue(String value) { cost.stringLeaf(value); quote(value); values++; }
	private void enumValue(String value) { cost.enumLeaf(value); quote(value); values++; }
	private void resource(ResourceLocation id) {
		cost.resource(id); generatedString(id.getNamespace().length() + id.getPath().length() + 1);
	}
	private void generatedString(int count) { strings += count; quoted++; length += count + 2L; writes += 3; values++; }
	private void quote(String string) {
		// This planning scan is another owned charAt pass, not credit against
		// the later native JsonWriter scan. Closed valid charAt96+local32.
		budget.work(128L * (string.length() + 1));
		long escaped = string.length(), runs = 1; boolean utf16 = false;
		for (int index = 0; index < string.length(); index++) {
			char c = string.charAt(index);
			if (c > 255) utf16 = true;
			if (c == '"' || c == '\\' || c == '\b' || c == '\f' || c == '\n' || c == '\r' || c == '\t') { escaped++; runs += 2; }
			else if (c < 32 || c == '\u2028' || c == '\u2029') { escaped += 5; runs += 2; }
		}
		strings += string.length(); quoted++; length += escaped + 2; writes += runs + 2;
		if (utf16) utf16Source = true;
	}
	private void numeric(Object number) {
		if (number instanceof Float || number instanceof Double) { decimals++; scalar(24); }
		else { integers++; scalar(20); }
	}
	private void scalar(int width) { values++; length += width; writes++; }
	private void array(int n, int depth) {
		arrays++; values++; elements += n; length += 2L + Math.max(0, n - 1); writes += 2L + Math.max(0, n - 1);
		maximumDepth = Math.max(maximumDepth, depth); cost.v(12, 0, 1, 1);
	}
	private void object(int n, int depth) {
		objects++; values++; length += 2L + n + Math.max(0, n - 1); writes += 2L + n + Math.max(0, n - 1);
		maximumDepth = Math.max(maximumDepth, depth); cost.map(n);
		cost.v(24, 0, 2L * n + 7, n == 0 ? 6 : 7);
		int log = 1; while ((1 << log) < Math.max(2, n)) log++;
		cost.w(69 + 227 + 72 + 41 + 6 + 88L * (n + 1)
			+ 164L * (n + 1) + 187L * (n + 1) * (log + 1)
			+ 64L * Math.max(0, n - 1) * (log + 1) + InventoryEncodingCost.pairs(n) + n);
	}
}
