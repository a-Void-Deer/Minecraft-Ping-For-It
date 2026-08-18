package nx.pingwheel.common.marker;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.name.TargetNameJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidatedMarkerTargetTest {

	private static final Target TARGET =
		new Target.EntityTarget("minecraft:overworld", new UUID(0L, 1L));
	private static final TargetMatchContext CONTEXT = TargetMatchContext.entityType("minecraft:item");
	private static final MarkerAnchor ANCHOR = new MarkerAnchor(1.0, 2.0, 3.0);
	private static final TargetNameJson NAME = new TargetNameJson("{\"translate\":\"minecraft.zombie\"}");

	@Test
	void preservesAllComponents() {
		ValidatedMarkerTarget validated = new ValidatedMarkerTarget(TARGET, CONTEXT, ANCHOR, NAME);

		assertEquals(TARGET, validated.normalizedTarget());
		assertEquals(CONTEXT, validated.matchContext());
		assertEquals(ANCHOR, validated.anchor());
		assertEquals(NAME, validated.authoritativeName());
	}

	@Test
	void rejectsNullNormalizedTarget() {
		assertThrows(NullPointerException.class, () -> new ValidatedMarkerTarget(null, CONTEXT, ANCHOR, NAME));
	}

	@Test
	void rejectsNullMatchContext() {
		assertThrows(NullPointerException.class, () -> new ValidatedMarkerTarget(TARGET, null, ANCHOR, NAME));
	}

	@Test
	void rejectsNullAnchor() {
		assertThrows(NullPointerException.class, () -> new ValidatedMarkerTarget(TARGET, CONTEXT, null, NAME));
	}

	@Test
	void rejectsNullAuthoritativeName() {
		assertThrows(NullPointerException.class, () -> new ValidatedMarkerTarget(TARGET, CONTEXT, ANCHOR, null));
	}
}
