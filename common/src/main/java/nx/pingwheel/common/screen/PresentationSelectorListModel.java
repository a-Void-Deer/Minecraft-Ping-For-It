package nx.pingwheel.common.screen;

import nx.pingwheel.common.presentation.ServerPresentationPolicyService;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure editing rules for one presentation selector list.
 *
 * <p>The model mirrors the server-side mutation semantics exactly: selectors
 * are validated with the public presentation selector grammar through
 * {@link ServerPresentationPolicyService#isValidSelector(String)}, duplicates
 * are matched case-sensitively, and every list shares the server capacity cap.
 * It owns no Minecraft types, so focused tests can drive every outcome and the
 * settings screen can use the same rules for local client lists and for
 * pre-validating server mutations before a request is sent.
 */
public final class PresentationSelectorListModel {
	/** Matches the server rule-view selector capacity. */
	public static final int MAX_SELECTORS = ServerPresentationPolicyService.MAX_SELECTORS;
	/** Matches the selector grammar's character limit. */
	public static final int MAX_SELECTOR_LENGTH = ServerPresentationPolicyService.MAX_SELECTOR_LENGTH;

	public enum AddResult {
		ADDED,
		EMPTY,
		INVALID,
		DUPLICATE,
		LIST_FULL
	}

	public enum RemoveResult {
		REMOVED,
		INVALID,
		NOT_FOUND
	}

	/** The outcome of one add attempt and the resulting immutable list. */
	public record AddOutcome(AddResult result, List<String> selectors) {
		public boolean added() {
			return result == AddResult.ADDED;
		}
	}

	/** The outcome of one remove attempt and the resulting immutable list. */
	public record RemoveOutcome(RemoveResult result, List<String> selectors) {
		public boolean removed() {
			return result == RemoveResult.REMOVED;
		}
	}

	private PresentationSelectorListModel() {
	}

	/** Trims surrounding whitespace; the selector grammar itself allows none. */
	public static String normalize(String raw) {
		return raw == null ? "" : raw.trim();
	}

	/** Whether the trimmed text is a non-empty, grammar-valid selector. */
	public static boolean isValidSelector(String raw) {
		return ServerPresentationPolicyService.isValidSelector(normalize(raw));
	}

	/** Appends a validated selector, or reports the exact reason it was rejected. */
	public static AddOutcome add(List<String> selectors, String raw) {
		final List<String> current = copyOf(selectors);
		final String selector = normalize(raw);

		if (selector.isEmpty()) {
			return new AddOutcome(AddResult.EMPTY, current);
		}
		if (!ServerPresentationPolicyService.isValidSelector(selector)) {
			return new AddOutcome(AddResult.INVALID, current);
		}
		if (current.contains(selector)) {
			return new AddOutcome(AddResult.DUPLICATE, current);
		}
		if (current.size() >= MAX_SELECTORS) {
			return new AddOutcome(AddResult.LIST_FULL, current);
		}

		final List<String> next = new ArrayList<>(current);
		next.add(selector);
		return new AddOutcome(AddResult.ADDED, List.copyOf(next));
	}

	/** Removes an exact, case-sensitive selector, or reports the reason it was not removed. */
	public static RemoveOutcome remove(List<String> selectors, String raw) {
		final List<String> current = copyOf(selectors);
		final String selector = normalize(raw);

		if (!ServerPresentationPolicyService.isValidSelector(selector)) {
			return new RemoveOutcome(RemoveResult.INVALID, current);
		}
		if (!current.contains(selector)) {
			return new RemoveOutcome(RemoveResult.NOT_FOUND, current);
		}

		final List<String> next = new ArrayList<>(current);
		next.remove(selector);
		return new RemoveOutcome(RemoveResult.REMOVED, List.copyOf(next));
	}

	/** A defensive immutable copy; {@code null} is treated as an empty list. */
	public static List<String> copyOf(List<String> selectors) {
		return selectors == null ? List.of() : List.copyOf(selectors);
	}
}
