package nx.pingwheel.common.client;

import java.util.Objects;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.POINT;
import org.lwjgl.system.windows.User32;

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
 *       {@code MouseHandler#releaseMouse()} so the cursor can select sectors,
 *       with the operating-system cursor in the window's
 *       {@code GLFW_CURSOR_HIDDEN} mode. On Windows an owned release replaces
 *       vanilla's mode/warp call: it leaves disabled mode before restoring the
 *       captured physical position, rather than GLFW's older saved position.
 *       Hiding an already free cursor never warps the pointer, so the
 *       selector keeps receiving absolute positions for its virtual pointer
 *       and no phantom travel or capture is produced. It remembers that only
 *       this controller released and hid the cursor, including when vanilla
 *       re-grabs the cursor after a screen closes mid-hold;</li>
 *   <li>on the transition out of {@code WHEEL_OPEN} (commit,
 *       cancellation, stale, superseded), the recorded cursor mode is restored
 *       and the mouse is re-grabbed only when this controller released it and
 *       no screen is open. A screen/focus handoff relinquishes that claim:
 *       the controller never steals the mouse from a newer owner;</li>
 *   <li>{@link #close(Minecraft)} applies the same restore/re-grab rule when
 *       the runtime is disposed on disconnect, focus loss, or a world
 *       abort.</li>
 * </ul>
 *
 * <p>The selector hides the cursor exactly while it actually owns the pointer:
 * the wheel is open, the window is focused, no screen is open, and the mouse is not grabbed. The hide
 * is owned together with its window handle and the cursor mode it replaced. A
 * replaced window or another owner changing the mode first makes the
 * controller drop its claim without touching that owner and without hiding
 * over it again until a fresh owned release; only a mode that is still the
 * hidden value this controller set is ever restored.
 *
 * <p>The transition policy itself is a pure, stateless function over one
 * sync snapshot ({@link #nextAction(boolean, boolean, boolean, boolean)}) so
 * it is unit tested without a game client; only the Minecraft
 * {@code MouseHandler} and GLFW adapter calls remain untested glue (compile
 * coverage).
 *
 * <p>Only open/release/regrab and cursor hide/restore transitions are debug
 * logged, never per-tick state.
 */
public final class WheelMouseCapture {

	/** The mouse transition to apply for this sync. */
	public enum Action {
		NONE,
		RELEASE,
		GRAB
	}

	/** Cursor mode values of the {@link MouseAccess} seam; the Minecraft adapter forwards them to GLFW. */
	static final int CURSOR_MODE_NORMAL = GLFW.GLFW_CURSOR_NORMAL;
	static final int CURSOR_MODE_HIDDEN = GLFW.GLFW_CURSOR_HIDDEN;
	static final int CURSOR_MODE_DISABLED = GLFW.GLFW_CURSOR_DISABLED;
	/** Any other value means the active cursor mode is unavailable. */
	static final int CURSOR_MODE_UNKNOWN = -1;

	private final PingInteractionLogger logger;
	private boolean releasedByWheel;
	private int transitionDepth;
	/** Window whose cursor mode this controller hid, or {@code 0L} while no hide is owned. */
	private long hiddenWindow;
	/** Cursor mode recorded immediately before this controller hid the cursor. */
	private int modeBeforeHide = CURSOR_MODE_UNKNOWN;
	private boolean cursorHiddenByWheel;
	/**
	 * Set when a replaced window or another owner's mode change invalidated an
	 * owned hide while the wheel stayed open; the selector does not hide over
	 * that owner again until it performs a fresh owned release.
	 */
	private boolean cursorHideSuppressed;
	/** Scoped to our releaseMouse call, never to a later screen/vanilla release. */
	private static final ThreadLocal<SelectorRelease> SELECTOR_RELEASE = new ThreadLocal<>();
	public record CursorPosition(double x, double y) {}
	private record SelectorRelease(WheelMouseCapture owner, MouseAccess mouse, long window, CursorPosition position) {}

	interface MouseAccess {
		boolean screenOpen();
		boolean grabbed();
		default boolean focused() { return true; }
		void release();
		void grab();
		/** Active window identity, or {@code 0L} when unavailable. */
		default long window() { return 0L; }
		/** Current cursor mode, or {@link WheelMouseCapture#CURSOR_MODE_UNKNOWN} when unavailable. */
		default int cursorMode() { return CURSOR_MODE_UNKNOWN; }
		/** Sets the cursor mode; leaving disabled mode may restore GLFW's saved position. */
		default void setCursorMode(int mode) {}
		default void setCursorPosition(CursorPosition position) {}
	}
	private record MinecraftMouseAccess(Minecraft game, WheelMouseCapture owner) implements MouseAccess {
		public boolean screenOpen() { return game.screen != null; }
		public boolean grabbed() { return game.mouseHandler.isMouseGrabbed(); }
		public boolean focused() { return game.isWindowActive(); }
		public void release() {
			CursorPosition position = physicalCursorPosition(window());
			if (position == null) { game.mouseHandler.releaseMouse(); return; }
			owner.releaseWithPosition(this, position, game.mouseHandler::releaseMouse);
		}
		public void grab() { game.mouseHandler.grabMouse(); }
		public long window() { return game.getWindow().getWindow(); }
		public int cursorMode() {
			int mode = GLFW.glfwGetInputMode(window(), GLFW.GLFW_CURSOR);
			return mode > 0 ? mode : CURSOR_MODE_UNKNOWN;
		}
		public void setCursorMode(int mode) { GLFW.glfwSetInputMode(window(), GLFW.GLFW_CURSOR, mode); }
		public void setCursorPosition(CursorPosition position) { GLFW.glfwSetCursorPos(window(), position.x(), position.y()); }
	}

	void releaseWithPosition(MouseAccess mouse, CursorPosition position, Runnable releaseMouse) {
		SelectorRelease previous = SELECTOR_RELEASE.get();
		SELECTOR_RELEASE.set(new SelectorRelease(this, mouse, mouse.window(), position));
		try { releaseMouse.run(); }
		finally {
			if (previous == null) SELECTOR_RELEASE.remove();
			else SELECTOR_RELEASE.set(previous);
		}
	}

	/** GLFW's disabled-mode position is virtual, not the physical Win32 cursor. */
	private static CursorPosition physicalCursorPosition(long window) {
		if (Platform.get() != Platform.WINDOWS || window == 0L) return null;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			POINT cursor = POINT.malloc(stack);
			POINT origin = POINT.calloc(stack);
			if (!User32.GetCursorPos(cursor)
				|| !User32.ClientToScreen(GLFWNativeWin32.glfwGetWin32Window(window), origin)) return null;
			return new CursorPosition((double) cursor.x() - origin.x(), (double) cursor.y() - origin.y());
		}
	}

	/** Called only by the releaseMouse redirect; ordinary releases keep vanilla ordering. */
	public static CursorPosition releaseCursor(long window, int mode, double x, double y) {
		SelectorRelease release = SELECTOR_RELEASE.get();
		if (release != null && mode == CURSOR_MODE_NORMAL && release.window() == window && release.mouse().window() == window
			&& release.owner().releasedByWheel && !release.mouse().screenOpen() && release.mouse().focused()) {
			return release.owner().releaseCursor(release.mouse(), release.position());
		}
		InputConstants.grabOrReleaseMouse(window, mode, x, y);
		return null;
	}

	/** The vanilla release has already cleared mouseGrabbed before reaching this call. */
	CursorPosition releaseCursor(MouseAccess mouse, CursorPosition position) {
		int mode = mouse.cursorMode();
		if (mode != CURSOR_MODE_DISABLED && mode != CURSOR_MODE_NORMAL) {
			releasedByWheel = false;
			cursorHideSuppressed = true;
			return null;
		}
		hiddenWindow = mouse.window();
		modeBeforeHide = CURSOR_MODE_NORMAL;
		cursorHiddenByWheel = true;
		mouse.setCursorMode(CURSOR_MODE_HIDDEN);
		// Leaving disabled mode restores GLFW's saved position. Apply the physical
		// release bias afterwards, but never after a reentrant disposal/handoff.
		if (!cursorHiddenByWheel || !releasedByWheel || mouse.window() != hiddenWindow
			|| mouse.cursorMode() != CURSOR_MODE_HIDDEN || mouse.grabbed() || mouse.screenOpen() || !mouse.focused()) return null;
		mouse.setCursorPosition(position);
		logger.debug("wheel cursor released hidden");
		return cursorHiddenByWheel && releasedByWheel && mouse.window() == hiddenWindow
			&& mouse.cursorMode() == CURSOR_MODE_HIDDEN && !mouse.grabbed() && !mouse.screenOpen() && mouse.focused() ? position : null;
	}
	public boolean isTransitioning() { return transitionDepth != 0; }

	/** A no-screen mouse press normally calls grabMouse before dispatching its key edge. */
	public static boolean preventVanillaGrab(PingInteractionPhase phase, boolean screenOpen, boolean focused) {
		return phase == PingInteractionPhase.WHEEL_OPEN && !screenOpen && focused;
	}

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
		sync(phase, new MinecraftMouseAccess(Objects.requireNonNull(game, "game"), this));
	}
	void sync(PingInteractionPhase phase, MouseAccess mouse) {
		Objects.requireNonNull(phase, "phase");

		boolean isOpen = phase == PingInteractionPhase.WHEEL_OPEN;
		boolean screenOpen = mouse.screenOpen();
		reconcileHiddenCursor(mouse, isOpen, screenOpen);
		if (!isOpen) cursorHideSuppressed = false;
		if (!mouse.focused()) return;

		Action action = nextAction(isOpen, releasedByWheel, screenOpen, mouse.grabbed());

		switch (action) {
			case RELEASE -> {
				releasedByWheel = true;
				// A fresh owned release re-establishes the selector's cursor
				// visibility even after a newer owner suppressed an earlier hide.
				cursorHideSuppressed = false;
				transitionDepth++;
				try { mouse.release(); }
				finally { transitionDepth--; }
				// A reentrant dispose during the release already dropped the claim.
				if (releasedByWheel) hideCursorIfOwned(mouse, isOpen, screenOpen);
				logger.debug("wheel mouse released");
			}
			case GRAB -> {
				releasedByWheel = false;
				transitionDepth++;
				try { mouse.grab(); }
				finally { transitionDepth--; }
				logger.debug("wheel mouse regrabbed");
			}
			default -> {
				// The selector can already own a free cursor without this
				// controller having released it; a screen still open keeps the
				// pending re-grab for a later tick.
				if (!cursorHideSuppressed) hideCursorIfOwned(mouse, isOpen, screenOpen);
			}
		}
	}

	/**
	 * Keeps an owned hide consistent with the live snapshot before the next
	 * transition: the claim is dropped without touching the mode when the
	 * window was replaced or another owner changed the mode first, and a claim
	 * that still matches is restored as soon as the selector no longer owns the
	 * pointer.
	 */
	private void reconcileHiddenCursor(MouseAccess mouse, boolean isOpen, boolean screenOpen) {
		if (!cursorHiddenByWheel) return;
		if (mouse.window() != hiddenWindow || mouse.cursorMode() != CURSOR_MODE_HIDDEN) {
			// A replaced window or a newer owner's mode: never touch it, and do
			// not hide over that owner again until a fresh owned release.
			dropHiddenCursor();
			cursorHideSuppressed = true;
			releasedByWheel = false;
		} else if (!isOpen || screenOpen || mouse.grabbed() || !mouse.focused()) {
			if (screenOpen || !mouse.focused()) {
				releasedByWheel = false;
				cursorHideSuppressed = true;
			}
			restoreHiddenCursor(mouse);
		}
	}

	private void hideCursorIfOwned(MouseAccess mouse, boolean isOpen, boolean screenOpen) {
		if (isOpen && !screenOpen && !mouse.screenOpen() && mouse.focused() && !mouse.grabbed()) hideCursor(mouse);
	}

	/** Hides from the visible mode only; never warps the pointer and never overwrites another owner. */
	private void hideCursor(MouseAccess mouse) {
		if (cursorHiddenByWheel) return;
		long window = mouse.window();
		int previous = mouse.cursorMode();
		if (window == 0L || previous != CURSOR_MODE_NORMAL) return;
		hiddenWindow = window;
		modeBeforeHide = previous;
		cursorHiddenByWheel = true;
		mouse.setCursorMode(CURSOR_MODE_HIDDEN);
		logger.debug("wheel cursor hidden");
	}

	/** Restores only a mode that is still the hidden value this controller set; never warps. */
	private void restoreHiddenCursor(MouseAccess mouse) {
		long window = hiddenWindow;
		int previous = modeBeforeHide;
		dropHiddenCursor();
		if (mouse.window() == window && mouse.cursorMode() == CURSOR_MODE_HIDDEN) {
			mouse.setCursorMode(previous);
			logger.debug("wheel cursor restored");
		}
	}

	private void dropHiddenCursor() {
		cursorHiddenByWheel = false;
		hiddenWindow = 0L;
		modeBeforeHide = CURSOR_MODE_UNKNOWN;
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
		close(game, false);
	}

	/** setScreen HEAD still exposes the old screen; an incoming screen must never be grabbed over. */
	public void close(Minecraft game, boolean screenTransition) {
		close(game == null ? null : new MinecraftMouseAccess(game, this), screenTransition);
	}
	void close(MouseAccess mouse, boolean screenTransition) {
		boolean reclaim = releasedByWheel;
		releasedByWheel = false;
		cursorHideSuppressed = screenTransition;
		if (cursorHiddenByWheel) {
			if (mouse != null && mouse.window() == hiddenWindow && mouse.cursorMode() == CURSOR_MODE_HIDDEN)
				restoreHiddenCursor(mouse);
			else {
				dropHiddenCursor();
				reclaim = false;
			}
		}

		if (!reclaim) {
			return;
		}

		if (mouse == null || mouse.screenOpen() || screenTransition) {
			return;
		}

		transitionDepth++;
		try { mouse.grab(); }
		finally { transitionDepth--; }
		logger.debug("wheel mouse regrabbed on close");
	}
}
