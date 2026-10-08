package nx.pingwheel.common.client.spatial;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.Candidate;
import nx.pingwheel.common.interaction.candidate.CaptureEquivalenceKey;
import nx.pingwheel.common.interaction.candidate.PreciseTargetType;
import nx.pingwheel.common.math.RaycastPolicy;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.*;

class PreciseCaptureRefreshTest {

	static Candidate candidate(boolean location, int x) {
		var snapshot = location ? TargetSnapshotFactory.location("minecraft:overworld", x, 2, 3)
			: TargetSnapshotFactory.block("minecraft:overworld", x, 2, 3, "minecraft:stone", false);
		var resolved = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop()).resolve(snapshot.target(), snapshot.matchContext());
		return new Candidate(0, resolved, new WorldVector(x, 2, 3), 1, Optional.empty(), Optional.empty(),
			CaptureEquivalenceKey.nativeTarget(resolved.target()));
	}
	static Map<PreciseTargetType, PreciseCaptureRefresh.Outcome> nativeSlots(int x) {
		var map = new EnumMap<PreciseTargetType, PreciseCaptureRefresh.Outcome>(PreciseTargetType.class);
		for (var type : PreciseTargetType.values()) if (type != PreciseTargetType.LOCATION) map.put(type, PreciseCaptureRefresh.Outcome.missing());
		map.put(PreciseTargetType.BLOCK, PreciseCaptureRefresh.Outcome.available(candidate(false, x)));
		return map;
	}
	static final class Harness {
		final InteractionToken token = new ActiveInteraction().begin();
		final Object level = new Object();
		boolean owned = true, pending = true, scheduled = true;
		int scans, x = 1;
		CapturedRay ray = CapturedRay.defaultRay();
		final List<PreciseCaptureRefresh.Request> requests = new ArrayList<>();
		final List<Consumer<PreciseCaptureRefresh.Outcome>> completions = new ArrayList<>();
		final List<PreciseCaptureRefresh.Published> publications = new ArrayList<>();
		final PreciseCaptureRefresh refresh;
		Harness(int period) {
			refresh = new PreciseCaptureRefresh(token, level, period,
				() -> Optional.of(new PreciseCaptureRefresh.Inputs(token, level, ray, RaycastPolicy.from(false, false, false), 10, 100)),
				() -> owned, request -> {
					scans++;
					return new PreciseCaptureRefresh.Scan(nativeSlots(x), PreciseCaptureRefresh.Outcome.available(candidate(true, x)), pending);
				}, (request, completion) -> {
					requests.add(request); completions.add(completion); return scheduled;
				}, publications::add);
		}
		PreciseCaptureRefresh.Published latest() { return publications.getLast(); }
		void complete(int index, int locationX) { completions.get(index).accept(PreciseCaptureRefresh.Outcome.available(candidate(true, locationX))); }
	}

	@ParameterizedTest @ValueSource(ints = {1, 50})
	void capturesImmediatelyOnlyOnBranchEntryThenOnFrozenTickPeriod(int period) {
		var h = new Harness(period); h.pending = false;
		h.refresh.tick(0); assertEquals(0, h.scans);
		h.refresh.enter(10); h.refresh.enter(10); assertEquals(1, h.scans);
		h.refresh.tick(10 + period - 1); assertEquals(1, h.scans);
		h.refresh.tick(10 + period); assertEquals(2, h.scans);
		h.refresh.tick(10 + period); assertEquals(2, h.scans);
		h.refresh.leave(); h.refresh.tick(1000); assertEquals(2, h.scans);
		h.refresh.enter(1001); assertEquals(3, h.scans);
		h.refresh.end(); h.refresh.enter(2000); h.refresh.tick(2001); assertEquals(3, h.scans);
	}

	@Test void nativePublishesBeforeDistantAndPendingKeepsCertifiedLocationWithItsOwnRay() {
		var h = new Harness(1); CapturedRay rayA = h.ray;
		h.refresh.enter(0);
		assertEquals(1, h.publications.size()); assertEquals(1, h.requests.size());
		assertTrue(h.latest().slots().get(PreciseTargetType.LOCATION).context().isEmpty());
		assertTrue(h.latest().slots().get(PreciseTargetType.BLOCK).context().isPresent());
		h.complete(0, 100);
		var certified = h.latest().slots().get(PreciseTargetType.LOCATION);
		h.ray = new CapturedRay(new WorldVector(5, 6, 7), new WorldVector(1, 0, 0)); h.x = 2; h.refresh.tick(1);
		var mixed = h.latest();
		assertSame(certified, mixed.slots().get(PreciseTargetType.LOCATION));
		assertEquals(rayA, mixed.slots().get(PreciseTargetType.LOCATION).context().orElseThrow().ray());
		assertEquals(h.ray, mixed.slots().get(PreciseTargetType.BLOCK).context().orElseThrow().ray());
		assertEquals(1, certified.generation()); assertEquals(2, mixed.generation());
		assertThrows(UnsupportedOperationException.class, () -> mixed.slots().clear());
		h.completions.get(1).accept(PreciseCaptureRefresh.Outcome.missing());
		assertTrue(h.latest().slots().get(PreciseTargetType.LOCATION).context().isEmpty(), "final missing replaces certified pending value");
	}

	@Test void oneInFlightCoalescesLatestAndSlowDistantDoesNotStarveOrOverwriteNewNative() {
		var h = new Harness(1); h.refresh.enter(0);
		for (int tick = 1; tick <= 20; tick++) { h.x = tick + 1; h.refresh.tick(tick); }
		assertEquals(21, h.scans); assertEquals(1, h.requests.size());
		h.complete(0, 100);
		assertEquals(2, h.requests.size()); assertEquals(21, h.requests.getLast().generation());
		assertEquals(1, h.latest().slots().get(PreciseTargetType.LOCATION).generation());
		assertEquals(candidate(false, 21).resolvedTarget(), h.latest().slots().get(PreciseTargetType.BLOCK).context().orElseThrow().resolvedTarget());
		long revision = h.latest().revision(); h.complete(0, 999);
		assertEquals(revision, h.latest().revision(), "duplicate old completion cannot replace the installed location");
		h.x = 22; h.refresh.tick(21); h.complete(1, 200);
		assertEquals(21, h.latest().slots().get(PreciseTargetType.LOCATION).generation());
		assertEquals(22, h.latest().slots().get(PreciseTargetType.BLOCK).generation());
		assertEquals(3, h.requests.size());
	}

	@Test void newerNativeLocationFencesOlderDistantCompletion() {
		var h = new Harness(1); h.refresh.enter(0); h.pending = false; h.x = 2; h.refresh.tick(1);
		long revision = h.latest().revision(); h.complete(0, 100);
		assertEquals(revision, h.latest().revision());
		assertEquals(candidate(true, 2).resolvedTarget(), h.latest().slots().get(PreciseTargetType.LOCATION).context().orElseThrow().resolvedTarget());
	}

	@Test void leaveAndReentryFencesOldCallbackWithoutStartingSecondPhysicalRequest() {
		var h = new Harness(1); h.refresh.enter(0); h.refresh.tick(1); h.refresh.leave();
		h.refresh.enter(10); assertEquals(1, h.requests.size());
		long revision = h.latest().revision(); h.complete(0, 100);
		assertEquals(revision, h.latest().revision(), "old branch cannot publish even into same hold");
		assertEquals(2, h.requests.size()); assertEquals(3, h.requests.getLast().generation());
		h.complete(1, 200); assertEquals(3, h.latest().slots().get(PreciseTargetType.LOCATION).generation());
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void endOrChangedWorldRejectsCompletionAndNeverPumpsQueuedWork(boolean end) {
		var h = new Harness(1); h.refresh.enter(0); h.refresh.tick(1);
		if (end) h.refresh.end(); else h.owned = false;
		int count = h.publications.size(); h.complete(0, 100); h.refresh.tick(2);
		assertEquals(count, h.publications.size()); assertEquals(1, h.requests.size());
	}

	@Test void schedulingFailureCompletesOwnFallbackAndIncompleteFinalDisablesLocation() {
		var h = new Harness(1); h.scheduled = false; h.refresh.enter(0);
		assertTrue(h.latest().slots().get(PreciseTargetType.LOCATION).context().isPresent());
		h.scheduled = true; h.refresh.tick(1);
		h.completions.getLast().accept(PreciseCaptureRefresh.Outcome.incomplete());
		assertTrue(h.latest().slots().get(PreciseTargetType.LOCATION).context().isEmpty());
	}

	@Test void nativeIncompleteReplacesOldSelectableSlotsWithoutOrdinaryFallback() {
		var token = new ActiveInteraction().begin(); Object level = new Object();
		List<PreciseCaptureRefresh.Published> seen = new ArrayList<>(); int[] scans = {0};
		var refresh = new PreciseCaptureRefresh(token, level, 1,
			() -> Optional.of(new PreciseCaptureRefresh.Inputs(token, level, CapturedRay.defaultRay(), RaycastPolicy.from(false, false, false), 10, 100)),
			() -> true, request -> ++scans[0] == 1 ? new PreciseCaptureRefresh.Scan(nativeSlots(1), PreciseCaptureRefresh.Outcome.missing(), false)
				: PreciseCaptureRefresh.Scan.incomplete(), (request, completion) -> false, seen::add);
		refresh.enter(0); assertTrue(seen.getLast().slots().get(PreciseTargetType.BLOCK).context().isPresent());
		refresh.tick(1); assertTrue(seen.getLast().slots().get(PreciseTargetType.BLOCK).context().isEmpty());
		assertEquals(nx.pingwheel.common.interaction.candidate.PreciseSlot.Availability.INCOMPLETE,
			seen.getLast().slots().get(PreciseTargetType.BLOCK).outcome().availability());
	}

	@Test void foreignInputsCannotStartCaptureEvenIfCallerClaimsCurrentOwnership() {
		var token = new ActiveInteraction().begin(); Object level = new Object();
		int[] scans = {0};
		var refresh = new PreciseCaptureRefresh(token, level, 1,
			() -> Optional.of(new PreciseCaptureRefresh.Inputs(token, new Object(), CapturedRay.defaultRay(),
				RaycastPolicy.from(false, false, false), 10, 100)), () -> true,
			request -> { scans[0]++; return PreciseCaptureRefresh.Scan.incomplete(); },
			(request, completion) -> fail("foreign level cannot start DH"), publication -> fail("foreign level cannot publish"));
		refresh.enter(0); refresh.tick(1); assertEquals(0, scans[0]);
	}

	@Test void publisherReentrantBranchExitCannotStartDistantWorkAfterItsNativePublication() {
		var token = new ActiveInteraction().begin(); Object level = new Object();
		PreciseCaptureRefresh[] owner = new PreciseCaptureRefresh[1]; int[] publications = {0};
		owner[0] = new PreciseCaptureRefresh(token, level, 1,
			() -> Optional.of(new PreciseCaptureRefresh.Inputs(token, level, CapturedRay.defaultRay(),
				RaycastPolicy.from(false, false, false), 10, 100)), () -> true,
			request -> new PreciseCaptureRefresh.Scan(nativeSlots(1), PreciseCaptureRefresh.Outcome.missing(), true),
			(request, completion) -> fail("branch exit fences optional work"), publication -> { publications[0]++; owner[0].leave(); });
		owner[0].enter(0); owner[0].tick(1); assertEquals(1, publications[0]);
	}
}
