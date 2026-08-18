package nx.pingwheel.common.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The Minecraft adapter of {@link ClientPingActionDispatcher.LocalErrorSink}.
 *
 * <p>Shows the dispatcher's exact message in its exact 24-bit color as a
 * client-side-only chat message via
 * {@code Minecraft.player.displayClientMessage(..., false)}. This is the only
 * place in the phase-7 wiring that touches the Minecraft chat overlay; the
 * pure dispatcher remains testable without a game client.
 */
public final class MinecraftLocalErrorSink implements ClientPingActionDispatcher.LocalErrorSink {

	@Override
	public void showLocalError(String message, int color) {
		Minecraft game = Minecraft.getInstance();

		if (game.player != null) {
			game.player.displayClientMessage(Component.literal(message).withColor(color), false);
		}
	}
}
