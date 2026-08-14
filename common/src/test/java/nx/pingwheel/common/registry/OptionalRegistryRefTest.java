package nx.pingwheel.common.registry;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalRegistryRefTest {

	@Test
	void presenceIsResolvedOnlyWhenQueried() {
		AtomicInteger lookups = new AtomicInteger();
		RegistryLookup lookup = (registryId, entryId) -> {
			lookups.incrementAndGet();
			return true;
		};

		OptionalRegistryRef ref = new OptionalRegistryRef("minecraft:block", "minecraft:stone");

		// constructing the ref performs no lookup
		assertEquals(0, lookups.get());

		assertTrue(ref.isPresent(lookup));
		assertEquals(1, lookups.get());
	}

	@Test
	void absentEntryReportsNotPresent() {
		RegistryLookup lookup = (registryId, entryId) -> false;

		OptionalRegistryRef ref = new OptionalRegistryRef("minecraft:block", "minecraft:missing");

		assertFalse(ref.isPresent(lookup));
	}

	@Test
	void lookupReceivesStableIds() {
		AtomicInteger registrySeen = new AtomicInteger();
		AtomicInteger entrySeen = new AtomicInteger();

		RegistryLookup lookup = (registryId, entryId) -> {
			registrySeen.set(registryId.length());
			entrySeen.set(entryId.length());
			return true;
		};

		OptionalRegistryRef ref = new OptionalRegistryRef("minecraft:block", "minecraft:stone");
		ref.isPresent(lookup);

		assertEquals("minecraft:block".length(), registrySeen.get());
		assertEquals("minecraft:stone".length(), entrySeen.get());
	}

	@Test
	void rejectsBlankRegistryId() {
		assertThrows(IllegalArgumentException.class,
			() -> new OptionalRegistryRef(" ", "minecraft:stone"));
		assertThrows(IllegalArgumentException.class,
			() -> new OptionalRegistryRef("", "minecraft:stone"));
	}

	@Test
	void rejectsBlankEntryId() {
		assertThrows(IllegalArgumentException.class,
			() -> new OptionalRegistryRef("minecraft:block", " "));
		assertThrows(IllegalArgumentException.class,
			() -> new OptionalRegistryRef("minecraft:block", ""));
	}

	@Test
	void rejectsNullRegistryId() {
		assertThrows(NullPointerException.class,
			() -> new OptionalRegistryRef(null, "minecraft:stone"));
	}

	@Test
	void rejectsNullEntryId() {
		assertThrows(NullPointerException.class,
			() -> new OptionalRegistryRef("minecraft:block", null));
	}

	@Test
	void rejectsNullLookup() {
		OptionalRegistryRef ref = new OptionalRegistryRef("minecraft:block", "minecraft:stone");

		assertThrows(NullPointerException.class, () -> ref.isPresent(null));
	}

	@Test
	void refsAreValueBased() {
		OptionalRegistryRef a = new OptionalRegistryRef("minecraft:block", "minecraft:stone");
		OptionalRegistryRef b = new OptionalRegistryRef("minecraft:block", "minecraft:stone");

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertEquals("minecraft:block", a.registryId());
		assertEquals("minecraft:stone", a.entryId());
	}
}
