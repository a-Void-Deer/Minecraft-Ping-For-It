package nx.pingwheel.common.client.outline;

import com.mojang.blaze3d.vertex.BufferBuilder;

/**
 * Bounded single-slot pool for attempt-local 1.20.1 {@link BufferBuilder}
 * instances.
 *
 * <p>1.20.1 {@link BufferBuilder} owns a direct {@link java.nio.ByteBuffer}
 * allocated through {@code MemoryTracker}, while {@link BufferBuilder#discard()}
 * only resets its counters and keeps that storage. Allocating one builder per
 * model attempt therefore grows native memory without bound. Reusing one
 * builder keeps exactly one retained allocation, and a complete reset covers
 * every exit path.</p>
 *
 * <p>Reset is deliberately public-API only: a builder that is still
 * {@linkplain BufferBuilder#building() building} (a recoverable attempt failed
 * after {@code begin()} or after a partial vertex) is first ended through
 * {@link BufferBuilder#endOrDiscardIfEmpty()} so the internal building/vertex
 * state is cleared, and {@link BufferBuilder#discard()} then resets the
 * rendered-buffer pointers. {@code discard()} alone would leave
 * {@code building == true} and make the next {@code begin()} throw.</p>
 *
 * <p>Main/render thread only. Attempts are sequential, so a single slot is the
 * correct bound; a nested lease falls back to a fresh builder rather than
 * handing the same instance out twice, and the extra builder is dropped once a
 * slot is already occupied.</p>
 */
final class AttemptBufferPool {

	static final AttemptBufferPool INSTANCE = new AttemptBufferPool();

	private BufferBuilder idle;

	private AttemptBufferPool() {}

	/**
	 * Leases a fully reset builder, allocating only when no builder is idle.
	 */
	BufferBuilder acquire(int initialCapacity) {
		BufferBuilder builder = this.idle;

		if (builder == null) {
			return new BufferBuilder(initialCapacity);
		}

		this.idle = null;
		reset(builder);
		return builder;
	}

	/**
	 * Resets the builder completely and retains it when the slot is free.
	 * A second builder is dropped so the pool can never retain more than one
	 * direct allocation.
	 */
	void release(BufferBuilder builder) {
		if (builder == null) {
			return;
		}

		reset(builder);

		if (this.idle == null) {
			this.idle = builder;
		}
	}

	/** Test seam: whether an idle builder is currently retained. */
	boolean hasIdleBuilder() {
		return this.idle != null;
	}

	/** Test seam: drops the retained builder so a focused test starts clean. */
	void clear() {
		this.idle = null;
	}

	private static void reset(BufferBuilder builder) {
		if (builder.building()) {
			// Ends (or discards, when empty) the in-flight batch and resets the
			// internal building/vertex/format state without touching storage.
			builder.endOrDiscardIfEmpty();
		}

		builder.discard();
	}
}
