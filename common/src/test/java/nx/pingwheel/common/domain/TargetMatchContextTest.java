package nx.pingwheel.common.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetMatchContextTest {

	@Test
	void noneHasEmptyClassification() {
		TargetMatchContext context = TargetMatchContext.none();

		assertTrue(context.entityTypeId().isEmpty());
		assertTrue(context.blockHasBlockEntity().isEmpty());
	}

	@Test
	void entityTypeCarriesTheExplicitId() {
		TargetMatchContext context = TargetMatchContext.entityType("minecraft:item");

		assertEquals("minecraft:item", context.entityTypeId().orElseThrow());
		assertTrue(context.blockHasBlockEntity().isEmpty());
	}

	@Test
	void blockEntityBlockCarriesTheExplicitClassification() {
		TargetMatchContext context = TargetMatchContext.blockEntityBlock(true);

		assertTrue(context.blockHasBlockEntity().orElseThrow());
		assertTrue(context.entityTypeId().isEmpty());

		assertFalse(TargetMatchContext.blockEntityBlock(false).blockHasBlockEntity().orElseThrow());
	}

	@Test
	void optionalMustNotBeNull() {
		assertThrows(NullPointerException.class, () -> new TargetMatchContext(null, Optional.empty()));
		assertThrows(NullPointerException.class, () -> new TargetMatchContext(Optional.empty(), null));
	}

	@Test
	void presentIdMustNotBeBlank() {
		assertThrows(IllegalArgumentException.class,
			() -> new TargetMatchContext(Optional.of(" "), Optional.empty()));
		assertThrows(IllegalArgumentException.class,
			() -> new TargetMatchContext(Optional.of(""), Optional.empty()));
	}

	@Test
	void emptyOptionalsAreAllowed() {
		TargetMatchContext context = new TargetMatchContext(Optional.empty(), Optional.empty());

		assertEquals(Optional.empty(), context.entityTypeId());
		assertEquals(Optional.empty(), context.blockHasBlockEntity());
	}

	@Test
	void contextIsValueBasedAndImmutable() {
		assertEquals(
			TargetMatchContext.entityType("minecraft:item"),
			TargetMatchContext.entityType("minecraft:item"));
		assertEquals(
			TargetMatchContext.entityType("minecraft:item").hashCode(),
			TargetMatchContext.entityType("minecraft:item").hashCode());
		assertEquals(
			TargetMatchContext.blockEntityBlock(true),
			TargetMatchContext.blockEntityBlock(true));
	}
}
