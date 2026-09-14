package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.config.EntityBlockRenderMode;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.TargetKey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPresentationCoverageTrackerTest {

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@BeforeEach
	void resetFrameState() {
		BlockModelOutlineState.INSTANCE.clear();
	}

	@AfterEach
	void clearFrameState() {
		BlockModelOutlineState.INSTANCE.clear();
	}

	@Test
	void activatesOnlyForTheExactRenderedOwnerSourceWithoutTransitivity() {
		BlockPresentation presentation = presentation(List.of(
			new BlockPresentationCoverageRelation(
				"lower", EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID, "upper"),
			new BlockPresentationCoverageRelation(
				"upper", EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID, "third")));
		BlockPresentationCoverageTracker tracker =
			BlockPresentationCoverageTracker.forPresentation(presentation);

		assertFalse(tracker.covers("upper"));
		assertFalse(tracker.covers("third"));
		tracker.recordOutcome("lower", EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID,
			EntityBlockGeometryOutcome.RENDERED);
		assertFalse(tracker.covers("upper"));
		tracker.recordOutcome("lower", EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID,
			EntityBlockGeometryOutcome.EMPTY);
		assertFalse(tracker.covers("upper"));
		tracker.recordOutcome("lower", EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID,
			EntityBlockGeometryOutcome.RENDERED);
		assertTrue(tracker.covers("upper"));
		assertFalse(tracker.covers("third"));
	}

	@Test
	void emptyRelationsDoNotAllocateFrameTracking() {
		assertNull(BlockPresentationCoverageTracker.forPresentation(presentation(List.of())));
	}

	@Test
	void renderedLowerBerSkipsOnlyItsCoveredUpperAndKeepsIndependentSubject() {
		BlockPresentation presentation = presentation(List.of(
			new BlockPresentationCoverageRelation(
				"lower", EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID, "upper")));
		List<String> renderedSubjects = new java.util.ArrayList<>();
		List<String> successfulSubjects = new java.util.ArrayList<>();

		BlockPresentationSubjectDispatcher.dispatch(
			presentation,
			ignored -> true,
			subject -> successfulSubjects.add(subject.subjectId()),
			(subject, observer) -> {
				renderedSubjects.add(subject.subjectId());
				if (observer != null) {
					observer.accept(
						EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID,
						EntityBlockGeometryOutcome.RENDERED);
					observer.accept(
						EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID,
						EntityBlockGeometryOutcome.RENDERED);
				}
				return true;
			});

		assertEquals(List.of("lower", "third"), renderedSubjects);
		assertEquals(List.of("lower", "upper", "third"), successfulSubjects);
	}

	@Test
	void bakedOnlyOrFailedLowerDoesNotCoverUpper() {
		for (EntityBlockGeometryOutcome outcome : List.of(
			EntityBlockGeometryOutcome.EMPTY,
			EntityBlockGeometryOutcome.FAILED,
			EntityBlockGeometryOutcome.RENDERED)) {
			BlockPresentation presentation = presentation(List.of(
				new BlockPresentationCoverageRelation(
					"lower", EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID, "upper")));
			List<String> renderedSubjects = new java.util.ArrayList<>();
			BlockPresentationSubjectDispatcher.dispatch(
				presentation,
				ignored -> true,
				ignored -> {},
				(subject, observer) -> {
					renderedSubjects.add(subject.subjectId());
					if (subject.subjectId().equals("lower") && observer != null) {
						observer.accept(
							EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID, outcome);
						observer.accept(
							EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID,
							EntityBlockGeometryOutcome.RENDERED);
					}
					return false;
				});

			if (outcome == EntityBlockGeometryOutcome.RENDERED) {
				assertEquals(List.of("lower", "third"), renderedSubjects);
			} else {
				assertEquals(List.of("lower", "upper", "third"), renderedSubjects);
			}
		}
	}

	private static BlockPresentation presentation(
		List<BlockPresentationCoverageRelation> relations
	) {
		BlockPos sourcePos = new BlockPos(0, 64, 0);
		return new BlockPresentation(
			new BlockOutlineSpec(
				new MarkerId(1L),
				new TargetKey.BlockKey(
					"minecraft:overworld", sourcePos.getX(), sourcePos.getY(), sourcePos.getZ(),
					"minecraft:stone"),
				"entity_block",
				"attention",
				0xFF000000),
			List.of(
				subject("lower", new BlockPos(0, 64, 0)),
				subject("upper", new BlockPos(0, 65, 0)),
				subject("third", new BlockPos(0, 66, 0))),
			relations);
	}

	private static BlockRenderSubject subject(String id, BlockPos pos) {
		return new BlockRenderSubject(
			id,
			pos,
			Blocks.STONE.defaultBlockState(),
			"minecraft:stone",
			"entity_block",
			BlockPresentationRelation.COMPOSITE);
	}

	@Test
	void animatedAllUsesActualBerCoverageAndKeepsThirdSubjectIndependent() {
		BlockPresentation presentation = coveredPresentation(0);
		BlockModelOutlineState state = beginFrame(presentation);
		RunnerHarness runner = new RunnerHarness();
		SubjectAttempt lower = attempt("lower", EntityBlockRenderMode.ALL,
			Behavior.RENDERED, Behavior.EMPTY, Behavior.EMPTY);
		SubjectAttempt upper = attempt("upper", EntityBlockRenderMode.ALL,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);
		SubjectAttempt third = attempt("third", EntityBlockRenderMode.ALL,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);

		Dispatch dispatch = dispatch(presentation, state, runner, attempts(lower, upper, third), ignored -> true);

		assertEquals(List.of("lower", "third"), dispatch.renderedSubjects());
		assertEquals(List.of("lower", "upper"), dispatch.successfulSubjects());
		assertEquals(List.of(
			EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID,
			EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID,
			"test:optional"), lower.sourceCalls());
		assertEquals(List.of(
			EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID + ":RENDERED",
			EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID + ":EMPTY",
			"test:optional:EMPTY"), lower.observedOutcomes());
		assertTrue(upper.sourceCalls().isEmpty(), "covered upper must not receive a second full dispatch");
		assertEquals(List.of(
			EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID,
			EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID,
			"test:optional"), third.sourceCalls(), "third subject must remain independent");
		assertEquals(Set.of(successKey(presentation, "lower"), successKey(presentation, "upper")),
			state.successKeys());
	}

	@Test
	void settledCompatibleAndAllRunUpperWhenOnlyLowerBakedGeometryRenders() {
		for (EntityBlockRenderMode mode : List.of(
			EntityBlockRenderMode.COMPATIBLE, EntityBlockRenderMode.ALL)) {
			BlockPresentation presentation = coveredPresentation(0);
			BlockModelOutlineState state = beginFrame(presentation);
			RunnerHarness runner = new RunnerHarness();
			SubjectAttempt lower = attempt("lower", mode,
				Behavior.EMPTY, Behavior.RENDERED, Behavior.EMPTY);
			SubjectAttempt upper = attempt("upper", mode,
				Behavior.EMPTY, Behavior.RENDERED, Behavior.EMPTY);
			SubjectAttempt third = attempt("third", mode,
				Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);

			Dispatch dispatch = dispatch(
				presentation, state, runner, attempts(lower, upper, third), ignored -> true);

			assertEquals(List.of("lower", "upper", "third"), dispatch.renderedSubjects(),
				mode + " must not convert lower aggregate success to upper coverage");
			assertTrue(upper.sourceCalls().contains(EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID));
			assertEquals(Set.of(successKey(presentation, "lower"), successKey(presentation, "upper")),
				state.successKeys());
		}
	}

	@Test
	void bridgeRunsAllLowerSourcesButSkipsCoveredUpper() {
		BlockPresentation presentation = coveredPresentation(0);
		BlockModelOutlineState state = beginFrame(presentation);
		RunnerHarness runner = new RunnerHarness();
		SubjectAttempt lower = attempt("lower", EntityBlockRenderMode.ALL,
			Behavior.RENDERED, Behavior.RENDERED, Behavior.RENDERED);
		SubjectAttempt upper = attempt("upper", EntityBlockRenderMode.ALL,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);
		SubjectAttempt third = attempt("third", EntityBlockRenderMode.ALL,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);

		Dispatch dispatch = dispatch(presentation, state, runner, attempts(lower, upper, third), ignored -> true);

		assertEquals(List.of("lower", "third"), dispatch.renderedSubjects());
		assertEquals(List.of(
			EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID,
			EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID,
			"test:optional"), lower.sourceCalls(),
			"ALL must not short-circuit after BER");
		assertTrue(upper.sourceCalls().isEmpty(), "bridge must prevent a second full-door dispatch");
		assertEquals(Set.of(successKey(presentation, "lower"), successKey(presentation, "upper")),
			state.successKeys());
	}

	@Test
	void failedEmptyAndThrownBerLeaveFallbackSuccessAbsent() {
		for (Behavior ber : List.of(Behavior.FAILED, Behavior.EMPTY, Behavior.THROWS)) {
			BlockPresentation presentation = coveredPresentation(0);
			BlockModelOutlineState state = beginFrame(presentation);
			RunnerHarness runner = new RunnerHarness();
			SubjectAttempt lower = attempt("lower", EntityBlockRenderMode.ALL,
				ber, Behavior.EMPTY, Behavior.EMPTY);
			SubjectAttempt upper = attempt("upper", EntityBlockRenderMode.ALL,
				Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);
			SubjectAttempt third = attempt("third", EntityBlockRenderMode.ALL,
				Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);

			Dispatch dispatch = dispatch(
				presentation, state, runner, attempts(lower, upper, third), ignored -> true);

			assertEquals(List.of("lower", "upper", "third"), dispatch.renderedSubjects(),
				ber + " BER must not cover upper");
			assertTrue(dispatch.successfulSubjects().isEmpty());
			assertTrue(state.successKeys().isEmpty(),
				ber + " with no emitted geometry must retain VoxelShape fallback");
			assertTrue(upper.sourceCalls().contains(EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID));
			if (ber == Behavior.THROWS) {
				assertTrue(lower.observedOutcomes().contains(
					EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID + ":FAILED"));
			}
		}
	}

	@Test
	void bakedOrOptionalOnlyLowerSuccessStillRunsAnEmptyUpper() {
		for (SourcePair lowerSuccess : List.of(
			new SourcePair(Behavior.RENDERED, Behavior.EMPTY),
			new SourcePair(Behavior.EMPTY, Behavior.RENDERED))) {
			BlockPresentation presentation = coveredPresentation(0);
			BlockModelOutlineState state = beginFrame(presentation);
			RunnerHarness runner = new RunnerHarness();
			SubjectAttempt lower = attempt("lower", EntityBlockRenderMode.ALL,
				Behavior.EMPTY, lowerSuccess.baked(), lowerSuccess.optional());
			SubjectAttempt upper = attempt("upper", EntityBlockRenderMode.ALL,
				Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);
			SubjectAttempt third = attempt("third", EntityBlockRenderMode.ALL,
				Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);

			Dispatch dispatch = dispatch(
				presentation, state, runner, attempts(lower, upper, third), ignored -> true);

			assertEquals(List.of("lower", "upper", "third"), dispatch.renderedSubjects());
			assertEquals(List.of("lower"), dispatch.successfulSubjects());
			assertTrue(upper.sourceCalls().contains(EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID));
			assertEquals(Set.of(successKey(presentation, "lower")), state.successKeys(),
				"empty upper must remain on the VoxelShape fallback path");
		}
	}

	@Test
	void voxelShapeOnlyInvokesNoContextSourceOrObserverAndCannotCoverUpper() {
		BlockPresentation presentation = coveredPresentation(0);
		BlockModelOutlineState state = beginFrame(presentation);
		RunnerHarness runner = new RunnerHarness();
		SubjectAttempt lower = attempt("lower", EntityBlockRenderMode.VOXEL_SHAPE_ONLY,
			Behavior.RENDERED, Behavior.RENDERED, Behavior.RENDERED);
		SubjectAttempt upper = attempt("upper", EntityBlockRenderMode.VOXEL_SHAPE_ONLY,
			Behavior.RENDERED, Behavior.RENDERED, Behavior.RENDERED);
		SubjectAttempt third = attempt("third", EntityBlockRenderMode.VOXEL_SHAPE_ONLY,
			Behavior.RENDERED, Behavior.RENDERED, Behavior.RENDERED);

		Dispatch dispatch = dispatch(presentation, state, runner, attempts(lower, upper, third), ignored -> true);

		assertEquals(List.of("lower", "upper", "third"), dispatch.renderedSubjects());
		assertTrue(dispatch.successfulSubjects().isEmpty());
		assertEquals(0, lower.contextCalls());
		assertEquals(0, upper.contextCalls());
		assertTrue(lower.sourceCalls().isEmpty());
		assertTrue(upper.sourceCalls().isEmpty());
		assertTrue(lower.observedOutcomes().isEmpty());
		assertTrue(state.successKeys().isEmpty());
	}

	@Test
	void beginFrameClearsAnimatedCoverageBeforeNextSettledFailureFrame() {
		BlockPresentation presentation = coveredPresentation(0);
		BlockModelOutlineState state = BlockModelOutlineState.INSTANCE;
		RunnerHarness runner = new RunnerHarness();
		state.beginFrame();
		state.setPresentations(List.of(presentation));
		SubjectAttempt animatedLower = attempt("lower", EntityBlockRenderMode.ALL,
			Behavior.RENDERED, Behavior.EMPTY, Behavior.EMPTY);
		SubjectAttempt animatedUpper = attempt("upper", EntityBlockRenderMode.ALL,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);
		SubjectAttempt animatedThird = attempt("third", EntityBlockRenderMode.ALL,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);

		dispatch(presentation, state, runner, attempts(animatedLower, animatedUpper, animatedThird), ignored -> true);
		assertEquals(Set.of(successKey(presentation, "lower"), successKey(presentation, "upper")),
			state.successKeys());
		long animatedFrame = state.frameId();

		state.beginFrame();
		state.setPresentations(List.of(presentation));
		SubjectAttempt settledLower = attempt("lower", EntityBlockRenderMode.COMPATIBLE,
			Behavior.FAILED, Behavior.EMPTY, Behavior.EMPTY);
		SubjectAttempt settledUpper = attempt("upper", EntityBlockRenderMode.COMPATIBLE,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);
		SubjectAttempt settledThird = attempt("third", EntityBlockRenderMode.COMPATIBLE,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);

		Dispatch settled = dispatch(
			presentation, state, runner, attempts(settledLower, settledUpper, settledThird), ignored -> true);

		assertTrue(state.frameId() > animatedFrame);
		assertEquals(List.of("lower", "upper", "third"), settled.renderedSubjects());
		assertTrue(state.successKeys().isEmpty(), "beginFrame must discard stale coverage and success");
		assertFalse(state.allPresentationsCovered());
	}

	@Test
	void liveRejectedCoveredUpperIsNotRecordedAsNativeSuccess() {
		BlockPresentation presentation = coveredPresentation(0);
		BlockModelOutlineState state = beginFrame(presentation);
		RunnerHarness runner = new RunnerHarness();
		SubjectAttempt lower = attempt("lower", EntityBlockRenderMode.ALL,
			Behavior.RENDERED, Behavior.EMPTY, Behavior.EMPTY);
		SubjectAttempt upper = attempt("upper", EntityBlockRenderMode.ALL,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);
		SubjectAttempt third = attempt("third", EntityBlockRenderMode.ALL,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);
		Map<String, SubjectAttempt> attempts = attempts(lower, upper, third);
		List<String> events = new ArrayList<>();

		BlockPresentationSubjectDispatcher.dispatch(
			presentation,
			subject -> {
				events.add("live:" + subject.subjectId());
				return !subject.subjectId().equals("upper");
			},
			subject -> {
				events.add("success:" + subject.subjectId());
				state.addSuccess(subject.successKey(presentation.sourceSpec()));
			},
			(subject, observer) -> {
				events.add("render:" + subject.subjectId());
				return runner.render(attempts.get(subject.subjectId()), observer);
			});

		assertEquals(List.of(
			"live:lower", "render:lower", "success:lower",
			"live:upper", "live:third", "render:third"), events);
		assertTrue(upper.sourceCalls().isEmpty());
		assertEquals(Set.of(successKey(presentation, "lower")), state.successKeys(),
			"the live predicate must run before the covered-success branch");
	}

	@Test
	void coverageCannotCrossToDifferentPresentationSource() {
		BlockPresentation first = coveredPresentation(0);
		BlockPresentation second = coveredPresentation(10);
		BlockModelOutlineState state = BlockModelOutlineState.INSTANCE;
		state.beginFrame();
		state.setPresentations(List.of(first, second));
		RunnerHarness runner = new RunnerHarness();
		SubjectAttempt firstLower = attempt("lower", EntityBlockRenderMode.ALL,
			Behavior.RENDERED, Behavior.EMPTY, Behavior.EMPTY);
		SubjectAttempt firstUpper = attempt("upper", EntityBlockRenderMode.ALL,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);
		SubjectAttempt firstThird = attempt("third", EntityBlockRenderMode.ALL,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);
		Dispatch firstDispatch = dispatch(
			first, state, runner, attempts(firstLower, firstUpper, firstThird), ignored -> true);
		SubjectAttempt secondLower = attempt("lower", EntityBlockRenderMode.ALL,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);
		SubjectAttempt secondUpper = attempt("upper", EntityBlockRenderMode.ALL,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);
		SubjectAttempt secondThird = attempt("third", EntityBlockRenderMode.ALL,
			Behavior.EMPTY, Behavior.EMPTY, Behavior.EMPTY);
		Dispatch secondDispatch = dispatch(
			second, state, runner, attempts(secondLower, secondUpper, secondThird), ignored -> true);

		assertEquals(List.of("lower", "third"), firstDispatch.renderedSubjects());
		assertEquals(List.of("lower", "upper", "third"), secondDispatch.renderedSubjects(),
			"a distinct presentation/source must receive fresh coverage tracking");
		assertEquals(Set.of(successKey(first, "lower"), successKey(first, "upper")), state.successKeys());
	}

	private static BlockPresentation coveredPresentation(int x) {
		BlockPos sourcePos = new BlockPos(x, 64, 0);
		return new BlockPresentation(
			new BlockOutlineSpec(
				new MarkerId(1L),
				new TargetKey.BlockKey("minecraft:overworld", x, 64, 0, "minecraft:stone"),
				"entity_block", "attention", 0xFF000000),
			List.of(
				subject("lower", sourcePos),
				subject("upper", sourcePos.above()),
				subject("third", sourcePos.above(2))),
			List.of(new BlockPresentationCoverageRelation(
				"lower", EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID, "upper")));
	}

	private static BlockModelOutlineState beginFrame(BlockPresentation presentation) {
		BlockModelOutlineState state = BlockModelOutlineState.INSTANCE;
		state.beginFrame();
		state.setPresentations(List.of(presentation));
		return state;
	}

	private static Dispatch dispatch(
		BlockPresentation presentation,
		BlockModelOutlineState state,
		RunnerHarness runner,
		Map<String, SubjectAttempt> attempts,
		Predicate<BlockRenderSubject> isLiveAndCurrent
	) {
		List<String> renderedSubjects = new ArrayList<>();
		List<String> successfulSubjects = new ArrayList<>();
		BlockPresentationSubjectDispatcher.dispatch(
			presentation,
			isLiveAndCurrent,
			subject -> {
				successfulSubjects.add(subject.subjectId());
				state.addSuccess(subject.successKey(presentation.sourceSpec()));
			},
			(subject, observer) -> {
				renderedSubjects.add(subject.subjectId());
				return runner.render(attempts.get(subject.subjectId()), observer);
			});
		return new Dispatch(renderedSubjects, successfulSubjects);
	}

	private static Map<String, SubjectAttempt> attempts(SubjectAttempt... attempts) {
		Map<String, SubjectAttempt> bySubject = new LinkedHashMap<>();
		for (SubjectAttempt attempt : attempts) {
			bySubject.put(attempt.subjectId, attempt);
		}
		return bySubject;
	}

	private static SubjectAttempt attempt(
		String subjectId,
		EntityBlockRenderMode mode,
		Behavior ber,
		Behavior baked,
		Behavior optional
	) {
		return new SubjectAttempt(subjectId, mode, ber, baked, optional);
	}

	private static BlockPresentationSuccessKey successKey(
		BlockPresentation presentation,
		String subjectId
	) {
		return presentation.renderSubjects().stream()
			.filter(subject -> subject.subjectId().equals(subjectId))
			.findFirst()
			.orElseThrow()
			.successKey(presentation.sourceSpec());
	}

	private record Dispatch(List<String> renderedSubjects, List<String> successfulSubjects) {}

	private record SourcePair(Behavior baked, Behavior optional) {}

	private enum Behavior {
		RENDERED(EntityBlockGeometryOutcome.RENDERED),
		EMPTY(EntityBlockGeometryOutcome.EMPTY),
		FAILED(EntityBlockGeometryOutcome.FAILED),
		THROWS(null);

		private final EntityBlockGeometryOutcome outcome;

		Behavior(EntityBlockGeometryOutcome outcome) {
			this.outcome = outcome;
		}

		EntityBlockGeometryOutcome outcome() {
			if (outcome == null) {
				throw new IllegalStateException("test-only source failure");
			}
			return outcome;
		}
	}

	private static final class SubjectAttempt {
		private final String subjectId;
		private final EntityBlockRenderMode mode;
		private final Behavior ber;
		private final Behavior baked;
		private final Behavior optional;
		private final List<String> sourceCalls = new ArrayList<>();
		private final List<String> observedOutcomes = new ArrayList<>();
		private int contextCalls;

		private SubjectAttempt(
			String subjectId,
			EntityBlockRenderMode mode,
			Behavior ber,
			Behavior baked,
			Behavior optional
		) {
			this.subjectId = subjectId;
			this.mode = mode;
			this.ber = ber;
			this.baked = baked;
			this.optional = optional;
		}

		EntityBlockGeometryOutcome invoke(String sourceId) {
			sourceCalls.add(sourceId);
			return switch (sourceId) {
				case EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID -> ber.outcome();
				case EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID -> baked.outcome();
				case "test:optional" -> optional.outcome();
				default -> throw new IllegalArgumentException("unexpected source " + sourceId);
			};
		}

		List<String> sourceCalls() {
			return sourceCalls;
		}

		List<String> observedOutcomes() {
			return observedOutcomes;
		}

		int contextCalls() {
			return contextCalls;
		}
	}

	private static final class RunnerHarness {
		private SubjectAttempt activeAttempt;
		private final EntityBlockGeometryRunner runner;

		private RunnerHarness() {
			EntityBlockGeometrySourceRegistry registry = new EntityBlockGeometrySourceRegistry(
				EntityBlockGeometrySourceRegistry.WarningSink.noop());
			registry.register(source("test:optional"));
			runner = new EntityBlockGeometryRunner(
				registry,
				source(EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID),
				source(EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID),
				(message, failure) -> {});
		}

		boolean render(
			SubjectAttempt attempt,
			BiConsumer<String, EntityBlockGeometryOutcome> coverageObserver
		) {
			activeAttempt = attempt;
			try {
				if (coverageObserver == null) {
					return runner.run(attempt.mode, () -> newContext(attempt));
				}
				return runner.run(attempt.mode, () -> newContext(attempt), (sourceId, outcome) -> {
					attempt.observedOutcomes.add(sourceId + ":" + outcome);
					coverageObserver.accept(sourceId, outcome);
				});
			} finally {
				activeAttempt = null;
			}
		}

		private EntityBlockGeometryContext newContext(SubjectAttempt attempt) {
			attempt.contextCalls++;
			return EntityBlockGeometryContext.empty();
		}

		private EntityBlockGeometrySource source(String sourceId) {
			return EntityBlockGeometrySource.of(sourceId, ignored -> {
				if (activeAttempt == null) {
					throw new IllegalStateException("test harness invoked without an active subject");
				}
				return activeAttempt.invoke(sourceId);
			});
		}
	}
}
