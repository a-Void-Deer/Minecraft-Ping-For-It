package nx.pingwheel.common.chat;

import java.util.Objects;

import net.minecraft.network.chat.Component;

import nx.pingwheel.common.domain.PingType;

/**
 * Builds the server-side ping chat line.
 *
 * <p>The message uses the fixed template
 * {@code pingforit.chat.request} with the structure equivalent to
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

	private PingChatBuilder() {}

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
			"pingforit.chat.request",
			Component.literal(playerName),
			Component.translatable(pingType.phraseKey()).withColor(pingType.textColor()),
			targetName);
	}
}
