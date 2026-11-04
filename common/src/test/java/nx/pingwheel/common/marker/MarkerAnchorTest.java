package nx.pingwheel.common.marker;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertEquals;
import static nx.pingwheel.common.testing.Assertions.assertNotEquals;
import static nx.pingwheel.common.testing.Assertions.assertThrows;

class MarkerAnchorTest {

	@Test
	void equalsAndHashCodeAreValueBased() {
		MarkerAnchor a = new MarkerAnchor(1.5, 2.5, 3.5);
		MarkerAnchor b = new MarkerAnchor(1.5, 2.5, 3.5);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
	}

	@Test
	void differsByAnyCoordinate() {
		MarkerAnchor a = new MarkerAnchor(1.5, 2.5, 3.5);

		assertNotEquals(a, new MarkerAnchor(1.6, 2.5, 3.5));
		assertNotEquals(a, new MarkerAnchor(1.5, 2.6, 3.5));
		assertNotEquals(a, new MarkerAnchor(1.5, 2.5, 3.6));
	}

	@Test
	void exposesExactCoordinates() {
		MarkerAnchor anchor = new MarkerAnchor(-1.25, 64.0, 0.75);

		assertEquals(-1.25, anchor.x());
		assertEquals(64.0, anchor.y());
		assertEquals(0.75, anchor.z());
	}

	@Test
	void rejectsNonFiniteCoordinates() {
		assertThrows(IllegalArgumentException.class,
			() -> new MarkerAnchor(Double.NaN, 0, 0));
		assertThrows(IllegalArgumentException.class,
			() -> new MarkerAnchor(0, Double.POSITIVE_INFINITY, 0));
		assertThrows(IllegalArgumentException.class,
			() -> new MarkerAnchor(0, 0, Double.NEGATIVE_INFINITY));
	}
}
