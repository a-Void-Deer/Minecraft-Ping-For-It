package nx.pingwheel.common.chat;

import java.util.Objects;
import java.util.function.Predicate;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import nx.pingwheel.common.domain.PingType;

/**
 * Builds ping chat lines. The established three-argument form remains the
 * explicit fallback when a client language template cannot be composed.
 *
 * <p>The legacy fallback uses the fixed translation key
 * {@code pingforit.chat.pingmsg} with the structure equivalent to
 * {@code <player> requests <ping-type phrase> <target>}:
 * <ul>
 *   <li>{@code player} is the plain, default-colored literal server profile
 *       name;</li>
 *   <li>{@code phrase} is {@code Component.translatable(pingType.phraseKey())}
 *       colored with {@code pingType.textColor()};</li>
 *   <li>{@code target} is the trusted, server-derived authoritative target
 *       name component, appended as-is with its default (colorless)
 *       style.</li>
 * </ul>
 *
 * <p>Only the ping-type phrase carries a {@code TextColor}; the outer
 * template, the player argument, and the target argument stay default-styled,
 * matching the spec's "only the phrase uses textColor" rule. This builder is
 * pure logic over components and ping types: no game state, config, or
 * registry access, so the coloring contract is unit tested directly.
 */
public final class PingChatBuilder {

	public static final String LEGACY_KEY = "pingforit.chat.pingmsg";
	public static final String TEMPLATE_KEY = "pingforit.chat.pingmsg.template";

	private static final String PLAYER_NAME_PLACEHOLDER = "playerName";
	private static final String PING_TYPE_PLACEHOLDER = "pingType";
	private static final String TARGET_NAME_PLACEHOLDER = "targetName";

	private PingChatBuilder() {}

	/**
	 * Returns the optional language override key for a validated catalog ping
	 * type. The caller must use the current client language when checking this
	 * key so resource-pack translations are included.
	 */
	public static String templateOverrideKey(PingType pingType) {
		Objects.requireNonNull(pingType, "pingType");
		return "pingforit.chat." + pingType.id() + ".template.override";
	}

	/**
	 * Selects the key to resolve for a ping chat template.
	 *
	 * <p>The presence predicate represents the merged client language, not the
	 * mod's bundled language, so an override supplied only by a resource pack is
	 * selected correctly. The selected value is parsed once by
	 * {@link #build(String, String, PingType, Component)}; parsing failure must
	 * fall back to the legacy component rather than selecting another template.
	 *
	 * @param pingType the already client-resolved and validated ping type
	 * @param hasTranslation whether the current language contains a key
	 */
	public static String selectTemplateKey(PingType pingType, Predicate<String> hasTranslation) {
		Objects.requireNonNull(pingType, "pingType");
		Objects.requireNonNull(hasTranslation, "hasTranslation");

		String overrideKey = templateOverrideKey(pingType);
		return hasTranslation.test(overrideKey) ? overrideKey : TEMPLATE_KEY;
	}

	/**
	 * Builds the ping chat line for one accepted marker.
	 *
	 * @param playerName the plain server profile name of the marker owner
	 * @param pingType   the catalog ping type of the marker
	 * @param targetName the trusted authoritative target name component
	 */
	public static Component build(String playerName, PingType pingType, Component targetName) {
		Objects.requireNonNull(playerName, "playerName");
		Objects.requireNonNull(pingType, "pingType");
		Objects.requireNonNull(targetName, "targetName");

		return Component.translatable(
			LEGACY_KEY,
			Component.literal(playerName),
			Component.translatable(pingType.phraseKey()).withColor(pingType.textColor()),
			targetName);
	}

	/**
	 * Builds a ping chat line from a client-resolved language template.
	 *
	 * <p>The template is parsed as named placeholders rather than as a
	 * translatable component format string, so the language controls the exact
	 * order and literal composition. Every required placeholder must occur at
	 * least once. A malformed or incomplete template deliberately falls back to
	 * {@link #build(String, PingType, Component)}, preserving the established
	 * server-side component shape.
	 *
	 * @param template   the resolved language value for {@link #TEMPLATE_KEY} or
	 *                   {@link #templateOverrideKey(PingType)}
	 * @param playerName the plain profile name of the marker owner
	 * @param pingType   the catalog ping type of the marker
	 * @param targetName the trusted target name component
	 */
	public static Component build(
		String template, String playerName, PingType pingType, Component targetName
	) {
		try {
			Objects.requireNonNull(template, "template");
			Objects.requireNonNull(playerName, "playerName");
			Objects.requireNonNull(pingType, "pingType");
			Objects.requireNonNull(targetName, "targetName");

			MutableComponent message = Component.empty();
			StringBuilder literal = new StringBuilder();
			boolean hasPlayerName = false;
			boolean hasPingType = false;
			boolean hasTargetName = false;

			for (int index = 0; index < template.length();) {
				char current = template.charAt(index);

				if (current == '{') {
					if (index + 1 < template.length() && template.charAt(index + 1) == '{') {
						literal.append('{');
						index += 2;
						continue;
					}

					int close = template.indexOf('}', index + 1);

					if (close < 0) {
						throw new IllegalArgumentException("unmatched template opening brace");
					}

					appendLiteral(message, literal);

					switch (template.substring(index + 1, close)) {
						case PLAYER_NAME_PLACEHOLDER -> {
							message.append(Component.literal(playerName));
							hasPlayerName = true;
						}
						case PING_TYPE_PLACEHOLDER -> {
							message.append(Component.translatable(pingType.phraseKey()).withColor(pingType.textColor()));
							hasPingType = true;
						}
						case TARGET_NAME_PLACEHOLDER -> {
							message.append(targetName);
							hasTargetName = true;
						}
						default -> throw new IllegalArgumentException("unknown template placeholder");
					}

					index = close + 1;
					continue;
				}

				if (current == '}') {
					if (index + 1 < template.length() && template.charAt(index + 1) == '}') {
						literal.append('}');
						index += 2;
						continue;
					}

					throw new IllegalArgumentException("unmatched template closing brace");
				}

				literal.append(current);
				index++;
			}

			appendLiteral(message, literal);

			if (!hasPlayerName || !hasPingType || !hasTargetName) {
				return build(playerName, pingType, targetName);
			}

			return message;
		} catch (RuntimeException ignored) {
			return build(playerName, pingType, targetName);
		}
	}

	private static void appendLiteral(MutableComponent message, StringBuilder literal) {
		if (literal.length() == 0) {
			return;
		}

		message.append(Component.literal(literal.toString()));
		literal.setLength(0);
	}
}
