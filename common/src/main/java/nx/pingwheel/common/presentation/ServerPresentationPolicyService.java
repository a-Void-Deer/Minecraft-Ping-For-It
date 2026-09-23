package nx.pingwheel.common.presentation;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure authority and validation seam for the server presentation policy rule
 * view. Reads disclose only the three presentation selector values. Mutations
 * are atomic add/remove or set-to-value operations: the candidate policy is
 * fully validated before any setter runs, so a malformed, duplicate, or
 * over-capacity request leaves the persisted settings unchanged and never
 * triggers the durable deny-all state. A request whose candidate equals the
 * current rule view is a no-op reported as {@code applied=false} with
 * {@link Status#OK}. Permission is supplied by the authenticated server-side
 * caller; the service never treats a client flag as authority.
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

	public record Result(boolean applied, Status status, PresentationPolicy policy) {}

	/** The current compiled rule view; a missing settings object fails closed. */
	public static PresentationPolicy read(PresentationSettings settings) {
		return settings == null ? denyAll() : settings.policy();
	}

	/**
	 * Creates a detached copy of {@code settings} carrying the exact persisted
	 * presentation fields. Callers mutate the copy and only swap it into the
	 * live config after persistence succeeds, so a rejected edit or a failed
	 * save leaves the original settings object untouched.
	 */
	public static PresentationSettings detachedCopy(PresentationSettings settings) {
		if (settings == null) {
			return null;
		}

		PresentationSettings copy = PresentationSettings.serverDefaults();
		copy.setWhite(settings.getWhite());
		copy.setBlack(settings.getBlack());
		copy.setWhitelistOnly(settings.isWhitelistOnly());
		copy.setMinUpdateIntervalTicks(settings.getMinUpdateIntervalTicks());
		copy.setScanBudget(settings.getScanBudget());
		copy.setPermissionLevels(settings.getPermissionLevels());
		copy.setUpdateIntervals(settings.getUpdateIntervals());
		return copy;
	}

	public static Result mutate(
		boolean hasPermission,
		PresentationSettings settings,
		Operation operation,
		String selector,
		boolean whitelistOnly
	) {
		if (settings == null) {
			return new Result(false, Status.INVALID, denyAll());
		}

		final PresentationPolicy current = settings.policy();

		if (!hasPermission) {
			return new Result(false, Status.DENIED, current);
		}

		if (operation == null || operation == Operation.READ) {
			return new Result(false, Status.OK, current);
		}

		return switch (operation) {
			case ADD_WHITE -> add(settings, current, selector, true);
			case REMOVE_WHITE -> remove(settings, current, selector, true);
			case ADD_BLACK -> add(settings, current, selector, false);
			case REMOVE_BLACK -> remove(settings, current, selector, false);
			case SET_WHITELIST_ONLY -> apply(settings, current.white(), current.black(), whitelistOnly);
			case READ -> new Result(false, Status.OK, current);
		};
	}

	private static Result add(
		PresentationSettings settings,
		PresentationPolicy current,
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
			toWhite ? candidate : current.white(),
			toWhite ? current.black() : candidate,
			current.whitelistOnly());
	}

	private static Result remove(
		PresentationSettings settings,
		PresentationPolicy current,
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
			fromWhite ? candidate : current.white(),
			fromWhite ? current.black() : candidate,
			current.whitelistOnly());
	}

	/** Validates the complete candidate before any setter runs, so a rejected edit cannot persist. */
	private static Result apply(
		PresentationSettings settings,
		List<String> white,
		List<String> black,
		boolean whitelistOnly
	) {
		final PresentationPolicy current = settings.policy();
		final PresentationPolicy candidate;

		try {
			candidate = new PresentationPolicy(white, black, whitelistOnly);
		} catch (RuntimeException ex) {
			return new Result(false, Status.INVALID, current);
		}

		if (candidate.equals(current)) {
			// A request that does not change the effective rule view is reported
			// as a no-op so callers never rewrite the config, advance the
			// revision, or broadcast unchanged state.
			return new Result(false, Status.OK, current);
		}

		settings.setWhite(candidate.white());
		settings.setBlack(candidate.black());
		settings.setWhitelistOnly(candidate.whitelistOnly());

		return new Result(true, Status.OK, settings.policy());
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

	private static PresentationPolicy denyAll() {
		return new PresentationPolicy(List.of(), List.of("*:*"), true);
	}
}
