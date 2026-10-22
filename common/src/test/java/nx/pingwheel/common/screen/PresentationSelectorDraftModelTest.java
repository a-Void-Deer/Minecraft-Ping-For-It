package nx.pingwheel.common.screen;

import nx.pingwheel.common.screen.PresentationSelectorDraftModel.Caret;
import nx.pingwheel.common.screen.PresentationSelectorDraftModel.Feedback;
import nx.pingwheel.common.screen.PresentationSelectorDraftModel.Slot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationSelectorDraftModelTest {
	@Test void draftFeedbackAndCaretAreIndependentAcrossTargetTypes() {
		var model = new PresentationSelectorDraftModel();
		Slot entityAllow = Slot.server("entity", true);
		Slot itemAllow = Slot.server("dropped_item", true);
		Slot itemDeny = Slot.server("dropped_item", false);
		model.setDraft(entityAllow, "minecraft:entity.health");
		model.setDraft(itemAllow, "minecraft:item.id");
		model.setDraft(itemDeny, "minecraft:item.count");
		model.setFeedback(itemAllow, Feedback.DUPLICATE);
		model.setCaret(entityAllow, 7, 3);
		model.setCaret(itemAllow, 12, 12);
		assertEquals("minecraft:entity.health", model.draft(entityAllow));
		assertEquals("minecraft:item.id", model.draft(itemAllow));
		assertEquals("minecraft:item.count", model.draft(itemDeny));
		assertNull(model.feedback(entityAllow));
		assertEquals(Feedback.DUPLICATE, model.feedback(itemAllow));
		assertEquals(new Caret(7, 3), model.caretFor(entityAllow, 20));
		assertEquals(new Caret(4, 4), model.caretFor(itemAllow, 4));
		assertThrows(IllegalArgumentException.class, () -> Slot.server("unknown", true));
	}

	@Test void oneGlobalPendingSubmissionClearsOnlyUnchangedSubmittedDraft() {
		var model = new PresentationSelectorDraftModel();
		Slot entity = Slot.server("entity", true), block = Slot.server("block", true);
		model.setDraft(entity, "a:b");
		model.setDraft(block, "c:d");
		assertTrue(model.beginSubmission(entity, "a:b"));
		assertFalse(model.beginSubmission(block, "c:d"));
		assertFalse(model.completeSubmission(Slot.server("block", true), true),
			"a different target type cannot complete the pending submission");
		assertTrue(model.hasPendingSubmission());
		model.setDraft(entity, "e:f");
		assertFalse(model.completeSubmission(Slot.server("entity", true), true),
			"a later edit of the submitted draft must not be discarded");
		assertFalse(model.hasPendingSubmission(), "a matching response completes the operation despite a later edit");
		assertEquals("e:f", model.draft(entity));
		assertEquals("c:d", model.draft(block));
		assertTrue(model.beginSubmission(block, "c:d"));
		assertTrue(model.completeSubmission(Slot.server("block", true), true),
			"a rebuilt page reconstructs an equal slot rather than reusing the original instance");
		assertEquals("", model.draft(block));
		assertEquals("e:f", model.draft(entity));
	}

	@Test void failedOrTimedOutSubmissionRetainsDraftAndAllowsAnotherSubmission() {
		var model = new PresentationSelectorDraftModel();
		Slot entityAllow = Slot.server("entity", true);
		Slot entityDeny = Slot.server("entity", false);
		Slot itemAllow = Slot.server("dropped_item", true);
		model.setDraft(entityAllow, "a:b");
		model.setDraft(itemAllow, "c:d");
		assertTrue(model.beginSubmission(entityAllow, "a:b"));
		assertFalse(model.abandonSubmission(entityDeny));
		assertFalse(model.abandonSubmission(itemAllow));
		assertTrue(model.hasPendingSubmission(), "a different list or target cannot end the in-flight edit");
		assertTrue(model.abandonSubmission(Slot.server("entity", true)),
			"the reconstructed slot must end an unsuccessful submission");
		assertFalse(model.hasPendingSubmission());
		assertEquals("a:b", model.draft(entityAllow));
		assertEquals("c:d", model.draft(itemAllow));
		assertTrue(model.beginSubmission(itemAllow, "c:d"));
		assertTrue(model.completeSubmission(Slot.server("dropped_item", true), true));
		assertEquals("", model.draft(itemAllow));
		assertEquals("a:b", model.draft(entityAllow));
		assertTrue(model.beginSubmission(entityAllow, "a:b"));
		assertFalse(model.completeSubmission(Slot.server("entity", true), false),
			"an unsuccessful response does not clear the submitted draft");
		assertFalse(model.hasPendingSubmission());
		assertEquals("a:b", model.draft(entityAllow));
		assertTrue(model.beginSubmission(itemAllow, "e:f"),
			"a failed submission releases the single global pending slot");
	}
}
