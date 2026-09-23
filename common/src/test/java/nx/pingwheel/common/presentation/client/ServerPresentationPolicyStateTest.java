package nx.pingwheel.common.presentation.client;

import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Operation;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPresentationPolicyStateTest {

	@BeforeEach
	void resetRequestIds() {
		ServerPresentationPolicyState.setRequestIdSequenceForTesting(0L);
	}

	@Test
	void stateStartsUnknownAndNotEditable() {
		var state = new ServerPresentationPolicyState();

		assertFalse(state.isConnected());
		assertFalse(state.isKnown());
		assertFalse(state.isReady());
		assertFalse(state.canEdit());
		assertEquals(-1L, state.revision());
		assertEquals(List.of(), state.white());
		assertEquals(List.of(), state.black());
		assertFalse(state.whitelistOnly());
		assertEquals(ServerPresentationPolicyState.NO_PENDING_REQUEST, state.pendingRequestId());
	}

	@Test
	void matchingOkSnapshotMakesValidEmptyViewReady() {
		var state = new ServerPresentationPolicyState();
		long requestId = state.beginConnection();

		assertTrue(state.applySnapshot(requestId, 0L, Status.OK, true, List.of(), List.of(), false));

		assertTrue(state.isKnown());
		assertTrue(state.isReady());
		assertTrue(state.canEdit());
		assertEquals(0L, state.revision());
		assertEquals(List.of(), state.white());
		assertEquals(Status.OK, state.lastStatus());
		assertEquals(ServerPresentationPolicyState.NO_PENDING_REQUEST, state.pendingRequestId());
	}

	@Test
	void errorSnapshotNeverMakesViewReady() {
		var state = new ServerPresentationPolicyState();
		long requestId = state.beginConnection();

		assertFalse(state.applySnapshot(requestId, 0L, Status.DENIED, false, List.of(), List.of(), false));

		assertFalse(state.isKnown());
		assertFalse(state.isReady());
		assertFalse(state.canEdit());
		assertEquals(Status.DENIED, state.lastStatus());
		assertEquals(List.of(), state.white());
	}

	@Test
	void mismatchedRequestIdIsIgnored() {
		var state = new ServerPresentationPolicyState();
		long requestId = state.beginConnection();

		assertFalse(state.applySnapshot(requestId + 1, 0L, Status.OK, true, List.of("a:b"), List.of(), false));
		assertFalse(state.isKnown());
		assertEquals(requestId, state.pendingRequestId());
	}

	@Test
	void unsolicitedRevisionRequiresKnownViewAndStrictlyIncreasingRevision() {
		var state = new ServerPresentationPolicyState();
		long requestId = state.beginConnection();
		state.applySnapshot(requestId, 1L, Status.OK, false, List.of("a:b"), List.of(), false);

		assertFalse(state.applySnapshot(0L, 1L, Status.OK, false, List.of("a:b", "c:d"), List.of(), false));
		assertFalse(state.applySnapshot(0L, 0L, Status.OK, false, List.of(), List.of(), false));
		assertTrue(state.applySnapshot(0L, 2L, Status.OK, false, List.of("a:b", "c:d"), List.of(), false));

		assertEquals(List.of("a:b", "c:d"), state.white());
		assertEquals(2L, state.revision());
	}

	@Test
	void unsolicitedBeforeKnownIsIgnored() {
		var state = new ServerPresentationPolicyState();
		state.beginConnection();

		assertFalse(state.applySnapshot(0L, 5L, Status.OK, true, List.of("a:b"), List.of(), false));
		assertFalse(state.isKnown());
	}

	@Test
	void staleCorrelatedResponseDoesNotRegressKnownRevision() {
		var state = new ServerPresentationPolicyState();
		long first = state.beginConnection();
		state.applySnapshot(first, 4L, Status.OK, false, List.of("a:b"), List.of(), false);

		long second = state.beginReadRequest();
		assertFalse(state.applySnapshot(second, 3L, Status.OK, false, List.of("z:z"), List.of(), false));

		assertEquals(List.of("a:b"), state.white());
		assertEquals(4L, state.revision());
		assertEquals(ServerPresentationPolicyState.NO_PENDING_REQUEST, state.pendingRequestId());
	}

	@Test
	void disconnectClearsStateAndRejectsLatePackets() {
		var state = new ServerPresentationPolicyState();
		long requestId = state.beginConnection();
		state.applySnapshot(requestId, 1L, Status.OK, true, List.of("a:b"), List.of(), false);

		state.resetForDisconnect();

		assertFalse(state.isConnected());
		assertFalse(state.isKnown());
		assertFalse(state.canEdit());
		assertEquals(List.of(), state.white());
		assertEquals(ServerPresentationPolicyState.NO_PENDING_REQUEST, state.pendingRequestId());

		assertFalse(state.applySnapshot(requestId, 2L, Status.OK, true, List.of("c:d"), List.of(), false));
		assertFalse(state.applySnapshot(0L, 2L, Status.OK, true, List.of("c:d"), List.of(), false));
		assertFalse(state.isKnown());
	}

	@Test
	void readRequestIsUnavailableWhileDisconnected() {
		var state = new ServerPresentationPolicyState();

		assertEquals(ServerPresentationPolicyState.NO_PENDING_REQUEST, state.beginReadRequest());
	}

	@Test
	void mutationHelperRequiresKnownEditPermission() {
		var state = new ServerPresentationPolicyState();
		long requestId = state.beginConnection();

		assertTrue(state.beginMutation(Operation.ADD_WHITE, "a:b", false).isEmpty());

		state.applySnapshot(requestId, 1L, Status.OK, false, List.of(), List.of(), false);
		assertTrue(state.beginMutation(Operation.ADD_WHITE, "a:b", false).isEmpty());

		long readId = state.beginReadRequest();
		state.applySnapshot(readId, 2L, Status.OK, true, List.of(), List.of(), false);

		var packet = state.beginMutation(Operation.ADD_WHITE, "a:b", false);

		assertTrue(packet.isPresent());
		assertEquals(Operation.ADD_WHITE, packet.get().operation());
		assertEquals("a:b", packet.get().selector());
		assertEquals(packet.get().requestId(), state.pendingRequestId());
	}

	@Test
	void invalidMutationSelectorIsNotAllocated() {
		var state = new ServerPresentationPolicyState();
		long requestId = state.beginConnection();
		state.applySnapshot(requestId, 1L, Status.OK, true, List.of(), List.of(), false);

		assertTrue(state.beginMutation(Operation.ADD_WHITE, "Not A Selector", false).isEmpty());
		assertTrue(state.beginMutation(Operation.ADD_WHITE, null, false).isEmpty());
	}

	@Test
	void invalidSnapshotListsAreRejectedWithoutMarkingReady() {
		var state = new ServerPresentationPolicyState();
		long requestId = state.beginConnection();

		assertFalse(state.applySnapshot(requestId, 1L, Status.OK, true, List.of("Not A Selector"), List.of(), false));
		assertFalse(state.isKnown());
	}

	@Test
	void deniedResponseClearsEditHintWithoutPublishingView() {
		var state = new ServerPresentationPolicyState();
		long readId = state.beginConnection();
		state.applySnapshot(readId, 1L, Status.OK, true, List.of("a:b"), List.of(), false);
		assertTrue(state.canEdit());

		var mutation = state.beginMutation(Operation.ADD_WHITE, "c:d", false);
		assertTrue(mutation.isPresent());
		assertFalse(state.applySnapshot(mutation.get().requestId(), 2L, Status.DENIED, false, List.of(), List.of(), false));

		assertFalse(state.canEdit());
		assertEquals(List.of("a:b"), state.white());
		assertEquals(1L, state.revision());
		assertEquals(Status.DENIED, state.lastStatus());
	}

	@Test
	void canMutateRequiresKnownGrantedPermissionAndNoPendingRequest() {
		var state = new ServerPresentationPolicyState();

		assertFalse(state.canMutate());

		long readId = state.beginConnection();
		assertFalse(state.canMutate());

		state.applySnapshot(readId, 1L, Status.OK, false, List.of("a:b"), List.of(), false);
		assertFalse(state.canMutate());

		long grantedId = state.beginReadRequest();
		state.applySnapshot(grantedId, 2L, Status.OK, true, List.of("a:b"), List.of(), false);
		assertTrue(state.canMutate());

		var mutation = state.beginMutation(Operation.ADD_WHITE, "c:d", false);
		assertTrue(mutation.isPresent());
		assertFalse(state.canMutate());

		// A revoked permission cannot submit the text that was typed while the
		// edit control was still enabled.
		assertFalse(state.applySnapshot(
			mutation.get().requestId(), 3L, Status.DENIED, false, List.of(), List.of(), false));
		assertFalse(state.canMutate());
		assertTrue(state.beginMutation(Operation.ADD_WHITE, "e:f", false).isEmpty());
		assertTrue(state.beginMutation(Operation.SET_WHITELIST_ONLY, "", true).isEmpty());
		assertEquals(ServerPresentationPolicyState.NO_PENDING_REQUEST, state.pendingRequestId());
	}

	@Test
	void listenersAreNotifiedOnKnownTransitionAndDisconnect() {
		var state = new ServerPresentationPolicyState();
		AtomicInteger notifications = new AtomicInteger();
		ServerPresentationPolicyState.ChangeListener listener = ignored -> notifications.incrementAndGet();
		state.addListener(listener);

		long requestId = state.beginConnection();
		state.applySnapshot(requestId, 1L, Status.OK, true, List.of(), List.of(), false);
		state.resetForDisconnect();

		assertTrue(notifications.get() >= 3);

		int before = notifications.get();
		state.removeListener(listener);
		state.beginReadRequest();
		assertEquals(before, notifications.get());
	}

	@Test
	void viewStatusReportsDisconnectedPendingFailureReadyAndUnavailable() {
		var state = new ServerPresentationPolicyState();
		assertEquals(ServerPresentationPolicyState.ViewStatus.DISCONNECTED, state.viewStatus());

		long requestId = state.beginConnection();
		assertEquals(ServerPresentationPolicyState.ViewStatus.PENDING, state.viewStatus());

		state.applySnapshot(requestId, 1L, Status.DENIED, false, List.of(), List.of(), false);
		assertEquals(ServerPresentationPolicyState.ViewStatus.FAILED, state.viewStatus());

		long readId = state.beginReadRequest();
		assertEquals(ServerPresentationPolicyState.ViewStatus.PENDING, state.viewStatus());
		state.applySnapshot(readId, 2L, Status.OK, true, List.of(), List.of(), false);
		assertEquals(ServerPresentationPolicyState.ViewStatus.READY, state.viewStatus());

		// A connected route that answers with an unusable snapshot never regresses
		// the retained known view and never becomes an authoritative empty view.
		long invalidRead = state.beginReadRequest();
		assertFalse(state.applySnapshot(
			invalidRead, 3L, Status.OK, true, List.of("Not A Selector"), List.of(), false));
		assertEquals(ServerPresentationPolicyState.ViewStatus.READY, state.viewStatus());
		assertTrue(state.isKnown());
		assertEquals(List.of(), state.white());
		assertEquals(2L, state.revision());
	}

	@Test
	void connectedRouteWithoutAnyUsableAnswerIsUnavailableNotReady() {
		var state = new ServerPresentationPolicyState();
		long requestId = state.beginConnection();

		assertFalse(state.applySnapshot(
			requestId, 1L, Status.OK, true, List.of("Not A Selector"), List.of(), false));

		assertFalse(state.isKnown());
		assertEquals(ServerPresentationPolicyState.ViewStatus.UNAVAILABLE, state.viewStatus());
	}

	@Test
	void retainedViewWithAFailedLatestMutationIsNotReportedReady() {
		var state = new ServerPresentationPolicyState();
		long readId = state.beginConnection();
		state.applySnapshot(readId, 1L, Status.OK, true, List.of("a:b"), List.of(), false);

		var mutation = state.beginMutation(Operation.ADD_WHITE, "c:d", false);
		assertTrue(mutation.isPresent());
		assertFalse(state.applySnapshot(
			mutation.get().requestId(), 2L, Status.DENIED, false, List.of("a:b"), List.of(), false));

		assertTrue(state.isKnown());
		assertEquals(List.of("a:b"), state.white());
		assertEquals(ServerPresentationPolicyState.ViewStatus.FAILED, state.viewStatus());
		assertFalse(state.canEdit());
	}

	@Test
	void expiredReadTimesOutAndLateResponseIsIgnored() {
		var clock = new AtomicLong();
		var state = new ServerPresentationPolicyState(clock::get, 5_000L);
		long requestId = state.beginConnection();

		assertEquals(ServerPresentationPolicyState.ViewStatus.PENDING, state.viewStatus());
		clock.set(4_999L);
		assertFalse(state.tick());
		assertEquals(requestId, state.pendingRequestId());

		clock.set(5_000L);
		assertTrue(state.tick());
		assertEquals(ServerPresentationPolicyState.NO_PENDING_REQUEST, state.pendingRequestId());
		assertEquals(ServerPresentationPolicyState.ViewStatus.TIMED_OUT, state.viewStatus());
		assertTrue(state.lastRequestTimedOut());
		assertFalse(state.mutationOutcomeUncertain());

		assertFalse(state.applySnapshot(requestId, 1L, Status.OK, true, List.of("a:b"), List.of(), false));
		assertFalse(state.isKnown());

		long retry = state.beginReadRequest();
		assertTrue(retry > 0L);
		assertEquals(ServerPresentationPolicyState.ViewStatus.PENDING, state.viewStatus());
	}

	@Test
	void mutationTimeoutRequiresAConfirmedReadBeforeAnotherMutation() {
		var clock = new AtomicLong();
		var state = new ServerPresentationPolicyState(clock::get, 5_000L);
		long readId = state.beginConnection();
		state.applySnapshot(readId, 1L, Status.OK, true, List.of(), List.of(), false);

		var mutation = state.beginMutation(Operation.ADD_WHITE, "a:b", false);
		assertTrue(mutation.isPresent());
		clock.set(5_000L);
		assertTrue(state.tick());

		assertEquals(ServerPresentationPolicyState.ViewStatus.TIMED_OUT, state.viewStatus());
		assertTrue(state.mutationOutcomeUncertain());
		assertFalse(state.canMutate());
		assertTrue(state.beginMutation(Operation.ADD_WHITE, "c:d", false).isEmpty());
		assertTrue(state.beginMutation(Operation.SET_WHITELIST_ONLY, "", true).isEmpty());
		assertFalse(state.applySnapshot(
			mutation.get().requestId(), 2L, Status.OK, true, List.of("a:b"), List.of(), false));
		assertTrue(state.isKnown());
		assertEquals(List.of(), state.white());
		assertEquals(1L, state.revision());

		long retry = state.beginReadRequest();
		assertTrue(retry > 0L);
		assertTrue(state.applySnapshot(retry, 2L, Status.OK, true, List.of("a:b"), List.of(), false));
		assertFalse(state.mutationOutcomeUncertain());
		assertTrue(state.canMutate());
	}

	@Test
	void oneOutstandingCommandCannotBeReplacedAndReadRetryNeverReplacesAMutation() {
		var state = new ServerPresentationPolicyState();
		long readId = state.beginConnection();
		state.applySnapshot(readId, 1L, Status.OK, true, List.of(), List.of(), false);

		var first = state.beginMutation(Operation.ADD_WHITE, "a:b", false);
		assertTrue(first.isPresent());
		assertTrue(state.beginMutation(Operation.ADD_BLACK, "c:d", false).isEmpty());
		assertEquals(ServerPresentationPolicyState.NO_PENDING_REQUEST, state.beginReadRequest());
		assertEquals(first.get().requestId(), state.pendingRequestId());
		assertEquals(ServerPresentationPolicyState.ViewStatus.PENDING, state.viewStatus());
	}

	@Test
	void newerBroadcastBeforeResponseIsKeptWhenTheCorrelatedResponseArrives() {
		var state = new ServerPresentationPolicyState();
		long readId = state.beginConnection();
		state.applySnapshot(readId, 1L, Status.OK, true, List.of(), List.of(), false);

		var mutation = state.beginMutation(Operation.ADD_WHITE, "a:b", false);
		assertTrue(mutation.isPresent());
		assertTrue(state.applySnapshot(0L, 2L, Status.OK, true, List.of("a:b"), List.of(), false));
		assertEquals(mutation.get().requestId(), state.pendingRequestId());

		// The correlated acknowledgement still completes the request even though
		// the broadcast already advanced the known revision.
		assertFalse(state.applySnapshot(
			mutation.get().requestId(), 1L, Status.OK, true, List.of("a:b"), List.of(), false));
		assertEquals(List.of("a:b"), state.white());
		assertEquals(2L, state.revision());
		assertEquals(ServerPresentationPolicyState.NO_PENDING_REQUEST, state.pendingRequestId());
		assertEquals(ServerPresentationPolicyState.ViewStatus.READY, state.viewStatus());
	}

	@Test
	void equalRevisionAcknowledgementStillClearsPending() {
		var state = new ServerPresentationPolicyState();
		long readId = state.beginConnection();
		state.applySnapshot(readId, 1L, Status.OK, true, List.of(), List.of(), false);

		var mutation = state.beginMutation(Operation.ADD_WHITE, "a:b", false);
		assertTrue(mutation.isPresent());
		assertTrue(state.applySnapshot(0L, 2L, Status.OK, true, List.of("a:b"), List.of(), false));
		assertTrue(state.applySnapshot(
			mutation.get().requestId(), 2L, Status.OK, true, List.of("a:b"), List.of(), false));
		assertEquals(ServerPresentationPolicyState.NO_PENDING_REQUEST, state.pendingRequestId());
	}

	@Test
	void unsolicitedSnapshotNeverClearsPendingAndUncorrelatedResponseIsIgnored() {
		var state = new ServerPresentationPolicyState();
		long readId = state.beginConnection();
		state.applySnapshot(readId, 1L, Status.OK, true, List.of(), List.of(), false);

		var mutation = state.beginMutation(Operation.ADD_WHITE, "a:b", false);
		assertTrue(mutation.isPresent());
		assertTrue(state.applySnapshot(0L, 2L, Status.OK, true, List.of("a:b"), List.of(), false));
		assertEquals(mutation.get().requestId(), state.pendingRequestId());

		assertFalse(state.applySnapshot(999_999L, 3L, Status.OK, true, List.of("x:y"), List.of(), false));
		assertEquals(mutation.get().requestId(), state.pendingRequestId());
	}

	@Test
	void disconnectDuringTimeoutClearsUncertaintyAndRejectsOldIdsAfterReconnect() {
		var clock = new AtomicLong();
		var state = new ServerPresentationPolicyState(clock::get, 5_000L);
		long firstRead = state.beginConnection();
		state.applySnapshot(firstRead, 1L, Status.OK, true, List.of("a:b"), List.of(), false);
		var mutation = state.beginMutation(Operation.ADD_WHITE, "c:d", false);
		assertTrue(mutation.isPresent());
		clock.set(5_000L);
		assertTrue(state.tick());

		state.resetForDisconnect();

		assertEquals(ServerPresentationPolicyState.ViewStatus.DISCONNECTED, state.viewStatus());
		assertFalse(state.mutationOutcomeUncertain());
		assertFalse(state.lastRequestTimedOut());

		long reconnectRead = state.beginConnection();
		assertFalse(state.applySnapshot(firstRead, 2L, Status.OK, true, List.of("z:z"), List.of(), false));
		assertFalse(state.applySnapshot(
			mutation.get().requestId(), 2L, Status.OK, true, List.of("z:z"), List.of(), false));
		assertEquals(reconnectRead, state.pendingRequestId());
		assertFalse(state.isKnown());
	}
}
