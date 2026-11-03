package nx.pingwheel.common.client.outline;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the 1.20.1 attempt-buffer ownership boundary.
 *
 * <p>1.20.1 {@link BufferBuilder#discard()} does not release its direct
 * storage, so a per-attempt allocation grows native memory. These tests pin
 * bounded reuse and the complete reset that must happen after success, a clean
 * empty attempt, a partially written attempt, and a failure.
 */
class AttemptBufferTest {

	@AfterEach
	void resetPool() {
		AttemptBufferPool.INSTANCE.clear();
	}

	@Test
	void reusesTheSameBuilderAcrossSequentialAttempts() {
		BufferBuilder first;

		try (AttemptBuffer attempt = new AttemptBuffer(256)) {
			first = attempt.builder();
		}

		try (AttemptBuffer attempt = new AttemptBuffer(256)) {
			assertSame(first, attempt.builder(),
				"a sequential attempt must reuse the bounded builder");
		}
	}

	@Test
	void retainedPoolNeverHoldsMoreThanOneBuilder() {
		try (AttemptBuffer first = new AttemptBuffer(256);
			AttemptBuffer second = new AttemptBuffer(256)) {
			assertNotSame(first.builder(), second.builder(),
				"a nested lease must not hand out the same builder twice");
		}

		assertTrue(AttemptBufferPool.INSTANCE.hasIdleBuilder());
	}

	@Test
	void closeIsIdempotent() {
		AttemptBuffer attempt = new AttemptBuffer(256);

		attempt.close();
		assertTrue(AttemptBufferPool.INSTANCE.hasIdleBuilder());

		attempt.close();
		assertTrue(AttemptBufferPool.INSTANCE.hasIdleBuilder(),
			"a second close must not release or corrupt the pooled builder");
	}

	@Test
	void startedButEmptyAttemptIsResetBeforeReuse() {
		BufferBuilder builder;

		try (AttemptBuffer attempt = new AttemptBuffer(256)) {
			builder = attempt.builder();
			builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
			assertTrue(builder.building());
		}

		assertFalse(builder.building(), "closing must clear the building state");

		try (AttemptBuffer attempt = new AttemptBuffer(256)) {
			assertSame(builder, attempt.builder());
			assertDoesNotThrow(
				() -> builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL),
				"a reused builder must accept the next attempt");
			builder.discard();
		}
	}

	@Test
	void partiallyWrittenAttemptIsResetBeforeReuse() {
		BufferBuilder builder;

		try (AttemptBuffer attempt = new AttemptBuffer(256)) {
			builder = attempt.builder();
			builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
			builder.vertex(0.0D, 0.0D, 0.0D)
				.color(0xFFFFFFFF)
				.normal(0.0F, 1.0F, 0.0F)
				.endVertex();
			assertTrue(builder.building());
		}

		assertFalse(builder.building(),
			"a failed mid-batch attempt must not poison the pooled builder");

		try (AttemptBuffer attempt = new AttemptBuffer(256)) {
			assertSame(builder, attempt.builder());
			assertDoesNotThrow(
				() -> builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL));
			builder.discard();
		}
	}
}
