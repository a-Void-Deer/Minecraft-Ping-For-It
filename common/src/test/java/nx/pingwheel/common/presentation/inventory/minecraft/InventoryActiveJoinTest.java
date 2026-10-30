package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.source.CaptureResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static nx.pingwheel.common.presentation.inventory.minecraft.InventoryPooledRuntimeTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent address/count/ownership oracles for certified pre-attachment observation reuse. */
@NotNullByDefault
class InventoryActiveJoinTest {
	@BeforeAll static void bootstrap() { InventoryPooledRuntimeTest.bootstrap(); }
	static InventoryRuntime.Consumer preview(InventoryRuntime runtime, int owner, BlockFace face) {
		var input = input(owner, face);
		return runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
	}
	static Object round(InventoryRuntime.Consumer consumer) { return fieldUnchecked(consumer, "round"); }
	@Test void latePreviewAndTrackingReplayOldPrefixAndReadOnlyUnreadCurrentAmounts() {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var origin = preview(runtime, 1, BlockFace.NORTH);
			var prefix = runtime.step(origin, 3).orElseThrow(); assertEquals(3, planner.addresses.size());
			planner.amount = 4_000_003L;
			var tracker = attach(runtime, input(2, BlockFace.SOUTH));
			assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(tracker));
			assertSame(round(origin), round(tracker)); assertEquals(0, fieldUnchecked(tracker, "cursor"));
			assertEquals(3, planner.addresses.size(), "JOIN_ONLY cannot visit content");
			var latePreview = preview(runtime, 3, BlockFace.UP);
			assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(latePreview));
			assertSame(round(origin), round(latePreview)); origin.close();
			assertEquals(prefix.slots(), runtime.step(latePreview, 3).orElseThrow().slots());
			assertEquals(3, planner.addresses.size(), "late Preview must replay the committed prefix");
			assertEquals(33_000_063L, finish(runtime, tracker));
			assertEquals(24_000_051L, finish(runtime, latePreview));
			assertEquals(List.of(0,1,2,3,4,5,6,7,8), planner.addresses);
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@ParameterizedTest @CsvSource({"true,true,1,NORTH,true", "true,true,2,NORTH,false", "true,false,2,NORTH,true", "true,false,2,SOUTH,false", "false,true,1,SOUTH,true", "false,true,2,SOUTH,false", "false,false,2,SOUTH,true"})
	void lateTrackingUsesEveryFrozenPartitionCombination(boolean faces, boolean players, int owner, BlockFace face, boolean joins) {
		var planner = new Planner(); var settings = settings(faces, players);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var p = preview(runtime, 1, BlockFace.NORTH); runtime.step(p, 3).orElseThrow();
			var t = attach(runtime, input(owner, face));
			assertEquals(joins ? InventoryRuntime.JoinResult.JOINED : InventoryRuntime.JoinResult.NO_MATCH, runtime.tryJoinInProgress(t));
			assertEquals(3, planner.addresses.size());
			assertEquals(27_000_063L, finish(runtime, t));
			assertEquals(joins ? 9 : 12, planner.addresses.size());
		} assertEquals(0, planner.open);
	}
	@Test void previewOnlySharesExactScopeAndDepartedTrackerCannotWidenIt() {
		var planner = new Planner(); var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var p = preview(runtime, 1, BlockFace.NORTH); runtime.step(p, 3).orElseThrow();
			var same = preview(runtime, 1, BlockFace.NORTH); assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(same));
			for (var input : List.of(input(1, BlockFace.SOUTH), input(2, BlockFace.NORTH))) {
				var isolated = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
				assertEquals(InventoryRuntime.JoinResult.NO_MATCH, runtime.tryJoinInProgress(isolated)); isolated.close();
			}
			var t = attach(runtime, input(2, BlockFace.SOUTH)); assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(t));
			var widened = preview(runtime, 3, BlockFace.UP); assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(widened));
			t.close(); var after = preview(runtime, 4, BlockFace.DOWN);
			assertEquals(InventoryRuntime.JoinResult.NO_MATCH, runtime.tryJoinInProgress(after));
			var exactSurvivor = preview(runtime, 3, BlockFace.UP);
			assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(exactSurvivor));
			assertEquals(3, planner.addresses.size()); assertEquals(0, fieldUnchecked(round(p), "activeTracking"));
		} assertEquals(0, planner.open);
	}
	@Test void completedIncompleteFailedAndReleasedRoundsNeverAdmitNewAssociations() {
		for (String terminal : List.of("complete", "capacity", "failure", "released")) {
			var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
			try (runtime) {
				runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); runtime.step(a, 3).orElseThrow();
				if (terminal.equals("complete")) finish(runtime, a);
				else if (terminal.equals("released")) a.close();
				else {
					planner.failAt = 5; planner.failCapacity = !terminal.equals("failure");
					runtime.step(a, 3).orElseThrow();
				}
				var late = attach(runtime, input(2, BlockFace.SOUTH)); int reads = planner.addresses.size();
				assertEquals(InventoryRuntime.JoinResult.NO_MATCH, runtime.tryJoinInProgress(late), terminal);
				assertEquals(reads, planner.addresses.size());
			} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained());
		}
	}
	@Test void actualCatalogCapacityStopsNewAdmissionButExistingPeerMayFinishItsPrefix() {
		var planner = new Planner(); planner.catalogEntries = 2; planner.distinctVariants = true; var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH));
			var existing = preview(runtime, 2, BlockFace.SOUTH); runtime.prepare(a); runtime.prepare(existing);
			var terminal = runtime.step(a, 3).orElseThrow(); assertEquals(CaptureResult.Completeness.INCOMPLETE, terminal.result().completeness());
			var late = preview(runtime, 3, BlockFace.UP); assertEquals(InventoryRuntime.JoinResult.NO_MATCH, runtime.tryJoinInProgress(late));
			assertEquals(terminal.slots(), runtime.step(existing, 3).orElseThrow().slots()); assertEquals(3, planner.addresses.size());
		}
	}
	@Test void lateReplayPaysIndependentPreviewAndTrackingLogicalHighWaterWithoutRefunds() {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		settings.getPreview().setMaxSlotsPerClient(nx.pingwheel.common.config.IntLimit.finite(2));
		settings.getTracking().setMaxSlotsPerTarget(nx.pingwheel.common.config.IntLimit.finite(3));
		try (runtime) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); runtime.step(a, 3).orElseThrow();
			var p = preview(runtime, 2, BlockFace.SOUTH); assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(p));
			assertEquals(2, runtime.step(p, 3).orElseThrow().slots().size()); assertTrue(runtime.step(p, 3).isEmpty());
			var b = attach(runtime, input(2, BlockFace.SOUTH)); assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(b));
			assertEquals(3, runtime.step(b, 3).orElseThrow().slots().size()); assertTrue(runtime.step(b, 3).isEmpty());
			assertEquals(3, planner.addresses.size()); p.close();
			var reopened = preview(runtime, 2, BlockFace.SOUTH); assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(reopened));
			assertEquals(2, runtime.step(reopened, 3).orElseThrow().slots().size()); assertTrue(runtime.step(reopened, 3).isEmpty());
			@SuppressWarnings("unchecked") var paid = (java.util.Map<InventoryRuntime.Subject, Integer>) fieldUnchecked(runtime, "logicalUsed");
			assertEquals(5, paid.values().stream().mapToInt(Integer::intValue).sum()); assertEquals(0, runtime.memory().reserved());
		} assertEquals(0, runtime.memory().retained());
	}
	@Test void unsupportedJoinOnlyKeepsLegacyEligibilityAndCreatesNoHandleOrCatalogueCache() {
		var planner = new Planner(); planner.supported = false; var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); runtime.step(a, 3).orElseThrow();
			var b = attach(runtime, a.input()); long retained = runtime.memory().retained(); int open = planner.open;
			assertEquals(InventoryRuntime.JoinResult.NO_MATCH, runtime.tryJoinInProgress(b));
			assertEquals(retained, runtime.memory().retained()); assertEquals(open, planner.open); assertEquals(3, planner.addresses.size());
			assertEquals(27_000_063L, finish(runtime, b)); assertEquals(12, planner.addresses.size(), "unsupported late attach still starts fresh");
		}
	}
	@Test void budgetDeferGrantsNothingAndRechecksCompletionOnRetry() {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); runtime.step(a, 3).orElseThrow();
			var late = attach(runtime, input(2, BlockFace.SOUTH)); runtime.memory().setCap(runtime.memory().retained());
			int callbacks = planner.callbacks; assertEquals(InventoryRuntime.JoinResult.DEFERRED, runtime.tryJoinInProgress(late)); assertEquals(callbacks, planner.callbacks);
			runtime.memory().setCap(settings.pendingMemoryBytes()); finish(runtime, a);
			assertEquals(InventoryRuntime.JoinResult.NO_MATCH, runtime.tryJoinInProgress(late));
			assertEquals(27_000_063L, finish(runtime, late)); assertEquals(18, planner.addresses.size());
		} assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void candidateCompletionDuringOwnValidationIsNotAnInstalledEntitlement() {
		var planner = new Planner(); var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); runtime.step(a, 3).orElseThrow();
			var late = attach(runtime, input(2, BlockFace.SOUTH));
			planner.duringCheck = () -> { planner.duringCheck = () -> {}; finish(runtime, a); };
			assertEquals(InventoryRuntime.JoinResult.DEFERRED, runtime.tryJoinInProgress(late));
			assertEquals(InventoryRuntime.JoinResult.NO_MATCH, runtime.tryJoinInProgress(late)); assertEquals(9, planner.addresses.size());
		}
	}
	@Test void currentOwnerDenialBeforeJoinRejectsWithoutReadingOrResettingPeerPrefix() {
		var planner = new Planner(); var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); runtime.step(a, 3).orElseThrow();
			var denied = attach(runtime, input(2, BlockFace.SOUTH));
			planner.duringCheck = () -> planner.denied.put(denied.input(), true);
			assertEquals(InventoryRuntime.JoinResult.INVALID, runtime.tryJoinInProgress(denied));
			assertTrue(denied.invalidated()); assertEquals(0, fieldUnchecked(denied, "cursor")); assertEquals(3, planner.addresses.size());
			planner.duringCheck = () -> {}; assertFalse(a.invalidated()); assertEquals(18_000_051L, finish(runtime, a)); assertEquals(9, planner.addresses.size());
		}
	}
	@Test void ownerRecoveryCannotReplaySurvivingPeersPreInvalidationPrefix() {
		var planner = new Planner(); var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); runtime.step(a, 3).orElseThrow();
			var b = attach(runtime, input(2, BlockFace.SOUTH)); assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(b));
			Object old = round(b); planner.denied.put(a.input(), true); assertFalse(runtime.probe(a).orElseThrow());
			assertFalse(b.invalidated()); assertEquals(0, fieldUnchecked(b, "cursor"));
			planner.denied.put(a.input(), false); a.restart(); assertEquals(InventoryRuntime.JoinResult.NO_MATCH, runtime.tryJoinInProgress(a));
			runtime.prepare(a); assertNotSame(old, round(a)); assertEquals(27_000_063L, finish(runtime, a)); assertEquals(27_000_063L, finish(runtime, b));
			assertEquals(18, planner.addresses.size());
		} assertEquals(0, planner.open);
	}
	@Test void completedEntitlementAdoptionCannotManufactureAnEntitlementForAnotherOldCohort() {
		var planner = new Planner(); var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); var aPeer = attach(runtime, input(2, BlockFace.SOUTH));
			runtime.prepare(a); runtime.prepare(aPeer); finish(runtime, a); finish(runtime, aPeer); a.restart(); aPeer.restart();
			var b = attach(runtime, input(3, BlockFace.UP)); var bPeer = attach(runtime, input(4, BlockFace.DOWN));
			runtime.prepare(b); runtime.prepare(bPeer); finish(runtime, b); finish(runtime, bPeer); b.restart(); bPeer.restart();
			runtime.step(a, 3).orElseThrow(); assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(b));
			Object replacement = round(a); finish(runtime, a); a.restart();
			// b adopted while active and can finish; bPeer was pregranted then, not now at completion.
			assertEquals(27_000_063L, finish(runtime, b)); b.restart();
			assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(bPeer)); assertSame(replacement, round(bPeer));
			var late = attach(runtime, input(5, BlockFace.NORTH)); assertEquals(InventoryRuntime.JoinResult.NO_MATCH, runtime.tryJoinInProgress(late));
			assertEquals(27, planner.addresses.size());
		}
	}
	@Test void proofWorldMemberOrMappingChangeNeverReplaysAnOldPrefix() {
		for (String boundary : List.of("world", "member", "mapping")) {
			var planner = new Planner(); var settings = settings(false, false);
			try (var runtime = planner.runtime(settings)) {
				runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); runtime.step(a, 3).orElseThrow();
				planner.differentWorlds = boundary.equals("world"); planner.differentMembers = boundary.equals("member"); planner.differentMaps = boundary.equals("mapping");
				var late = attach(runtime, input(2, BlockFace.SOUTH)); assertEquals(InventoryRuntime.JoinResult.NO_MATCH, runtime.tryJoinInProgress(late), boundary);
				assertEquals(3, planner.addresses.size());
				planner.structure = false; assertEquals(InventoryRuntime.JoinResult.INVALID, runtime.tryJoinInProgress(late));
				assertEquals(3, planner.addresses.size());
			}
		}
	}
	@Test void reversePolicyFlipNeverReopensOldPreviewRoundAndCurrentParticipantsFinishIt() {
		var planner = new Planner(); var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var p = preview(runtime, 1, BlockFace.NORTH); runtime.step(p, 3).orElseThrow();
			settings.getTracking().setDistinguishFaces(true); runtime.advance(0, settings);
			settings.getTracking().setDistinguishFaces(false); runtime.advance(0, settings);
			var late = preview(runtime, 1, BlockFace.NORTH); assertEquals(InventoryRuntime.JoinResult.NO_MATCH, runtime.tryJoinInProgress(late));
			assertEquals(18_000_051L, finish(runtime, p)); assertEquals(27_000_063L, finish(runtime, late)); assertEquals(18, planner.addresses.size());
		}
	}
	@Test void staggeredCohortsConvergeOnNewerActiveRoundButNeverOwnAbandonedAttempt() {
		var planner = new Planner(); var settings = settings(false, false);
		try (var runtime = planner.runtime(settings)) {
			runtime.advance(0, settings); var a = attach(runtime, input(1, BlockFace.NORTH)); finish(runtime, a); a.restart();
			var b = attach(runtime, input(2, BlockFace.SOUTH)); finish(runtime, b); b.restart();
			var c = attach(runtime, input(3, BlockFace.UP)); finish(runtime, c); c.restart();
			Object displayedA = fieldUnchecked(a, "completedRound");
			runtime.step(c, 3).orElseThrow(); Object newer = round(c);
			assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(b)); assertSame(newer, round(b));
			a.restart(); assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(a)); assertSame(newer, round(a));
			assertSame(displayedA, fieldUnchecked(a, "completedRound"));
			a.restart(); assertEquals(InventoryRuntime.JoinResult.NO_MATCH, runtime.tryJoinInProgress(a));
			assertEquals(27_000_063L, finish(runtime, b)); assertEquals(18_000_051L, finish(runtime, c));
			assertEquals(36, planner.addresses.size());
		} assertEquals(0, planner.open);
	}
	@ParameterizedTest @CsvSource({"false", "true"})
	void oneShotPreviewKeepsItsInitialReplayWhileSlowTrackingAloneReservesSuccessors(boolean seal) {
		var planner = new Planner(); var settings = settings(false, false); var runtime = planner.runtime(settings);
		try (runtime) {
			runtime.advance(0, settings); var fast = attach(runtime, input(1, BlockFace.NORTH));
			runtime.step(fast, 3).orElseThrow(); Object initial = round(fast);
			var slow = attach(runtime, input(2, BlockFace.SOUTH)); assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(slow));
			var p = preview(runtime, 3, BlockFace.UP); assertEquals(InventoryRuntime.JoinResult.JOINED, runtime.tryJoinInProgress(p));
			assertEquals(3, runtime.step(p, 3).orElseThrow().slots().size());
			assertEquals(18_000_051L, finish(runtime, fast)); assertEquals(27_000_063L, finish(runtime, slow));
			fast.restart(); assertEquals(3, runtime.step(fast, 3).orElseThrow().slots().size()); Object successor = round(fast);
			assertSame(successor, fieldUnchecked(slow, "successorEntitlement"));
			assertNull(InventoryPooledBackendTest.stateField(p, "successorEntitlement"), "one-shot Preview has no future observation to reserve");
			assertEquals(1, fieldUnchecked(successor, "incomingEntitlements"));
			if (seal) { settings.getTracking().setDistinguishFaces(true); runtime.advance(0, settings); }
			int reads = planner.addresses.size(); assertEquals(18_000_051L, finish(runtime, p));
			assertSame(initial, round(p)); assertEquals(reads, planner.addresses.size(), "Preview finishes its original paid journal even after Tracking starts a replacement");
			assertEquals(18_000_051L, finish(runtime, fast)); fast.restart();
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(fast), "real slow Tracking successor protection is unchanged");
			slow.restart(); assertEquals(27_000_063L, finish(runtime, slow)); assertSame(successor, round(slow));
			assertEquals(18, planner.addresses.size(), "pregranted Tracking handoff remains usable even after policy sealing");
			slow.close(); // its real handoff was consumed; the surviving Preview alone must never reserve another
			for (int cycle = 1; cycle <= 3; cycle++) {
				runtime.advance(cycle * 40L, settings); assertEquals(27_000_063L, finish(runtime, fast));
				assertEquals(18 + cycle * 9, planner.addresses.size()); fast.restart();
				assertSame(initial, round(p)); assertEquals(9, fieldUnchecked(p, "cursor"));
				assertTrue(runtime.probe(p).orElseThrow());
			}
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void legacyHandoffEndsWithRecurringPeersEvenWhileOriginalPreviewRetainsItsEvidence() {
		var planner = new Planner(); var settings = settings(false, false);
		var runtime = new InventoryRuntime((input, cap) -> java.util.Optional.of(planner.source(input, cap)), settings.pendingMemoryBytes());
		try (runtime) {
			runtime.advance(0, settings); var fast = attach(runtime, input(1, BlockFace.NORTH)); var slow = attach(runtime, fast.input());
			var p = preview(runtime, 1, BlockFace.NORTH); runtime.prepare(fast); runtime.prepare(slow); runtime.prepare(p);
			assertEquals(27_000_063L, finish(runtime, fast)); assertEquals(27_000_063L, finish(runtime, slow));
			Object initial = round(p); fast.restart(); assertEquals(27_000_063L, finish(runtime, fast)); fast.restart();
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(fast), "legacy slow recurring peer still has a protected handoff");
			slow.restart(); assertEquals(27_000_063L, finish(runtime, slow)); assertEquals(18, planner.addresses.size());
			assertEquals(27_000_063L, finish(runtime, p)); assertSame(initial, round(p)); slow.close();
			for (int cycle = 1; cycle <= 3; cycle++) {
				runtime.advance(cycle * 40L, settings); assertEquals(27_000_063L, finish(runtime, fast)); fast.restart();
				assertEquals(18 + 9 * cycle, planner.addresses.size()); assertEquals(java.util.Optional.of(true), runtime.probe(p));
				assertSame(initial, round(p)); assertEquals(9, fieldUnchecked(p, "cursor"));
			}
		} assertEquals(0, planner.open); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
}
