package nx.pingwheel.common.interaction.cancel;

import java.util.UUID;

import nx.pingwheel.common.testing.Test;

import nx.pingwheel.common.domain.MarkerId;

import static nx.pingwheel.common.testing.Assertions.assertEquals;
import static nx.pingwheel.common.testing.Assertions.assertThrows;

class CancelMarkerCandidateTest {

	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Test
	void acceptsValidCandidate() {
		CancelMarkerCandidate candidate = new CancelMarkerCandidate(
			new MarkerId(1L), OWNER, "minecraft:overworld", new WorldVector(1, 2, 3));

		assertEquals(new MarkerId(1L), candidate.markerId());
		assertEquals(OWNER, candidate.ownerId());
		assertEquals("minecraft:overworld", candidate.dimensionId());
		assertEquals(new WorldVector(1, 2, 3), candidate.position());
	}

	@Test
	void rejectsNullFields() {
		assertThrows(NullPointerException.class,
			() -> new CancelMarkerCandidate(null, OWNER, "minecraft:overworld", new WorldVector(1, 2, 3)));
		assertThrows(NullPointerException.class,
			() -> new CancelMarkerCandidate(new MarkerId(1L), null, "minecraft:overworld", new WorldVector(1, 2, 3)));
		assertThrows(NullPointerException.class,
			() -> new CancelMarkerCandidate(new MarkerId(1L), OWNER, null, new WorldVector(1, 2, 3)));
		assertThrows(NullPointerException.class,
			() -> new CancelMarkerCandidate(new MarkerId(1L), OWNER, "minecraft:overworld", null));
	}

	@Test
	void rejectsBlankDimensionId() {
		assertThrows(IllegalArgumentException.class,
			() -> new CancelMarkerCandidate(new MarkerId(1L), OWNER, "", new WorldVector(1, 2, 3)));
		assertThrows(IllegalArgumentException.class,
			() -> new CancelMarkerCandidate(new MarkerId(1L), OWNER, "   ", new WorldVector(1, 2, 3)));
	}
}
