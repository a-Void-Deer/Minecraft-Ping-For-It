package nx.pingwheel.common.core;

import com.mojang.math.Matrix4f;
import nx.pingwheel.common.config.ClientConfig;

import java.util.ArrayList;
import java.util.Objects;

import static nx.pingwheel.common.CommonClient.Game;

public class PingManager {
	private PingManager() {}

	private static final ClientConfig CLIENT_CONFIG = ClientConfig.HANDLER.getConfig();

	public static final ArrayList<PingView> PING_REPO = new ArrayList<>();

	public static void clearPings() {
		PING_REPO.clear();
	}

	public static void addOrReplacePing(PingView newPing) {
		int index = -1;

		for (int i = 0; i < PING_REPO.size(); i++) {
			var entry = PING_REPO.get(i);

			if (Objects.equals(entry.authorId, newPing.authorId) && entry.sequence == newPing.sequence) {
				index = i;
				break;
			}
		}

		if (index != -1) {
			PING_REPO.set(index, newPing);
		} else {
			PING_REPO.add(newPing);
		}
	}

	public static void updatePings(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, float tickDelta) {
		if (Game.player == null || Game.level == null || PING_REPO.isEmpty()) {
			return;
		}

		final var time = (int)Game.level.getGameTime();
		final var cameraPos = Game.player.getEyePosition(tickDelta);
		PingView target = null;

		for (var iter = PING_REPO.iterator(); iter.hasNext(); ) {
			final var ping = iter.next();

			ping.update(modelViewMatrix, projectionMatrix, tickDelta, cameraPos, time);

			if (ping.isExpired()) {
				iter.remove();
			} else if (PingController.isPingQueued() && ping.isRemovable() && ping.isCloserToCenter(target)) {
				target = ping;
			}
		}

		if (target != null && PING_REPO.remove(target)) {
			PingController.revokePingAction();
		}

		PING_REPO.sort((a, b) -> Double.compare(b.getDistance(), a.getDistance()));
	}
}
