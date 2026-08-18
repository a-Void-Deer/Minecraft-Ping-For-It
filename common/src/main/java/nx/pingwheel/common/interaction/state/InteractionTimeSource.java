package nx.pingwheel.common.interaction.state;

/**
 * A monotonic millisecond clock used by the interaction state machine.
 *
 * <p>Implementations must return a value that never decreases between
 * successive calls while an interaction is active. The state machine enforces
 * this contract and throws an {@link IllegalStateException} if a value moves
 * backwards, so a broken clock can never produce a negative hold duration or
 * reopen a closed wheel.
 */
@FunctionalInterface
public interface InteractionTimeSource {

	/**
	 * The current time in milliseconds from an unspecified, monotonic epoch.
	 */
	long nowMillis();

	/**
	 * A clock backed by the monotonic {@link System#nanoTime()}, converted to
	 * milliseconds. This never depends on wall-clock time, so clock adjustments
	 * or NTP sync cannot move the interaction clock backwards.
	 */
	static InteractionTimeSource system() {
		return () -> System.nanoTime() / 1_000_000L;
	}
}
