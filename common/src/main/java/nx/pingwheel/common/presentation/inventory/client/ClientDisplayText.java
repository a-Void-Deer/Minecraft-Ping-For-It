package nx.pingwheel.common.presentation.inventory.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.*;

import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.UnsafeDisplay;
import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay.Validation;

/**
 * Owned native text, without resident translation/visual/tooltip caches. Native
 * formatting supplies a one-level token plan; Ping, not a native visitor, walks
 * every expanded occurrence (including empty ones) against the operation's root.
 */
final class ClientDisplayText {

	private ClientDisplayText() {}

	static OwnedText own(Component source, int depth, Validation budget) {
		Context context = new Context(budget);
		Component result = context.copy(source, depth);
		context.expand(result, depth, null);
		context.sameLanguage();
		return new OwnedText(result, context, depth);
	}

	static Component snapshot(Component source, int depth, Validation budget) {
		return own(source, depth, budget).component();
	}

	/** Already shape-checked graph; never invokes a resident visitor/cache. */
	static void certify(Component source, int depth, Validation budget) {
		Context context = new Context(budget);
		context.expand(source, depth, null);
		context.hoverExpansions(source, depth);
		context.sameLanguage();
	}

	static final class OwnedText {
		private final Component value;
		private final Context context;
		private final int depth;
		private OwnedText(Component value, Context context, int depth) {
			this.value = value; this.context = context; this.depth = depth;
		}
		Component component() { context.sameLanguage(); return value; }
		String plain() {
			context.sameLanguage();
			StringBuilder output = new StringBuilder();
			context.expand(value, depth, output);
			context.sameLanguage();
			context.budget.characterUnits(output.length());
			return output.toString();
		}
	}

	private record Part(String literal, int argument) {
		static Part literal(String text) { return new Part(text, -1); }
		static Part argument(int index) { return new Part(null, index); }
	}

	private static final class Context {
		private final Validation budget;
		private final Language language;
		private final IdentityHashMap<TranslatableContents, List<Part>> plans = new IdentityHashMap<>();
		Context(Validation budget) { this.budget = budget; language = budget.language; }
		void sameLanguage() { if (Language.getInstance() != language) throw new UnsafeDisplay(); }

		Component copy(Component source, int depth) {
			budget.visit(depth);
			if (source == null || source.getClass() != MutableComponent.class) throw new UnsafeDisplay();
			ComponentContents contents = source.getContents();
			ComponentContents owned;
			Class<?> type = contents.getClass();
			if (contents == PlainTextContents.EMPTY) owned = PlainTextContents.EMPTY;
			else if (type == PlainTextContents.LiteralContents.class) {
				String text = ((PlainTextContents.LiteralContents) contents).text(); budget.characters(text);
				owned = new PlainTextContents.LiteralContents(text);
			} else if (type == TranslatableContents.class) {
				TranslatableContents trans = (TranslatableContents) contents;
				budget.characters(trans.getKey());
				if (trans.getFallback() != null) budget.characters(trans.getFallback());
				Object[] args = trans.getArgs(); budget.cells(args.length);
				Object[] cloned = new Object[args.length];
				for (int i = 0; i < args.length; i++) {
					budget.visit(depth);
					cloned[i] = args[i] instanceof Component child ? copy(child, depth + 1) : primitive(args[i]);
				}
				owned = new TranslatableContents(trans.getKey(), trans.getFallback(), cloned);
			} else if (type == SelectorContents.class) {
				SelectorContents selector = (SelectorContents) contents; ClientDisplaySyntax.selector(selector.getPattern(), depth, budget);
				owned = new SelectorContents(selector.getPattern(), separator(selector.getSeparator(), depth));
			} else if (type == ScoreContents.class) {
				ScoreContents score = (ScoreContents) contents; ClientDisplaySyntax.selector(score.getName(), depth, budget); budget.characters(score.getObjective());
				owned = new ScoreContents(score.getName(), score.getObjective());
			} else if (type == NbtContents.class) {
				NbtContents nbt = (NbtContents) contents; budget.characters(nbt.getNbtPath());
				ClientDisplaySyntax.nbt(nbt.getNbtPath(), depth, budget);
				DataSource data = nbt.getDataSource(); DataSource cloned;
				if (data.getClass() == BlockDataSource.class) {
					String pattern = ((BlockDataSource) data).posPattern(); budget.characters(pattern); cloned = new BlockDataSource(pattern);
				} else if (data.getClass() == EntityDataSource.class) {
					String pattern = ((EntityDataSource) data).selectorPattern(); ClientDisplaySyntax.selector(pattern, depth, budget); cloned = new EntityDataSource(pattern);
				} else if (data.getClass() == StorageDataSource.class) {
					var id = ((StorageDataSource) data).id(); budget.characters(id.getNamespace()); budget.characters(id.getPath());
					cloned = new StorageDataSource(id);
				} else throw new UnsafeDisplay();
				owned = new NbtContents(nbt.getNbtPath(), nbt.isInterpreting(), separator(nbt.getSeparator(), depth), cloned);
			} else throw new UnsafeDisplay(); // keybind's mutable external resolver is not an admitted child.
			MutableComponent result = MutableComponent.create(owned);
			for (Object sibling : ClientItemDisplay.admittedList(source.getSiblings(), budget)) {
				if (!(sibling instanceof Component child)) throw new UnsafeDisplay();
				result.append(copy(child, depth + 1));
			}
			result.setStyle(style(source.getStyle(), depth));
			return result;
		}

		private Optional<Component> separator(Optional<Component> separator, int depth) {
			return separator.map(child -> copy(child, depth + 1));
		}

		private Object primitive(Object value) {
			if (value == null) return null;
			Class<?> type = value.getClass();
			if (type == String.class) { budget.characters((String) value); return value; }
			if (type != Boolean.class && type != Byte.class && type != Short.class && type != Integer.class
				&& type != Long.class && type != Float.class && type != Double.class) throw new UnsafeDisplay();
			return value;
		}

		private Style style(Style style, int depth) {
			budget.visit(depth);
			if (style == null || style.getClass() != Style.class) throw new UnsafeDisplay();
			if (style.getInsertion() != null) budget.characters(style.getInsertion());
			budget.characters(style.getFont().getNamespace());
			budget.characters(style.getFont().getPath());
			if (style.getClickEvent() != null) {
				if (style.getClickEvent().getClass() != net.minecraft.network.chat.ClickEvent.class) throw new UnsafeDisplay();
				budget.characters(style.getClickEvent().getValue());
			}
			// Style's native scalar fields are immutable; retaining the exact style
			// preserves nullable flags, font, click and insertion without isBold loss.
			HoverEvent hover = style.getHoverEvent();
			if (hover == null) return style;
			budget.visit(depth);
			if (hover.getClass() != HoverEvent.class) throw new UnsafeDisplay();
			HoverEvent cloned;
			if (hover.getAction() == HoverEvent.Action.SHOW_TEXT) {
				Component text = copy(hover.getValue(HoverEvent.Action.SHOW_TEXT), depth + 1);
				expand(text, depth + 1, null);
				cloned = new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
			} else if (hover.getAction() == HoverEvent.Action.SHOW_ENTITY) {
				var entity = hover.getValue(HoverEvent.Action.SHOW_ENTITY);
				if (entity == null || entity.getClass() != HoverEvent.EntityTooltipInfo.class) throw new UnsafeDisplay();
				Optional<Component> name = entity.name.map(child -> copy(child, depth + 1));
				name.ifPresent(child -> expand(child, depth + 1, null));
				cloned = new HoverEvent(HoverEvent.Action.SHOW_ENTITY, new HoverEvent.EntityTooltipInfo(entity.type, entity.id, name));
			} else if (hover.getAction() == HoverEvent.Action.SHOW_ITEM) {
				cloned = new HoverEvent(HoverEvent.Action.SHOW_ITEM,
					ClientItemDisplay.HoverStack.owned(hover.getValue(HoverEvent.Action.SHOW_ITEM), depth + 1, budget));
			} else throw new UnsafeDisplay();
			// Clear before replacement: native Style compares two nonnull hover
			// values with deep equals, which is not an admitted text traversal.
			return style.withHoverEvent(null).withHoverEvent(cloned);
		}

		void expand(Component component, int depth, StringBuilder output) {
			budget.visit(depth); // Pay even EMPTY, unresolved score/NBT and every repeated argument.
			ComponentContents contents = component.getContents();
			if (contents instanceof PlainTextContents.LiteralContents literal) append(literal.text(), output);
			else if (contents instanceof TranslatableContents trans) {
				List<Part> plan = plans.get(trans);
				if (plan == null) { plan = plan(trans, depth); plans.put(trans, plan); }
				for (Part part : plan) {
					budget.visit(depth);
					if (part.argument < 0) append(part.literal, output);
					else {
						Object arg = trans.getArgs()[part.argument];
						if (arg instanceof Component child) expand(child, depth + 1, output);
						else {
							budget.cells(1); // Fixed-width exact boxed primitive conversion, never arbitrary Number/toString.
							if (arg != null && !(arg instanceof String)) budget.characterUnits(32);
							append(arg == null ? "null" : arg.toString(), output);
						}
					}
				}
			} else if (contents instanceof SelectorContents selector) append(selector.getPattern(), output);
			// Native unresolved score and NBT have no own text. Separators were
			// checked and copied, but only a world resolve would visit them.
			for (Component sibling : component.getSiblings()) expand(sibling, depth + 1, output);
		}

		private void hoverExpansions(Component component, int depth) {
			budget.visit(depth);
			ComponentContents contents = component.getContents();
			if (contents instanceof TranslatableContents trans) {
				budget.cells(trans.getArgs().length);
				for (Object arg : trans.getArgs()) if (arg instanceof Component child) hoverExpansions(child, depth + 1);
			} else if (contents instanceof SelectorContents selector) selector.getSeparator().ifPresent(child -> hoverExpansions(child, depth + 1));
			else if (contents instanceof NbtContents nbt) nbt.getSeparator().ifPresent(child -> hoverExpansions(child, depth + 1));
			for (Component child : component.getSiblings()) hoverExpansions(child, depth + 1);
			HoverEvent hover = component.getStyle().getHoverEvent();
			if (hover == null) return;
			if (hover.getAction() == HoverEvent.Action.SHOW_TEXT) {
				Component child = hover.getValue(HoverEvent.Action.SHOW_TEXT);
				expand(child, depth + 1, null); hoverExpansions(child, depth + 1);
			} else if (hover.getAction() == HoverEvent.Action.SHOW_ENTITY) {
				hover.getValue(HoverEvent.Action.SHOW_ENTITY).name.ifPresent(child -> { expand(child, depth + 1, null); hoverExpansions(child, depth + 1); });
			}
			// SHOW_ITEM's effective text values were certified by its checked map.
		}

		private void append(String text, StringBuilder output) {
			budget.characters(text); // Before append or builder expansion, not inside a native character callback.
			if (output != null) output.append(text);
		}

		private List<Part> plan(TranslatableContents trans, int depth) {
			sameLanguage();
			String template = language.getOrDefault(trans.getKey(), trans.getFallback() == null ? trans.getKey() : trans.getFallback());
			budget.characters(template);
			// Native regex/decomposition creates at most two parts per percent and
			// one trailing part. Reserve scratch/tokens before calling the adapter.
			int percents = 0;
			for (int i = 0; i < template.length(); i++) if (template.charAt(i) == '%') percents++;
			budget.cells(Math.addExact(Math.multiplyExact(percents, 2), 1));
			Object[] args = trans.getArgs(); budget.cells(args.length);
			// TranslatableFormatException describes its fresh owner. Native
			// diagnostics stringify each EMPTY token with fixed size; reserve that
			// error workspace too, before parsing can throw on an index/format.
			budget.characterUnits(256L * args.length + 8L * template.length() + 256L);
			Object[] tokens = new Object[args.length];
			IdentityHashMap<FormattedText, Integer> indices = new IdentityHashMap<>();
			for (int i = 0; i < tokens.length; i++) {
				budget.visit(depth);
				Component token = Component.empty(); tokens[i] = token; indices.put(token, i);
			}
			// Only fresh fixed-cost tokens reach native diagnostics, never resident
			// Components or arbitrary argument objects (including on format errors).
			TranslatableContents parser = new TranslatableContents("", null, tokens);
			List<Part> parts = new ArrayList<>();
			NativeTemplate.parse(parser, template, text -> {
				budget.visit(depth);
				Integer index = indices.get(text);
				if (index != null) parts.add(Part.argument(index));
				else {
					if (text.getClass() != NativeTemplate.LITERAL) throw new UnsafeDisplay();
					text.visit(literal -> { budget.characters(literal); parts.add(Part.literal(literal)); return Optional.empty(); });
				}
			}, () -> {
				// Native decompose discards ALL partial parts on any format error.
				parts.clear(); budget.visit(depth); budget.characters(template); parts.add(Part.literal(template));
			});
			sameLanguage();
			return parts;
		}
	}

	/** One exact owner and descriptor; unavailable/ambiguous adapters fail closed. */
	private static final class NativeTemplate {
		static final Class<?> LITERAL = FormattedText.of("").getClass();
		static final Method PARSE = find();
		private static Method find() {
			try {
				Method result = null;
				for (Method method : TranslatableContents.class.getDeclaredMethods()) {
					if (!Modifier.isStatic(method.getModifiers()) && Modifier.isPrivate(method.getModifiers()) && method.getReturnType() == void.class
						&& Arrays.equals(method.getParameterTypes(), new Class<?>[] {String.class, Consumer.class})) {
						if (result != null || !method.trySetAccessible()) return null;
						result = method;
					}
				}
				return result;
			} catch (RuntimeException unavailable) { return null; }
		}
		static void parse(TranslatableContents fresh, String template, Consumer<FormattedText> sink, Runnable invalid) {
			if (PARSE == null) throw new UnsafeDisplay();
			try { PARSE.invoke(fresh, template, sink); }
			catch (IllegalAccessException unavailable) { throw new UnsafeDisplay(unavailable); }
			catch (InvocationTargetException failure) {
				@org.jetbrains.annotations.Nullable Throwable cause = failure.getCause();
				if (cause instanceof TranslatableFormatException) invalid.run();
				else if (cause instanceof RuntimeException runtime) throw runtime;
				else if (cause instanceof Error error) throw error;
				else throw new UnsafeDisplay(failure);
			}
		}
	}
}
