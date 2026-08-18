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

	/** The physical key and armed state of the current claimed hold. */
	private static final ClaimedInputState<ClaimedKey> PING_INPUT_STATE = new ClaimedInputState<>();

	/**
	 * Claims one raw {@link KeyMapping#click(InputConstants.Key)} edge.
	 *
	 * <p>For a dedicated binding only the custom mapping is consumed. When the
	 * mapping is shared with vanilla pick-item, the established MISS or
	 * non-creative ENTITY cases consume one queued click from both mappings and
	 * claim if either mapping received the raw event; in all other cases the
	 * vanilla click remains queued.
	 */
	public static boolean claimPingClick(InputConstants.Key rawKey) {
		if (rawKey == null || !matchesPingBinding(rawKey)) {
			return false;
		}

		if (Game == null || Game.options == null) {
			return false;
		}

		boolean sharedWithPick = KEY_BINDING_PING.same(Game.options.keyPickItem);
		boolean currentlyEligible = !sharedWithPick || isSharedPickEligible();
		PingClickArbitration.Plan plan = PingClickArbitration.plan(sharedWithPick, currentlyEligible);
		boolean alreadyArmed = PING_INPUT_STATE.isArmed();

		// The plan is decided before either counter is consumed.  A shared
		// eligible event drains both counters and claims if either mapping got the
		// raw edge; an ineligible event deliberately leaves Pick Block untouched.
		boolean pickConsumed = plan.consumePick() && Game.options.keyPickItem.consumeClick();
		boolean customConsumed = plan.consumeCustom() && KEY_BINDING_PING.consumeClick();

		// Some loader input maps can expose the same physical edge through more
		// than one mapping entry. Drain the counters for each callback, but only
		// the first callback may arm one physical hold; otherwise a shared binding
		// would start two interactions for one press.
		if (!alreadyArmed && plan.claims(pickConsumed, customConsumed)) {
			arm(rawKey);
			return true;
		}

		return false;
	}

	private static boolean isSharedPickEligible() {
		if (Game.player == null || Game.hitResult == null) {
			return false;
		}

		return Game.hitResult.getType() == HitResult.Type.MISS
			|| (!Game.player.isCreative() && Game.hitResult.getType() == HitResult.Type.ENTITY);
	}

	private static boolean matchesPingBinding(InputConstants.Key rawKey) {
		return switch (rawKey.getType()) {
			case MOUSE -> KEY_BINDING_PING.matchesMouse(rawKey.getValue());
			case KEYSYM -> KEY_BINDING_PING.matches(rawKey.getValue(), InputConstants.UNKNOWN.getValue());
			case SCANCODE -> KEY_BINDING_PING.matches(InputConstants.UNKNOWN.getValue(), rawKey.getValue());
		};
	}

	private static void arm(InputConstants.Key rawKey) {
		PING_INPUT_STATE.arm(ClaimedKey.from(rawKey));
	}

	/** Observes a raw mapping state transition and claims only the matching release. */
	public static boolean observeKeyState(InputConstants.Key rawKey, boolean isDown) {
		return PING_INPUT_STATE.observe(
			rawKey == null ? null : ClaimedKey.from(rawKey),
			isDown);
	}

	/**
	 * Reports the held state of the ping key.
	 *
	 * <p>The state is event-driven. It is deliberately not re-gated by the
	 * current hit result, creative mode, or the currently configured binding:
	 * those were arbitrated at the press edge, and a mid-hold camera change or
	 * rebinding must not fake a release.
	 */
	public static boolean isPingHotkeyDown() {
		return PING_INPUT_STATE.isArmed();
	}

	/**
	 * Disarms the ping hold without touching the raw key state.
	 *
	 * <p>Called when leaving a server or when Minecraft releases all mappings
	 * (for example on focus loss), so a held key cannot leak into the next
	 * connection or screen. The raw Minecraft mapping state is owned by
	 * {@link KeyMapping}; only this custom claim is cleared here.
	 */
	public static void resetPingHold() {
		PING_INPUT_STATE.reset();
	}

	/** Value identity avoids depending on loader-specific Key object interning. */
	private record ClaimedKey(InputConstants.Type type, int value) {
		private static ClaimedKey from(InputConstants.Key key) {
			return new ClaimedKey(key.getType(), key.getValue());
		}
	}
}
