package nx.pingwheel.common.client.outline;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.world.entity.Entity;

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

	@Test
	void optionalCreateRegistrationIsOnlyExercisedWhenItsBoundaryIsAvailable() throws Exception {
		ClassLoader loader = EntitySelectionBlacklist.class.getClassLoader();
		final Class<?> adapterClass;
		final Class<?> superGlueClass;
		try {
			adapterClass = Class.forName(
				"nx.pingwheel.neoforge.integration.create.CreateEntityOutlineAdapter", false, loader);
			superGlueClass = Class.forName(
				"com.simibubi.create.content.contraptions.glue.SuperGlueEntity", false, loader);
		} catch (ClassNotFoundException | LinkageError absentOptionalIntegration) {
			return;
		}

		Object uninitializedSuperGlue = allocateWithoutConstructor(superGlueClass);
		if (!(uninitializedSuperGlue instanceof Entity superGlue)) {
			return;
		}

		var register = adapterClass.getMethod("register");
		var close = adapterClass.getMethod("close");
		try {
			register.invoke(null);
			assertTrue(EntitySelectionBlacklist.INSTANCE.isBlacklisted(superGlue));
		} finally {
			close.invoke(null);
		}

		assertFalse(EntitySelectionBlacklist.INSTANCE.isBlacklisted(superGlue));
	}

	private static Object allocateWithoutConstructor(Class<?> type) throws Exception {
		Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
		Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		Object unsafe = unsafeField.get(null);
		return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
	}
}
