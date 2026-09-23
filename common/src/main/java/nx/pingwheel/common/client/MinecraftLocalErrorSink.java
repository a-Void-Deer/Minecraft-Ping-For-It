package nx.pingwheel.common.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The Minecraft adapter of {@link ClientPingActionDispatcher.LocalErrorSink}.
 *
 * <p>Resolves the dispatcher's message key against the active language and
 * shows it in its exact 24-bit color as a client-side-only chat message via
 * {@code Minecraft.player.displayClientMessage(..., false)}. The visible line
 * begins with the resource-supplied {@code [ping for it]} marker. This is the
 * only place in the phase-7 wiring that touches the Minecraft chat overlay;
 * the pure dispatcher remains testable without a game client.
 */
public final class MinecraftLocalErrorSink implements ClientPingActionDispatcher.LocalErrorSink {

	/**
	 * The language-resource key of the exact visible marker that prefixes
	 * local feedback chat lines. Every bundled locale supplies the literal
	 * {@code [ping for it]} marker.
	 */
	public static final String PREFIX_KEY = "pingforit.chat.local_error.prefix";

	@Override
	public void showLocalError(String messageKey, int color) {
		Minecraft game = Minecraft.getInstance();

		if (game.player != null) {
			game.player.displayClientMessage(componentFor(messageKey, color), false);
		}
	}

	/**
	 * Builds the exact local chat line from language resources: the prefix
	 * marker followed by the message, in {@code color}. Kept separate from the
	 * static Minecraft client lookup so the composed payload can be tested.
	 */
	public static Component componentFor(String messageKey, int color) {
		return Component.translatable(PREFIX_KEY)
			.append(Component.translatable(messageKey))
			.withColor(color);
	}
}
