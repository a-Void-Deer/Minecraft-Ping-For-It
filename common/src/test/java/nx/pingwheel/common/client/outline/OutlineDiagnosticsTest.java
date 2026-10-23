package nx.pingwheel.common.client.outline;

import nx.pingwheel.common.config.EntityBlockRenderMode;
import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class OutlineDiagnosticsTest {

	@Test
	void failedModelClaimKeepsRetryingAndRunsTheNextAdapterWithItsOriginalCauseReported() {
		try (OutlineDiagnosticCapture capture = new OutlineDiagnosticCapture(Level.WARN)) {
			AtomicInteger claims = new AtomicInteger();
			AtomicInteger rendered = new AtomicInteger();
			WorldAwareBlockModelOutlineAdapter broken = WorldAwareBlockModelOutlineAdapter.of("test:broken", context -> {
				claims.incrementAndGet();
				throw failure("claim-original", "claim-cause");
			}, (context, buffer) -> fail("broken adapter must not render"));
			WorldAwareBlockModelOutlineAdapter next = WorldAwareBlockModelOutlineAdapter.of("test:next", context -> true,
				(context, buffer) -> {});
			for (int frame = 0; frame < 2; frame++) {
				assertEquals(WorldAwareBlockModelOutlineOutcome.RENDERED,
					VirtualBlockDisplayRenderer.attemptWorldAwareBakedModel(List.of(broken, next),
						EntityBlockGeometryContext.empty(), adapter -> {
							assertSame(next, adapter);
							rendered.incrementAndGet();
							return WorldAwareBlockModelOutlineOutcome.RENDERED;
						}));
			}
			assertEquals(2, claims.get());
			assertEquals(2, rendered.get());
			assertEquals(1, capture.events.size());
			assertBoundedCause(capture, "claim-original", "claim-cause");
		}
	}

	@Test
	void observerAndNullOutcomeDiagnosticsDoNotChangeRenderingOrRetryAndUseTheirOwnOnceKeys() {
		try (OutlineDiagnosticCapture capture = new OutlineDiagnosticCapture(Level.WARN)) {
			AtomicInteger attempts = new AtomicInteger();
			EntityBlockGeometrySource nullSource = EntityBlockGeometrySource.of("test:null", context -> {
				attempts.incrementAndGet();
				return null;
			});
			EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(new EntityBlockGeometrySourceRegistry(),
				nullSource, EntityBlockGeometrySource.of("test:valid", context -> EntityBlockGeometryOutcome.RENDERED));
			for (int frame = 0; frame < 2; frame++) {
				assertTrue(runner.run(EntityBlockRenderMode.COMPATIBLE, EntityBlockGeometryContext::empty,
					(id, outcome) -> { throw failure("observer-original", "observer-cause"); }));
			}
			assertEquals(2, attempts.get());
			assertEquals(3, capture.events.size(), "one null-outcome and one observer warning per source");
			assertBoundedCause(capture, "observer-original", "observer-cause");
		}
	}

	@Test
	void sourceIdFailureRetainsItsCauseAndRejectedRegistrationCannotDisplaceTheWinner() {
		try (OutlineDiagnosticCapture capture = new OutlineDiagnosticCapture(Level.WARN)) {
			EntityBlockGeometrySourceRegistry registry = new EntityBlockGeometrySourceRegistry();
			EntityBlockGeometrySource invalid = new EntityBlockGeometrySource() {
				@Override public String id() { throw failure("source-id-original", "source-id-cause"); }
				@Override public EntityBlockGeometryOutcome attempt(EntityBlockGeometryContext context) {
					return EntityBlockGeometryOutcome.EMPTY;
				}
			};
			assertFalse(registry.register(invalid).accepted());
			EntityBlockGeometrySource winner = EntityBlockGeometrySource.of("test:winner", context -> EntityBlockGeometryOutcome.EMPTY);
			assertTrue(registry.register(winner).accepted());
			assertFalse(registry.register(winner).accepted());
			assertEquals(List.of(winner), registry.snapshot());
			assertBoundedCause(capture, "source-id-original", "source-id-cause");
		}
	}

	@Test
	void warningSinkGetsOneBoundedStringAndSinkFailureStaysConsumedWithoutBlockingRetry() {
		List<String> reports = new ArrayList<>();
		AtomicInteger attempts = new AtomicInteger();
		EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(new EntityBlockGeometrySourceRegistry(),
			EntityBlockGeometrySource.of("test:broken", context -> {
				attempts.incrementAndGet();
				throw failure("original" + "x".repeat(20_000), "cause");
			}), EntityBlockGeometrySource.of("test:empty", context -> EntityBlockGeometryOutcome.EMPTY),
			(message, attached) -> {
				assertNull(attached);
				reports.add(message);
				throw new IllegalStateException("sink failure");
			});
		assertFalse(runner.run(EntityBlockRenderMode.COMPATIBLE, EntityBlockGeometryContext::empty));
		assertFalse(runner.run(EntityBlockRenderMode.COMPATIBLE, EntityBlockGeometryContext::empty));
		assertEquals(2, attempts.get());
		assertEquals(1, reports.size());
		assertTrue(reports.getFirst().length() <= SafeExceptionReport.MAX_CHARACTERS);
		assertTrue(reports.getFirst().contains("cause"));
	}

	@Test
	void changingLogLevelDoesNotResetAlreadyConsumedSourceWarning() {
		try (OutlineDiagnosticCapture capture = new OutlineDiagnosticCapture(Level.OFF)) {
			EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(new EntityBlockGeometrySourceRegistry(),
				EntityBlockGeometrySource.of("test:broken", context -> { throw failure("original", "cause"); }),
				EntityBlockGeometrySource.of("test:empty", context -> EntityBlockGeometryOutcome.EMPTY));
			runner.run(EntityBlockRenderMode.COMPATIBLE, EntityBlockGeometryContext::empty);
			capture.level(Level.WARN);
			runner.run(EntityBlockRenderMode.COMPATIBLE, EntityBlockGeometryContext::empty);
			capture.level(Level.OFF);
			runner.run(EntityBlockRenderMode.COMPATIBLE, EntityBlockGeometryContext::empty);
			capture.level(Level.WARN);
			runner.run(EntityBlockRenderMode.COMPATIBLE, EntityBlockGeometryContext::empty);
			assertEquals(1, capture.events.size());
		}
	}

	private static RuntimeException failure(String message, String cause) {
		return new IllegalStateException(message, new IllegalArgumentException(cause));
	}

	private static void assertBoundedCause(OutlineDiagnosticCapture capture, String message, String cause) {
		assertTrue(capture.events.stream().anyMatch(event -> {
			String text = event.getMessage().getFormattedMessage();
			return text.contains(message) && text.contains(cause) && text.contains("OutlineDiagnosticsTest");
		}));
		capture.events.forEach(event -> {
			assertNull(event.getThrown());
			assertTrue(event.getMessage().getFormattedMessage().length() <= SafeExceptionReport.MAX_CHARACTERS);
		});
	}
}
