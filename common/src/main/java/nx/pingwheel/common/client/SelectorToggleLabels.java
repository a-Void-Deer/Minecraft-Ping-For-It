package nx.pingwheel.common.client;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import nx.pingwheel.common.client.spatial.SelectorIntent;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.math.RaycastPolicy;
import nx.pingwheel.common.resource.LanguageUtils;

/**
 * Live display labels for the native selector's target-selection toggles.
 *
 * <p>The headless selector publishes each toggle as its localization key;
 * this client-side resolver appends the state sampled from the supplied live
 * raycast policy on every call, so a committed toggle is reflected on the next
 * draw without reopening the wheel. The choice's own name, separator, and
 * border remain unchanged.</p>
 */
public final class SelectorToggleLabels {
	private static final String TOGGLE_KEY_PREFIX = "pingforit.spatial.toggle.";
	/** Matches the target-selection notice's status colors. */
	private static final int ON_COLOR = 0x55FF55;
	private static final int OFF_COLOR = 0xFF5555;

	private final Supplier<RaycastPolicy> livePolicy;

	public SelectorToggleLabels(Supplier<RaycastPolicy> livePolicy) {
		this.livePolicy = Objects.requireNonNull(livePolicy, "livePolicy");
	}

	/**
	 * Builds one toggle's {@code <name>: <on/off>} label from the live policy.
	 *
	 * @return the composed label, or {@code null} when the choice is not one of
	 *         the target-selection toggles so the caller keeps its own resolver
	 */
	public Component label(SpatialController.ChoiceView choice) {
		SelectorIntent.CaptureToggle toggle = toggleOf(choice);
		if (toggle == null) return null;
		RaycastPolicy policy = Objects.requireNonNull(livePolicy.get(), "live raycast policy");
		boolean on = isOn(toggle, policy);
		MutableComponent state = Component.translatable(LanguageUtils.keyOf("notice", on ? "on" : "off"))
			.withStyle(style -> style.withColor(TextColor.fromRgb(on ? ON_COLOR : OFF_COLOR)));
		return Component.translatable(choice.label()).append(": ").append(state);
	}

	private static SelectorIntent.CaptureToggle toggleOf(SpatialController.ChoiceView choice) {
		if (choice == null || choice.label() == null || !choice.label().startsWith(TOGGLE_KEY_PREFIX)) return null;
		try {
			return SelectorIntent.CaptureToggle.valueOf(
				choice.label().substring(TOGGLE_KEY_PREFIX.length()).toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException unknown) {
			return null;
		}
	}

	private static boolean isOn(SelectorIntent.CaptureToggle toggle, RaycastPolicy policy) {
		return switch (toggle) {
			case FLUIDS -> policy.fluidMode() == RaycastPolicy.FluidMode.ANY;
			case ENTITY_BLACKLIST -> policy.includeIgnoredEntities();
			case TRANSPARENT_BLOCKS -> policy.blockMode() == RaycastPolicy.BlockMode.VISUAL;
		};
	}
}
