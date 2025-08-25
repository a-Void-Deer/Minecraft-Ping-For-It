package nx.pingwheel.common.util;

import net.minecraft.world.phys.HitResult;

import static nx.pingwheel.common.ClientGlobal.Game;
import static nx.pingwheel.common.ClientGlobal.KEY_BINDING_PING;

public class InputUtils {
	InputUtils() {}

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
