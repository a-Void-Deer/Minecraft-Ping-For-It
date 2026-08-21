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

	@Test
	void suppressedWarningDoesNotEvaluateItsLazyContextSupplier() {
		AtomicInteger supplierCalls = new AtomicInteger();
		AtomicInteger warningCalls = new AtomicInteger();
		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
			quietRegistry(),
			source("test:ber", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY),
			source("test:baked", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY),
			(message, failure) -> warningCalls.incrementAndGet());

		runner.warnOnce("same-failure", () -> {
			supplierCalls.incrementAndGet();
			return "complete context";
		}, null);
		runner.warnOnce("same-failure", () -> {
			supplierCalls.incrementAndGet();
			return "must remain suppressed";
		}, null);

		assertEquals(1, warningCalls.get());
		assertEquals(1, supplierCalls.get());
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

	private static final class FatalGeometryError extends Error {
		private FatalGeometryError() {
			super("test-only fatal error");
		}
	}
}
