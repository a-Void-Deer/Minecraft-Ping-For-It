package nx.pingwheel.neoforge.integration.create;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/** Serializes installation/cleanup without running registry callbacks or diagnostics under its monitor. */
@NotNullByDefault
final class CreateRegistrationLifecycle<H extends CreateRegistrationLifecycle.Handle> {
	private @Nullable H registration;
	private boolean changing;
	private @Nullable Thread owningThread;
	private String state = "not-registered";

	/** A null result is an idempotent no-op; only the installing caller reports the returned handle. */
	@Nullable H register(Supplier<H> install) {
		synchronized (this) {
			awaitIdle();
			if (registration != null) return null;
			changing = true;
			owningThread = Thread.currentThread();
		}
		try {
			H installed = install.get();
			String installedState;
			try {
				installedState = installed.state();
			} catch (RuntimeException | Error failure) {
				installed.close();
				throw failure;
			}
			synchronized (this) {
				registration = installed;
				state = installedState;
			}
			return installed;
		} finally {
			synchronized (this) {
				owningThread = null;
				changing = false;
				notifyAll();
			}
		}
	}

	boolean close() {
		H retained;
		synchronized (this) {
			awaitIdle();
			if (registration == null) return false;
			retained = registration;
			changing = true;
			owningThread = Thread.currentThread();
		}
		try {
			retained.close();
			return true;
		} finally {
			synchronized (this) {
				registration = null;
				state = "not-registered";
				owningThread = null;
				changing = false;
				notifyAll();
			}
		}
	}

	synchronized String state() {
		return state;
	}

	/** Business transitions remain serial even when interrupted; a state probe never waits for a transition. */
	private void awaitIdle() {
		boolean interrupted = false;
		while (changing) {
			// A synchronous registry/backend callback cannot wait for its own unfinished transition.
			if (owningThread == Thread.currentThread()) {
				throw new IllegalStateException("create-registration-lifecycle-reentry");
			}
			try {
				wait();
			} catch (InterruptedException ignored) {
				interrupted = true;
			}
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	@NotNullByDefault
	interface Handle extends AutoCloseable {
		String state();
		@Override void close();
	}
}
