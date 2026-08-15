package nx.pingwheel.common.marker;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidatedMarkerTargetTest {

	private static final Target TARGET =
		new Target.EntityTarget("minecraft:overworld", new UUID(0L, 1L));
	private static final TargetMatchContext CONTEXT = TargetMatchContext.entityType("minecraft:item");
	private static final MarkerAnchor ANCHOR = new MarkerAnchor(1.0, 2.0, 3.0);

	@Test
	void preservesAllComponents() {
		ValidatedMarkerTarget validated = new ValidatedMarkerTarget(TARGET, CONTEXT, ANCHOR);

		assertEquals(TARGET, validated.normalizedTarget());
		assertEquals(CONTEXT, validated.matchContext());
		assertEquals(ANCHOR, validated.anchor());
	}

	@Test
	void rejectsNullNormalizedTarget() {
		assertThrows(NullPointerException.class, () -> new ValidatedMarkerTarget(null, CONTEXT, ANCHOR));
	}

	@Test
	void rejectsNullMatchContext() {
		assertThrows(NullPointerException.class, () -> new ValidatedMarkerTarget(TARGET, null, ANCHOR));
	}

	@Test
	void rejectsNullAnchor() {
		assertThrows(NullPointerException.class, () -> new ValidatedMarkerTarget(TARGET, CONTEXT, null));
	}
}
