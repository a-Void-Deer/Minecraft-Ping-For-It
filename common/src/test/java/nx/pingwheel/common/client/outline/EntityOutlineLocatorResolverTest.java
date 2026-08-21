package nx.pingwheel.common.client.outline;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import nx.pingwheel.common.domain.EntityLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class EntityOutlineLocatorResolverTest {

	@BeforeAll
	static void bootStrap() {
		TestEntitySupport.bootStrap();
	}

	@Test
	void uuidLocatorsResolveToTheLiveEntity() {
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		EntityLocator locator = EntityLocator.uuid(entity.getUUID());

		Entity resolved = EntityOutlineLocatorResolver.resolve(locator, key -> entity);

		assertSame(entity, resolved);
	}

	@Test
	void runtimeIdLocatorsResolveOnlyForExperienceOrbs() {
		ExperienceOrb orb = new ExperienceOrb(null, 1.0, 2.0, 3.0, 5);
		EntityLocator locator = EntityLocator.runtimeId(orb.getId());

		Entity resolved = EntityOutlineLocatorResolver.resolve(locator, key -> orb);

		assertSame(orb, resolved);
	}

	@Test
	void runtimeIdLocatorForANonOrbIsRejectedAfterCanonicalization() {
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		// A non-orb entity canonicalizes to a UUID locator, so a runtime-id
		// locator cannot match it and must be rejected.
		EntityLocator locator = EntityLocator.runtimeId(entity.getId());

		assertNull(EntityOutlineLocatorResolver.resolve(locator, key -> entity));
	}

	@Test
	void goneEntityResolvesToNull() {
		EntityLocator locator = EntityLocator.uuid(UUID.randomUUID());
		assertNull(EntityOutlineLocatorResolver.resolve(locator, key -> null));
	}

	@Test
	void nullLocatorOrLookupResolvesToNull() {
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		assertNull(EntityOutlineLocatorResolver.resolve(null, key -> entity));
		assertNull(EntityOutlineLocatorResolver.resolve(EntityLocator.uuid(entity.getUUID()), null));
	}

	@Test
	void mismatchedCanonicalLocatorIsRejected() {
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		// The lookup returns a live entity whose own locator differs from the
		// requested one (stale/miskeyed lookup); canonicalization cannot make
		// them match, so resolution must reject it rather than render it.
		EntityLocator stale = EntityLocator.uuid(new UUID(0L, 1L));

		assertNull(EntityOutlineLocatorResolver.resolve(stale, key -> entity));
	}

	@Test
	void resolverReadsTheLookupMapByRequestedLocator() {
		TestEntitySupport.TestEntity a = TestEntitySupport.newEntity();
		ExperienceOrb orb = new ExperienceOrb(null, 1.0, 2.0, 3.0, 5);
		Map<EntityLocator, Entity> map = new HashMap<>();
		map.put(EntityLocator.uuid(a.getUUID()), a);
		map.put(EntityLocator.runtimeId(orb.getId()), orb);

		assertSame(a, EntityOutlineLocatorResolver.resolve(EntityLocator.uuid(a.getUUID()), map::get));
		assertSame(orb, EntityOutlineLocatorResolver.resolve(EntityLocator.runtimeId(orb.getId()), map::get));
		assertNull(EntityOutlineLocatorResolver.resolve(EntityLocator.uuid(UUID.randomUUID()), map::get));
	}
}
