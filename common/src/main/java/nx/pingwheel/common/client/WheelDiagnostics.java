package nx.pingwheel.common.client;

import net.minecraft.client.Minecraft;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;

import static nx.pingwheel.common.Global.LOGGER;

/**
 * Low-volume diagnostic for a long press that never becomes a visible wheel.
 *
 * <p>Normal interactions produce no log line. Only a held press that stays in
 * {@code PRESSED} for longer than the long-press threshold, or an open wheel
 * without a presentation snapshot, emits at most one line every two seconds.
 * The fields distinguish the two failure classes:</p>
 *
 * <ul>
 *   <li>{@code phase=PRESSED, capture=false} — the frame path runs but the
 *       press capture never completed;</li>
 *   <li>{@code phase=PRESSED, capture=true} — the capture exists but the
 *       wheel-open transition never happened (frame path not advancing);</li>
 *   <li>{@code phase=WHEEL_OPEN, presentation=false} — the wheel opened but
 *       the GUI has no snapshot to draw;</li>
 *   <li>{@code screen} non-null — a screen is swallowing the interaction.</li>
 * </ul>
 */
public final class WheelDiagnostics {

	public static final WheelDiagnostics INSTANCE = new WheelDiagnostics();

	private static final long HOLD_WARN_MILLIS = 700L;
	private static final long LOG_INTERVAL_MILLIS = 2000L;

	private long anomalySince;
	private long lastLog;

	private WheelDiagnostics() {}

	public void note(
		boolean keyDown,
		PingInteractionPhase phase,
		boolean capturePresent,
		boolean presentationPresent,
		Minecraft game
	) {
		long now = nowMillis();
		boolean anomalous = (phase == PingInteractionPhase.PRESSED && keyDown)
			|| (phase == PingInteractionPhase.WHEEL_OPEN && !presentationPresent);

		if (!anomalous) {
			this.anomalySince = 0L;
			return;
		}

		if (this.anomalySince == 0L) {
			this.anomalySince = now;
			return;
		}

		if (now - this.anomalySince < HOLD_WARN_MILLIS
			|| now - this.lastLog < LOG_INTERVAL_MILLIS) {
			return;
		}

		this.lastLog = now;

		LOGGER.info(
			"ping wheel diagnostic: phase={}, keyDown={}, capture={}, presentation={}, screen={}, level={}, mouseGrabbed={}",
			phase,
			keyDown,
			capturePresent,
			presentationPresent,
			game == null || game.screen == null ? "<none>" : game.screen.getClass().getSimpleName(),
			game != null && game.level != null,
			game != null && game.mouseHandler != null && game.mouseHandler.isMouseGrabbed());
	}

	private static long nowMillis() {
		return System.nanoTime() / 1_000_000L;
	}
}
