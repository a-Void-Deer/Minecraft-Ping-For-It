package nx.pingwheel.common.interaction.cancel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CancellationContextTest {

	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Test
	void acceptsValidContext() {
		WorldVector eye = new WorldVector(0, 0, 0);
		WorldVector look = new WorldVector(0, 0, 1);
		CancelMarkerCandidate candidate = candidate(1L, new WorldVector(0, 0, 5));

		CancellationContext context = new CancellationContext(OWNER, "minecraft:overworld", eye, look, List.of(candidate));

		assertEquals(OWNER, context.localOwnerId());
		assertEquals("minecraft:overworld", context.currentDimensionId());
		assertEquals(eye, context.eyePosition());
		assertEquals(look, context.lookDirection());
		assertEquals(List.of(candidate), context.candidates());
	}

	@Test
	void rejectsNullFields() {
		WorldVector eye = new WorldVector(0, 0, 0);
		WorldVector look = new WorldVector(0, 0, 1);
		List<CancelMarkerCandidate> empty = List.of();

		assertThrows(NullPointerException.class,
			() -> new CancellationContext(null, "minecraft:overworld", eye, look, empty));
		assertThrows(NullPointerException.class,
			() -> new CancellationContext(OWNER, null, eye, look, empty));
		assertThrows(NullPointerException.class,
			() -> new CancellationContext(OWNER, "minecraft:overworld", null, look, empty));
		assertThrows(NullPointerException.class,
			() -> new CancellationContext(OWNER, "minecraft:overworld", eye, null, empty));
		assertThrows(NullPointerException.class,
			() -> new CancellationContext(OWNER, "minecraft:overworld", eye, look, null));
	}

	@Test
	void rejectsBlankDimensionId() {
		WorldVector eye = new WorldVector(0, 0, 0);
		WorldVector look = new WorldVector(0, 0, 1);

		assertThrows(IllegalArgumentException.class,
			() -> new CancellationContext(OWNER, " ", eye, look, List.of()));
	}

	@Test
	void rejectsZeroLookDirection() {
		WorldVector eye = new WorldVector(0, 0, 0);
		WorldVector zero = new WorldVector(0, 0, 0);

		assertThrows(IllegalArgumentException.class,
			() -> new CancellationContext(OWNER, "minecraft:overworld", eye, zero, List.of()));
	}

	@Test
	void rejectsNullCandidateElement() {
		List<CancelMarkerCandidate> withNull = new ArrayList<>();
		withNull.add(null);

		assertThrows(NullPointerException.class,
			() -> new CancellationContext(OWNER, "minecraft:overworld",
				new WorldVector(0, 0, 0), new WorldVector(0, 0, 1), withNull));
	}

	@Test
	void candidatesListIsDefensivelyCopiedAndImmutable() {
		List<CancelMarkerCandidate> source = new ArrayList<>();
		source.add(candidate(1L, new WorldVector(0, 0, 5)));

		CancellationContext context = new CancellationContext(
			OWNER, "minecraft:overworld", new WorldVector(0, 0, 0), new WorldVector(0, 0, 1), source);

		source.clear();

		assertEquals(1, context.candidates().size());
		assertThrows(UnsupportedOperationException.class, () -> context.candidates().clear());
	}

	private static CancelMarkerCandidate candidate(long id, WorldVector position) {
		return new CancelMarkerCandidate(new MarkerId(id), OWNER, "minecraft:overworld", position);
	}
}
