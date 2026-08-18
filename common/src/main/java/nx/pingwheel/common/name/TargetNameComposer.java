package nx.pingwheel.common.name;

import java.util.Objects;

import net.minecraft.network.chat.Component;

/**
 * Pure composition helpers for server-authoritative target display names.
 *
 * <p>Naming rules (spec Confirmed Decisions → Names):
 * <ul>
 *   <li>an unnamed target uses the localized/base vanilla component as-is,
 *       which carries the default style and therefore no color;</li>
 *   <li>a custom-named target is displayed as
 *       {@code Custom Name (Vanilla Name)}: the custom text is reduced to a
 *       plain, unstyled literal (its own color, style, and events are
 *       deliberately stripped), followed by a literal {@code " ("}, the
 *       trusted localized base component, and a literal {@code ")"}.</li>
 * </ul>
 *
 * <p>The base component is always supplied by a trusted server-side source
 * (localized block/entity/item names), so it is appended unchanged and never
 * restyled. No color or style is added anywhere by this class.
 *
 * <p>{@link #here()} and {@link #unknown()} provide the fixed location and
 * fail-safe display names. Only Minecraft component types are used here; no
 * game state, config, or registry access happens, so this class is unit
 * tested without a running game.
 */
public final class TargetNameComposer {

	private TargetNameComposer() {}

	/**
	 * Composes {@code Custom Name (Vanilla Name)} from the custom text and the
	 * trusted base component.
	 *
	 * <p>The custom component's own style and events are stripped: only its
	 * plain {@link Component#getString() string} participates. The base
	 * component is appended as-is with no additional style.
	 */
	public static Component compose(Component customName, Component baseName) {
		Objects.requireNonNull(customName, "customName");
		Objects.requireNonNull(baseName, "baseName");

		return Component.literal(customName.getString())
			.append(Component.literal(" ("))
			.append(baseName)
			.append(Component.literal(")"));
	}

	/**
	 * The fixed display name for a pure location target ({@code HERE},
	 * localized through {@code pingforit.target.here}). Plain translatable, no
	 * color.
	 */
	public static Component here() {
		return Component.translatable("pingforit.target.here");
	}

	/**
	 * The fixed fail-safe display name used when no authoritative name can be
	 * derived ({@code Unknown Target}, localized through
	 * {@code pingforit.target.unknown}). Plain translatable, no color.
	 */
	public static Component unknown() {
		return Component.translatable("pingforit.target.unknown");
	}
}
