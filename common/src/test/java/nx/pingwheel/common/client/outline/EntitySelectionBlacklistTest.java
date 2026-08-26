package nx.pingwheel.common.client.outline;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.math.EntitySelectionBlacklist;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySelectionBlacklistTest {

	@BeforeAll
	static void bootStrap() {
		TestEntitySupport.bootStrap();
	}

	@Test
	void registrationIsRemovedByItsIdempotentCloseHandle() {
		EntitySelectionBlacklist blacklist = new EntitySelectionBlacklist();
		TestEntitySupport.TestEntity target = TestEntitySupport.newEntity();

		EntitySelectionBlacklist.Registration registration = blacklist.register(entity -> entity == target);
		assertTrue(registration.isActive());
		assertTrue(blacklist.isBlacklisted(target));

		registration.close();
		registration.close();
		assertFalse(registration.isActive());
		assertFalse(blacklist.isBlacklisted(target));
	}

	@Test
	void multiplePredicatesAggregateAndClosingOnePreservesTheOthers() {
		EntitySelectionBlacklist blacklist = new EntitySelectionBlacklist();
		TestEntitySupport.TestEntity first = TestEntitySupport.newEntity();
		TestEntitySupport.TestEntity second = TestEntitySupport.newEntity();
		TestEntitySupport.TestEntity other = TestEntitySupport.newEntity();

		EntitySelectionBlacklist.Registration firstRegistration =
			blacklist.register(entity -> entity == first);
		EntitySelectionBlacklist.Registration secondRegistration =
			blacklist.register(entity -> entity == second);

		assertTrue(blacklist.isBlacklisted(first));
		assertTrue(blacklist.isBlacklisted(second));
		assertFalse(blacklist.isBlacklisted(other));

		firstRegistration.close();
		assertFalse(blacklist.isBlacklisted(first));
		assertTrue(blacklist.isBlacklisted(second));

		secondRegistration.close();
	}
}
