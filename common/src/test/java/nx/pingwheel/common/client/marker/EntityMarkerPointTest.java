package nx.pingwheel.common.client.marker;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityMarkerPointTest {

	@Test
	void topCenterLiftsBaseByBoundingBoxYSize() {
		assertEquals(new Vec3(10.5, 66.0, -3.0),
			EntityMarkerPoint.topCenter(new Vec3(10.5, 64.25, -3.0), 1.75));
	}

	@Test
	void topCenterKeepsXAndZAndAddsY() {
		assertEquals(new Vec3(-7.0, 4.0, 21.5),
			EntityMarkerPoint.topCenter(new Vec3(-7.0, 0.0, 21.5), 4.0));
	}

	@Test
	void zeroHeightReturnsBasePosition() {
		Vec3 base = new Vec3(3.0, 8.0, 9.0);

		assertEquals(base, EntityMarkerPoint.topCenter(base, 0.0));
	}

	@Test
	void movingInterpolatedBaseStillComputesTopCenter() {
		// An interpolated base between ticks (a moving entity) is lifted by the
		// full bounding box Y size, not scaled by the partial tick.
		assertEquals(new Vec3(0.5, 66.5, 1.5),
			EntityMarkerPoint.topCenter(new Vec3(0.5, 64.0, 1.5), 2.5));
	}

	@Test
	void rejectsNullBasePosition() {
		assertThrows(NullPointerException.class, () -> EntityMarkerPoint.topCenter(null, 1.0));
	}
}
