package nx.pingwheel.common.client;

import java.util.Objects;

import net.minecraft.client.Minecraft;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;

/**
 * Client-only controller that owns the Minecraft mouse grab state while the
 * ping wheel is open.
 *
	 * <p>After each event/frame state-machine advance,
	 * {@link ClientPingRuntime} calls {@link #sync(PingInteractionPhase, Minecraft)}
 * with the machine's current phase:
 * <ul>
 *   <li>whenever the wheel is open, no screen is open, and the mouse is
 *       grabbed, the controller releases the mouse via the 1.21.1
 *       {@code MouseHandler#releaseMouse()} so the cursor can select sectors.
 *       It remembers that only this controller released it, including when
 *       vanilla re-grabs the cursor after a screen closes mid-hold;</li>
 *   <li>on the transition out of {@code WHEEL_OPEN} (commit, timeout,
 *       cancellation, stale, superseded), the mouse is re-grabbed only when
 *       this controller released it and no screen is open. While a screen is
 *       open the re-grab is deferred to a later tick: the controller never
 *       steals the mouse from a screen;</li>
 *   <li>{@link #close(Minecraft)} applies the same re-grab rule when the
 *       runtime is disposed on disconnect.</li>
 * </ul>
 *
 * <p>The transition policy itself is a pure, stateless function over one
 * sync snapshot ({@link #nextAction(boolean, boolean, boolean, boolean)}) so
 * it is unit tested without a game client; only the Minecraft
 * {@code MouseHandler} calls are untested glue (compile coverage).
 *
 * <p>Only open/release/regrab transitions are debug logged, never per-tick
 * state.
 */
public final class WheelMouseCapture {

	/** The mouse transition to apply for this sync. */
	public enum Action {
		NONE,
		RELEASE,
		GRAB
	}

	private final PingInteractionLogger logger;
	private boolean releasedByWheel;

	public WheelMouseCapture(PingInteractionLogger logger) {
		this.logger = Objects.requireNonNull(logger, "logger");
	}

	/**
	 * The pure, stateless mouse transition policy over one sync snapshot.
	 *
	 * <ul>
	 *   <li>while the wheel is open, no screen is open, and the mouse is grabbed,
	 *       the mouse must be released and claimed ({@link Action#RELEASE}).
	 *       This fires whether the wheel just opened or stayed open across a
	 *       mid-hold screen close that made vanilla re-grab the cursor;</li>
	 *   <li>leaving the wheel re-grabs only when this controller released it
	 *       and no screen is open; with a screen open the re-grab stays
	 *       pending ({@link Action#NONE}) until a later tick;</li>
	 *   <li>any other combination changes nothing.</li>
	 * </ul>
	 *
	 * @param isOpen          the current machine phase is {@code WHEEL_OPEN}
	 * @param releasedByWheel this controller currently holds a release it
	 *                        performed
	 * @param screenOpen      a screen is currently open
	 * @param mouseGrabbed    the {@code MouseHandler} currently has the mouse
	 *                        grabbed
	 */
	static Action nextAction(boolean isOpen, boolean releasedByWheel, boolean screenOpen, boolean mouseGrabbed) {
		if (isOpen && !screenOpen && mouseGrabbed) {
			return Action.RELEASE;
		}

		if (!isOpen && releasedByWheel && !screenOpen) {
			return Action.GRAB;
		}

		return Action.NONE;
	}

	/**
	 * Applies the transition policy for the machine's current phase.
	 *
	 * <p>The policy is evaluated against the live {@code MouseHandler} grab
	 * state, so a release is only claimed when the mouse is actually grabbed
	 * at this moment: an already free cursor is never released again, and a
	 * cursor that vanilla re-grabs when a screen closes mid-hold is released
	 * and claimed on the next tick while the wheel is still open.
	 */
	public void sync(PingInteractionPhase phase, Minecraft game) {
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(game, "game");

		Action action = nextAction(
			phase == PingInteractionPhase.WHEEL_OPEN,
			releasedByWheel,
			game.screen != null,
			game.mouseHandler.isMouseGrabbed());

		switch (action) {
			case RELEASE -> {
				game.mouseHandler.releaseMouse();
				releasedByWheel = true;
				logger.debug("wheel mouse released");
			}
			case GRAB -> {
				releasedByWheel = false;
				game.mouseHandler.grabMouse();
				logger.debug("wheel mouse regrabbed");
			}
			default -> {
				// Screen still open: keep the pending re-grab for a later tick.
			}
		}
	}

	/**
	 * Resets this controller on runtime disposal/disconnect.
	 *
	 * <p>A pending wheel release is re-grabbed when no screen is open; when a
	 * screen is still open the grab state is left to the game (vanilla
	 * re-grabs when the screen closes) and the pending flag is dropped since
	 * this controller is done.
	 */
	public void close(Minecraft game) {
		if (!releasedByWheel) {
			return;
		}

		releasedByWheel = false;

		if (game == null || game.screen != null) {
			return;
		}

		game.mouseHandler.grabMouse();
		logger.debug("wheel mouse regrabbed on close");
	}
}
