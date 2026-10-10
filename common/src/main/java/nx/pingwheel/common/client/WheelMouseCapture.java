package nx.pingwheel.common.client;

import java.util.Objects;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

/** Owns native disabled cursor input without giving vanilla ownership of camera look. */
public final class WheelMouseCapture {

	public enum Action { NONE, RELEASE, GRAB }
	static final int CURSOR_MODE_NORMAL = GLFW.GLFW_CURSOR_NORMAL;
	static final int CURSOR_MODE_DISABLED = GLFW.GLFW_CURSOR_DISABLED;
	static final int CURSOR_MODE_UNKNOWN = -1;

	/** Implemented by the mouse mixin, including for native-only capture transitions. */
	public interface MouseState {
		void pingforit$clearAccumulatedMovement();
	}

	/** Prime before native changes and sample the resulting virtual position afterwards. */
	public interface InputTransition {
		void before();
		void after(double x, double y);
	}
	private static final InputTransition NO_INPUT = new InputTransition() {
		public void before() {}
		public void after(double x, double y) {}
	};

	interface MouseAccess {
		boolean screenOpen();
		boolean grabbed();
		default boolean focused() { return true; }
		void release();
		void grab();
		default long window() { return 0L; }
		default int cursorMode() { return CURSOR_MODE_UNKNOWN; }
		default void setCursorMode(int mode) {}
		/** Same position-before-mode ordering as InputConstants. */
		default void applyCursorMode(int mode, double x, double y) { setCursorMode(mode); }
		default void beforeTransition() {}
		default void afterTransition() {}
	}

	private record MinecraftMouseAccess(Minecraft game, InputTransition input) implements MouseAccess {
		public boolean screenOpen() { return game.screen != null; }
		public boolean grabbed() { return game.mouseHandler.isMouseGrabbed(); }
		public boolean focused() { return game.isWindowActive(); }
		public void release() { game.mouseHandler.releaseMouse(); }
		public void grab() { game.mouseHandler.grabMouse(); }
		public long window() { return game.getWindow().getWindow(); }
		public int cursorMode() {
			int mode = GLFW.glfwGetInputMode(window(), GLFW.GLFW_CURSOR);
			return mode > 0 ? mode : CURSOR_MODE_UNKNOWN;
		}
		public void setCursorMode(int mode) { GLFW.glfwSetInputMode(window(), GLFW.GLFW_CURSOR, mode); }
		public void applyCursorMode(int mode, double x, double y) { InputConstants.grabOrReleaseMouse(window(), mode, x, y); }
		private void clearLook() { ((MouseState) game.mouseHandler).pingforit$clearAccumulatedMovement(); }
		public void beforeTransition() { clearLook(); input.before(); }
		public void afterTransition() {
			clearLook();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				var x = stack.mallocDouble(1);
				var y = stack.mallocDouble(1);
				GLFW.glfwGetCursorPos(window(), x, y);
				input.after(x.get(0), y.get(0));
			}
		}
	}

	private final PingInteractionLogger logger;
	private boolean releasedByWheel;
	private long ownedWindow;
	private boolean nativeOwned;
	private boolean acquisitionSuppressed;
	private int transitionDepth;
	private long ownershipVersion;
	/** Consumed by the HEAD hook of exactly the vanilla call we requested. */
	private Action expectedCapture = Action.NONE;
	private static final ThreadLocal<SelectorRelease> SELECTOR_RELEASE = new ThreadLocal<>();
	private static final ThreadLocal<Integer> RELEASE_DEPTH = ThreadLocal.withInitial(() -> 0);
	private record SelectorRelease(WheelMouseCapture owner, MouseAccess mouse, long window, long version, int depth) {}

	public WheelMouseCapture(PingInteractionLogger logger) { this.logger = Objects.requireNonNull(logger, "logger"); }
	public boolean isTransitioning() { return transitionDepth != 0; }

	/** Only the onPress auto-grab call is guarded; raw button edges and explicit grabs remain vanilla. */
	public static boolean preventVanillaGrab(PingInteractionPhase phase, boolean screenOpen, boolean focused) {
		return phase == PingInteractionPhase.WHEEL_OPEN && !screenOpen && focused;
	}

	static Action nextAction(boolean isOpen, boolean releasedByWheel, boolean screenOpen, boolean mouseGrabbed) {
		if (isOpen && !screenOpen && mouseGrabbed) return Action.RELEASE;
		if (!isOpen && releasedByWheel && !screenOpen) return Action.GRAB;
		return Action.NONE;
	}

	/** Redirects only the native call inside our scoped vanilla release. Ordinary releases stay NORMAL. */
	public static void releaseCursor(long window, int mode, double x, double y) {
		SelectorRelease release = SELECTOR_RELEASE.get();
		if (release == null || mode != CURSOR_MODE_NORMAL || release.depth() != RELEASE_DEPTH.get()) {
			InputConstants.grabOrReleaseMouse(window, mode, x, y);
			return;
		}
		var owner = release.owner();
		var mouse = release.mouse();
		// A reentrant screen/window/capture takeover fences the older release call.
		if (release.window() != window || mouse.window() != window || release.version() != owner.ownershipVersion
			|| !owner.releasedByWheel || mouse.grabbed() || mouse.screenOpen() || !mouse.focused()) return;
		if (mouse.cursorMode() != CURSOR_MODE_DISABLED && mouse.cursorMode() != CURSOR_MODE_NORMAL) {
			owner.dropClaims(mouse);
			return;
		}
		owner.ownedWindow = window;
		owner.nativeOwned = true;
		// Vanilla already cleared mouseGrabbed and centered xpos/ypos. Its position
		// request resets virtual coordinates, but staying DISABLED never frees the physical cursor.
		mouse.applyCursorMode(CURSOR_MODE_DISABLED, x, y);
		owner.logger.debug("wheel cursor disabled");
	}

	/** Nested ordinary vanilla releases must not inherit the outer selector's redirect scope. */
	public static void beforeVanillaRelease() { RELEASE_DEPTH.set(RELEASE_DEPTH.get() + 1); }
	public static void afterVanillaRelease() {
		int depth = RELEASE_DEPTH.get() - 1;
		if (depth <= 0) RELEASE_DEPTH.remove(); else RELEASE_DEPTH.set(depth);
	}

	private void transition(MouseAccess mouse, Runnable change) {
		transitionDepth++;
		try { mouse.beforeTransition(); change.run(); }
		finally {
			transitionDepth--;
			mouse.afterTransition();
		}
	}

	private void vanillaCapture(Action action, Runnable change) {
		Action previous = expectedCapture;
		expectedCapture = action;
		try { change.run(); }
		finally { expectedCapture = previous; }
	}

	public void sync(PingInteractionPhase phase, Minecraft game) { sync(phase, game, NO_INPUT); }
	public void sync(PingInteractionPhase phase, Minecraft game, InputTransition input) {
		sync(phase, new MinecraftMouseAccess(Objects.requireNonNull(game, "game"), Objects.requireNonNull(input, "input")));
	}
	void sync(PingInteractionPhase phase, MouseAccess mouse) {
		Objects.requireNonNull(phase, "phase");
		if (isTransitioning()) return;
		boolean open = phase == PingInteractionPhase.WHEEL_OPEN;
		if (!open) {
			endOwnership(mouse, false);
			acquisitionSuppressed = false;
			return;
		}
		if (nativeOwned && (mouse.window() != ownedWindow || mouse.cursorMode() != CURSOR_MODE_DISABLED || mouse.grabbed())) {
			// Vanilla also uses DISABLED: a new grabbed owner must never be restored to NORMAL.
			dropClaims(mouse);
		}
		if (mouse.screenOpen() || !mouse.focused()) {
			endOwnership(mouse, true);
			return;
		}
		if (nextAction(true, releasedByWheel, mouse.screenOpen(), mouse.grabbed()) == Action.RELEASE) {
			releasedByWheel = true;
			acquisitionSuppressed = false;
			long version = ++ownershipVersion;
			int releaseDepth = RELEASE_DEPTH.get();
			SelectorRelease previous = SELECTOR_RELEASE.get();
			SELECTOR_RELEASE.set(new SelectorRelease(this, mouse, mouse.window(), version, releaseDepth + 1));
			try { transition(mouse, () -> {
				if (version == ownershipVersion && mouse.grabbed() && !mouse.screenOpen() && mouse.focused())
					vanillaCapture(Action.RELEASE, mouse::release);
			}); }
			finally {
				// Recover our nesting scope even if vanilla release throws before its TAIL hook.
				if (releaseDepth == 0) RELEASE_DEPTH.remove(); else RELEASE_DEPTH.set(releaseDepth);
				if (previous == null) SELECTOR_RELEASE.remove(); else SELECTOR_RELEASE.set(previous);
			}
			if (version == ownershipVersion && (mouse.grabbed() || mouse.screenOpen() || !mouse.focused()
				|| !nativeOwned || mouse.window() != ownedWindow || mouse.cursorMode() != CURSOR_MODE_DISABLED)) endOwnership(mouse, true);
			logger.debug("wheel mouse released");
		} else if (!nativeOwned && !acquisitionSuppressed && mouse.window() != 0L && mouse.cursorMode() == CURSOR_MODE_NORMAL) {
			ownedWindow = mouse.window();
			nativeOwned = true;
			long version = ++ownershipVersion;
			transition(mouse, () -> {
				if (version == ownershipVersion && mouse.window() == ownedWindow && !mouse.grabbed()
					&& !mouse.screenOpen() && mouse.focused() && mouse.cursorMode() == CURSOR_MODE_NORMAL)
					mouse.setCursorMode(CURSOR_MODE_DISABLED);
			});
			if (version == ownershipVersion && (mouse.grabbed() || mouse.screenOpen() || !mouse.focused()
				|| mouse.window() != ownedWindow || mouse.cursorMode() != CURSOR_MODE_DISABLED)) endOwnership(mouse, true);
			logger.debug("wheel cursor disabled");
		}
	}

	public boolean ownsInput(Minecraft game) { return game != null && ownsInput(new MinecraftMouseAccess(game, NO_INPUT)); }
	boolean ownsInput(MouseAccess mouse) {
		if (isTransitioning()) return false;
		if (nativeOwned && (mouse.window() != ownedWindow || mouse.cursorMode() != CURSOR_MODE_DISABLED || mouse.grabbed()))
			dropClaims(mouse);
		return nativeOwned && mouse.window() == ownedWindow && mouse.focused()
			&& !mouse.screenOpen() && !mouse.grabbed() && mouse.cursorMode() == CURSOR_MODE_DISABLED;
	}

	/** External vanilla capture events invalidate ownership even when the native mode stays DISABLED. */
	public void vanillaCaptureChanged(boolean grabbing) {
		Action action = grabbing ? Action.GRAB : Action.RELEASE;
		if (expectedCapture == action) { expectedCapture = Action.NONE; return; }
		if (!nativeOwned && !releasedByWheel && !isTransitioning()) return;
		nativeOwned = false;
		ownedWindow = 0L;
		releasedByWheel = false;
		acquisitionSuppressed = true;
		ownershipVersion++;
	}

	private void dropClaims(MouseAccess mouse) {
		nativeOwned = false;
		ownedWindow = 0L;
		releasedByWheel = false;
		acquisitionSuppressed = true;
		ownershipVersion++;
		transition(mouse, () -> {});
	}

	private void endOwnership(MouseAccess mouse, boolean handoff) {
		if (!nativeOwned && !releasedByWheel) {
			if (handoff) { acquisitionSuppressed = true; ownershipVersion++; }
			return;
		}
		long window = ownedWindow;
		boolean restore = mouse != null && nativeOwned && mouse.window() == window
			&& !mouse.grabbed() && mouse.cursorMode() == CURSOR_MODE_DISABLED;
		boolean reclaim = restore && !handoff && mouse.focused()
			&& nextAction(false, releasedByWheel, mouse.screenOpen(), mouse.grabbed()) == Action.GRAB;
		nativeOwned = false;
		ownedWindow = 0L;
		releasedByWheel = false;
		acquisitionSuppressed = true;
		long version = ++ownershipVersion;
		if (mouse == null) return;
		transition(mouse, () -> {
			if (restore && version == ownershipVersion && mouse.window() == window && !mouse.grabbed()
				&& mouse.cursorMode() == CURSOR_MODE_DISABLED) mouse.setCursorMode(CURSOR_MODE_NORMAL);
			// Re-check after a potentially reentrant native mode write: never grab over a newer owner.
			if (reclaim && version == ownershipVersion && mouse.window() == window && mouse.focused()
				&& !mouse.screenOpen() && !mouse.grabbed() && mouse.cursorMode() == CURSOR_MODE_NORMAL) {
				vanillaCapture(Action.GRAB, mouse::grab);
				logger.debug("wheel mouse regrabbed");
			}
		});
	}

	public void close(Minecraft game) { close(game, false); }
	public void close(Minecraft game, boolean screenTransition) { close(game, screenTransition, NO_INPUT); }
	public void close(Minecraft game, boolean screenTransition, InputTransition input) {
		close(game == null ? null : new MinecraftMouseAccess(game, Objects.requireNonNull(input, "input")), screenTransition);
	}
	/** setScreen HEAD still sees the old null screen; explicit handoff forbids reclaim there. */
	void close(MouseAccess mouse, boolean screenTransition) { endOwnership(mouse, screenTransition); }
}
