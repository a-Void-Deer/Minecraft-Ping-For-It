package nx.pingwheel.common.client;

/**
 * Bounded hold/tap state for an inventory selector that stays open after its
 * first release.
 *
 * <p>The controller owns no selector, capture, preview or input state. It only
 * records whether the next physical press is the staying selector's tap, and
 * distinguishes a tap release (commit once) from reaching the frozen ordinary
 * long-press threshold while still held (cancel the whole selector). A cancel
 * or close leaves a finite swallow window for the physical hold's remaining
 * repeated edges and release so they cannot re-enter another interaction.
 */
public final class InventorySelectionStayController {

	public enum ReleaseDecision { NONE, COMMIT, CANCEL }

	private boolean staying;
	private boolean held;
	private boolean swallowUntilRelease;
	private long pressTimestamp;
	private long thresholdMillis;

	/** Enters the staying surface with the originally frozen hold threshold. */
	public void begin(long frozenLongPressMillis) {
		staying = true;
		held = false;
		swallowUntilRelease = false;
		pressTimestamp = 0L;
		thresholdMillis = Math.max(0L, frozenLongPressMillis);
	}

	/** Whether the selector is currently staying open between taps. */
	public boolean active() {
		return staying;
	}

	/** Whether any remaining physical edge of the closed hold must be swallowed. */
	public boolean consuming() {
		return staying || swallowUntilRelease;
	}

	/** Whether one staying tap is currently held down. */
	public boolean held() {
		return held;
	}

	/** Records one press. Returns true when the press belongs to this staying surface. */
	public boolean press(long nowMillis) {
		if (!staying) return swallowUntilRelease;
		if (!held) {
			held = true;
			pressTimestamp = nowMillis;
		}
		return true;
	}

	/** Resolves a physical release as one commit, one cancel, or nothing. */
	public ReleaseDecision release(long nowMillis) {
		if (swallowUntilRelease) {
			swallowUntilRelease = false;
			return ReleaseDecision.NONE;
		}
		if (!staying || !held) return ReleaseDecision.NONE;

		held = false;
		if (nowMillis >= pressTimestamp
			&& nowMillis - pressTimestamp >= thresholdMillis) {
			staying = false;
			return ReleaseDecision.CANCEL;
		}

		staying = false;
		return ReleaseDecision.COMMIT;
	}

	/**
	 * Checks the hold on a frame so a threshold reached without an intermediate
	 * release cannot later be misread as a committable tap.
	 *
	 * @return true when the selector must be closed and the remaining held
	 *         edges swallowed
	 */
	public boolean cancelOnFrame(long nowMillis) {
		if (!staying || !held) return false;
		if (nowMillis < pressTimestamp || nowMillis - pressTimestamp < thresholdMillis) return false;

		held = false;
		staying = false;
		swallowUntilRelease = true;
		return true;
	}

	/** Closes the staying surface without manufacturing an input edge. */
	public void close() {
		staying = false;
		held = false;
	}

	/**
	 * Closes the staying surface because its inventory path left. A still-held
	 * physical tap must not leak into another interaction, so it becomes a
	 * swallow window until release.
	 */
	public void closeForPathExit() {
		swallowUntilRelease = held;
		staying = false;
		held = false;
	}

	/** Full lifecycle reset, including any pending physical-edge swallow. */
	public void reset() {
		staying = false;
		held = false;
		swallowUntilRelease = false;
		pressTimestamp = 0L;
		thresholdMillis = 0L;
	}
}
