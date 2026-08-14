package nx.pingwheel.common.interaction.state;

/**
 * The lifecycle phase of one ping-key interaction.
 *
 * <ul>
 *   <li>{@link #IDLE}: no interaction in progress;</li>
 *   <li>{@link #PRESSED}: the key is (or was) held and the capture is either
 *       pending or ready but below the long-press threshold;</li>
 *   <li>{@link #WHEEL_OPEN}: the long-press threshold was reached and the ping
 *       type wheel is open.</li>
 * </ul>
 */
public enum PingInteractionPhase {
	IDLE,
	PRESSED,
	WHEEL_OPEN
}
