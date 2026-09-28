package nx.pingwheel.common.presentation.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import nx.pingwheel.common.network.ServerPresentationPolicyS2CPacket;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Operation;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.RulesView;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Status;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerPresentationPolicyStateTest {
	private static Map<String, RulesView> rules(String entityAllow, String blockAllow) {
		var all = new java.util.LinkedHashMap<>(ServerPresentationPolicyS2CPacket.defaultRules());
		all.put("entity", new RulesView(entityAllow.isEmpty() ? List.of() : List.of(entityAllow), List.of(), false));
		all.put("block", new RulesView(blockAllow.isEmpty() ? List.of() : List.of(blockAllow), List.of(), false));
		return all;
	}

	@Test void fullRuleViewIsReadOnlyByTypeAndMutationsCarrySelectedType() {
		var state = new ServerPresentationPolicyState();
		assertNull(state.rulesFor("entity"));
		long first = state.beginConnection();
		assertNull(state.policyFor("block"));
		assertTrue(state.applySnapshot(first, 1, Status.OK, true, rules("minecraft:entity.health", "minecraft:block.state")));
		assertEquals(List.of("minecraft:entity.health"), state.rulesFor("entity").white());
		assertEquals(List.of("minecraft:block.state"), state.rulesFor("block").white());
		assertTrue(state.policyFor("entity").allows("minecraft:entity.health", false));
		assertTrue(state.beginMutation("unknown", Operation.ADD_WHITE, "a:b", false).isEmpty());
		var mutation = state.beginMutation("block", Operation.ADD_WHITE, "a:b", false).orElseThrow();
		assertEquals("block", mutation.targetTypeId());
		assertEquals(ServerPresentationPolicyState.NO_PENDING_REQUEST, state.beginReadRequest());
		assertFalse(state.canMutate());
		state.resetForDisconnect();
		assertNull(state.rulesFor("block"));
		assertFalse(state.applySnapshot(mutation.requestId(), 2, Status.OK, true, rules("", "a:b")));
	}

	@Test void timeoutAndBroadcastOrderingNeverCreateOrRegressRuleViews() {
		var time = new AtomicLong();
		var state = new ServerPresentationPolicyState(time::get, 100);
		long first = state.beginConnection();
		assertFalse(state.applySnapshot(0, 1, Status.OK, true, rules("", "")));
		assertFalse(state.applySnapshot(first, 1, Status.DENIED, false, rules("", "")));
		assertNull(state.rulesFor("entity"));
		long read = state.beginReadRequest();
		assertTrue(state.applySnapshot(read, 1, Status.OK, true, rules("a:b", "c:d")));
		var mutation = state.beginMutation("entity", Operation.ADD_WHITE, "e:f", false).orElseThrow();
		assertTrue(state.applySnapshot(0, 3, Status.OK, true, rules("e:f", "c:d")));
		assertFalse(state.applySnapshot(mutation.requestId(), 2, Status.OK, true, rules("a:b", "c:d")));
		assertEquals(List.of("e:f"), state.rulesFor("entity").white());
		var next = state.beginMutation("block", Operation.ADD_WHITE, "f:g", false).orElseThrow();
		time.set(101);
		assertTrue(state.tick());
		assertTrue(state.mutationOutcomeUncertain());
		assertTrue(state.beginMutation("entity", Operation.ADD_WHITE, "g:h", false).isEmpty());
		assertFalse(state.applySnapshot(next.requestId(), 4, Status.OK, true, rules("", "")));
		assertEquals(List.of("e:f"), state.rulesFor("entity").white());
		long confirmation = state.beginReadRequest();
		assertTrue(state.applySnapshot(confirmation, 4, Status.OK, true, rules("e:f", "f:g")));
		assertFalse(state.mutationOutcomeUncertain());
		assertEquals(List.of("f:g"), state.rulesFor("block").white());
	}

	@Test void incompleteViewCannotPublishAnAllowPolicy() {
		var state = new ServerPresentationPolicyState();
		long read = state.beginConnection();
		assertFalse(state.applySnapshot(read, 1, Status.OK, true,
			Map.of("entity", new RulesView(List.of(), List.of(), false))));
		assertNull(state.policyFor("entity"));
	}
}
