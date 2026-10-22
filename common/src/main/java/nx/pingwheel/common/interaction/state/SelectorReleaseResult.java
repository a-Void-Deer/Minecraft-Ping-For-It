package nx.pingwheel.common.interaction.state;

import java.util.Objects;
import java.util.Optional;

/**
 * One terminal selector result, not proof of dispatch. A validated create
 * returns its original intent beside CreatePing; TargetGone returns only the
 * feedback action. A local intent has no network action, and a cancellation
 * sends only when its action contains a selected marker.
 */
public record SelectorReleaseResult<P>(Optional<P> admittedIntent, Optional<PingInteractionAction> action) {
	public SelectorReleaseResult {
		Objects.requireNonNull(admittedIntent, "admittedIntent");
		Objects.requireNonNull(action, "action");
	}

	public static <P> SelectorReleaseResult<P> empty() {
		return new SelectorReleaseResult<>(Optional.empty(), Optional.empty());
	}
}
