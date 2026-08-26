package nx.pingwheel.common.integration.sable.server;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableExternalBlockLocatorTest {

	private static final UUID SUB_LEVEL = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

	@Test
	void canonicalLocatorRoundTripsSubLevelAndLocalPosition() {
		SableExternalBlockLocator locator = new SableExternalBlockLocator(SUB_LEVEL, -12, 64, 300);

		assertEquals(
			locator,
			SableExternalBlockLocator.parse(locator.encode()).orElseThrow());
	}

	@Test
	void malformedOrUnboundedLocatorsFailSoft() {
		for (String value : new String[] {
			"",
			"not-a-locator",
			SUB_LEVEL + "/1,2",
			SUB_LEVEL + "/1,2,3,4",
			SUB_LEVEL + "/1,2,",
			SUB_LEVEL + "/1,+2,3",
			SUB_LEVEL + "/30000001,2,3",
			" " + SUB_LEVEL + "/1,2,3",
			"Aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/1,2,3"
		}) {
			assertTrue(SableExternalBlockLocator.parse(value).isEmpty(), value);
		}
	}
}
