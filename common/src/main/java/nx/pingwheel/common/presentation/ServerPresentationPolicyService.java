package nx.pingwheel.common.presentation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure authority and validation seam for the versioned server presentation
 * policy rule view. A read discloses the complete per-target-type rule view; a
 * mutation selects exactly one existing target type and changes only that
 * type's allow/deny/whitelist-only lists, preserving that type's persisted child
 * deny list. The candidate is fully validated before any persisted value
 * changes, so a malformed, duplicate, missing, over-capacity, or no-op request
 * leaves the stored policy untouched. Permission is supplied by the
 * authenticated server-side caller; the service never treats a client flag as
 * authority.
 *
 * <p>{@link RulesView} carries only the selector fields; the child deny list is
 * not part of this route's current wire version, so extending the disclosed
 * view and its packet is a protocol update that must be versioned rather than
 * emitted on the existing route.
 */
public final class ServerPresentationPolicyService {
	private ServerPresentationPolicyService() {}

	/** Matches {@link PresentationPolicy} and {@link PresentationSettings} selector capacity. */
	public static final int MAX_SELECTORS = 128;
	/** Matches {@link PresentationPolicy}'s selector character limit. */
	public static final int MAX_SELECTOR_LENGTH = 193;

	public enum Operation {
		READ(false),
		ADD_WHITE(true),
		REMOVE_WHITE(true),
		ADD_BLACK(true),
		REMOVE_BLACK(true),
		SET_WHITELIST_ONLY(false);

		private final boolean requiresSelector;

		Operation(boolean requiresSelector) {
			this.requiresSelector = requiresSelector;
		}

		public boolean requiresSelector() {
			return requiresSelector;
		}
	}

	public enum Status {
		OK,
		DENIED,
		INVALID,
		DUPLICATE,
		NOT_FOUND,
		LIST_FULL,
		/** The candidate was valid but could not be persisted; the rule view was not changed. */
		FAILED
	}

	/** One target type's disclosed rule view; construction validates the selector grammar. */
	public record RulesView(List<String> white, List<String> black, boolean whitelistOnly) {
		public RulesView {
			white = white == null ? List.of() : List.copyOf(white);
			black = black == null ? List.of() : List.copyOf(black);
			new PresentationPolicy(white, black, whitelistOnly);
		}

		public static RulesView of(PresentationPolicy policy) {
			return new RulesView(policy.white(), policy.black(), policy.whitelistOnly());
		}

		public static RulesView denyAll() {
			return new RulesView(List.of(), List.of("*:*"), true);
		}

		public PresentationPolicy policy() {
			return new PresentationPolicy(white, black, whitelistOnly);
		}
	}

	public record Result(boolean applied, Status status, RulesView rules) {}

	/** The complete rule view for every existing target type, in catalog order. */
	public static Map<String, RulesView> readAll(PresentationSettings settings) {
		Map<String, RulesView> views = new LinkedHashMap<>();
		for (String id : PresentationSettings.TARGET_TYPE_IDS) views.put(id, read(settings, id));
		return Collections.unmodifiableMap(views);
	}

	/** The selected rule view; a missing settings object or unknown type fails closed. */
	public static RulesView read(PresentationSettings settings, String targetTypeId) {
		if (settings == null || !PresentationSettings.isKnownTargetType(targetTypeId)) return RulesView.denyAll();
		return RulesView.of(settings.policyFor(targetTypeId));
	}

	/** A detached copy of every persisted presentation field. */
	public static PresentationSettings detachedCopy(PresentationSettings settings) {
		return settings == null ? null : settings.deepCopy();
	}

	/**
	 * Applies one bounded selector or whitelist-only mutation to exactly one
	 * existing target type. The candidate is validated before installation, so
	 * a rejected edit cannot persist and other target types never change.
	 */
	public static Result mutateSelectedRules(
		boolean hasPermission,
		PresentationSettings settings,
		String selectedTargetTypeId,
		Operation operation,
		String selector,
		boolean whitelistOnly
	) {
		if (settings == null || !PresentationSettings.isKnownTargetType(selectedTargetTypeId)) {
			return new Result(false, Status.INVALID, RulesView.denyAll());
		}

		final RulesView current = read(settings, selectedTargetTypeId);

		if (!hasPermission) {
			return new Result(false, Status.DENIED, current);
		}

		if (operation == null || operation == Operation.READ) {
			return new Result(false, Status.OK, current);
		}

		return switch (operation) {
			case ADD_WHITE -> add(settings, selectedTargetTypeId, current, selector, true);
			case REMOVE_WHITE -> remove(settings, selectedTargetTypeId, current, selector, true);
			case ADD_BLACK -> add(settings, selectedTargetTypeId, current, selector, false);
			case REMOVE_BLACK -> remove(settings, selectedTargetTypeId, current, selector, false);
			case SET_WHITELIST_ONLY -> apply(settings, selectedTargetTypeId, current.white(), current.black(), whitelistOnly);
			case READ -> new Result(false, Status.OK, current);
		};
	}

	private static Result add(
		PresentationSettings settings,
		String targetTypeId,
		RulesView current,
		String selector,
		boolean toWhite
	) {
		if (!isValidSelector(selector)) {
			return new Result(false, Status.INVALID, current);
		}

		final List<String> target = toWhite ? current.white() : current.black();

		if (target.contains(selector)) {
			return new Result(false, Status.DUPLICATE, current);
		}
		if (target.size() >= MAX_SELECTORS) {
			return new Result(false, Status.LIST_FULL, current);
		}

		final List<String> candidate = new ArrayList<>(target);
		candidate.add(selector);

		return apply(
			settings,
			targetTypeId,
			toWhite ? candidate : current.white(),
			toWhite ? current.black() : candidate,
			current.whitelistOnly());
	}

	private static Result remove(
		PresentationSettings settings,
		String targetTypeId,
		RulesView current,
		String selector,
		boolean fromWhite
	) {
		if (!isValidSelector(selector)) {
			return new Result(false, Status.INVALID, current);
		}

		final List<String> target = fromWhite ? current.white() : current.black();

		if (!target.contains(selector)) {
			return new Result(false, Status.NOT_FOUND, current);
		}

		final List<String> candidate = new ArrayList<>(target);
		candidate.remove(selector);

		return apply(
			settings,
			targetTypeId,
			fromWhite ? candidate : current.white(),
			fromWhite ? current.black() : candidate,
			current.whitelistOnly());
	}

	/** Validates the complete candidate before installation, so a rejected edit cannot persist. */
	private static Result apply(
		PresentationSettings settings,
		String targetTypeId,
		List<String> white,
		List<String> black,
		boolean whitelistOnly
	) {
		final RulesView current = read(settings, targetTypeId);
		final PresentationPolicy candidate;

		try {
			candidate = new PresentationPolicy(white, black, whitelistOnly);
		} catch (RuntimeException ex) {
			return new Result(false, Status.INVALID, current);
		}

		if (candidate.equals(current.policy())) {
			// A request that does not change the effective rule view is reported
			// as a no-op so callers never rewrite the config, advance the
			// revision, or broadcast unchanged state.
			return new Result(false, Status.OK, current);
		}

		// The selector mutation never touches the child deny list, so the
		// installed rule set keeps the persisted child references instead of
		// resetting them to the semantic default.
		final List<PresentationPropertyRef> childBlack = settings.rulesFor(targetTypeId).getChildBlack();
		settings.setRules(targetTypeId, new PresentationSettings.RuleSet(
			candidate.white(), candidate.black(), candidate.whitelistOnly(), childBlack));

		return new Result(true, Status.OK, read(settings, targetTypeId));
	}

	/** Whether {@code selector} is non-empty, within the length cap, and grammar-valid. */
	public static boolean isValidSelector(String selector) {
		if (selector == null || selector.isEmpty() || selector.length() > MAX_SELECTOR_LENGTH) {
			return false;
		}

		try {
			new PresentationPolicy(List.of(selector), List.of(), false);
			return true;
		} catch (RuntimeException ex) {
			return false;
		}
	}
}
