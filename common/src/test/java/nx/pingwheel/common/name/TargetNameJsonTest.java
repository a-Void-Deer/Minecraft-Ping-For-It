package nx.pingwheel.common.name;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertDoesNotThrow;
import static nx.pingwheel.common.testing.Assertions.assertEquals;
import static nx.pingwheel.common.testing.Assertions.assertNotEquals;
import static nx.pingwheel.common.testing.Assertions.assertThrows;

class TargetNameJsonTest {

	@Test
	void acceptsNonBlankJson() {
		TargetNameJson name = new TargetNameJson("{\"translate\":\"pingforit.target.unknown\"}");

		assertEquals("{\"translate\":\"pingforit.target.unknown\"}", name.value());
	}

	@Test
	void preservesUnicode() {
		String json = "{\"text\":\"僵尸\\u00df\"}";

		assertEquals(json, new TargetNameJson(json).value());
	}

	@Test
	void rejectsNull() {
		assertThrows(NullPointerException.class, () -> new TargetNameJson(null));
	}

	@Test
	void rejectsBlank() {
		assertThrows(IllegalArgumentException.class, () -> new TargetNameJson(""));
		assertThrows(IllegalArgumentException.class, () -> new TargetNameJson("   "));
	}

	@Test
	void acceptsExactlyMaxLength() {
		// {"text":" ... "} wrapper is exactly 11 characters.
		String json = "{\"text\":\"" + "x".repeat(TargetNameJson.MAX_LENGTH - 11) + "\"}";

		assertDoesNotThrow(() -> new TargetNameJson(json));
	}

	@Test
	void rejectsOneOverMaxLength() {
		String json = "x".repeat(TargetNameJson.MAX_LENGTH + 1);

		assertThrows(IllegalArgumentException.class, () -> new TargetNameJson(json));
	}

	@Test
	void equalsAndHashCodeFollowValue() {
		TargetNameJson a = new TargetNameJson("{\"translate\":\"a\"}");
		TargetNameJson b = new TargetNameJson("{\"translate\":\"a\"}");
		TargetNameJson c = new TargetNameJson("{\"translate\":\"b\"}");

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, c);
	}
}
