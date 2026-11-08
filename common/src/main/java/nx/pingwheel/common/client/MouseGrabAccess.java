package nx.pingwheel.common.client;

/**
 * Client-side access to a cursor re-grab that does not pass through
 * {@code Minecraft.setScreen(null)}.
 *
 * <p>1.20.1 {@code MouseHandler.grabMouse()} always calls
 * {@code Minecraft.setScreen(null)}. While the ping wheel is open the screen is
 * already null, so that call is functionally redundant, but it still notifies
 * every {@code setScreen} listener. The Blur mod reacts to that notification by
 * starting its {@code fadeOutTimeMillis} blur fade-out whenever it has seen any
 * non-excluded screen earlier in the session, which makes the whole game view
 * blur for a moment every time the wheel closes.</p>
 *
 * <p>The wheel therefore re-grabs through this accessor, which mirrors the
 * cursor state changes of {@code grabMouse()} without the screen transition.
 * The accessor lives outside the mixin package so production code can use it
 * without referencing any Mixin type.</p>
 */
public interface MouseGrabAccess {

	/**
	 * Grabs the cursor when the window is active and the cursor is currently
	 * free, exactly like {@code MouseHandler.grabMouse()} except that it never
	 * calls {@code Minecraft.setScreen(null)} and never resets every
	 * {@code KeyMapping}.
	 */
	void pingforit$grabWithoutScreenChange();
}
