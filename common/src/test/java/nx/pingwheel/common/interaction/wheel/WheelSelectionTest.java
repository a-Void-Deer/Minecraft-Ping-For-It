package nx.pingwheel.common.interaction.wheel;

import nx.pingwheel.common.testing.Test;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;

import static nx.pingwheel.common.testing.Assertions.assertEquals;
import static nx.pingwheel.common.testing.Assertions.assertNotEquals;
import static nx.pingwheel.common.testing.Assertions.assertSame;
import static nx.pingwheel.common.testing.Assertions.assertThrows;
import static nx.pingwheel.common.testing.Assertions.assertTrue;

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
