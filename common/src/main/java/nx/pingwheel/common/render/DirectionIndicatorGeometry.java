package nx.pingwheel.common.render;

import net.minecraft.world.phys.Vec2;
import nx.pingwheel.common.math.MathUtils;
import nx.pingwheel.common.math.ScreenPos;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Headless bearing and safe-rectangle landing for an off-screen marker. A
 * behind-camera point is perspective-divided through the projection center, so
 * its reported screen position is mirrored about that center; negating it
 * around the safe-zone center would carry the safe-area inset bias into the
 * bearing. Forward targets keep the established safe-zone-center bearing.
 */
@NotNullByDefault
final class DirectionIndicatorGeometry {
	private DirectionIndicatorGeometry() {}

	static Placement place(ScreenPos screenPos, Vec2 screenSize, Vec2 safeZoneTopLeft, Vec2 safeZoneBottomRight) {
		final var direction = screenPos.isBehindCamera()
			? new Vec2(screenSize.x * 0.5f - screenPos.x, screenSize.y * 0.5f - screenPos.y)
			: new Vec2(
				screenPos.x - (safeZoneTopLeft.x + safeZoneBottomRight.x) * 0.5f,
				screenPos.y - (safeZoneTopLeft.y + safeZoneBottomRight.y) * 0.5f);
		final var angle = (float)Math.atan2(direction.y, direction.x);
		final var edgePosition = MathUtils.calculateAngleRectIntersection(
			angle, safeZoneTopLeft, safeZoneBottomRight);

		return new Placement(angle, edgePosition);
	}

	@NotNullByDefault
	record Placement(float angle, Vec2 edgePosition) {}
}
