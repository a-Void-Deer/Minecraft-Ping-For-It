package nx.pingwheel.common.client.outline;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.MarkerId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityOutlineRunnerTest {

	@BeforeAll
	static void bootStrap() {
		TestEntitySupport.bootStrap();
	}

	@Test
	void firstRenderedSourceShortCircuitsInRegistryOrder() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		AtomicInteger firstCalls = new AtomicInteger();
		AtomicInteger secondCalls = new AtomicInteger();
		AtomicInteger thirdCalls = new AtomicInteger();

		registry.register(source("mod:second", e -> e == entity, secondCalls, EntityBlockGeometryOutcome.EMPTY));
		registry.register(source("mod:first", e -> e == entity, firstCalls, EntityBlockGeometryOutcome.RENDERED));
		registry.register(source("mod:third", e -> e == entity, thirdCalls, EntityBlockGeometryOutcome.RENDERED));

		EntityOutlineRunner runner = new EntityOutlineRunner(registry);
		EntityOutlineContext context = EntityOutlineContext.empty(entity, spec());

		assertEquals(EntityBlockGeometryOutcome.RENDERED, runner.run(context));
		// Registration order is authoritative: mod:second runs first, mod:first
		// renders and short-circuits, so mod:third never runs.
		assertEquals(1, secondCalls.get());
		assertEquals(1, firstCalls.get());
		assertEquals(0, thirdCalls.get());
	}

	@Test
	void unhandledSourcesAreSkipped() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		AtomicInteger calls = new AtomicInteger();
		registry.register(source("mod:handles", e -> e == entity, calls, EntityBlockGeometryOutcome.RENDERED));

		EntityOutlineRunner runner = new EntityOutlineRunner(registry);
		assertEquals(
			EntityBlockGeometryOutcome.RENDERED,
			runner.run(EntityOutlineContext.empty(entity, spec())));
		assertEquals(1, calls.get());
	}

	@Test
	void emptyContinuesAndReturnsEmptyWhenNothingRenders() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		AtomicInteger calls = new AtomicInteger();
		registry.register(source("mod:empty", e -> e == entity, calls, EntityBlockGeometryOutcome.EMPTY));
		registry.register(source("mod:also-empty", e -> e == entity, calls, EntityBlockGeometryOutcome.EMPTY));

		EntityOutlineRunner runner = new EntityOutlineRunner(registry);
		assertEquals(
			EntityBlockGeometryOutcome.EMPTY,
			runner.run(EntityOutlineContext.empty(entity, spec())));
		assertEquals(2, calls.get());
	}

	@Test
	void failedContinuesButWinsWhenNothingRenders() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		AtomicInteger calls = new AtomicInteger();
		registry.register(source("mod:failed", e -> e == entity, calls, EntityBlockGeometryOutcome.FAILED));
		registry.register(source("mod:empty", e -> e == entity, calls, EntityBlockGeometryOutcome.EMPTY));

		EntityOutlineRunner runner = new EntityOutlineRunner(registry);
		assertEquals(
			EntityBlockGeometryOutcome.FAILED,
			runner.run(EntityOutlineContext.empty(entity, spec())));
		assertEquals(2, calls.get());
	}

	@Test
	void recoverableHandlesAndAttemptFailuresFailSoftAndContinue() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		AtomicInteger laterCalls = new AtomicInteger();
		registry.register(EntityOutlineSource.of("mod:throwing-handles", e -> {
			throw new IllegalStateException("test-only handles failure");
		}, ctx -> EntityBlockGeometryOutcome.EMPTY));
		registry.register(EntityOutlineSource.of("mod:throwing-attempt", e -> true, ctx -> {
			throw new LinkageError("test-only attempt failure");
		}));
		registry.register(source("mod:later", e -> e == entity, laterCalls, EntityBlockGeometryOutcome.RENDERED));

		EntityOutlineRunner runner = new EntityOutlineRunner(registry);
		assertEquals(
			EntityBlockGeometryOutcome.RENDERED,
			runner.run(EntityOutlineContext.empty(entity, spec())));
		assertEquals(1, laterCalls.get());
	}

	@Test
	void fatalErrorOutsideFailSoftSetPropagatesAndStopsLaterSources() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		AtomicInteger laterCalls = new AtomicInteger();
		registry.register(EntityOutlineSource.of("mod:fatal", e -> true, ctx -> {
			throw new FatalOutlineError();
		}));
		registry.register(source("mod:later", e -> e == entity, laterCalls, EntityBlockGeometryOutcome.RENDERED));

		EntityOutlineRunner runner = new EntityOutlineRunner(registry);
		assertThrows(FatalOutlineError.class,
			() -> runner.run(EntityOutlineContext.empty(entity, spec())));
		assertEquals(0, laterCalls.get());
	}

	@Test
	void noHandlingSourceReturnsEmpty() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		EntityOutlineRunner runner = new EntityOutlineRunner(registry);

		assertEquals(
			EntityBlockGeometryOutcome.EMPTY,
			runner.run(EntityOutlineContext.empty(entity, spec())));
	}

	@Test
	void everyCallRetriesEverySourceEvenAfterFailure() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		AtomicInteger calls = new AtomicInteger();
		registry.register(EntityOutlineSource.of("mod:flaky", e -> true, ctx -> {
			calls.incrementAndGet();
			throw new IllegalStateException("test-only flaky failure");
		}));

		EntityOutlineRunner runner = new EntityOutlineRunner(registry);
		EntityOutlineContext context = EntityOutlineContext.empty(entity, spec());
		runner.run(context);
		runner.run(context);

		// A failed source is never blacklisted; it is retried on every call.
		assertEquals(2, calls.get());
	}

	@Test
	void suppressedWarningDoesNotEvaluateItsLazyContextSupplier() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		AtomicInteger supplierCalls = new AtomicInteger();
		AtomicInteger warningCalls = new AtomicInteger();
		EntityOutlineRunner runner = new EntityOutlineRunner(
			registry, (message, failure) -> warningCalls.incrementAndGet());

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

	@Test
	void sourceReportsRenderedWhenACommitBudgetFailsAfterWritingVertices() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		AtomicInteger laterCalls = new AtomicInteger();

		registry.register(EntityOutlineSource.of("mod:partial", e -> e == entity, context -> {
			int committedVertices = 0;
			try {
				committedVertices++;
				throw new BudgetCommitException();
			} catch (BudgetCommitException failure) {
				return committedVertices > 0
					? EntityBlockGeometryOutcome.RENDERED
					: EntityBlockGeometryOutcome.FAILED;
			}
		}));
		registry.register(source("mod:must-not-run", e -> e == entity, laterCalls,
			EntityBlockGeometryOutcome.RENDERED));

		assertEquals(
			EntityBlockGeometryOutcome.RENDERED,
			new EntityOutlineRunner(registry).run(EntityOutlineContext.empty(entity, spec())));
		assertEquals(0, laterCalls.get(), "partial shared-buffer commits own the frame");
	}

	@Test
	void sourceMayReportFailedWhenACommitBudgetFailsBeforeAnyVertex() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		AtomicInteger laterCalls = new AtomicInteger();

		registry.register(EntityOutlineSource.of("mod:empty-commit", e -> e == entity, context -> {
			int committedVertices = 0;
			try {
				throw new BudgetCommitException();
			} catch (BudgetCommitException failure) {
				return committedVertices > 0
					? EntityBlockGeometryOutcome.RENDERED
					: EntityBlockGeometryOutcome.FAILED;
			}
		}));
		registry.register(source("mod:fallback", e -> e == entity, laterCalls,
			EntityBlockGeometryOutcome.RENDERED));

		assertEquals(
			EntityBlockGeometryOutcome.RENDERED,
			new EntityOutlineRunner(registry).run(EntityOutlineContext.empty(entity, spec())));
		assertEquals(1, laterCalls.get(), "zero-write failures allow the next source");
	}

	private static EntityOutlineSpec spec() {
		return new EntityOutlineSpec(
			new MarkerId(1L),
			EntityLocator.uuid(UUID.randomUUID()),
			"attention",
			0xFFFFFFFF);
	}

	private static EntityOutlineSourceRegistry quietRegistry() {
		return new EntityOutlineSourceRegistry(EntityOutlineSourceRegistry.WarningSink.noop());
	}

	private static EntityOutlineSource source(
		String id,
		java.util.function.Predicate<net.minecraft.world.entity.Entity> handles,
		AtomicInteger calls,
		EntityBlockGeometryOutcome outcome
	) {
		return EntityOutlineSource.of(id, handles, ctx -> {
			calls.incrementAndGet();
			return outcome;
		});
	}

	private static final class FatalOutlineError extends Error {
		private FatalOutlineError() {
			super("test-only fatal error");
		}
	}

	private static final class BudgetCommitException extends Exception {
		private BudgetCommitException() {
			super("test-only shared-buffer budget failure");
		}
	}
}
