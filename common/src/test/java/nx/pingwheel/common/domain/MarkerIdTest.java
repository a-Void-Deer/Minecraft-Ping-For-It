package nx.pingwheel.common.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerIdTest {

	@Test
	void largerNumericIdSortsLater() {
		MarkerId small = new MarkerId(1L);
		MarkerId large = new MarkerId(2L);

		assertTrue(small.compareTo(large) < 0);
		assertTrue(large.compareTo(small) > 0);
		assertEquals(0, small.compareTo(new MarkerId(1L)));
	}

	@Test
	void zeroIsValidAndLowest() {
		MarkerId zero = new MarkerId(0L);

		assertEquals(0L, zero.value());
		assertTrue(zero.compareTo(new MarkerId(1L)) < 0);
	}

	@Test
	void rejectsNegativeIds() {
		assertThrows(IllegalArgumentException.class, () -> new MarkerId(-1L));
	}

	@Test
	void equalsAndHashCodeAreValueBased() {
		assertEquals(new MarkerId(5L), new MarkerId(5L));
		assertEquals(new MarkerId(5L).hashCode(), new MarkerId(5L).hashCode());
		assertNotEquals(new MarkerId(5L), new MarkerId(6L));
	}

	@Test
	void compareToAndEqualsAreConsistent() {
		assertEquals(0, new MarkerId(7L).compareTo(new MarkerId(7L)));
		assertEquals(new MarkerId(7L), new MarkerId(7L));

		assertTrue(new MarkerId(3L).compareTo(new MarkerId(9L)) < 0);
		assertNotEquals(new MarkerId(3L), new MarkerId(9L));

		assertTrue(new MarkerId(9L).compareTo(new MarkerId(3L)) > 0);
		assertNotEquals(new MarkerId(9L), new MarkerId(3L));
	}
}
