package nx.pingwheel.common.marker;

import java.util.*;
import nx.pingwheel.common.domain.*;
import nx.pingwheel.common.name.TargetNameJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DedicatedMarkerAdmissionTest {
	@Test void dedicatedAdmissionRunsWithoutSectionIntentAndFailureCannotStoreMarker() {
		var store = new ServerMarkerStore(new MarkerIdSource()); var target = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
		var type = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow(); UUID owner = new UUID(1, 1);
		var service = new MarkerCreationService(store, (t, c) -> new ResolvedTarget(t, type), PingTypeCatalog.builtIn(),
			(p, t) -> AuthoritativeTargetValidation.accepted(new ValidatedMarkerTarget(t, TargetMatchContext.none(), new MarkerAnchor(1, 2, 3), new TargetNameJson("{\"text\":\"chest\"}"))));
		int[] admissions = {0};
		var rejected = service.createDedicated(null, owner, target, type.defaultPingType().id(), 0, 20, List.of(owner), (t, id, p, audience, intents) -> {
			admissions[0]++; assertTrue(intents.isEmpty()); return MarkerCreationService.AdmissionResult.rejected(MarkerRejectReason.INVALID_REQUEST);
		});
		assertFalse(rejected.isAccepted()); assertEquals(1, admissions[0]); assertEquals(0, store.size());
		var accepted = service.createDedicated(null, owner, target, type.defaultPingType().id(), 0, 20, List.of(owner),
			(t, id, p, audience, intents) -> MarkerCreationService.AdmissionResult.accepted(List.of(), Map.of()));
		assertTrue(accepted.isAccepted()); assertEquals(1, store.size()); assertTrue(accepted.creation().orElseThrow().marker().properties().isEmpty());
	}
}
