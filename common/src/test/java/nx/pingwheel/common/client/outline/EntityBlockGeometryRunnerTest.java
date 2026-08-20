package nx.pingwheel.common.client.outline;

import nx.pingwheel.common.config.EntityBlockRenderMode;
import nx.pingwheel.common.marker.TargetKey;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityBlockGeometryRunnerTest {
	@Test
	void allInvokesBothBuiltInsAndEveryModdedSource() {
		EntityBlockGeometrySourceRegistry registry = quietRegistry();
		AtomicInteger berCalls = new AtomicInteger();
		AtomicInteger bakedCalls = new AtomicInteger();
		AtomicInteger moddedCalls = new AtomicInteger();
		registry.register(source("test:first", moddedCalls, EntityBlockGeometryOutcome.RENDERED));
		registry.register(source("test:second", moddedCalls, EntityBlockGeometryOutcome.RENDERED));

		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
			registry,
			source("test:ber", berCalls, EntityBlockGeometryOutcome.RENDERED),
			source("test:baked", bakedCalls, EntityBlockGeometryOutcome.EMPTY));

		assertTrue(runner.run(EntityBlockRenderMode.ALL, EntityBlockGeometryContext::empty));
		assertEquals(1, berCalls.get());
		assertEquals(1, bakedCalls.get());
		assertEquals(2, moddedCalls.get());
	}

	@Test
	void eachSourceGetsAnIndependentDeferredSinkForTheSameTarget() {
		DeferredEntityBlockGeometryState state = DeferredEntityBlockGeometryState.INSTANCE;
		TargetKey.BlockKey key = new TargetKey.BlockKey(
			"minecraft:overworld", 20, 64, 0, "minecraft:stone");
		state.beginFrame();
		EntityBlockGeometrySourceRegistry registry = quietRegistry();

		registry.register(deferredSource("test:first", key, 1));
		registry.register(deferredSource("test:second", key, 2));
		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
			registry,
			EntityBlockGeometrySource.of("test:ber", ignored -> EntityBlockGeometryOutcome.EMPTY),
			EntityBlockGeometrySource.of("test:baked", ignored -> EntityBlockGeometryOutcome.EMPTY));

		try {
			assertTrue(runner.run(EntityBlockRenderMode.ALL,
				() -> context(state.open(key), key)));
			assertEquals(2, state.linesFor(key).size());
			assertEquals(2, state.committedLineCount());
		} finally {
			state.leave();
		}
	}

	@Test
	void allAllowsOnlyModdedSuccessToSuppressVoxelFallback() {
		EntityBlockGeometrySourceRegistry registry = quietRegistry();
		AtomicInteger moddedCalls = new AtomicInteger();
		registry.register(source("test:success", moddedCalls, EntityBlockGeometryOutcome.RENDERED));
		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
			registry,
			source("test:ber", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY),
			source("test:baked", new AtomicInteger(), EntityBlockGeometryOutcome.FAILED));

		assertTrue(runner.run(EntityBlockRenderMode.ALL, EntityBlockGeometryContext::empty));
		assertEquals(1, moddedCalls.get());
	}

	@Test
	void allFailuresReturnFalseAndDoNotSuppressVoxelFallback() {
		EntityBlockGeometrySourceRegistry registry = quietRegistry();
		AtomicInteger laterCalls = new AtomicInteger();
		registry.register(source("test:later", laterCalls, EntityBlockGeometryOutcome.FAILED));
		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
			registry,
			source("test:ber", new AtomicInteger(), EntityBlockGeometryOutcome.FAILED),
			source("test:baked", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY));

		assertFalse(runner.run(EntityBlockRenderMode.ALL, EntityBlockGeometryContext::empty));
		assertEquals(1, laterCalls.get());
	}

	@Test
	void compatibleSkipsAllModdedSources() {
		EntityBlockGeometrySourceRegistry registry = quietRegistry();
		AtomicInteger moddedCalls = new AtomicInteger();
		registry.register(source("test:ignored", moddedCalls, EntityBlockGeometryOutcome.RENDERED));
		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
			registry,
			source("test:ber", new AtomicInteger(), EntityBlockGeometryOutcome.RENDERED),
			source("test:baked", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY));

		assertTrue(runner.run(EntityBlockRenderMode.COMPATIBLE, EntityBlockGeometryContext::empty));
		assertEquals(0, moddedCalls.get());
	}

	@Test
	void voxelShapeOnlyInvokesNothingAndDoesNotConstructContext() {
		EntityBlockGeometrySourceRegistry registry = quietRegistry();
		AtomicInteger sourceCalls = new AtomicInteger();
		registry.register(source("test:ignored", sourceCalls, EntityBlockGeometryOutcome.RENDERED));
		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
			registry,
			source("test:ber", sourceCalls, EntityBlockGeometryOutcome.RENDERED),
			source("test:baked", sourceCalls, EntityBlockGeometryOutcome.RENDERED));
		AtomicInteger contextCalls = new AtomicInteger();

		assertFalse(runner.run(EntityBlockRenderMode.VOXEL_SHAPE_ONLY, () -> {
			contextCalls.incrementAndGet();
			return EntityBlockGeometryContext.empty();
		}));
		assertEquals(0, contextCalls.get());
		assertEquals(0, sourceCalls.get());
	}

	@Test
	void emptyIsNotSuccess() {
		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
			quietRegistry(),
			source("test:ber", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY),
			source("test:baked", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY));

		assertFalse(runner.run(EntityBlockRenderMode.COMPATIBLE, EntityBlockGeometryContext::empty));
	}

	@Test
	void failedAndThrownSourcesDoNotStopLaterSources() {
		EntityBlockGeometrySourceRegistry registry = quietRegistry();
		AtomicInteger laterCalls = new AtomicInteger();
		registry.register(source("mod:later", laterCalls, EntityBlockGeometryOutcome.RENDERED));
		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
			registry,
			source("test:ber", new AtomicInteger(), EntityBlockGeometryOutcome.FAILED),
		EntityBlockGeometrySource.of("test:baked", ignored -> {
				throw new IllegalStateException("test-only failure");
			}));

		assertTrue(runner.run(EntityBlockRenderMode.ALL, EntityBlockGeometryContext::empty));
		assertEquals(1, laterCalls.get());
	}

	@Test
	void linkageAndAssertionFailuresDoNotStopLaterSources() {
		EntityBlockGeometrySourceRegistry registry = quietRegistry();
		AtomicInteger laterCalls = new AtomicInteger();
		registry.register(EntityBlockGeometrySource.of("test:linkage", ignored -> {
			throw new LinkageError("test-only linkage failure");
		}));
		registry.register(EntityBlockGeometrySource.of("test:assertion", ignored -> {
			throw new AssertionError("test-only assertion failure");
		}));
		registry.register(source("test:later", laterCalls, EntityBlockGeometryOutcome.RENDERED));

		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
			registry,
			source("test:ber", new AtomicInteger(), EntityBlockGeometryOutcome.FAILED),
			EntityBlockGeometrySource.of("test:baked", ignored -> {
				throw new IllegalArgumentException("test-only exception");
			}));

		assertTrue(runner.run(EntityBlockRenderMode.ALL, EntityBlockGeometryContext::empty));
		assertEquals(1, laterCalls.get());
	}

	@Test
	void fatalErrorOutsideFailSoftSetPropagatesAndStopsLaterSources() {
		EntityBlockGeometrySourceRegistry registry = quietRegistry();
		AtomicInteger laterCalls = new AtomicInteger();
		registry.register(source("test:later", laterCalls, EntityBlockGeometryOutcome.RENDERED));
		EntityBlockGeometrySource fatal = EntityBlockGeometrySource.of("test:fatal", ignored -> {
			throw new FatalGeometryError();
		});
		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
			registry,
			fatal,
			source("test:baked", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY));

		assertThrows(FatalGeometryError.class,
			() -> runner.run(EntityBlockRenderMode.ALL, EntityBlockGeometryContext::empty));
		assertEquals(0, laterCalls.get());
	}

	@Test
	void switchingModeOnTheSameRunnerTakesEffectImmediately() {
		EntityBlockGeometrySourceRegistry registry = quietRegistry();
		AtomicInteger moddedCalls = new AtomicInteger();
		registry.register(source("test:dynamic", moddedCalls, EntityBlockGeometryOutcome.RENDERED));
		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
			registry,
			source("test:ber", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY),
			source("test:baked", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY));

		assertTrue(runner.run(EntityBlockRenderMode.ALL, EntityBlockGeometryContext::empty));
		assertFalse(runner.run(EntityBlockRenderMode.COMPATIBLE, EntityBlockGeometryContext::empty));
		assertFalse(runner.run(EntityBlockRenderMode.VOXEL_SHAPE_ONLY, () -> {
			throw new AssertionError("VOXEL_SHAPE_ONLY must not construct context");
		}));
		assertEquals(1, moddedCalls.get());
	}

	private static EntityBlockGeometrySourceRegistry quietRegistry() {
		return new EntityBlockGeometrySourceRegistry(EntityBlockGeometrySourceRegistry.WarningSink.noop());
	}

	private static EntityBlockGeometrySource source(
		String id,
		AtomicInteger calls,
		EntityBlockGeometryOutcome outcome
	) {
		return EntityBlockGeometrySource.of(id, ignored -> {
			calls.incrementAndGet();
			return outcome;
		});
	}

	private static EntityBlockGeometrySource deferredSource(
		String id,
		TargetKey.BlockKey key,
		int offset
	) {
		return EntityBlockGeometrySource.of(id, context -> {
			EntityBlockGeometryLineSink sink = context.lineSink();
			assertTrue(sink.addLine(offset, 0, 0, offset, 1, 0));
			return sink.commit()
				? EntityBlockGeometryOutcome.RENDERED
				: EntityBlockGeometryOutcome.FAILED;
		});
	}

	private static EntityBlockGeometryContext context(
		EntityBlockGeometryLineSink sink,
		TargetKey.BlockKey key
	) {
		return new EntityBlockGeometryContext(
			null, null, null, null, 0xFFFFFFFF, null, 0.0F, 0,
			null, null, null, key, sink);
	}

	private static final class FatalGeometryError extends Error {
		private FatalGeometryError() {
			super("test-only fatal error");
		}
	}
}
