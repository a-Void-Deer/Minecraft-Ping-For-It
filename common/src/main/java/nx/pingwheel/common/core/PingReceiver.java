package nx.pingwheel.common.core;

import net.minecraft.sounds.SoundSource;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.util.DirectionalSoundInstance;

import static nx.pingwheel.common.ClientGlobal.Game;
import static nx.pingwheel.common.ClientGlobal.PING_SOUND_EVENT;
import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.config.ClientConfig.MAX_PING_DISTANCE;

public class PingReceiver {
	private PingReceiver() {}

	private static final ClientConfig CLIENT_CONFIG = ClientConfig.HANDLER.getConfig();

	public static void acceptPingPacket(PingLocationS2CPacket packet) {
		if (packet.isCorrupt()) {
			LOGGER.warn("received invalid ping location from server");
			return;
		}

		final var connection = Game.getConnection();

		if (Game.player == null || Game.level == null || connection == null) {
			return;
		}

		if (!packet.channel().equals(CLIENT_CONFIG.getChannel())) {
			return;
		}

		if (CLIENT_CONFIG.getPingDistance() < MAX_PING_DISTANCE) {
			var vecToPing = Game.player.position().vectorTo(packet.pos());

			if (vecToPing.length() > CLIENT_CONFIG.getPingDistance()) {
				return;
			}
		}

		final var authorInfo = connection.getPlayerInfo(packet.author());

		Game.execute(() -> {
			PingManager.addOrReplacePing(new PingData(
				packet.pos(),
				packet.entity(),
				authorInfo,
				packet.sequence(),
				packet.dimension(),
				(int)Game.level.getGameTime()
			));

			if (packet.dimension() == GameContext.getDimension()) {
				Game.getSoundManager().play(
					new DirectionalSoundInstance(
						PING_SOUND_EVENT,
						SoundSource.MASTER,
						CLIENT_CONFIG.getPingVolume() / 100f,
						1f,
						packet.pos()
					)
				);
			}
		});
	}
}
