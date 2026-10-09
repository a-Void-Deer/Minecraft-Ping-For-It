package nx.pingwheel.common.presentation.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.presentation.PresentationKineticFormat;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;

/**
 * Formats only addressed, received properties; never samples client world state.
 *
 * <p>The HUD terminal keeps its established {@code String} path, which reads
 * {@code getString()} of the Component form. The public Component exits exist
 * for the content-message composer: they keep trusted component styles and let
 * the caller decide how one text value is rendered.
 */
public final class PresentationPropertyFormatter {
	private static final String PREFIX = "presentation.pingforit.";
	private static final String HEALTH = "minecraft:entity.health";
	private static final String MAX_HEALTH = "minecraft:entity.max_health";
	private PresentationPropertyFormatter() {}
	record LabelProperty(PresentationPropertyRef ref, String pingTypeId) {}

	static List<String> labels(PresentationView view) {
		List<String> result = new ArrayList<>();
		for (LabelProperty property : plan(view)) {
			add(result, view, property.ref(), property.pingTypeId());
			if (result.size() == 3) break;
		}
		return List.copyOf(result);
	}

	/**
	 * The default goes first, followed by distinct explicit server annotations in
	 * ref order. The root {@code minecraft:target.name} is excluded: the HUD
	 * renders the authoritative name on its own line, so neither a name default
	 * reference nor a name annotation may add a duplicate property line.
	 */
	static List<LabelProperty> plan(PresentationView view) {
		List<LabelProperty> result = new ArrayList<>();
		Set<PresentationPropertyRef> shown = new HashSet<>();
		PresentationPropertyRef def = view.defaultRef();
		if (displayable(view, def)) {
			result.add(new LabelProperty(def, annotation(view, def)));
			shown.add(def);
		}
		List<PresentationPropertyRef> annotated = view.sections().values().stream()
			.flatMap(section -> section.annotations().keySet().stream())
			.sorted(PresentationPropertyRef.DETERMINISTIC_ORDER).toList();
		for (PresentationPropertyRef ref : annotated) {
			if (shown.add(ref) && displayable(view, ref))
				result.add(new LabelProperty(ref, annotation(view, ref)));
		}
		return List.copyOf(result);
	}

	private static boolean displayable(PresentationView view, PresentationPropertyRef ref) {
		if (ref == null || view.property(ref) == null) return false;
		if (ref.isRoot() && ClientPresentation.NAME.equals(ref.fieldId())) return false;
		return !ref.isRoot() || !HEALTH.equals(ref.fieldId())
			|| (view.property(ref) instanceof PresentationValue.NumberValue
				&& view.field(ref.adapterId(), MAX_HEALTH) instanceof PresentationValue.NumberValue);
	}

	private static String annotation(PresentationView view, PresentationPropertyRef ref) {
		PresentationSection section = view.sections().get(ref.adapterId());
		return section == null ? null : section.annotations().get(ref);
	}

	private static void add(List<String> result, PresentationView view, PresentationPropertyRef ref, String ping) {
		String value = value(view, ref);
		if (value == null || value.isBlank()) return;
		String label = ping == null ? value : Component.translatable(PREFIX + "type." + ping + ".phrase", value).getString();
		if (!label.isBlank()) result.add(label);
	}

	/**
	 * The presentation-owned atomic Ping Type display word, colored with that
	 * Ping Type's text color. The content message family uses this display word
	 * instead of the {@code %s} property phrase, so the localized template owns
	 * the connective wording.
	 */
	public static Component pingTypeDisplay(PingType pingType) {
		Objects.requireNonNull(pingType, "pingType");
		return Component.translatable(PREFIX + "type." + pingType.id() + ".display")
			.withColor(pingType.textColor());
	}

	/**
	 * Bounded plain literal text. The bound follows the established HUD text
	 * reduction; the caller keeps any quoting outside this call.
	 */
	public static Component boundedText(String text) {
		return Component.literal(shortText(text));
	}

	/**
	 * The Component form of the field label with the HUD's precedence: the
	 * presentation-local key, then the accepted advertised label as a bounded
	 * literal, then a bounded humanized field id. Untrusted advertised text is
	 * never treated as a translation key or a template.
	 */
	public static Component fieldLabel(PresentationView view, PresentationPropertyRef ref) {
		Objects.requireNonNull(view, "view");
		Objects.requireNonNull(ref, "ref");
		return ref.recordPath().isEmpty()
			? label(view, ref.fieldId(), ref.adapterId())
			: label(view, ref.recordPath().get(ref.recordPath().size() - 1), null);
	}

	/**
	 * The Component form of the semantic value formats. Trusted components keep
	 * their own styles and are passed as translatable arguments, so a parent
	 * never tints a value child. A missing value, a missing health pair, an
	 * unaddressable item id, or a blank result is {@code null}.
	 */
	public static Component valueComponent(PresentationView view, PresentationPropertyRef ref) {
		Objects.requireNonNull(view, "view");
		Objects.requireNonNull(ref, "ref");
		PresentationValue value = view.property(ref);
		if (value == null) return null;
		String field = ref.fieldId();
		if (field.equals("minecraft:item.id") && ref.isRoot() && value instanceof PresentationValue.Text id) {
			Component item = itemName(id.value(), false);
			if (item == null) return null;
			PresentationValue count = view.field(ref.adapterId(), "minecraft:item.count");
			return count instanceof PresentationValue.NumberValue n
				? format("item_count", item, number(n.value())) : format("item", item);
		}
		if (field.equals(HEALTH) && ref.isRoot() && value instanceof PresentationValue.NumberValue hp) {
			PresentationValue max = view.field(ref.adapterId(), MAX_HEALTH);
			return max instanceof PresentationValue.NumberValue m
				? format("health_max", number(hp.value()), number(m.value())) : null;
		}
		if (field.equals("create:kinetic.speed") && ref.isRoot()
			&& value instanceof PresentationValue.RecordValue record
			&& record.values().get("effective_rpm") instanceof PresentationValue.NumberValue rpm) {
			return format("rpm", number(rpm.value()));
		}
		if (field.equals("create:kinetic.speed") && ref.recordPath().equals(List.of("effective_rpm"))
			&& value instanceof PresentationValue.NumberValue rpm) return format("rpm", number(rpm.value()));
		if (ref.recordPath().size() == 2 && ref.recordPath().get(0).equals("counts")
			&& value instanceof PresentationValue.NumberValue count) {
			boolean fluid = field.equals("create:fluid.summary");
			if (fluid || field.equals("create:inventory.summary")) {
				Component name = itemName(ref.recordPath().get(1), fluid);
				if (name != null) return format(fluid ? "fluid_count" : "item_count", name, number(count.value()));
			}
		}
		Component named = fieldLabel(view, ref);
		Component kinetic = PresentationKineticFormat.value(ref, value, capacity -> view.property(capacity));
		if (kinetic != null) return format("number", named, kinetic);
		if (value instanceof PresentationValue.Text text) {
			return format("text", named, boundedText(text.value()));
		}
		if (value instanceof PresentationValue.NumberValue number) return format("number", named, number(number.value()));
		if (value instanceof PresentationValue.Flag flag)
			return format("flag", named, Component.translatable(PREFIX + (flag.value() ? "yes" : "no")));
		if (value instanceof PresentationValue.RecordValue record)
			return format("record", named, Integer.toString(record.values().size()));
		if (value instanceof PresentationValue.Sequence sequence)
			return format("record", named, Integer.toString(sequence.values().size()));
		return null;
	}

	private static String value(PresentationView view, PresentationPropertyRef ref) {
		Component value = valueComponent(view, ref);
		return value == null ? null : value.getString();
	}

	private static Component label(PresentationView view, String field, String adapter) {
		String suffix = field.replace(':', '_').replace('.', '_');
		String key = "settings.pingforit.presentation.field." + suffix + ".name";
		Component translated = Component.translatable(key);
		if (!translated.getString().equals(key)) return translated;
		if (adapter != null) {
			String advertised = view.fieldLabels().getOrDefault(adapter, Map.of()).get(field);
			if (advertised != null && !advertised.isBlank()) return boundedText(advertised);
		}
		String tail = field.substring(Math.max(field.indexOf(':'), field.lastIndexOf('.')) + 1);
		return boundedText(tail.replace('_', ' '));
	}

	private static Component itemName(String id, boolean fluid) {
		ResourceLocation location = ResourceLocation.tryParse(id);
		if (location == null) return null;
		if (fluid && BuiltInRegistries.FLUID.containsKey(location)) {
			Component translated = BuiltInRegistries.FLUID.get(location).defaultFluidState().createLegacyBlock()
				.getBlock().getName();
			if (!translated.getString().startsWith("block.")) return translated;
		}
		if (!fluid && BuiltInRegistries.ITEM.containsKey(location))
			return BuiltInRegistries.ITEM.get(location).getDescription();
		return boundedText(location.getPath().replace('_', ' '));
	}

	private static String number(double value) {
		return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP)
			.stripTrailingZeros().toPlainString();
	}

	private static String shortText(String text) {
		if (text == null) return "";
		String safe = text.replaceAll("[\\p{Cntrl}{}\\[\\]]", " ").strip();
		return safe.substring(0, Math.min(safe.length(), 48));
	}

	private static Component format(String suffix, Object... values) {
		return Component.translatable(PREFIX + "format." + suffix, values);
	}
}
