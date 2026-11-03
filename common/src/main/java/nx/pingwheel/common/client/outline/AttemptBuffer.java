package nx.pingwheel.common.client.outline;

import com.mojang.blaze3d.vertex.BufferBuilder;

/**
 * 1.20.1 attempt-local transient buffer.
 *
 * <p>1.21.1 exposes {@code ByteBufferBuilder}, which owns its storage and is
 * {@link AutoCloseable}. The 1.20.1 equivalent is a plain {@link BufferBuilder}
 * whose storage is not released by {@link BufferBuilder#discard()}. This wrapper
 * preserves the attempt-scoped try-with-resources ownership used by the
 * outline renderer while leasing the builder from a bounded pool, so a
 * continuous outline pass reuses one direct allocation instead of creating a
 * new one per attempt.</p>
 *
 * <p>The pool reset runs in {@link #close()} on success, failure, and
 * exceptional exit, and {@link #close()} is idempotent.</p>
 */
final class AttemptBuffer implements AutoCloseable {

	private final BufferBuilder builder;
	private boolean closed;

	AttemptBuffer(int initialCapacity) {
		this.builder = AttemptBufferPool.INSTANCE.acquire(initialCapacity);
	}

	BufferBuilder builder() {
		return this.builder;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		AttemptBufferPool.INSTANCE.release(this.builder);
	}
}
