package nx.pingwheel.common.client.marker;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerOverlayStatePresentationLookupTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	private static final UUID OWNER = new UUID(0L, 1L);

	@Test
	void noMatchingViewReturnsNoPresentationPosition() {
		MarkerOverlayState.INSTANCE.clear();

		assertTrue(MarkerOverlayState.INSTANCE.lookupPresentationPosition(
			new MarkerId(1L),
			new Target.EntityTarget(OVERWORLD, new UUID(0L, 2L)),
			OVERWORLD).isEmpty());
	}

	@Test
	void mismatchedPayloadOrDimensionCannotExposeStalePosition() {
		Target originalTarget = new Target.EntityTarget(OVERWORLD, new UUID(0L, 2L));
		MarkerView staleView = new MarkerView(marker(originalTarget));

		assertTrue(MarkerOverlayState.matchingPresentationPosition(
			staleView,
			new Target.EntityTarget(OVERWORLD, new UUID(0L, 3L)),
			OVERWORLD).isEmpty());
		assertTrue(MarkerOverlayState.matchingPresentationPosition(
			staleView,
			originalTarget,
			NETHER).isEmpty());
	}

	private static ClientMarker marker(Target target) {
		return new ClientMarker(
			new MarkerId(1L),
			OWNER,
			target,
			"entity",
			"attention",
			new MarkerAnchor(1.0, 2.0, 3.0),
			1L,
			100L,
			0L,
			100L);
	}
}
