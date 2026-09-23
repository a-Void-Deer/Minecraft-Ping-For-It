package nx.pingwheel.common.screen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationSelectorListModelTest {
	private static final String MAX_LENGTH_SELECTOR = "a:" + "b".repeat(
		PresentationSelectorListModel.MAX_SELECTOR_LENGTH - 2);

	@Test
	void addAcceptsGrammarValidSelectorsAndNormalizesSurroundingWhitespace() {
		var outcome = PresentationSelectorListModel.add(List.of(), "  minecraft:stone  ");

		assertTrue(outcome.added());
		assertEquals(List.of("minecraft:stone"), outcome.selectors());
		assertTrue(PresentationSelectorListModel.isValidSelector("minecraft:*"));
		assertTrue(PresentationSelectorListModel.isValidSelector("*:*"));
		assertTrue(PresentationSelectorListModel.isValidSelector("some_mod:block/path"));
	}

	@Test
	void addReportsEmptyInputDistinctlyFromInvalidGrammar() {
		assertEquals(PresentationSelectorListModel.AddResult.EMPTY,
			PresentationSelectorListModel.add(List.of(), null).result());
		assertEquals(PresentationSelectorListModel.AddResult.EMPTY,
			PresentationSelectorListModel.add(List.of(), "   ").result());

		for (String invalid : List.of(
			"no_colon",
			":path",
			"namespace:",
			"namespace:path:extra",
			"namespace:UPPER",
			"namespace:path space")) {
			assertEquals(PresentationSelectorListModel.AddResult.INVALID,
				PresentationSelectorListModel.add(List.of(), invalid).result(),
				() -> "must reject invalid selector: " + invalid);
		}
	}

	@Test
	void addEnforcesTheSharedSelectorLengthCap() {
		assertEquals(PresentationSelectorListModel.MAX_SELECTOR_LENGTH, MAX_LENGTH_SELECTOR.length());
		assertTrue(PresentationSelectorListModel.add(List.of(), MAX_LENGTH_SELECTOR).added());
		assertEquals(PresentationSelectorListModel.AddResult.INVALID,
			PresentationSelectorListModel.add(List.of(), MAX_LENGTH_SELECTOR + "c").result());
	}

	@Test
	void addRejectsExactDuplicatesCaseSensitively() {
		var outcome = PresentationSelectorListModel.add(List.of("namespace:path"), "namespace:path");

		assertEquals(PresentationSelectorListModel.AddResult.DUPLICATE, outcome.result());
		assertEquals(List.of("namespace:path"), outcome.selectors());

		assertTrue(PresentationSelectorListModel.add(List.of("namespace:path"), "namespace:path*").added());
	}

	@Test
	void addReportsListFullAtTheServerCapacityAndLeavesTheInputListUntouched() {
		var full = new ArrayList<String>();
		for (int index = 0; index < PresentationSelectorListModel.MAX_SELECTORS; index++) {
			full.add("namespace:path" + index);
		}

		var outcome = PresentationSelectorListModel.add(full, "namespace:extra");

		assertEquals(PresentationSelectorListModel.AddResult.LIST_FULL, outcome.result());
		assertEquals(PresentationSelectorListModel.MAX_SELECTORS, full.size());
		assertFalse(outcome.added());
	}

	@Test
	void addAndRemoveReturnImmutableCopiesWithoutMutatingTheSourceList() {
		var source = new ArrayList<>(List.of("namespace:one"));

		var added = PresentationSelectorListModel.add(source, "namespace:two");
		assertTrue(added.added());
		assertEquals(List.of("namespace:one"), source);
		assertEquals(List.of("namespace:one", "namespace:two"), added.selectors());
		assertThrows(UnsupportedOperationException.class, () -> added.selectors().add("namespace:three"));

		var removed = PresentationSelectorListModel.remove(added.selectors(), "namespace:one");
		assertTrue(removed.removed());
		assertEquals(List.of("namespace:one", "namespace:two"), added.selectors());
		assertEquals(List.of("namespace:two"), removed.selectors());
		assertThrows(UnsupportedOperationException.class, () -> removed.selectors().add("namespace:four"));
	}

	@Test
	void removeReportsNotFoundAndInvalidDistinctly() {
		assertEquals(PresentationSelectorListModel.RemoveResult.NOT_FOUND,
			PresentationSelectorListModel.remove(List.of("namespace:one"), "namespace:two").result());
		assertEquals(PresentationSelectorListModel.RemoveResult.INVALID,
			PresentationSelectorListModel.remove(List.of("namespace:one"), "not a selector").result());
		assertEquals(PresentationSelectorListModel.RemoveResult.INVALID,
			PresentationSelectorListModel.remove(List.of("namespace:one"), "").result());
	}

	@Test
	void copyOfTreatsNullAsEmptyAndAlwaysReturnsAnImmutableCopy() {
		var copied = PresentationSelectorListModel.copyOf(null);

		assertTrue(copied.isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> copied.add("namespace:path"));
	}
}
