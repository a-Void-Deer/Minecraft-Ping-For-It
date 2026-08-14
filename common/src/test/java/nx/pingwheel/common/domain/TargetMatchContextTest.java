package nx.pingwheel.common.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetMatchContextTest {

	@Test
	void noneHasEmptyEntityTypeId() {
		TargetMatchContext context = TargetMatchContext.none();

		assertTrue(context.entityTypeId().isEmpty());
	}

	@Test
	void entityTypeCarriesTheExplicitId() {
		TargetMatchContext context = TargetMatchContext.entityType("minecraft:item");

		assertEquals("minecraft:item", context.entityTypeId().orElseThrow());
	}

	@Test
	void optionalMustNotBeNull() {
		assertThrows(NullPointerException.class, () -> new TargetMatchContext(null));
	}

	@Test
	void presentIdMustNotBeBlank() {
		assertThrows(IllegalArgumentException.class,
			() -> new TargetMatchContext(Optional.of(" ")));
		assertThrows(IllegalArgumentException.class,
			() -> new TargetMatchContext(Optional.of("")));
	}

	@Test
	void emptyOptionalIsAllowed() {
		assertEquals(Optional.empty(), new TargetMatchContext(Optional.empty()).entityTypeId());
	}

	@Test
	void contextIsValueBasedAndImmutable() {
		TargetMatchContext a = TargetMatchContext.entityType("minecraft:item");
		TargetMatchContext b = TargetMatchContext.entityType("minecraft:item");

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
	}
}
