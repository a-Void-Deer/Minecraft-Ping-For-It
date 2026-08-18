package nx.pingwheel.common.interaction.cancel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldVectorTest {

	@Test
	void subtractReturnsComponentWiseDifference() {
		WorldVector a = new WorldVector(3, 4, 5);
		WorldVector b = new WorldVector(1, 2, 3);

		assertEquals(new WorldVector(2, 2, 2), a.subtract(b));
	}

	@Test
	void dotComputesStandardDotProduct() {
		WorldVector a = new WorldVector(1, 2, 3);
		WorldVector b = new WorldVector(4, -5, 6);

		assertEquals(1 * 4 + 2 * -5 + 3 * 6, a.dot(b));
	}

	@Test
	void lengthSquaredIsSelfDot() {
		WorldVector v = new WorldVector(2, 3, 6);

		assertEquals(4 + 9 + 36, v.lengthSquared());
		assertEquals(v.dot(v), v.lengthSquared());
	}

	@Test
	void lengthSquaredOfZeroIsZero() {
		assertEquals(0.0, new WorldVector(0, 0, 0).lengthSquared());
	}

	@Test
	void rejectsNonFiniteCoordinates() {
		assertThrows(IllegalArgumentException.class, () -> new WorldVector(Double.NaN, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new WorldVector(0, Double.NaN, 0));
		assertThrows(IllegalArgumentException.class, () -> new WorldVector(0, 0, Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> new WorldVector(Double.POSITIVE_INFINITY, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new WorldVector(0, Double.NEGATIVE_INFINITY, 0));
		assertThrows(IllegalArgumentException.class, () -> new WorldVector(0, 0, Double.POSITIVE_INFINITY));
	}

	@Test
	void rejectsNullOperands() {
		WorldVector v = new WorldVector(1, 1, 1);

		assertThrows(NullPointerException.class, () -> v.subtract(null));
		assertThrows(NullPointerException.class, () -> v.dot(null));
	}
}
