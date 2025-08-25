package nx.pingwheel.common.core;

import com.mojang.math.Matrix4f;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.math.MathUtils;

import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

import static nx.pingwheel.common.ClientGlobal.Game;

public class PingManager {
	private PingManager() {}

	private static final ClientConfig CLIENT_CONFIG = ClientConfig.HANDLER.getConfig();

	public static final ArrayList<PingData> PING_REPO = new ArrayList<>();

	public static void clearPings() {
		PING_REPO.clear();
	}

	public static void addOrReplacePing(PingData newPing) {
		int index = -1;

		for (int i = 0; i < PING_REPO.size(); i++) {
			var entry = PING_REPO.get(i);

			if (Objects.equals(entry.getAuthor(), newPing.getAuthor()) && entry.getSequence() == newPing.getSequence()) {
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

		var time = (int)Game.level.getGameTime();

		var cameraPos = Game.player.getEyePosition(tickDelta);
		PingData target = null;

		for (var iter = PING_REPO.iterator(); iter.hasNext(); ) {
			var ping = iter.next();

			if (ping.getUuid() != null) {
				var ent = getEntity(ping.getUuid());

				if (ent != null) {
					if (ent.getType() == EntityType.ITEM && CLIENT_CONFIG.isItemIconVisible()) {
						ping.setItemStack(((ItemEntity)ent).getItem().copy());
					}

					ping.setPos(ent.getPosition(tickDelta).add(0, ent.getBoundingBox().getYsize(), 0));
				}
			}

			ping.setDistance(cameraPos.distanceTo(ping.getPos()));
			ping.setScreenPos(MathUtils.worldToScreen(ping.getPos(), modelViewMatrix, projectionMatrix));
			ping.setAge(time - ping.getSpawnTime());

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

	private static Entity getEntity(UUID uuid) {
		if (Game.level == null) {
			return null;
		}

		for (var entity : Game.level.entitiesForRendering()) {
			if (entity.getUUID().equals(uuid)) {
				return entity;
			}
		}

		return null;
	}
}
