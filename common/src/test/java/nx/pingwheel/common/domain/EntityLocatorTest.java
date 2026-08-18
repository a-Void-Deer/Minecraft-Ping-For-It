package nx.pingwheel.common.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityLocatorTest {

	private static final UUID ENTITY = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");

	@Test
	void variantsExposeStableKindAndAsciiTags() {
		EntityLocator uuid = EntityLocator.uuid(ENTITY);
		EntityLocator runtime = EntityLocator.runtimeId(42);

		assertEquals(EntityLocator.Kind.UUID, uuid.kind());
		assertEquals("uuid", uuid.tag());
		assertEquals(0, uuid.wireTag());
		assertEquals(EntityLocator.Kind.RUNTIME_ID, runtime.kind());
		assertEquals("runtime_id", runtime.tag());
		assertEquals(1, runtime.wireTag());
	}

	@Test
	void valuesHaveValueEqualityAndDifferentKindsNeverCollide() {
		assertEquals(EntityLocator.uuid(ENTITY), new EntityLocator.UUID(ENTITY));
		assertEquals(EntityLocator.runtimeId(42), new EntityLocator.RuntimeId(42));
		assertNotEquals(EntityLocator.uuid(ENTITY), EntityLocator.runtimeId(42));
	}

	@Test
	void uuidValueMustNotBeNull() {
		assertThrows(NullPointerException.class, () -> new EntityLocator.UUID(null));
	}

	@Test
	void runtimeIdMustBeNonNegative() {
		assertThrows(IllegalArgumentException.class, () -> new EntityLocator.RuntimeId(-1));
	}

	@Test
	void unknownWireTagIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> EntityLocator.kindFromWireTag(99));
	}
}
