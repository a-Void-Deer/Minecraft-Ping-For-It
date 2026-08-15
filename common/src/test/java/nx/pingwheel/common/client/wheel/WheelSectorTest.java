package nx.pingwheel.common.client.wheel;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WheelSectorTest {

	private static final double TWO_PI = Math.PI * 2.0;

	@Test
	void exposesIndexPingTypeAndAngles() {
		PingType pingType = pingType("attention");
		WheelSector sector = new WheelSector(3, pingType, 1.0, 2.0);

		assertEquals(3, sector.index());
		assertEquals(pingType, sector.pingType());
		assertEquals(1.0, sector.startAngleRadians());
		assertEquals(2.0, sector.endAngleRadians());
	}

	@Test
	void outlineColorDelegatesToPingType() {
		PingType pingType = pingType("danger");
		WheelSector sector = new WheelSector(0, pingType, 0.0, 1.0);

		assertEquals(pingType.outlineColor(), sector.outlineColor());
	}

	@Test
	void singleSectorMaySpanFullRing() {
		PingType pingType = pingType("attention");
		WheelSector sector = new WheelSector(0, pingType, 0.0, TWO_PI);

		assertEquals(0.0, sector.startAngleRadians());
		assertEquals(TWO_PI, sector.endAngleRadians());
	}

	@Test
	void rejectsNegativeIndex() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new WheelSector(-1, pingType("attention"), 0.0, 1.0));
	}

	@Test
	void rejectsNullPingType() {
		assertThrows(NullPointerException.class, () -> new WheelSector(0, null, 0.0, 1.0));
	}

	@Test
	void rejectsNonFiniteAngles() {
		PingType pingType = pingType("attention");

		assertThrows(IllegalArgumentException.class, () -> new WheelSector(0, pingType, Double.NaN, 1.0));
		assertThrows(IllegalArgumentException.class, () -> new WheelSector(0, pingType, 0.0, Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> new WheelSector(0, pingType, Double.POSITIVE_INFINITY, 1.0));
		assertThrows(IllegalArgumentException.class, () -> new WheelSector(0, pingType, 0.0, Double.NEGATIVE_INFINITY));
	}

	@Test
	void rejectsStartOutsideHalfOpenRange() {
		PingType pingType = pingType("attention");

		assertThrows(IllegalArgumentException.class, () -> new WheelSector(0, pingType, -0.001, 1.0));
		assertThrows(IllegalArgumentException.class, () -> new WheelSector(0, pingType, TWO_PI, TWO_PI));
	}

	@Test
	void rejectsEndNotAfterStart() {
		PingType pingType = pingType("attention");

		assertThrows(IllegalArgumentException.class, () -> new WheelSector(0, pingType, 1.0, 1.0));
		assertThrows(IllegalArgumentException.class, () -> new WheelSector(0, pingType, 2.0, 1.0));
	}

	@Test
	void rejectsEndBeyondFullRing() {
		PingType pingType = pingType("attention");

		assertThrows(
			IllegalArgumentException.class,
			() -> new WheelSector(0, pingType, 0.0, Math.nextUp(TWO_PI)));
	}

	@Test
	void equalityIsValueBased() {
		PingType pingType = pingType("attention");

		assertEquals(
			new WheelSector(1, pingType, 0.5, 1.5),
			new WheelSector(1, pingType, 0.5, 1.5));
		assertNotEquals(
			new WheelSector(1, pingType, 0.5, 1.5),
			new WheelSector(2, pingType, 0.5, 1.5));
		assertNotEquals(
			new WheelSector(1, pingType, 0.5, 1.5),
			new WheelSector(1, pingType("danger"), 0.5, 1.5));
		assertNotEquals(
			new WheelSector(1, pingType, 0.5, 1.5),
			new WheelSector(1, pingType, 0.5, 1.75));
	}

	private static PingType pingType(String id) {
		return PingTypeCatalog.builtIn().findById(id).orElseThrow();
	}
}
