package nx.pingwheel.common.resolve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetMatcherRegistryTest {

	private static final TargetMatcher MATCHER = (target, context) -> true;

	@Test
	void lookupReturnsBoundMatcher() {
		TargetMatcherRegistry registry = TargetMatcherRegistry.builder()
			.bind("block", MATCHER)
			.build();

		assertSame(MATCHER, registry.find("block").orElseThrow());
	}

	@Test
	void lookupIsEmptyForUnboundId() {
		TargetMatcherRegistry registry = TargetMatcherRegistry.builder()
			.bind("block", MATCHER)
			.build();

		assertTrue(registry.find("entity").isEmpty());
	}

	@Test
	void rejectsDuplicateBinding() {
		assertThrows(IllegalArgumentException.class,
			() -> TargetMatcherRegistry.builder()
				.bind("block", MATCHER)
				.bind("block", (target, context) -> false));
	}

	@Test
	void rejectsNullId() {
		assertThrows(NullPointerException.class,
			() -> TargetMatcherRegistry.builder().bind(null, MATCHER));
	}

	@Test
	void rejectsBlankId() {
		assertThrows(IllegalArgumentException.class,
			() -> TargetMatcherRegistry.builder().bind(" ", MATCHER));
	}

	@Test
	void rejectsNullMatcher() {
		assertThrows(NullPointerException.class,
			() -> TargetMatcherRegistry.builder().bind("block", null));
	}

	@Test
	void lookupRejectsNullId() {
		TargetMatcherRegistry registry = TargetMatcherRegistry.builder().build();

		assertThrows(NullPointerException.class, () -> registry.find(null));
	}

	@Test
	void multipleBindingsAreIndependent() {
		TargetMatcher first = (target, context) -> true;
		TargetMatcher second = (target, context) -> false;

		TargetMatcherRegistry registry = TargetMatcherRegistry.builder()
			.bind("block", first)
			.bind("entity", second)
			.build();

		assertSame(first, registry.find("block").orElseThrow());
		assertSame(second, registry.find("entity").orElseThrow());
		assertTrue(registry.find("location").isEmpty());
	}
}
