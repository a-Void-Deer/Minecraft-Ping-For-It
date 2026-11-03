package nx.pingwheel.common.screen;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Defers one structural action until the outermost synchronous input dispatch
 * has completely unwound.  Native widgets may update their focus after their
 * callback returns, so replacing a widget tree from inside that callback can
 * otherwise leave focus pointing at an entry that no longer belongs to it.
 */
final class DeferredActionCoordinator {
	private int dispatchDepth;
	private Runnable pendingAction;

	<T> T dispatch(Supplier<T> callback) {
		Objects.requireNonNull(callback, "callback");
		this.dispatchDepth++;
		boolean completed = false;
		try {
			final T result = callback.get();
			completed = true;
			return result;
		} finally {
			this.dispatchDepth--;
			if (!completed) {
				this.pendingAction = null;
			}
			if (this.dispatchDepth == 0 && completed) {
				this.runPendingAction();
			}
		}
	}

	void runAfterDispatch(Runnable action) {
		Objects.requireNonNull(action, "action");
		if (this.dispatchDepth == 0) {
			action.run();
			return;
		}
		// A single input gesture can visit more than one native listener.  Only
		// the final requested structural transition can describe the resulting UI.
		this.pendingAction = action;
	}

	private void runPendingAction() {
		final Runnable action = this.pendingAction;
		this.pendingAction = null;
		if (action != null) {
			action.run();
		}
	}
}
