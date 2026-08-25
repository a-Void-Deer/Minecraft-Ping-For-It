package nx.pingwheel.common.integration.externalblock;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalBlockReferenceIndexTest {

	private static final ExternalBlockReferenceIndex.LocatorKey FIRST =
		new ExternalBlockReferenceIndex.LocatorKey("sable", "locator-a", "minecraft:chest", true);
	private static final ExternalBlockReferenceIndex.LocatorKey SECOND =
		new ExternalBlockReferenceIndex.LocatorKey("sable", "locator-b", "minecraft:chest", true);

	@Test
	void uncommittedPrepareCanBeRolledBackWithoutLeavingAnEntry() {
		ExternalBlockReferenceIndex index = new ExternalBlockReferenceIndex();
		List<String> removed = new ArrayList<>();

		ExternalBlockReferenceIndex.Lease lease = index.prepare(FIRST, () -> "tracking-1");

		assertTrue(lease.newlyCreated());
		assertTrue(index.rollback(lease, removed::add));
		assertEquals(List.of("tracking-1"), removed);
		assertEquals(0, index.size());
		assertFalse(index.rollback(lease, removed::add));
		assertEquals(List.of("tracking-1"), removed);
	}

	@Test
	void sameLocatorReusesStableIdAndRemovesOnlyAfterLastRelease() {
		ExternalBlockReferenceIndex index = new ExternalBlockReferenceIndex();
		List<String> removed = new ArrayList<>();

		ExternalBlockReferenceIndex.Lease first = index.prepare(FIRST, () -> "tracking-1");
		ExternalBlockReferenceIndex.Lease second = index.prepare(FIRST, () -> "tracking-2");

		assertEquals("tracking-1", second.stableId());
		assertFalse(second.newlyCreated());
		assertTrue(index.commit(first));
		assertTrue(index.commit(second));
		assertEquals(2, index.references("tracking-1"));

		assertTrue(index.release("tracking-1", removed::add));
		assertTrue(removed.isEmpty());
		assertEquals(1, index.references("tracking-1"));
		assertTrue(index.release("tracking-1", removed::add));
		assertEquals(List.of("tracking-1"), removed);
		assertFalse(index.release("tracking-1", removed::add));
		assertEquals(0, index.size());
	}

	@Test
	void migrationPreservesStableIdAndReferencesAndRejectsCollisions() {
		ExternalBlockReferenceIndex index = new ExternalBlockReferenceIndex();
		ExternalBlockReferenceIndex.Lease first = index.prepare(FIRST, () -> "tracking-1");
		assertTrue(index.commit(first));

		assertTrue(index.migrate("tracking-1", SECOND));
		assertEquals("tracking-1", index.stableFor(SECOND).orElseThrow());
		assertTrue(index.stableFor(FIRST).isEmpty());
		assertEquals(1, index.references("tracking-1"));

		ExternalBlockReferenceIndex.Lease other = index.prepare(FIRST, () -> "tracking-2");
		assertTrue(index.commit(other));
		assertFalse(index.migrate("tracking-1", FIRST));
		assertEquals("tracking-2", index.stableFor(FIRST).orElseThrow());
	}

	@Test
	void closeInvokesEachPointOnceAndClearsSharedState() {
		ExternalBlockReferenceIndex index = new ExternalBlockReferenceIndex();
		ExternalBlockReferenceIndex.Lease first = index.prepare(FIRST, () -> "tracking-1");
		ExternalBlockReferenceIndex.Lease second = index.prepare(SECOND, () -> "tracking-2");
		assertTrue(index.commit(first));
		assertTrue(index.commit(second));
		assertTrue(index.commit(index.prepare(FIRST, () -> "unused")));

		List<String> removed = new ArrayList<>();
		index.close(removed::add);

		assertEquals(List.of("tracking-1", "tracking-2"), removed);
		assertEquals(0, index.size());
		index.close(removed::add);
		assertEquals(List.of("tracking-1", "tracking-2"), removed);
	}
}
