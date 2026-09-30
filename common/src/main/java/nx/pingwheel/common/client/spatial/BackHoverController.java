package nx.pingwheel.common.client.spatial;

import java.util.Objects;

/**
 * Pure hover-to-return timing for one focused Back affordance.
 *
 * <p>A session starts with {@link #start(boolean, long)} and freezes the two
 * parameters it needs: whether hover-to-return is enabled and the required
 * hold duration in milliseconds. The caller then reports the current Back
 * focus once per frame (or per movement sample) through
 * {@link #update(String, boolean, long)} and applies the returned
 * {@link Result}. Nothing in this class calls a clock, reads configuration,
 * touches a renderer, or performs a ping action; the square progress paint is
 * a renderer concern driven by {@link Result#progress()}.
 *
 * <p>{@code update} semantics:
 * <ul>
 *   <li>no active session, disabled sessions, no Back focus, and a blocked
 *       parent all report zero progress without arming a timer;</li>
 *   <li>a newly focused Back (first report, menu change, or re-entry after the
 *       focus was lost) starts the hold clock at that timestamp and reports
 *       zero progress;</li>
 *   <li>a non-negative elapsed duration below the threshold reports the
 *       clamped ratio; clock rewind is clamped to zero instead of resetting the
 *       baseline;</li>
 *   <li>at or past the threshold the result is exactly
 *       {@code progress == 1.0, triggerPop == true} once. The focused Back is
 *       forgotten and a blocked flag is armed so the same held focus can never
 *       pop twice: the caller performs exactly one one-level pop, and the
 *       parent's Back - even if already focused because the pointer stayed in
 *       that direction - remains blocked until the Back focus is lost once,
 *       after which a fresh entry arms a new timer;</li>
 *   <li>{@link #end()} (interaction cancel/abort) clears the timer and the
 *       blocked flag and makes further updates inert until the next
 *       {@link #start(boolean, long)}.</li>
 * </ul>
 *
 * <p>The controller is intentionally stateless across sessions and owns no
 * synchronization; it is advanced from the client thread like the rest of the
 * interaction state seams.
 */
public final class BackHoverController {

	/** One frame's hover result; {@code progress} is in {@code [0, 1]}. */
	public record Result(double progress, boolean triggerPop) {}

	private static final Result INERT = new Result(0.0, false);

	private boolean active;
	private boolean enabled;
	private long hoverMillis;
	private String focusedMenuId;
	private long focusedSinceMillis;
	private boolean hasFocusedBack;
	private boolean blocked;

	/**
	 * Starts a session and freezes the hover parameters for its whole
	 * lifetime. A previous session, if any, is discarded first.
	 *
	 * @param enabled           whether Back hover may ever trigger a pop
	 * @param hoverDurationMillis required continuous focus in milliseconds,
	 *                          clamped to at least zero
	 */
	public void start(boolean enabled, long hoverDurationMillis) {
		this.active = true;
		this.enabled = enabled;
		this.hoverMillis = Math.max(0L, hoverDurationMillis);
		this.focusedMenuId = null;
		this.focusedSinceMillis = 0L;
		this.hasFocusedBack = false;
		this.blocked = false;
	}

	/**
	 * Reports the Back focus of the currently active menu and advances the
	 * hold clock to {@code nowMillis}.
	 *
	 * @param menuId        identity of the menu that owns the focused Back;
	 *                      a change restarts the hold clock
	 * @param backFocused   whether the Back affordance of {@code menuId} is
	 *                      currently focused
	 * @param nowMillis     monotonic client timestamp for this sample
	 */
	public Result update(String menuId, boolean backFocused, long nowMillis) {
		Objects.requireNonNull(menuId, "menuId");

		if (!active || !enabled) {
			return INERT;
		}

		if (!backFocused) {
			// Losing the focus also releases a parent blocked by an earlier pop,
			// so a deliberate leave/re-entry can arm the next one-level return.
			clearFocus();
			blocked = false;
			return INERT;
		}

		if (blocked) {
			// Held focus after the pop (including across the menu change to the
			// parent) must not cascade into another return.
			return INERT;
		}

		if (!hasFocusedBack || !menuId.equals(focusedMenuId)) {
			focusedMenuId = menuId;
			focusedSinceMillis = nowMillis;
			hasFocusedBack = true;
			return INERT;
		}

		long elapsed = nowMillis - focusedSinceMillis;

		if (elapsed < 0L) {
			// Defensive monotonic clamp: a rewound clock keeps the baseline and
			// reports the minimum instead of arming or restarting the timer.
			elapsed = 0L;
		}

		if (elapsed >= hoverMillis) {
			clearFocus();
			blocked = true;
			return new Result(1.0, true);
		}

		return new Result((double) elapsed / hoverMillis, false);
	}

	/**
	 * Ends the session and clears the hold clock, the tracked focus and the
	 * blocked parent. Safe to call repeatedly; updates after this are inert
	 * until a new {@link #start(boolean, long)}.
	 */
	public void end() {
		active = false;
		enabled = false;
		hoverMillis = 0L;
		clearFocus();
		blocked = false;
	}

	/** Whether a session has been started and not yet ended. */
	public boolean isActive() {
		return active;
	}

	private void clearFocus() {
		focusedMenuId = null;
		focusedSinceMillis = 0L;
		hasFocusedBack = false;
	}
}
