package nx.pingwheel.common.interaction.wheel;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelSelectionTest {

	@Test
	void noneAndCenterAreSingletons() {
		assertSame(WheelSelection.NONE, WheelSelection.None.INSTANCE);
		assertSame(WheelSelection.CENTER, WheelSelection.Center.INSTANCE);
	}

	@Test
	void sectorCarriesPingType() {
		PingType attention = pingType("attention");

		WheelSelection selection = WheelSelection.sector(attention);

		assertTrue(selection instanceof WheelSelection.Sector);
		assertEquals(attention, ((WheelSelection.Sector) selection).pingType());
	}

	@Test
	void sectorRejectsNullPingType() {
		assertThrows(NullPointerException.class, () -> WheelSelection.sector(null));
		assertThrows(NullPointerException.class, () -> new WheelSelection.Sector(null));
	}

	@Test
	void sectorEqualityIsValueBased() {
		PingType attention = pingType("attention");

		assertEquals(WheelSelection.sector(attention), WheelSelection.sector(attention));
		assertNotEquals(WheelSelection.sector(attention), WheelSelection.sector(pingType("danger")));
	}

	@Test
	void distinctKindsAreNotEqual() {
		assertNotEquals(WheelSelection.NONE, WheelSelection.CENTER);
		assertNotEquals(WheelSelection.NONE, WheelSelection.sector(pingType("attention")));
		assertNotEquals(WheelSelection.CENTER, WheelSelection.sector(pingType("attention")));
	}

	private static PingType pingType(String id) {
		return PingTypeCatalog.builtIn().findById(id).orElseThrow();
	}
}
