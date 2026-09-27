package nx.pingwheel.common.screen;

import nx.pingwheel.common.presentation.PresentationIds;
import nx.pingwheel.common.presentation.PresentationPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure decision model for the single ordinary client field control.
 *
 * <p>The local receive and display panels expose one truthful On/Hidden
 * control per field. Enabling adds exactly one exact allow selector; hiding
 * removes only the exact allow selector entries of that field id. The block
 * list, the whitelist-only setting, wildcard selectors, and every other
 * selector entry are never modified.
 *
 * <p>Client evaluation always passes the server-authorized default, so a field
 * can still be allowed by default settings or by a remaining group (wildcard)
 * allow rule after its exact allow rule is removed. The result reports that
 * outcome so the screen can explain a still-On control instead of pretending
 * the field was hidden.
 */
public final class PresentationClientFieldControl {

	public enum Result {
		/** The exact allow rule was added; the field is allowed. */
		ENABLED,
		/** The exact allow rule already exists; nothing changed. */
		ALREADY_ENABLED,
		/** The exact allow rule was removed; the field is now hidden. */
		REMOVED,
		/** The exact allow rule was removed; default settings still allow the field. */
		REMOVED_DEFAULT_STILL_ALLOWED,
		/** The exact allow rule was removed; a remaining allow selector still matches the field. */
		REMOVED_GROUP_STILL_ALLOWED,
		/** Hiding found no exact allow rule and the default still allows the field; nothing changed. */
		NO_EXACT_ALLOW,
		/** Hiding found no exact allow rule and a remaining allow selector still matches; nothing changed. */
		NO_EXACT_ALLOW_GROUP,
		/** The allow list is at capacity; nothing changed. */
		LIST_FULL,
		/** The supplied id is not a plain presentation field id; nothing changed. */
		INVALID_FIELD,
		/** No policy is known; nothing changed. */
		MISSING_POLICY
	}

	/**
	 * One control decision: the resulting allow list and whether the field is
	 * allowed under the candidate policy with the client's server-authorized
	 * default. {@code white} is the list the caller installs when
	 * {@link #changed()} is true; otherwise it is the unchanged current list.
	 */
	public record Decision(Result result, List<String> white, boolean effectiveAllowed) {
		public Decision {
			white = List.copyOf(white);
		}

		public boolean changed() {
			return result == Result.ENABLED
				|| result == Result.REMOVED
				|| result == Result.REMOVED_DEFAULT_STILL_ALLOWED
				|| result == Result.REMOVED_GROUP_STILL_ALLOWED;
		}
	}

	private PresentationClientFieldControl() {
	}

	/**
	 * Applies the desired enabled state to the allow list only.
	 *
	 * <p>{@code desiredEnabled} is the requested action, not the current
	 * membership: an allowed field is hidden and a hidden field is enabled.
	 */
	public static Decision apply(PresentationPolicy policy, String fieldId, boolean desiredEnabled) {
		if (policy == null) {
			return new Decision(Result.MISSING_POLICY, List.of(), false);
		}
		final List<String> current = policy.white();
		if (!isPlainFieldId(fieldId)) {
			return new Decision(Result.INVALID_FIELD, current, false);
		}

		if (desiredEnabled) {
			final var add = PresentationSelectorListModel.add(current, fieldId);
			return switch (add.result()) {
				case ADDED -> new Decision(Result.ENABLED, add.selectors(), true);
				case DUPLICATE -> new Decision(Result.ALREADY_ENABLED, current, true);
				case LIST_FULL -> new Decision(Result.LIST_FULL, current, allows(policy, fieldId));
				case EMPTY, INVALID -> new Decision(Result.INVALID_FIELD, current, allows(policy, fieldId));
			};
		}

		if (!current.contains(fieldId)) {
			final boolean allowed = allows(policy, fieldId);
			return new Decision(
				allowed && !policy.matchingSelectors(fieldId, true).isEmpty()
					? Result.NO_EXACT_ALLOW_GROUP
					: Result.NO_EXACT_ALLOW,
				current,
				allowed);
		}

		final List<String> candidate = removeAll(current, fieldId);
		final PresentationPolicy next;
		try {
			next = new PresentationPolicy(candidate, policy.black(), policy.whitelistOnly());
		} catch (RuntimeException failure) {
			return new Decision(Result.INVALID_FIELD, current, allows(policy, fieldId));
		}

		if (next.allows(fieldId, true)) {
			return new Decision(
				next.matchingSelectors(fieldId, true).isEmpty()
					? Result.REMOVED_DEFAULT_STILL_ALLOWED
					: Result.REMOVED_GROUP_STILL_ALLOWED,
				candidate,
				true);
		}
		return new Decision(Result.REMOVED, candidate, false);
	}

	/**
	 * The exact-id control accepts only a real field id, never a wildcard or a
	 * malformed selector; the buttons are driven by catalogue entries, so an
	 * invalid id is a defensive rejection rather than a normal outcome.
	 */
	public static boolean isPlainFieldId(String fieldId) {
		if (fieldId == null || fieldId.indexOf('*') >= 0) {
			return false;
		}
		try {
			PresentationIds.validate(fieldId);
			return true;
		} catch (RuntimeException failure) {
			return false;
		}
	}

	private static boolean allows(PresentationPolicy policy, String fieldId) {
		try {
			return policy.allows(fieldId, true);
		} catch (RuntimeException failure) {
			return false;
		}
	}

	/** Removes every exact occurrence while preserving the order of every other entry. */
	private static List<String> removeAll(List<String> selectors, String fieldId) {
		final List<String> next = new ArrayList<>(selectors.size());
		for (String selector : selectors) {
			if (!fieldId.equals(selector)) {
				next.add(selector);
			}
		}
		return List.copyOf(next);
	}
}
