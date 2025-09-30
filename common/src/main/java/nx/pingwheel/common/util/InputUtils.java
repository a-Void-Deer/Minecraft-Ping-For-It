package nx.pingwheel.common.util;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import static nx.pingwheel.common.CommonClient.Game;
import static nx.pingwheel.common.Global.MOD_ID;

public class InputUtils {
	InputUtils() {}

	private static final KeyMapping.Category SETTINGS_CATEGORY = KeyMapping.Category.register(ResourceLocation.fromNamespaceAndPath(MOD_ID, "name"));
	public static final KeyMapping KEY_BINDING_PING = new KeyMapping("ping-wheel.key.ping-location", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_5, SETTINGS_CATEGORY);
	public static final KeyMapping KEY_BINDING_SETTINGS = new KeyMapping("ping-wheel.key.open-settings", InputConstants.Type.KEYSYM, -1, SETTINGS_CATEGORY);
	public static final KeyMapping KEY_BINDING_NAME_LABELS = new KeyMapping("ping-wheel.key.name-labels", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, SETTINGS_CATEGORY);

	public static boolean consumePingHotkey() {
		if (!KEY_BINDING_PING.same(Game.options.keyPickItem)) {
			return KEY_BINDING_PING.consumeClick();
		}

		if (Game.player == null || Game.hitResult == null) {
			return false;
		}

		var isMiss = Game.hitResult.getType() == HitResult.Type.MISS || (!Game.player.isCreative() && Game.hitResult.getType() == HitResult.Type.ENTITY);

		return isMiss && Game.options.keyPickItem.consumeClick();
	}
}
