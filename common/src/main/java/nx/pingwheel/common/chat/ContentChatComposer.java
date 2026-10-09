package nx.pingwheel.common.chat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.name.TargetNameJsonCodec;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.client.PresentationPropertyFormatter;
import nx.pingwheel.common.presentation.client.PresentationView;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;

/**
 * Component-level content-message composer for the first-receipt content
 * family. It only reads the caller-supplied authorized projection and the
 * accepted tracking entry; it never samples a world, a preview observation or
 * an uploaded value, and it never renders a partial message.
 *
 * <p>A property message keeps the retained author and composed target, derives
 * the selected annotation phrase from the explicit refs' authorized
 * annotations, and lists the complete selection set in the given order. A
 * missing or incompatible value, an absent annotation, an undecodable
 * authoritative name JSON, or a missing dependency makes the whole message
 * unavailable ({@link Optional#empty()}) instead of a partial line.
 *
 * <p>An inventory message uses the accepted tracking entry's exact long count,
 * the localized item display decoded from the server-bounded display JSON or
 * the registry localized name fallback, and the entry's annotation Ping Type.
 * A negative (unknown) count or a terminal quality is unavailable; an explicit
 * zero is an authoritative count. The component-fallback and uncertain quality
 * stay visible through the existing localized quality keys.
 *
 * <p>Template selection reuses the selected-locale-only override policy: the
 * override key applies only when the selected locale's own resource stack
 * supplies it, and no per-type override is mandatory. A malformed selected
 * template falls back to the safe default content template.
 */
public final class ContentChatComposer {

	/** The localized template source for one derived annotation Ping Type. */
	@FunctionalInterface
	public interface TemplateSource {
		String template(PingType annotationType);

		/** Compatible defaults keep existing lambda/constructor ports functional. */
		default String multipleTemplate() { return ContentChatTemplate.DEFAULT_MULTIPLE_TEMPLATE; }
		default String entryTemplate(PingType annotationType) { return ContentChatTemplate.DEFAULT_ENTRY_TEMPLATE; }
	}

	private static final String INVENTORY_STATUS_PREFIX = "pingforit.spatial.inventory.";

	private ContentChatComposer() {}

	/**
	 * Builds the selected-locale-only template source. {@code
	 * hasSelectedLocaleKey} is the selected locale's own presence predicate,
	 * never the merged language; {@code templateValue} resolves one key's
	 * localized value.
	 */
	public static TemplateSource localized(
		Predicate<String> hasSelectedLocaleKey,
		Function<String, String> templateValue
	) {
		Objects.requireNonNull(hasSelectedLocaleKey, "hasSelectedLocaleKey");
		Objects.requireNonNull(templateValue, "templateValue");
		return new TemplateSource() {
			@Override public String template(PingType annotationType) {
				return lookup(() -> ContentChatTemplate.selectTemplateKey(annotationType, hasSelectedLocaleKey));
			}
			@Override public String multipleTemplate() {
				return lookup(() -> ContentChatTemplate.MULTIPLE_TEMPLATE_KEY);
			}
			@Override public String entryTemplate(PingType annotationType) {
				return lookup(() -> ContentChatTemplate.selectEntryKey(annotationType, hasSelectedLocaleKey));
			}
			private String lookup(java.util.function.Supplier<String> key) {
				try {
					return templateValue.apply(key.get());
				} catch (RuntimeException unavailable) {
					return null;
				}
			}
		};
	}

	/**
	 * Compatibility summary for callers needing the first annotation: every ref
	 * must have a known annotation. This is not a type for the whole selection;
	 * composition resolves each ref's annotation independently.
	 */
	public static Optional<PingType> annotation(PresentationView view, List<PresentationPropertyRef> selectedRefs) {
		Objects.requireNonNull(view, "view");
		Objects.requireNonNull(selectedRefs, "selectedRefs");
		PingType first = null;
		for (PresentationPropertyRef ref : selectedRefs) {
			PingType known = annotationFor(view, ref).orElse(null);
			if (known == null) return Optional.empty();
			if (first == null) first = known;
		}
		return Optional.ofNullable(first);
	}

	/** Resolves only this reference's authorized annotation; never inherits another entry's type. */
	private static Optional<PingType> annotationFor(PresentationView view, PresentationPropertyRef ref) {
		Objects.requireNonNull(view, "view");
		if (ref == null) return Optional.empty();
		PresentationSection section = view.sections().get(ref.adapterId());
		String id = section == null ? null : section.annotations().get(ref);
		return id == null ? Optional.empty() : PingTypeCatalog.builtIn().findById(id);
	}

	/**
	 * Production name decoder: the existing authoritative name codec over the
	 * client registry access. Malformed JSON is unavailable, never raw text.
	 */
	public static Function<String, Optional<Component>> nameJsonDecoder(HolderLookup.Provider registryAccess) {
		Objects.requireNonNull(registryAccess, "registryAccess");
		return json -> {
			if (json == null) return Optional.empty();
			try {
				return Optional.of(TargetNameJsonCodec.decode(new TargetNameJson(json), registryAccess));
			} catch (RuntimeException malformed) {
				return Optional.empty();
			}
		};
	}

	/**
	 * Production item decoder: the server-bounded single-item display JSON,
	 * decoded to the exact variant's display name. Custom component data is
	 * flattened before localized quoting beside the trusted base translation;
	 * incoming descendant styles and events never enter chat. Empty means the
	 * caller uses the entry's registry localized name fallback instead.
	 */
	public static Function<String, Optional<Component>> itemDisplayDecoder(HolderLookup.Provider registryAccess) {
		Objects.requireNonNull(registryAccess, "registryAccess");
		return json -> {
			if (json == null || json.isBlank()) return Optional.empty();
			try {
				JsonElement element = JsonParser.parseString(json);
				return ItemStack.SINGLE_ITEM_CODEC
					.parse(registryAccess.createSerializationContext(JsonOps.INSTANCE), element)
					.result()
					.filter(stack -> !stack.isEmpty())
					.map(ContentChatComposer::itemDisplayName);
			} catch (RuntimeException malformed) {
				return Optional.empty();
			}
		};
	}

	private static Component itemDisplayName(ItemStack stack) {
		Component base = Component.translatable(stack.getDescriptionId());
		Component custom = stack.get(DataComponents.CUSTOM_NAME);
		// ITEM_NAME is also server-provided component data, not a trusted registry name.
		if (custom == null) custom = stack.get(DataComponents.ITEM_NAME);
		// Keep the item implementation's normal localized variant name when no
		// untrusted name override is present (for example, a potion's base name).
		if (custom == null) return stack.getItem().getName(stack);
		Component quoted = Component.translatable(ContentChatTemplate.QUOTE_KEY, Component.literal(custom.getString()));
		return Component.translatable(ContentChatTemplate.CUSTOM_ITEM_NAME_KEY, quoted, base);
	}

	/**
	 * One property content message from the explicit selection set.
	 *
	 * @param author        the retained plain author component
	 * @param target        the composed authoritative target name component
	 * @param view          the current authorized presentation projection
	 * @param selectedRefs  the explicit selection refs in deterministic order
	 * @param nameJsonDecoder decodes an authoritative name JSON value, or empty
	 * @param templates     the localized template source
	 */
	public static Optional<Component> propertyMessage(
		Component author,
		Component target,
		PresentationView view,
		List<PresentationPropertyRef> selectedRefs,
		Function<String, Optional<Component>> nameJsonDecoder,
		TemplateSource templates
	) {
		Objects.requireNonNull(author, "author");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(view, "view");
		Objects.requireNonNull(selectedRefs, "selectedRefs");
		Objects.requireNonNull(nameJsonDecoder, "nameJsonDecoder");
		Objects.requireNonNull(templates, "templates");

		if (selectedRefs.isEmpty()) return Optional.empty();
		MutableComponent content = Component.empty();
		boolean first = true;

		for (PresentationPropertyRef ref : selectedRefs) {
			PingType annotationType = annotationFor(view, ref).orElse(null);
			if (annotationType == null) return Optional.empty();
			Component value = propertyValue(view, ref, nameJsonDecoder);
			if (value == null || value.getString().isBlank()) return Optional.empty();
			if (selectedRefs.size() == 1) return Optional.of(ContentChatTemplate.build(
				templates.template(annotationType), author, annotationType, target, value));
			if (!first) content.append(Component.translatable(ContentChatTemplate.SEPARATOR_KEY));
			content.append(ContentChatTemplate.buildEntry(templates.entryTemplate(annotationType), annotationType, value));
			first = false;
		}

		return Optional.of(ContentChatTemplate.buildMultiple(templates.multipleTemplate(), author, target, content));
	}

	/**
	 * One inventory content message from the accepted tracking entry.
	 *
	 * @param author          the retained plain author component
	 * @param target          the composed authoritative target name component
	 * @param entry           the accepted tracking entry projection
	 * @param displayDecoder  decodes the server-bounded item display JSON, or empty
	 * @param templates       the localized template source
	 */
	public static Optional<Component> inventoryMessage(
		Component author,
		Component target,
		ClientInventory.EntryView entry,
		Function<String, Optional<Component>> displayDecoder,
		TemplateSource templates
	) {
		Objects.requireNonNull(author, "author");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(entry, "entry");
		Objects.requireNonNull(displayDecoder, "displayDecoder");
		Objects.requireNonNull(templates, "templates");

		// A negative count is unknown; an explicit zero is an authoritative count.
		if (entry.count() < 0) return Optional.empty();

		PingType annotationType = entry.itemPingType() == null ? null
			: PingTypeCatalog.builtIn().findById(entry.itemPingType()).orElse(null);
		if (annotationType == null) return Optional.empty();

		Component name = displayName(entry, displayDecoder);
		if (name == null) return Optional.empty();

		Component content = Component.translatable(
			ContentChatTemplate.ITEM_COUNT_KEY, name, Long.toString(entry.count()));

		InventoryS2CPacket.Status quality = entry.fallback()
			? InventoryS2CPacket.Status.COMPONENT_TOO_LONG : entry.quality();
		if (quality != null && quality != InventoryS2CPacket.Status.READY) {
			String suffix = qualitySuffix(quality);
			if (suffix == null) return Optional.empty();
			content = Component.translatable(ContentChatTemplate.QUALITY_KEY, content,
				Component.translatable(INVENTORY_STATUS_PREFIX + suffix));
		}

		return Optional.of(ContentChatTemplate.build(
			templates.template(annotationType), author, annotationType, target, content));
	}

	private static Component propertyValue(
		PresentationView view,
		PresentationPropertyRef ref,
		Function<String, Optional<Component>> nameJsonDecoder
	) {
		PresentationValue value = view.property(ref);
		if (value == null) return null;
		if (value instanceof PresentationValue.Text text) {
			Component rendered = textValue(ref, text.value(), nameJsonDecoder);
			return rendered == null ? null
				: Component.translatable(ContentChatTemplate.FIELD_KEY,
					PresentationPropertyFormatter.fieldLabel(view, ref), rendered);
		}
		try {
			return PresentationPropertyFormatter.valueComponent(view, ref);
		} catch (RuntimeException unavailable) {
			return null;
		}
	}

	private static Component textValue(
		PresentationPropertyRef ref,
		String text,
		Function<String, Optional<Component>> nameJsonDecoder
	) {
		if (ref.isRoot() && PresentationBasic.NAME.equals(ref.fieldId())) {
			try {
				return nameJsonDecoder.apply(text).orElse(null);
			} catch (RuntimeException malformed) {
				return null;
			}
		}
		if (ref.isRoot() && PresentationBasic.CUSTOM_NAME.equals(ref.fieldId()))
			return Component.translatable(ContentChatTemplate.QUOTE_KEY, Component.literal(text));
		// PresentationSection already enforces the wire text bound. Chat preserves
		// its literal payload; the HUD's 48-character/punctuation reducer is not a
		// content-message bound, including for individually selected record entries.
		return Component.literal(text);
	}

	private static Component displayName(
		ClientInventory.EntryView entry,
		Function<String, Optional<Component>> displayDecoder
	) {
		if (entry.displayJson() != null && !entry.displayJson().isBlank()) {
			try {
				Component decoded = displayDecoder.apply(entry.displayJson()).orElse(null);
				if (decoded != null) return decoded;
			} catch (RuntimeException malformed) {
				// A malformed server display never becomes raw text or an exception.
			}
		}
		return entry.label() == null || entry.label().isBlank()
			? null : Component.translatable(entry.label());
	}

	private static String qualitySuffix(InventoryS2CPacket.Status quality) {
		return switch (quality) {
			case UNCERTAIN -> "uncertain";
			case COMPONENT_TOO_LONG -> "component_too_long";
			default -> null;
		};
	}
}
