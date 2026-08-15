package nx.pingwheel.common.util;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.phys.HitResult;
import nx.pingwheel.common.resource.LanguageUtils;
import org.lwjgl.glfw.GLFW;

import static nx.pingwheel.common.CommonClient.Game;

public class InputUtils {
	InputUtils() {}

	private static final String SETTINGS_CATEGORY = LanguageUtils.keyOf("key.category", "name");
	public static final KeyMapping KEY_BINDING_PING = new KeyMapping(LanguageUtils.keyOf("key", "ping_location"), InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_5, SETTINGS_CATEGORY);
	public static final KeyMapping KEY_BINDING_SETTINGS = new KeyMapping(LanguageUtils.keyOf("key", "open_settings"), InputConstants.Type.KEYSYM, -1, SETTINGS_CATEGORY);

	/**
	 * True between the moment a ping press edge was consumed and the physical
	 * release of the bound key. The hold is decided once, at the press edge:
	 * rotating the camera afterwards (changing {@code hitResult} or creative
	 * state) must never retroactively end or start a ping hold.
	 */
	private static boolean pingHoldArmed = false;

	/**
	 * Consumes the ping-key press edge.
	 *
	 * <p>The edge is the single arbitration point against the live hit result:
	 * when the ping key shares its binding with the pick-item key, only a miss
	 * press is claimed for pinging and the click is left unconsumed otherwise
	 * (so vanilla pick-block keeps working). A consumed edge arms the hold;
	 * see {@link #isPingHotkeyDown()}.
	 */
	public static boolean consumePingHotkey() {
		if (!KEY_BINDING_PING.same(Game.options.keyPickItem)) {
			var edge = KEY_BINDING_PING.consumeClick();

			if (edge) {
				pingHoldArmed = true;
			}

			return edge;
		}

		if (Game.player == null || Game.hitResult == null) {
			return false;
		}

		var isMiss = Game.hitResult.getType() == HitResult.Type.MISS || (!Game.player.isCreative() && Game.hitResult.getType() == HitResult.Type.ENTITY);

		var edge = isMiss && Game.options.keyPickItem.consumeClick();

		if (edge) {
			pingHoldArmed = true;
		}

		return edge;
	}

	/**
	 * Reports the held state of the ping key.
	 *
	 * <p>Once the press edge was consumed, the hold follows the raw key state
	 * only (the ping key, or the shared pick-item key) until physical release.
	 * It is deliberately not re-gated by the current hit result or creative
	 * mode: those were already arbitrated at the press edge, and a mid-hold
	 * camera change must not fake a release. A hold whose edge was never
	 * consumed (for example pick-item aimed at a block) is not a ping hold.
	 */
	public static boolean isPingHotkeyDown() {
		if (!pingHoldArmed) {
			return false;
		}

		var rawDown = KEY_BINDING_PING.same(Game.options.keyPickItem)
			? Game.options.keyPickItem.isDown()
			: KEY_BINDING_PING.isDown();

		if (!rawDown) {
			pingHoldArmed = false;
		}

		return rawDown;
	}

	/**
	 * Disarms the ping hold without touching the raw key state.
	 *
	 * <p>Called when leaving a server so a disconnect while the ping key is
	 * held can never leak the armed hold into the next connection. The raw key
	 * state is deliberately left alone: the physical key still reports its
	 * current state, and only the armed-hold edge arbitration is forgotten.
	 */
	public static void resetPingHold() {
		pingHoldArmed = false;
	}
}
