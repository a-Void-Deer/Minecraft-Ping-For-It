package nx.pingwheel.common.client.outline;

import nx.pingwheel.common.config.EntityBlockRenderMode;
import nx.pingwheel.common.util.SafeExceptionReport;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.DiagnosticFaultReporter.Stage;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.integration.IntegrationDiagnosticCapture;
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
	void worldAwareFailuresKeepTheOnceKeyOnTheRealBlockAndReuseTheRegisteredAdapterIdWithoutAnotherGetter() {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
		AtomicInteger idReads = new AtomicInteger();
		WorldAwareBlockModelOutlineAdapter adapter = new WorldAwareBlockModelOutlineAdapter() {
			@Override public String id() { idReads.incrementAndGet(); return "test:once_adapter"; }
			@Override public boolean handles(EntityBlockGeometryContext context) { return true; }
			@Override public void render(EntityBlockGeometryContext context, OutlineOnlyBufferSource buffer) {}
		};
		try (var registration = WorldAwareBlockModelOutlineAdapterRegistry.INSTANCE.register(adapter);
			OutlineDiagnosticCapture capture = new OutlineDiagnosticCapture(Level.ALL)) {
			assertTrue(registration.accepted());
			var renderer = new VirtualBlockDisplayRenderer();
			for (var block : List.of(net.minecraft.world.level.block.Blocks.STONE, net.minecraft.world.level.block.Blocks.DIRT)) {
				var context = new EntityBlockGeometryContext(null, net.minecraft.core.BlockPos.ZERO, block.defaultBlockState(),
					null, -1, net.minecraft.world.phys.Vec3.ZERO, 0, 0, 0, null, null, null, null, null, 0, null);
				for (int attempt = 0; attempt < 2; attempt++) renderer.recordWorldAwareFailure(adapter, context,
					VirtualBlockDisplayRenderer.FailureStage.RENDER, failure("original", "cause"));
			}
			assertEquals(2, capture.events.size(), "one adapter failing two blocks must retain one report per block registry id");
			assertEquals(1, idReads.get(), "admitted high-profile detail reuses the validated registration id");
			assertTrue(capture.events.getFirst().getMessage().getFormattedMessage().contains("registry=minecraft:stone"));
			assertTrue(capture.events.getLast().getMessage().getFormattedMessage().contains("registry=minecraft:dirt"));
			assertTrue(capture.events.stream().allMatch(event -> event.getMessage().getFormattedMessage().contains("adapterId=test:once_adapter")));
		}
	}
	@Test
	void failedOriginalSourceReportsEligibilityEvenWhenALaterSourceRendersOrTheNativeShapeIsEmpty() {
		for (boolean siblingRenders : List.of(false, true)) {
			var renderer = new VirtualBlockDisplayRenderer();
			try (OutlineDiagnosticCapture capture = new OutlineDiagnosticCapture(Level.ALL)) {
				EntityBlockGeometryRunner runner = new EntityBlockGeometryRunner(new EntityBlockGeometrySourceRegistry(),
					EntityBlockGeometrySource.of("test:broken", context -> {
						renderer.recordFailure("test:block", null, null, null, null,
							VirtualBlockDisplayRenderer.FailureRoute.BLOCK_ENTITY,
							VirtualBlockDisplayRenderer.FailureStage.RENDER, failure("original", "cause"));
						return EntityBlockGeometryOutcome.FAILED;
					}), EntityBlockGeometrySource.of("test:sibling", context -> siblingRenders
						? EntityBlockGeometryOutcome.RENDERED : EntityBlockGeometryOutcome.EMPTY));
				assertEquals(siblingRenders, runner.run(EntityBlockRenderMode.COMPATIBLE, EntityBlockGeometryContext::empty));
				if (!siblingRenders) {
					AtomicInteger edges = new AtomicInteger();
					net.minecraft.world.phys.shapes.Shapes.empty().forAllEdges((x1, y1, z1, x2, y2, z2) -> edges.incrementAndGet());
					assertEquals(0, edges.get(), "eligibility does not guarantee visible fallback geometry");
				}
				assertEquals(1, capture.events.size());
				String text = capture.events.getFirst().getMessage().getFormattedMessage();
				assertTrue(text.contains("source-render-failed"));
				assertTrue(text.contains("fallback-eligible"));
				assertFalse(text.contains("applied"));
				renderer.clear();
				renderer.recordFailure("test:block", null, null, null, null,
					VirtualBlockDisplayRenderer.FailureRoute.BLOCK_ENTITY,
					VirtualBlockDisplayRenderer.FailureStage.FLUSH, failure("again", "cause"));
				assertEquals(1, capture.events.size(), "world teardown must not reset the session once-set");
			}
		}
	}
	@Test
	void normalSummariesDistinguishContextObserverAndSourceFailureWithoutDisclosingIdsOrCollectingDetails() {
		AtomicInteger reads = new AtomicInteger();
		try (IntegrationDiagnosticCapture capture = new IntegrationDiagnosticCapture(Level.ALL, LoggingProfile.NORMAL)) {
			for (OutlineDiagnostics.Category category : List.of(OutlineDiagnostics.Category.CONTEXT_CREATION,
				OutlineDiagnostics.Category.OBSERVER, OutlineDiagnostics.Category.ATTEMPT)) {
				OutlineDiagnostics.approved("entity-block-geometry", category,
					() -> { reads.incrementAndGet(); return "private:source position=1,2,3"; }, failure("private", "private cause"));
			}
			assertEquals(0, reads.get());
			assertEquals(List.of(Level.WARN, Level.WARN, Level.ERROR), capture.events().stream().map(event -> event.getLevel()).toList());
			assertEquals(3, capture.events().stream().map(event -> event.getMessage().getFormattedMessage()).distinct().count());
			assertTrue(capture.events().getFirst().getMessage().getFormattedMessage().contains("context-creation-failed"));
			assertTrue(capture.events().get(1).getMessage().getFormattedMessage().contains("observer-ignored"));
			assertTrue(capture.events().getLast().getMessage().getFormattedMessage().contains("fallback-eligible"));
			assertTrue(capture.events().stream().noneMatch(event -> event.getMessage().getFormattedMessage().contains("private")));
		}
	}

	@Test
	void registrationFailureRetainsFiniteCategoryButDoesNotDiscloseSourceIdentityAtNormal() {
		try (IntegrationDiagnosticCapture capture = new IntegrationDiagnosticCapture(Level.ALL, LoggingProfile.NORMAL)) {
			EntityOutlineSourceRegistry registry = new EntityOutlineSourceRegistry();
			assertFalse(registry.register(null).accepted());
			EntityOutlineSource source = EntityOutlineSource.of("private:source", entity -> false,
				context -> EntityBlockGeometryOutcome.EMPTY);
			assertTrue(registry.register(source).accepted());
			assertFalse(registry.register(source).accepted());
			assertEquals(2, capture.events().size());
			String duplicate = capture.events().getLast().getMessage().getFormattedMessage();
			assertTrue(duplicate.contains("duplicate-id-first-registration-kept"));
			assertFalse(duplicate.contains("private:source"));
			assertEquals(List.of(source), registry.snapshot());
		}
	}

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

	@Test
	void contextSelfFaultLeavesUnavailableContextAndTheOriginalBusinessCause() {
		long before = Diagnostics.faults().observations().stream()
			.filter(observation -> observation.key().side() == DiagnosticSide.CLIENT
				&& observation.key().site().equals("outline/custom-sink") && observation.key().stage() == Stage.CONTEXT)
			.mapToLong(observation -> observation.occurrences()).sum();
		List<String> reports = new ArrayList<>();
		OutlineDiagnostics.deliver(reports::add, () -> { throw new IllegalStateException("context fault"); },
			failure("business-original", "business-cause"));
		assertEquals(1, reports.size());
		assertTrue(reports.getFirst().contains("context unavailable"));
		assertTrue(reports.getFirst().contains("business-original"));
		assertTrue(reports.getFirst().contains("business-cause"));
		long after = Diagnostics.faults().observations().stream()
			.filter(observation -> observation.key().side() == DiagnosticSide.CLIENT
				&& observation.key().site().equals("outline/custom-sink") && observation.key().stage() == Stage.CONTEXT)
			.mapToLong(observation -> observation.occurrences()).sum();
		assertEquals(before + 1, after);
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
