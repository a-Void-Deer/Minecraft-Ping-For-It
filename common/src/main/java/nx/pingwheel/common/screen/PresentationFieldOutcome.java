package nx.pingwheel.common.screen;

import nx.pingwheel.common.presentation.PresentationPolicy;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Operation;
import nx.pingwheel.common.presentation.client.ServerPresentationPolicyState;

import java.util.List;

/**
 * Pure decision model for one presentation field row: the effective outcome
 * under a compiled policy, the exact allow/block membership of the field id,
 * and the matching rule diagnostics the UI may show.
 *
 * <p>The evaluation mirrors the runtime call sites: client receive and display
 * always pass the server-authorized default, so a local policy can only add
 * allow rules, add deny rules, or switch to whitelist-only; the server policy
 * passes the field's manifest default, which is the server-advertised default
 * for an accepted offer.
 */
public final class PresentationFieldOutcome {
	public enum Role {
		CLIENT_RECEIVE,
		CLIENT_DISPLAY,
		SERVER_POLICY
	}

	public enum Effective {
		ALLOWED_BY_RULE,
		ALLOWED_BY_DEFAULT,
		ALLOW_RULE_PRIORITY,
		BLOCKED_BY_RULE,
		BLOCKED_BY_WHITELIST_ONLY,
		DISABLED_BY_DEFAULT,
		UNKNOWN
	}

	/**
	 * The evaluated row state. {@code allowOn}/{@code blockOn} report exact
	 * membership of the field id in the respective list, not the final outcome,
	 * because a broad rule of the other list can still override it.
	 */
	public record Outcome(
		Effective effective,
		boolean allowOn,
		boolean blockOn,
		List<String> matchingWhite,
		List<String> matchingBlack
	) {
		public Outcome {
			matchingWhite = List.copyOf(matchingWhite);
			matchingBlack = List.copyOf(matchingBlack);
		}

		public boolean allowed() {
			return effective == Effective.ALLOWED_BY_RULE
				|| effective == Effective.ALLOWED_BY_DEFAULT
				|| effective == Effective.ALLOW_RULE_PRIORITY;
		}
	}

	private PresentationFieldOutcome() {
	}

	/**
	 * Evaluates one field. A null policy reports {@link Effective#UNKNOWN} with
	 * both membership controls off, so an unknown server view never fabricates
	 * an empty authoritative rule list.
	 */
	public static Outcome evaluate(Role role, PresentationPolicy policy, String fieldId, boolean manifestDefault) {
		if (policy == null) {
			return new Outcome(Effective.UNKNOWN, false, false, List.of(), List.of());
		}

		List<String> whiteMatches = policy.matchingSelectors(fieldId, true);
		List<String> blackMatches = policy.matchingSelectors(fieldId, false);
		boolean allowOn = policy.white().contains(fieldId);
		boolean blockOn = policy.black().contains(fieldId);
		boolean effectiveDefault = role == Role.SERVER_POLICY ? manifestDefault : true;
		boolean allowed = policy.allows(fieldId, effectiveDefault);

		Effective effective;
		if (!whiteMatches.isEmpty() && !blackMatches.isEmpty()) {
			effective = Effective.ALLOW_RULE_PRIORITY;
		} else if (!whiteMatches.isEmpty()) {
			effective = Effective.ALLOWED_BY_RULE;
		} else if (!blackMatches.isEmpty()) {
			effective = Effective.BLOCKED_BY_RULE;
		} else if (policy.whitelistOnly()) {
			effective = Effective.BLOCKED_BY_WHITELIST_ONLY;
		} else if (role != Role.SERVER_POLICY) {
			effective = Effective.ALLOWED_BY_DEFAULT;
		} else {
			effective = manifestDefault ? Effective.ALLOWED_BY_DEFAULT : Effective.DISABLED_BY_DEFAULT;
		}

		if (allowed && !isAllowedOutcome(effective)) {
			// Never claim a deny when the runtime would allow the field.
			effective = Effective.ALLOW_RULE_PRIORITY;
		} else if (!allowed && isAllowedOutcome(effective)) {
			// Never claim an allow when the runtime would deny the field.
			effective = policy.whitelistOnly()
				? Effective.BLOCKED_BY_WHITELIST_ONLY
				: Effective.BLOCKED_BY_RULE;
		}

		return new Outcome(effective, allowOn, blockOn, whiteMatches, blackMatches);
	}

	/** One operation per exact field-id membership toggle; never a list replacement. */
	public static Operation toggleAllow(boolean allowOn) {
		return allowOn ? Operation.REMOVE_WHITE : Operation.ADD_WHITE;
	}

	public static Operation toggleBlock(boolean blockOn) {
		return blockOn ? Operation.REMOVE_BLACK : Operation.ADD_BLACK;
	}

	/** The server rule controls follow the same pending/revoked/uncertain gate. */
	public static boolean canEditServerPolicy(ServerPresentationPolicyState state) {
		return state != null && state.canMutate();
	}

	private static boolean isAllowedOutcome(Effective effective) {
		return effective == Effective.ALLOWED_BY_RULE
			|| effective == Effective.ALLOWED_BY_DEFAULT
			|| effective == Effective.ALLOW_RULE_PRIORITY;
	}
}
