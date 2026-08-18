package nx.pingwheel.common.interaction;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.interaction.cancel.WorldVector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class CapturedRayTest {

	@Test
	void preservesTheExactFinitePressRayWithoutNormalizingDirection() {
		WorldVector origin = new WorldVector(1.25, -2.5, 3.75);
		WorldVector direction = new WorldVector(0.0, 0.0, 2.0);

		CapturedRay ray = new CapturedRay(origin, direction);

		assertSame(origin, ray.origin());
		assertSame(direction, ray.direction());
		assertEquals(2.0, ray.direction().z());
	}

	@Test
	void rejectsNullAndNonFiniteValues() {
		WorldVector validOrigin = new WorldVector(0.0, 0.0, 0.0);
		WorldVector validDirection = new WorldVector(0.0, 0.0, 1.0);

		assertThrows(NullPointerException.class, () -> new CapturedRay(null, validDirection));
		assertThrows(NullPointerException.class, () -> new CapturedRay(validOrigin, null));
		assertThrows(IllegalArgumentException.class, () -> new CapturedRay(
			new WorldVector(Double.NaN, 0.0, 0.0), validDirection));
		assertThrows(IllegalArgumentException.class, () -> new CapturedRay(
			validOrigin, new WorldVector(0.0, Double.POSITIVE_INFINITY, 1.0)));
	}

	@Test
	void rejectsZeroDirection() {
		assertThrows(IllegalArgumentException.class, () -> new CapturedRay(
			new WorldVector(0.0, 0.0, 0.0), new WorldVector(0.0, 0.0, 0.0)));
	}
}
