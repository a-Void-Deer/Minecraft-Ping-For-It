package nx.pingwheel.neoforge.integration.create;

/**
 * Render-thread scope used by the optional Create entity-outline pass.
 *
 * <p>Create's normal visualization backend must be disabled only while the
 * entity dispatcher is asked to render a contraption or package into the
 * outline mask.  A nesting counter rather than a boolean keeps nested render
 * calls safe, while the returned handle makes cleanup reliable in the face of
 * exceptions.</p>
 */
public final class CreateEntityOutlineMaskScope {
	private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

	private CreateEntityOutlineMaskScope() {}

	/** Enters the current render-thread mask scope. */
	public static Scope enter() {
		DEPTH.set(DEPTH.get() + 1);
		return new Scope();
	}

	/** Whether the current thread is inside at least one mask scope. */
	public static boolean active() {
		return DEPTH.get() > 0;
	}

	/**
	 * Package-private source/test seam.  It deliberately exposes no mutable
	 * production state, but lets a focused test assert nesting and cleanup.
	 */
	static int depthForTests() {
		return DEPTH.get();
	}

	/** Clears a test thread without affecting any other render thread. */
	static void resetForTests() {
		DEPTH.remove();
	}

	/** Idempotent close handle for one {@link #enter()} call. */
	public static final class Scope implements AutoCloseable {
		private boolean closed;

		private Scope() {}

		@Override
		public void close() {
			if (closed) {
				return;
			}

			closed = true;
			int remaining = DEPTH.get() - 1;
			if (remaining <= 0) {
				DEPTH.remove();
			} else {
				DEPTH.set(remaining);
			}
		}
	}
}
