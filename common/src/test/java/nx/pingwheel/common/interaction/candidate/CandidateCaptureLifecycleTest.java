package nx.pingwheel.common.interaction.candidate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.TargetResolver;
import nx.pingwheel.common.interaction.*;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.*;

class CandidateCaptureLifecycleTest {
	private static final CapturedRay RAY = CapturedRay.defaultRay();
	private static final TargetSnapshot BLOCK = TargetSnapshotFactory.block("minecraft:overworld", 0, 0, 1,
		"minecraft:stone", false, BlockFace.NORTH);
	private static final TargetResolver RESOLVER = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());

	@Test
	void completionKeepsFaceAndOrdinaryConstructorsAndAttachesEvidenceOnce() {
		ActiveInteraction active = new ActiveInteraction();
		PingCaptureCoordinator coordinator = coordinator(active, RESOLVER);
		InteractionToken token = coordinator.begin();
		var acquisition = acquisition(token);
		CapturedPingContext accepted = coordinator.complete(token, BLOCK, RAY, Optional.of(acquisition)).orElseThrow();
		assertEquals(Optional.of(BlockFace.NORTH), accepted.blockHitFace());
		assertEquals(accepted.resolvedTarget(), accepted.selectorCandidates().orElseThrow().ordinary().resolvedTarget());
		assertTrue(coordinator.complete(token, BLOCK, RAY, Optional.of(acquisition)).isEmpty());
		assertSame(accepted, active.currentContext().orElseThrow());
		var legacy = new CapturedPingContext(token, accepted.resolvedTarget(), RAY, Optional.empty(), Optional.of(BlockFace.NORTH));
		assertTrue(legacy.selectorCandidates().isEmpty());
		assertEquals(Optional.of(BlockFace.NORTH), legacy.blockHitFace());
	}

	@Test
	void supersededOrAbortedTokenNeverResolvesOrAttachesItsCandidates() {
		ActiveInteraction active = new ActiveInteraction();
		AtomicInteger calls = new AtomicInteger();
		PingCaptureCoordinator coordinator = coordinator(active, (target, context) -> {
			calls.incrementAndGet();
			return RESOLVER.resolve(target, context);
		});
		InteractionToken a = coordinator.begin();
		var evidenceA = acquisition(a);
		coordinator.begin();
		assertTrue(coordinator.complete(a, BLOCK, RAY, Optional.of(evidenceA)).isEmpty());
		assertEquals(0, calls.get());
		InteractionToken b = coordinator.begin();
		active.invalidate(b);
		assertTrue(coordinator.complete(b, BLOCK, RAY, Optional.of(acquisition(b))).isEmpty());
		assertTrue(active.currentContext().isEmpty());
		assertEquals(0, calls.get());
	}

	@Test
	void acquisitionCannotBeAccidentallyReusedForAnotherTokenOrRay() {
		ActiveInteraction active = new ActiveInteraction();
		PingCaptureCoordinator coordinator = coordinator(active, RESOLVER);
		InteractionToken a = coordinator.begin();
		var evidenceA = acquisition(a);
		InteractionToken b = coordinator.begin();
		assertThrows(IllegalArgumentException.class, () -> coordinator.complete(b, BLOCK, RAY, Optional.of(evidenceA)));
		assertThrows(IllegalArgumentException.class, () -> coordinator.complete(a, BLOCK,
			new CapturedRay(new WorldVector(1, 0, 0), RAY.direction()), Optional.of(evidenceA)));
		assertTrue(active.currentContext().isEmpty());
	}

	@Test
	void optionalFinalizationFailureDoesNotLoseOrdinaryReadinessOrFace() {
		ActiveInteraction active = new ActiveInteraction();
		PingCaptureCoordinator coordinator = coordinator(active, (target, context) -> {
			if (target instanceof nx.pingwheel.common.domain.Target.LocationTarget) throw new IllegalStateException("selector-only failure");
			return RESOLVER.resolve(target, context);
		});
		InteractionToken token = coordinator.begin();
		CapturedPingContext accepted = coordinator.complete(token, BLOCK, RAY, Optional.of(acquisition(token))).orElseThrow();
		assertEquals(BLOCK.target(), accepted.resolvedTarget().target());
		assertEquals(Optional.of(BlockFace.NORTH), accepted.blockHitFace());
		assertTrue(accepted.selectorCandidates().isEmpty());
	}

	@Test
	void newPressDuringCandidateFinalizationCannotCompleteOldCapture() {
		ActiveInteraction active = new ActiveInteraction();
		PingCaptureCoordinator coordinator = coordinator(active, (target, context) -> {
			if (target instanceof nx.pingwheel.common.domain.Target.LocationTarget) active.begin();
			return RESOLVER.resolve(target, context);
		});
		InteractionToken token = coordinator.begin();
		assertTrue(coordinator.complete(token, BLOCK, RAY, Optional.of(acquisition(token))).isEmpty());
		assertTrue(active.currentContext().isEmpty());
	}

	@Test
	void supplementMatchingOrdinaryIsNotResolvedAgainAndMissIsResolvedOnlyOnce() {
		ActiveInteraction active = new ActiveInteraction();
		AtomicInteger ordinaryResolutions = new AtomicInteger();
		PingCaptureCoordinator coordinator = coordinator(active, (target, context) -> {
			if (target.equals(BLOCK.target())) ordinaryResolutions.incrementAndGet();
			return RESOLVER.resolve(target, context);
		});
		InteractionToken token = coordinator.begin();
		TargetSnapshot observed = BLOCK.withCandidateHit(new CandidateHit(new WorldVector(0, 0, 1),
			CaptureEquivalenceKey.nativeTarget(BLOCK.target())));
		var acquisition = new FrozenCandidateAcquisition(token, RAY, 20, new WorldVector(0, 0, 1),
			List.of(new CandidateEvidence(observed, 1)), Set.of(PreciseTargetType.BLOCK));
		assertTrue(coordinator.complete(token, observed, RAY, Optional.of(acquisition)).orElseThrow().selectorCandidates().isPresent());
		assertEquals(1, ordinaryResolutions.get());
		AtomicInteger missResolutions = new AtomicInteger();
		PingCaptureCoordinator missCoordinator = coordinator(new ActiveInteraction(), (target, context) -> {
			missResolutions.incrementAndGet();
			return RESOLVER.resolve(target, context);
		});
		InteractionToken missToken = missCoordinator.begin();
		missCoordinator.complete(missToken, TargetSnapshotFactory.location("minecraft:overworld", 0, 0, 20),
			RAY, Optional.of(acquisition(missToken))).orElseThrow();
		assertEquals(1, missResolutions.get());
	}

	private static PingCaptureCoordinator coordinator(ActiveInteraction active, TargetResolver resolver) {
		return new PingCaptureCoordinator(resolver, active, PingCaptureLogger.noop());
	}
	private static FrozenCandidateAcquisition acquisition(InteractionToken token) {
		return new FrozenCandidateAcquisition(token, RAY, 20, new WorldVector(0, 0, 1), List.of(), Set.of());
	}
}
