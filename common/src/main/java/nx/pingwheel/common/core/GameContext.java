package nx.pingwheel.common.core;

import lombok.Getter;
import net.minecraft.client.multiplayer.ClientLevel;

import static nx.pingwheel.common.CommonClient.Game;

public class GameContext {
	private GameContext() {}

	@Getter
	private static int dimension = 0;
	private static ClientLevel lastWorld = null;

	public static void updateDimension() {
		if (Game.level == null || lastWorld == Game.level) {
			return;
		}

		lastWorld = Game.level;
		dimension = lastWorld.dimension().location().hashCode();
	}
}
