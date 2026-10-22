package nx.pingwheel.common.chat;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.presentation.client.PresentationPropertyFormatter;

/**
 * The content-message family's full localized template.
 *
 * <p>Unlike the ordinary whole-message template, the content family is a
 * separate key family with its own connectives and quoting. The template
 * carries exactly four required named placeholders — {@code {author}},
 * {@code {type}}, {@code {target}} and {@code {content}} — so every localized
 * value controls the complete order and wording. A doubled opening or closing
 * brace emits one literal brace. Multi-selection messages instead use an
 * outer author/target/content template and a type/content template per entry.
 * Each parser accepts only its own required placeholder names.
 *
 * <p>A missing, malformed, unknown-placeholder, unmatched-brace or incomplete
 * template falls back to the safe default for its own family. The single-entry
 * default is {@link #DEFAULT_TEMPLATE}; list messages retain the outer tokens
 * and every independently annotated entry. None falls back to the whole-message
 * family or renders a partial line.
 */
public final class ContentChatTemplate {

	public static final String TEMPLATE_KEY = "pingforit.chat.content.template";
	public static final String MULTIPLE_TEMPLATE_KEY = "pingforit.chat.content.multiple.template";
	public static final String ENTRY_TEMPLATE_KEY = "pingforit.chat.content.entry";
	public static final String SEPARATOR_KEY = "pingforit.chat.content.separator";
	public static final String QUOTE_KEY = "pingforit.chat.content.quote";
	public static final String CUSTOM_ITEM_NAME_KEY = "pingforit.chat.content.custom_item_name";
	public static final String FIELD_KEY = "pingforit.chat.content.field";
	public static final String ITEM_COUNT_KEY = "pingforit.chat.content.item_count";
	public static final String QUALITY_KEY = "pingforit.chat.content.quality";

	/** The safe default resource: a complete four-piece template. */
	public static final String DEFAULT_TEMPLATE = "{author}: {type} {target} {content}";
	public static final String DEFAULT_MULTIPLE_TEMPLATE = "{author}: {target} {content}";
	public static final String DEFAULT_ENTRY_TEMPLATE = "{type} {content}";

	private static final String AUTHOR = "author";
	private static final String TYPE = "type";
	private static final String TARGET = "target";
	private static final String CONTENT = "content";

	private ContentChatTemplate() {}

	/**
	 * Returns the optional selected-locale override key for one annotation Ping
	 * Type. Presence is checked against the selected locale's own resource
	 * stack, exactly like the whole-message override policy; no per-type
	 * override is mandatory.
	 */
	public static String templateOverrideKey(PingType annotationType) {
		Objects.requireNonNull(annotationType, "annotationType");
		return "pingforit.chat.content." + annotationType.id() + ".template.override";
	}

	/** Selects the selected-locale override when it exists, else the common content template. */
	public static String selectTemplateKey(PingType annotationType, Predicate<String> hasSelectedLocaleKey) {
		Objects.requireNonNull(annotationType, "annotationType");
		Objects.requireNonNull(hasSelectedLocaleKey, "hasSelectedLocaleKey");
		String override = templateOverrideKey(annotationType);
		return hasSelectedLocaleKey.test(override) ? override : TEMPLATE_KEY;
	}

	/** The independent per-entry override uses the same selected-locale presence policy. */
	public static String entryOverrideKey(PingType annotationType) {
		Objects.requireNonNull(annotationType, "annotationType");
		return "pingforit.chat.content." + annotationType.id() + ".entry.override";
	}

	public static String selectEntryKey(PingType annotationType, Predicate<String> hasSelectedLocaleKey) {
		Objects.requireNonNull(hasSelectedLocaleKey, "hasSelectedLocaleKey");
		String override = entryOverrideKey(annotationType);
		return hasSelectedLocaleKey.test(override) ? override : ENTRY_TEMPLATE_KEY;
	}

	/**
	 * Builds the message from one selected template, falling back to the safe
	 * default resource when the selected template is missing or malformed.
	 */
	public static Component build(String template, Component author, PingType annotationType,
		Component target, Component content) {
		return parse(template, author, annotationType, target, content)
			.orElseGet(() -> parse(DEFAULT_TEMPLATE, author, annotationType, target, content).orElseThrow());
	}

	/** Builds an outer list message without inventing a whole-set annotation phrase. */
	public static Component buildMultiple(String template, Component author, Component target, Component content) {
		return parseMultiple(template, author, target, content)
			.orElseGet(() -> parseMultiple(DEFAULT_MULTIPLE_TEMPLATE, author, target, content).orElseThrow());
	}

	/** Validates the outer family with only author, target and content as required tokens. */
	public static Optional<Component> parseMultiple(String template, Component author, Component target, Component content) {
		Objects.requireNonNull(author, "author");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(content, "content");
		return parse(template, Map.of(AUTHOR, author, TARGET, target, CONTENT, content));
	}

	/** Builds one independently annotated entry; only its own type word is colored. */
	public static Component buildEntry(String template, PingType annotationType, Component content) {
		return parseEntry(template, annotationType, content)
			.orElseGet(() -> parseEntry(DEFAULT_ENTRY_TEMPLATE, annotationType, content).orElseThrow());
	}

	/** Validates one entry with only type and content as required tokens. */
	public static Optional<Component> parseEntry(String template, PingType annotationType, Component content) {
		Objects.requireNonNull(annotationType, "annotationType");
		Objects.requireNonNull(content, "content");
		try {
			return parse(template, Map.of(TYPE, PresentationPropertyFormatter.pingTypeDisplay(annotationType), CONTENT, content));
		} catch (RuntimeException unavailable) {
			return Optional.empty();
		}
	}

	/**
	 * Parses one template. Each required placeholder must occur at least once;
	 * a repeated placeholder is allowed. Any unknown placeholder, unmatched or
	 * isolated brace, or missing required placeholder yields an empty result so
	 * the caller can use the safe default.
	 */
	public static Optional<Component> parse(String template, Component author, PingType annotationType,
		Component target, Component content) {
		Objects.requireNonNull(author, "author");
		Objects.requireNonNull(annotationType, "annotationType");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(content, "content");
		try {
			return parse(template, Map.of(AUTHOR, author, TYPE, PresentationPropertyFormatter.pingTypeDisplay(annotationType),
				TARGET, target, CONTENT, content));
		} catch (RuntimeException unavailable) {
			return Optional.empty();
		}
	}

	private static Optional<Component> parse(String template, Map<String, Component> tokens) {
		if (template == null) return Optional.empty();

		try {
			MutableComponent message = Component.empty();
			StringBuilder literal = new StringBuilder();
			Set<String> present = new HashSet<>();

			for (int index = 0; index < template.length();) {
				char current = template.charAt(index);

				if (current == '{') {
					if (index + 1 < template.length() && template.charAt(index + 1) == '{') {
						literal.append('{');
						index += 2;
						continue;
					}

					int close = template.indexOf('}', index + 1);

					if (close < 0) return Optional.empty();

					appendLiteral(message, literal);

					String token = template.substring(index + 1, close);
					Component replacement = tokens.get(token);
					if (replacement == null) return Optional.empty();
					message.append(replacement);
					present.add(token);

					index = close + 1;
					continue;
				}

				if (current == '}') {
					if (index + 1 < template.length() && template.charAt(index + 1) == '}') {
						literal.append('}');
						index += 2;
						continue;
					}

					return Optional.empty();
				}

				literal.append(current);
				index++;
			}

			appendLiteral(message, literal);

			if (!present.containsAll(tokens.keySet())) return Optional.empty();
			return Optional.of(message);
		} catch (RuntimeException ignored) {
			return Optional.empty();
		}
	}

	private static void appendLiteral(MutableComponent message, StringBuilder literal) {
		if (literal.length() == 0) return;
		message.append(Component.literal(literal.toString()));
		literal.setLength(0);
	}
}
