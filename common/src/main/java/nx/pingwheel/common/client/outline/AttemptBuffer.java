package nx.pingwheel.common.client.outline;

import com.mojang.blaze3d.vertex.BufferBuilder;

/**
 * 1.20.1 attempt-local transient buffer.
 *
 * <p>1.21.1 exposes {@code ByteBufferBuilder}, which owns its storage and is
 * {@link AutoCloseable}. The 1.20.1 equivalent is a plain {@link BufferBuilder}
 * whose contents are discarded by {@link BufferBuilder#discard()}; this small
 * wrapper preserves the attempt-scoped try-with-resources ownership used by
 * the outline renderer.</p>
 */
final class AttemptBuffer implements AutoCloseable {

	private final BufferBuilder builder;

	AttemptBuffer(int initialCapacity) {
		this.builder = new BufferBuilder(initialCapacity);
	}

	BufferBuilder builder() {
		return this.builder;
	}

	@Override
	public void close() {
		this.builder.discard();
	}
}
