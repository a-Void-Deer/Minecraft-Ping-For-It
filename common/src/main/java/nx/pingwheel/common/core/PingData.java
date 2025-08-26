package nx.pingwheel.common.core;

import com.mojang.math.Matrix4f;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.math.MathUtils;
import nx.pingwheel.common.math.ScreenPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

import static nx.pingwheel.common.CommonClient.Game;
import static nx.pingwheel.common.config.ClientConfig.*;

@Getter
public class PingData {

	private static final ClientConfig CLIENT_CONFIG = ClientConfig.HANDLER.getConfig();

	@Setter
	private Vec3 pos;
	@Nullable
	private final UUID uuid;
	private final PlayerInfo author;
	private final int sequence;
	private final int dimension;
	private final int spawnTime;

	private int age;
	private double distance;
	private float scale;
	@Nullable
	private ScreenPos screenPos;
	@Nullable
	private ItemStack itemStack;

	public PingData(Vec3 pos, @Nullable UUID uuid, PlayerInfo author, int sequence, int dimension, int spawnTime) {
		this.pos = pos;
		this.uuid = uuid;
		this.author = author;
		this.sequence = sequence;
		this.dimension = dimension;
		this.spawnTime = spawnTime;
	}

	public void update(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, float tickDelta, Vec3 cameraPos, int gameTime) {
		if (uuid != null) {
			final var ent = GameContext.getEntity(uuid);

			if (ent != null) {
				if (ent.getType() == EntityType.ITEM && CLIENT_CONFIG.isItemIconVisible()) {
					this.itemStack = ((ItemEntity)ent).getItem().copy();
				}

				this.pos = ent.getPosition(tickDelta).add(0, ent.getBoundingBox().getYsize(), 0);
			}
		}

		this.distance = cameraPos.distanceTo(pos);
		this.calculateScale();
		this.screenPos = MathUtils.worldToScreen(pos, modelViewMatrix, projectionMatrix);
		this.age = gameTime - spawnTime;
	}

	public boolean isExpired() {
		return CLIENT_CONFIG.getPingDuration() < MAX_PING_DURATION && this.age > CLIENT_CONFIG.getPingDuration() * TPS;
	}

	public boolean isRemovable() {
		return (CLIENT_CONFIG.getCorrectionPeriod() >= MAX_CORRECTION_PERIOD || this.age > CLIENT_CONFIG.getCorrectionPeriod() * TPS) && this.distanceToCenter() < CLIENT_CONFIG.getRemoveRadius();
	}

	public float distanceToCenter() {
		if (this.screenPos == null) {
			return 0f;
		}

		final var wnd = Game.getWindow();
		final var center = new Vec2(wnd.getGuiScaledWidth() * 0.5f, wnd.getGuiScaledHeight() * 0.5f);

		return this.screenPos.distanceTo(center);
	}

	public boolean isCloserToCenter(@Nullable PingData b) {
		if (b == null) {
			return true;
		}

		return this.distanceToCenter() < b.distanceToCenter();
	}

	private void calculateScale() {
		final var scale = 2.0 / Math.pow(distance, 0.3);
		final var pingSize = CLIENT_CONFIG.getPingSize() / 100f;

		this.scale = (float)Math.max(1.0, scale) * 0.4f * pingSize;
	}
}
