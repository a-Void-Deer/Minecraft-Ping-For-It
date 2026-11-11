package nx.pingwheel.common.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.CooldownFeedbackMode;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The Minecraft adapter of {@link ClientPingActionDispatcher.LocalErrorSink}.
 *
 * <p>Resolves the dispatcher's message key against the active language and
 * shows it in its exact 24-bit color as a client-side-only chat message via
 * {@code Minecraft.player.displayClientMessage(..., false)}. The visible line
 * begins with the resource-supplied {@code [ping for it]} marker. The
 * courtesy-cooldown notice additionally honors the configured local placement.
 * This is the only Minecraft presentation boundary for local feedback; the pure
 * dispatcher remains testable without a game client.
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

	/**
	 * Shows the courtesy-cooldown notice in the configured local placement.
	 * The tracking-creation failure keeps its own chat presentation.
	 */
	@Override
	public void showCreateCooldown(String messageKey, int color) {
		Minecraft game = Minecraft.getInstance();
		@Nullable LocalPlayer player = game.player;

		if (player == null) {
			return;
		}

		presentCooldown(componentFor(messageKey, color), ClientConfig.HANDLER.getConfig().getCooldownFeedback(),
			message -> player.displayClientMessage(message, true),
			message -> player.displayClientMessage(message, false));
	}

	/** Pure placement boundary shared with the headless adapter test. */
	static void presentCooldown(Component message, CooldownFeedbackMode mode,
		Consumer<Component> actionBar, Consumer<Component> chat) {
		Objects.requireNonNull(message, "message");
		Objects.requireNonNull(mode, "mode");
		Objects.requireNonNull(actionBar, "actionBar");
		Objects.requireNonNull(chat, "chat");

		switch (mode) {
			case ACTIONBAR -> actionBar.accept(message);
			case CHAT -> chat.accept(message);
			case HIDDEN -> {
				// The local notice is intentionally suppressed; nothing is sent anywhere.
			}
		}
	}

	@Override
	public void showTrackingFailure(InventoryFailureReason reason) {
		Minecraft game = Minecraft.getInstance();
		if (game.player != null) game.player.displayClientMessage(trackingFailureComponent(reason), false);
	}
	public static Component trackingFailureComponent(InventoryFailureReason reason) {
		return Component.translatable("pingforit.inventory.tracking_failed", Component.translatable(reason.translationKey()))
			.withColor(0xFF5555);
	}
}
