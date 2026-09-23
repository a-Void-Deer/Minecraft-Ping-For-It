package nx.pingwheel.common.screen;

import nx.pingwheel.common.screen.PresentationSelectorDraftModel.Caret;
import nx.pingwheel.common.screen.PresentationSelectorDraftModel.Feedback;
import nx.pingwheel.common.screen.PresentationSelectorDraftModel.Slot;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationSelectorDraftModelTest {

	@Test
	void everyInputSlotKeepsItsOwnDraftAcrossUnrelatedEdits() {
		var model = new PresentationSelectorDraftModel();

		for (Slot slot : Slot.values()) {
			model.setDraft(slot, "namespace:" + slot.name().toLowerCase(Locale.ROOT));
		}
		for (Slot slot : Slot.values()) {
			assertEquals("namespace:" + slot.name().toLowerCase(Locale.ROOT), model.draft(slot));
		}

		// A local edit in one slot, an unrelated feedback update, and a
		// submission elsewhere must not disturb any other slot's draft.
		model.setDraft(Slot.DISPLAY_BLACK, "minecraft:stone");
		model.setFeedback(Slot.SERVER_WHITE, Feedback.DUPLICATE);
		model.setDraft(Slot.SERVER_BLACK, "minecraft:dirt");
		model.clearFeedback(Slot.SERVER_WHITE);

		assertEquals("minecraft:stone", model.draft(Slot.DISPLAY_BLACK));
		assertEquals("minecraft:dirt", model.draft(Slot.SERVER_BLACK));
		assertEquals("namespace:receive_white", model.draft(Slot.RECEIVE_WHITE));

		model.clearDraft(Slot.DISPLAY_BLACK);
		assertEquals("", model.draft(Slot.DISPLAY_BLACK));
		assertEquals("minecraft:dirt", model.draft(Slot.SERVER_BLACK));
	}

	@Test
	void slotsAreStablePerPanelAndList() {
		assertEquals(Slot.RECEIVE_WHITE, Slot.local("receive", true));
		assertEquals(Slot.RECEIVE_BLACK, Slot.local("receive", false));
		assertEquals(Slot.DISPLAY_WHITE, Slot.local("display", true));
		assertEquals(Slot.DISPLAY_BLACK, Slot.local("display", false));
		assertEquals(Slot.SERVER_WHITE, Slot.server(true));
		assertEquals(Slot.SERVER_BLACK, Slot.server(false));

		assertTrue(Slot.SERVER_WHITE.isServer());
		assertFalse(Slot.RECEIVE_WHITE.isServer());
		assertTrue(Slot.DISPLAY_WHITE.isWhite());
		assertFalse(Slot.SERVER_BLACK.isWhite());
		assertThrows(IllegalArgumentException.class, () -> Slot.local("unknown", true));
	}

	@Test
	void feedbackIsTrackedIndependentlyPerSlot() {
		var model = new PresentationSelectorDraftModel();

		model.setFeedback(Slot.SERVER_WHITE, Feedback.DUPLICATE);
		model.setFeedback(Slot.DISPLAY_BLACK, Feedback.EMPTY);

		assertEquals(Feedback.DUPLICATE, model.feedback(Slot.SERVER_WHITE));
		assertEquals(Feedback.EMPTY, model.feedback(Slot.DISPLAY_BLACK));
		assertNull(model.feedback(Slot.SERVER_BLACK));

		model.clearFeedback(Slot.SERVER_WHITE);
		assertNull(model.feedback(Slot.SERVER_WHITE));
		assertEquals(Feedback.EMPTY, model.feedback(Slot.DISPLAY_BLACK));
	}

	@Test
	void onlyOneSubmissionIsOutstandingAtATime() {
		var model = new PresentationSelectorDraftModel();
		model.setDraft(Slot.SERVER_WHITE, "a:b");

		assertTrue(model.beginSubmission(Slot.SERVER_WHITE, "a:b"));
		assertFalse(model.beginSubmission(Slot.SERVER_BLACK, "c:d"));
		assertTrue(model.hasPendingSubmission());
		assertEquals(Slot.SERVER_WHITE, model.submittedSlot());
		assertEquals("a:b", model.submittedValue());
	}

	@Test
	void acceptedSubmissionClearsTheUnchangedDraftOnly() {
		var model = new PresentationSelectorDraftModel();
		model.setDraft(Slot.SERVER_WHITE, " a:b ");
		assertTrue(model.beginSubmission(Slot.SERVER_WHITE, " a:b "));

		assertTrue(model.completeSubmission(Slot.SERVER_WHITE, true));

		assertEquals("", model.draft(Slot.SERVER_WHITE));
		assertFalse(model.hasPendingSubmission());
	}

	@Test
	void editAfterSubmissionIsNeverDiscardedByALateSuccess() {
		var model = new PresentationSelectorDraftModel();
		model.setDraft(Slot.SERVER_WHITE, "a:b");
		assertTrue(model.beginSubmission(Slot.SERVER_WHITE, "a:b"));
		model.setDraft(Slot.SERVER_WHITE, "c:d");

		assertFalse(model.completeSubmission(Slot.SERVER_WHITE, true));
		assertEquals("c:d", model.draft(Slot.SERVER_WHITE));
		assertFalse(model.hasPendingSubmission());
	}

	@Test
	void failedSubmissionRetainsTheDraftForCorrection() {
		var model = new PresentationSelectorDraftModel();
		model.setDraft(Slot.SERVER_BLACK, "a:b");
		assertTrue(model.beginSubmission(Slot.SERVER_BLACK, "a:b"));

		assertTrue(model.abandonSubmission(Slot.SERVER_BLACK));
		model.setFeedback(Slot.SERVER_BLACK, Feedback.DENIED);

		assertEquals("a:b", model.draft(Slot.SERVER_BLACK));
		assertEquals(Feedback.DENIED, model.feedback(Slot.SERVER_BLACK));
		assertFalse(model.hasPendingSubmission());
	}

	@Test
	void submissionCompletionForAnotherSlotIsIgnored() {
		var model = new PresentationSelectorDraftModel();
		model.setDraft(Slot.SERVER_WHITE, "a:b");
		assertTrue(model.beginSubmission(Slot.SERVER_WHITE, "a:b"));

		assertFalse(model.completeSubmission(Slot.SERVER_BLACK, true));
		assertTrue(model.hasPendingSubmission());
		assertEquals("a:b", model.draft(Slot.SERVER_WHITE));
	}

	@Test
	void resetSubmissionKeepsEveryDraft() {
		var model = new PresentationSelectorDraftModel();
		model.setDraft(Slot.RECEIVE_WHITE, "a:b");
		model.setDraft(Slot.SERVER_WHITE, "c:d");
		assertTrue(model.beginSubmission(Slot.SERVER_WHITE, "c:d"));

		model.resetSubmission();

		assertFalse(model.hasPendingSubmission());
		assertEquals("a:b", model.draft(Slot.RECEIVE_WHITE));
		assertEquals("c:d", model.draft(Slot.SERVER_WHITE));
	}

	@Test
	void caretPreservesForwardAndBackwardSelections() {
		var model = new PresentationSelectorDraftModel();
		model.setDraft(Slot.SERVER_WHITE, "namespace:path");

		model.setCaret(Slot.SERVER_WHITE, 2, 7);
		assertEquals(new Caret(2, 7), model.caretFor(Slot.SERVER_WHITE, 14));
		assertTrue(model.caretFor(Slot.SERVER_WHITE, 14).hasSelection());
		assertEquals(2, model.caretFor(Slot.SERVER_WHITE, 14).selectionStart());
		assertEquals(7, model.caretFor(Slot.SERVER_WHITE, 14).selectionEnd());

		// A backward selection keeps its direction instead of being normalized.
		model.setCaret(Slot.SERVER_WHITE, 9, 3);
		assertEquals(new Caret(9, 3), model.caretFor(Slot.SERVER_WHITE, 14));
		assertEquals(3, model.caretFor(Slot.SERVER_WHITE, 14).selectionStart());
		assertEquals(9, model.caretFor(Slot.SERVER_WHITE, 14).selectionEnd());
	}

	@Test
	void caretClampsAfterShorterOrClearedText() {
		var model = new PresentationSelectorDraftModel();
		model.setDraft(Slot.SERVER_BLACK, "namespace:path");
		model.setCaret(Slot.SERVER_BLACK, 12, 14);
		assertEquals(new Caret(12, 14), model.caretFor(Slot.SERVER_BLACK, 14));

		// The page rebuilds with the shorter text after a later edit.
		model.setDraft(Slot.SERVER_BLACK, "a:b");
		assertEquals(new Caret(3, 3), model.caretFor(Slot.SERVER_BLACK, 3));

		// A successful add clears the text; the recorded caret clamps to zero.
		assertTrue(model.beginSubmission(Slot.SERVER_BLACK, "a:b"));
		assertTrue(model.completeSubmission(Slot.SERVER_BLACK, true));
		assertEquals("", model.draft(Slot.SERVER_BLACK));
		assertEquals(new Caret(0, 0), model.caretFor(Slot.SERVER_BLACK, 0));
	}

	@Test
	void caretsAreIndependentPerSlotAndSurviveReconstruction() {
		var model = new PresentationSelectorDraftModel();
		model.setDraft(Slot.RECEIVE_WHITE, "minecraft:stone");
		model.setCaret(Slot.RECEIVE_WHITE, 4, 4);
		model.setDraft(Slot.SERVER_WHITE, "minecraft:dirt");
		model.setCaret(Slot.SERVER_WHITE, 5, 9);

		// Re-reading during a rebuild neither mutates nor reorders the ranges.
		assertEquals("minecraft:stone", model.draft(Slot.RECEIVE_WHITE));
		assertEquals(new Caret(4, 4), model.caretFor(Slot.RECEIVE_WHITE, 15));
		assertEquals(new Caret(5, 9), model.caretFor(Slot.SERVER_WHITE, 14));
		assertNull(model.caretFor(Slot.RECEIVE_BLACK, 15));

		model.clearCaret(Slot.RECEIVE_WHITE);
		assertNull(model.caretFor(Slot.RECEIVE_WHITE, 15));
		assertEquals(new Caret(5, 9), model.caretFor(Slot.SERVER_WHITE, 14));
	}

	@Test
	void negativeCaretValuesClampToZero() {
		var model = new PresentationSelectorDraftModel();
		model.setCaret(Slot.DISPLAY_WHITE, -3, -1);
		assertEquals(new Caret(0, 0), model.caretFor(Slot.DISPLAY_WHITE, 10));
	}
}
