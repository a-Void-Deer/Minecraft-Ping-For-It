package nx.pingwheel.common.client.outline;

import nx.pingwheel.common.config.EntityBlockRenderMode;
import nx.pingwheel.common.marker.TargetKey;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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
	void allPinsBuiltInOrderAndBuildsFreshExternalContextsWithBothTimings() {
		EntityBlockGeometrySourceRegistry registry = quietRegistry();
		List<String> order = new ArrayList<>();
		List<EntityBlockGeometryContext> contexts = new ArrayList<>();
		AtomicInteger liveBlockEntityLookups = new AtomicInteger();
		TargetKey.ExternalBlockKey externalKey = new TargetKey.ExternalBlockKey(
			"minecraft:overworld", "sable", "plot-1", "minecraft:chest");
		EntityBlockGeometryTransform transform =
			new EntityBlockGeometryTransform(new Matrix4d().translation(20_000_000.0D, 0.0D, 0.0D));

		registry.register(EntityBlockGeometrySource.of("test:modded", context -> {
			order.add("modded");
			contexts.add(context);
			return EntityBlockGeometryOutcome.EMPTY;
		}));
		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
			registry,
			EntityBlockGeometrySource.of("test:ber", context -> {
				order.add("ber");
				contexts.add(context);
				return EntityBlockGeometryOutcome.EMPTY;
			}),
			EntityBlockGeometrySource.of("test:baked", context -> {
				order.add("baked");
				contexts.add(context);
				return EntityBlockGeometryOutcome.EMPTY;
			}));

		assertFalse(runner.run(EntityBlockRenderMode.ALL, () -> {
			liveBlockEntityLookups.incrementAndGet();
			return new EntityBlockGeometryContext(
				null,
				new BlockPos(4, 5, 6),
				null,
				null,
				0x00123456,
				new Vec3(1.25D, 2.5D, 3.75D),
				0.25F,
				0.75F,
				123,
				null,
				null,
				null,
				externalKey,
				42L,
				transform);
		}));

		assertEquals(List.of("ber", "baked", "modded"), order);
		assertEquals(3, liveBlockEntityLookups.get());
		assertEquals(3, contexts.size());
		assertNotSame(contexts.get(0), contexts.get(1));
		assertNotSame(contexts.get(1), contexts.get(2));
		for (EntityBlockGeometryContext context : contexts) {
			assertSame(externalKey, context.targetKey());
			assertSame(transform, context.transform());
			assertEquals(0.25F, context.partialTick());
			assertEquals(0.75F, context.flywheelPartialTick());
			assertEquals(123, context.packedLight());
			assertEquals(42L, context.frameId());
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
	void onlyRenderedEmbeddedOutcomesSuppressTheVoxelFallback() {
		for (EntityBlockGeometryOutcome outcome : new EntityBlockGeometryOutcome[] {
			EntityBlockGeometryOutcome.EMPTY,
			EntityBlockGeometryOutcome.FAILED
		}) {
			EntityBlockGeometrySourceRegistry registry = quietRegistry();
			registry.register(EntityBlockGeometrySource.of(
				"test:embedded", ignored -> outcome));
			EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(
				registry,
				source("test:ber", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY),
				source("test:baked", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY));

			assertFalse(
				runner.run(EntityBlockRenderMode.ALL, EntityBlockGeometryContext::empty),
				"%s must leave the VoxelShape fallback available".formatted(outcome));
		}

		EntityBlockGeometrySourceRegistry renderedRegistry = quietRegistry();
		renderedRegistry.register(EntityBlockGeometrySource.of(
			"test:embedded", ignored -> EntityBlockGeometryOutcome.RENDERED));
		EntityBlockGeometryRunner renderedRunner = new EntityBlockGeometryRunner(
			renderedRegistry,
			source("test:ber", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY),
			source("test:baked", new AtomicInteger(), EntityBlockGeometryOutcome.EMPTY));

		assertTrue(
			renderedRunner.run(EntityBlockRenderMode.ALL, EntityBlockGeometryContext::empty),
			"RENDERED must suppress the VoxelShape fallback");
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
