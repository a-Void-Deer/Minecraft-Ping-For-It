package nx.pingwheel.common.name;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import nx.pingwheel.common.domain.MarkerId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTargetNameStoreTest {

	private static TargetNameJson name(String text) {
		return new TargetNameJson("{\"translate\":\"" + text + "\"}");
	}

	// --- created / upsert / idempotency ---

	@Test
	void createdStoresNameForKeyedId() {
		ClientTargetNameStore store = new ClientTargetNameStore();
		MarkerId id = new MarkerId(5L);
		TargetNameJson value = name("minecraft.zombie");

		store.onCreated(id, value);

		assertEquals(Optional.of(value), store.find(id));
		assertEquals(Map.of(id, value), store.snapshot());
	}

	@Test
	void createdIsIdempotentForSameIdAndName() {
		ClientTargetNameStore store = new ClientTargetNameStore();
		MarkerId id = new MarkerId(3L);
		TargetNameJson value = name("minecraft.zombie");

		store.onCreated(id, value);
		store.onCreated(id, value);

		assertEquals(1, store.snapshot().size());
		assertEquals(Optional.of(value), store.find(id));
	}

	@Test
	void createdSameIdReplacesNameWithLatest() {
		ClientTargetNameStore store = new ClientTargetNameStore();
		MarkerId id = new MarkerId(3L);

		store.onCreated(id, name("minecraft.zombie"));
		store.onCreated(id, name("minecraft.skeleton"));

		assertEquals(1, store.snapshot().size());
		assertEquals(Optional.of(name("minecraft.skeleton")), store.find(id));
	}

	@Test
	void createdRejectsNulls() {
		ClientTargetNameStore store = new ClientTargetNameStore();

		assertThrows(NullPointerException.class, () -> store.onCreated(null, name("a")));
		assertThrows(NullPointerException.class, () -> store.onCreated(new MarkerId(1L), null));
	}

	// --- removed / unknown ---

	@Test
	void removedRemovesName() {
		ClientTargetNameStore store = new ClientTargetNameStore();
		MarkerId id = new MarkerId(7L);

		store.onCreated(id, name("a"));
		store.onRemoved(id);

		assertTrue(store.find(id).isEmpty());
		assertTrue(store.snapshot().isEmpty());
	}

	@Test
	void removedUnknownIdIsSafe() {
		ClientTargetNameStore store = new ClientTargetNameStore();

		assertDoesNotThrow(() -> store.onRemoved(new MarkerId(99L)));
	}

	@Test
	void removedRejectsNull() {
		assertThrows(NullPointerException.class, () -> new ClientTargetNameStore().onRemoved(null));
	}

	// --- find / clear ---

	@Test
	void findRejectsNull() {
		assertThrows(NullPointerException.class, () -> new ClientTargetNameStore().find(null));
	}

	@Test
	void clearDropsEverything() {
		ClientTargetNameStore store = new ClientTargetNameStore();

		store.onCreated(new MarkerId(1L), name("a"));
		store.onCreated(new MarkerId(2L), name("b"));
		store.clear();

		assertTrue(store.snapshot().isEmpty());
	}

	// --- snapshot determinism ---

	@Test
	void snapshotIsSortedByAscendingMarkerIdRegardlessOfInsertionOrder() {
		ClientTargetNameStore store = new ClientTargetNameStore();

		store.onCreated(new MarkerId(9L), name("a"));
		store.onCreated(new MarkerId(2L), name("b"));
		store.onCreated(new MarkerId(7L), name("c"));

		assertEquals(
			Map.of(
				new MarkerId(2L), name("b"),
				new MarkerId(7L), name("c"),
				new MarkerId(9L), name("a")),
			store.snapshot());
	}

	@Test
	void snapshotIsImmutableAndDetached() {
		ClientTargetNameStore store = new ClientTargetNameStore();

		store.onCreated(new MarkerId(1L), name("a"));

		Map<MarkerId, TargetNameJson> snapshot = store.snapshot();

		assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());

		store.onCreated(new MarkerId(2L), name("b"));

		assertEquals(1, snapshot.size());
		assertNotSame(store.snapshot(), snapshot);
	}
}
