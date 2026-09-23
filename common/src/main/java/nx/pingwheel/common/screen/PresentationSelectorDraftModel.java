package nx.pingwheel.common.screen;

import java.util.EnumMap;
import java.util.Map;

/**
 * Screen-owned editing state for the presentation selector inputs.
 *
 * <p>Every input slot keeps its own draft text and its own validation or
 * server feedback, so a page rebuild, a server state broadcast, a resize, or an
 * unrelated list edit can never erase text the player typed in another slot.
 * The model owns no Minecraft types; the settings screen restores each field
 * from {@link #draft(Slot)} and reports every keystroke through
 * {@link #setDraft(Slot, String)}, and focused tests drive the same sequence.
 *
 * <p>A server add is submitted through {@link #beginSubmission(Slot, String)}
 * before the request is sent, so a synchronous state notification can complete
 * it. {@link #completeSubmission(Slot, boolean)} clears the draft only for the
 * matching slot, only after an accepted operation, and only while the draft
 * still equals the submitted text; a later edit or a failed/denied/timed-out
 * operation always retains the text for correction.
 */
public final class PresentationSelectorDraftModel {

	/** The six stable presentation selector input slots. */
	public enum Slot {
		RECEIVE_WHITE,
		RECEIVE_BLACK,
		DISPLAY_WHITE,
		DISPLAY_BLACK,
		SERVER_WHITE,
		SERVER_BLACK;

		/** The stable slot of one local receive/display list. */
		public static Slot local(String panelKey, boolean white) {
			return switch (panelKey) {
				case "receive" -> white ? RECEIVE_WHITE : RECEIVE_BLACK;
				case "display" -> white ? DISPLAY_WHITE : DISPLAY_BLACK;
				default -> throw new IllegalArgumentException("unknown local presentation panel: " + panelKey);
			};
		}

		/** The stable slot of one server-managed list. */
		public static Slot server(boolean white) {
			return white ? SERVER_WHITE : SERVER_BLACK;
		}

		public boolean isServer() {
			return this == SERVER_WHITE || this == SERVER_BLACK;
		}

		public boolean isWhite() {
			return this == RECEIVE_WHITE || this == DISPLAY_WHITE || this == SERVER_WHITE;
		}
	}

	/** The compact per-slot feedback kinds the screen translates to text. */
	public enum Feedback {
		EMPTY,
		INVALID,
		DUPLICATE,
		LIST_FULL,
		NOT_FOUND,
		DENIED,
		ERROR,
		PENDING,
		TIMEOUT
	}

	/**
	 * One non-negative cursor/highlight pair. The order is preserved, so a
	 * backward selection (highlight before cursor) survives reconstruction
	 * exactly like a forward one.
	 */
	public record Caret(int cursor, int highlight) {
		public Caret {
			cursor = Math.max(0, cursor);
			highlight = Math.max(0, highlight);
		}

		public boolean hasSelection() {
			return cursor != highlight;
		}

		public int selectionStart() {
			return Math.min(cursor, highlight);
		}

		public int selectionEnd() {
			return Math.max(cursor, highlight);
		}
	}

	private final Map<Slot, String> drafts = new EnumMap<>(Slot.class);
	private final Map<Slot, Feedback> feedback = new EnumMap<>(Slot.class);
	private final Map<Slot, Caret> carets = new EnumMap<>(Slot.class);
	private Slot submittedSlot;
	private String submittedValue;

	/** The current draft text of the slot; an empty string when none was typed. */
	public String draft(Slot slot) {
		return slot == null ? "" : drafts.getOrDefault(slot, "");
	}

	/** Stores the raw field text; empty text clears the slot's draft. */
	public void setDraft(Slot slot, String text) {
		if (slot == null) {
			return;
		}
		if (text == null || text.isEmpty()) {
			drafts.remove(slot);
		} else {
			drafts.put(slot, text);
		}
	}

	public void clearDraft(Slot slot) {
		if (slot != null) {
			drafts.remove(slot);
		}
	}

	public void clearAllDrafts() {
		drafts.clear();
	}

	/** The slot's pending feedback kind, or null when there is none. */
	public Feedback feedback(Slot slot) {
		return slot == null ? null : feedback.get(slot);
	}

	public void setFeedback(Slot slot, Feedback value) {
		if (slot == null) {
			return;
		}
		if (value == null) {
			feedback.remove(slot);
		} else {
			feedback.put(slot, value);
		}
	}

	public void clearFeedback(Slot slot) {
		if (slot != null) {
			feedback.remove(slot);
		}
	}

	public void clearAllFeedback() {
		feedback.clear();
	}

	/** The slot's recorded cursor/highlight pair, or null when none was captured. */
	public Caret caret(Slot slot) {
		return slot == null ? null : carets.get(slot);
	}

	/** Stores the raw live cursor/highlight; negative values clamp to zero. */
	public void setCaret(Slot slot, int cursor, int highlight) {
		if (slot == null) {
			return;
		}
		carets.put(slot, new Caret(cursor, highlight));
	}

	/**
	 * The recorded caret clamped to {@code textLength}, or null when the slot
	 * has no recorded caret. Both ends clamp independently, so a successful
	 * add that cleared the text or any later shorter text can never position
	 * the caret past its end.
	 */
	public Caret caretFor(Slot slot, int textLength) {
		final Caret caret = caret(slot);
		if (caret == null) {
			return null;
		}
		final int limit = Math.max(0, textLength);
		return new Caret(Math.min(caret.cursor(), limit), Math.min(caret.highlight(), limit));
	}

	public void clearCaret(Slot slot) {
		if (slot != null) {
			carets.remove(slot);
		}
	}

	public void clearAllCarets() {
		carets.clear();
	}

	/**
	 * Records the submitted slot and the exact field text at submission time.
	 * Reports {@code false} while another submission is still outstanding, so
	 * only one command can ever be in flight.
	 */
	public boolean beginSubmission(Slot slot, String value) {
		if (slot == null || submittedSlot != null) {
			return false;
		}
		submittedSlot = slot;
		submittedValue = value == null ? "" : value;
		return true;
	}

	public boolean hasPendingSubmission() {
		return submittedSlot != null;
	}

	public Slot submittedSlot() {
		return submittedSlot;
	}

	public String submittedValue() {
		return submittedValue == null ? "" : submittedValue;
	}

	/**
	 * Completes the submission when it belongs to {@code slot}. An accepted
	 * submission clears the draft only while the draft still equals the text
	 * captured at submission time, so an edit made after the request is never
	 * discarded. Reports whether the draft was cleared.
	 */
	public boolean completeSubmission(Slot slot, boolean accepted) {
		if (slot == null || slot != submittedSlot) {
			return false;
		}

		final boolean clearedDraft = accepted && submittedValue.equals(draft(slot));
		if (clearedDraft) {
			clearDraft(slot);
		}
		submittedSlot = null;
		submittedValue = null;
		return clearedDraft;
	}

	/**
	 * Ends the submission without touching the draft, so a failed, denied, or
	 * timed-out add keeps its text for correction.
	 */
	public boolean abandonSubmission(Slot slot) {
		if (slot == null || slot != submittedSlot) {
			return false;
		}
		submittedSlot = null;
		submittedValue = null;
		return true;
	}

	/** Drops any outstanding submission while keeping every draft. */
	public void resetSubmission() {
		submittedSlot = null;
		submittedValue = null;
	}
}
