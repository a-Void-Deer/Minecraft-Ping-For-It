package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOutlineSourceRegistryTest {

	@BeforeAll
	static void bootStrap() {
		TestEntitySupport.bootStrap();
	}

	@Test
	void registrationOrderIsStableAndSnapshotsAreImmutable() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		EntityOutlineSource first = source("mod:first");
		EntityOutlineSource second = source("mod:second");

		try (EntityOutlineSourceRegistry.Registration ignoredFirst = registry.register(first);
			 EntityOutlineSourceRegistry.Registration ignoredSecond = registry.register(second)) {
			List<EntityOutlineSource> snapshot = registry.snapshot();

			assertEquals(List.of(first, second), snapshot);
			assertThrows(UnsupportedOperationException.class,
				() -> snapshot.add(source("mod:third")));
			assertEquals(List.of(first, second), registry.snapshot());
		}
	}

	@Test
	void duplicateIdKeepsTheFirstSourceAndWarnsWithoutChangingSnapshot() {
		List<String> warnings = new ArrayList<>();
		EntityOutlineSourceRegistry registry = new EntityOutlineSourceRegistry(
			(message, failure) -> warnings.add(message));
		EntityOutlineSource first = source("mod:duplicate");
		EntityOutlineSource second = source("mod:duplicate");

		EntityOutlineSourceRegistry.Registration firstRegistration = registry.register(first);
		EntityOutlineSourceRegistry.Registration rejectedDuplicate = registry.register(second);
		rejectedDuplicate.close();
		assertSameFirst(first, registry);
		assertEquals(1, warnings.size());
		assertTrue(warnings.get(0).contains("mod:duplicate"));
		firstRegistration.close();
		assertEquals(List.of(), registry.snapshot());
	}

	@Test
	void registrationHandleRemovesOnlyItsOwnRegistrationAndIsIdempotent() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		EntityOutlineSource first = source("mod:first");
		EntityOutlineSource second = source("mod:second");
		EntityOutlineSourceRegistry.Registration firstRegistration = registry.register(first);
		EntityOutlineSourceRegistry.Registration secondRegistration = registry.register(second);

		firstRegistration.close();
		firstRegistration.close();
		assertEquals(1, registry.snapshot().size());
		assertEquals(second, registry.snapshot().get(0));
		secondRegistration.close();
		assertEquals(List.of(), registry.snapshot());
	}

	@Test
	void invalidSourceIdsAreRejectedWithoutEchoingTheUnvalidatedValue() {
		List<String> warnings = new ArrayList<>();
		EntityOutlineSourceRegistry registry = new EntityOutlineSourceRegistry(
			(message, failure) -> warnings.add(message));
		String invalidId = "not a resource id at x=1,y=2,z=3";
		EntityOutlineSource invalid = new EntityOutlineSource() {
			@Override
			public String id() {
				return invalidId;
			}

			@Override
			public boolean handles(net.minecraft.world.entity.Entity entity) {
				return false;
			}

			@Override
			public EntityBlockGeometryOutcome attempt(EntityOutlineContext context) {
				return EntityBlockGeometryOutcome.EMPTY;
			}
		};

		try (EntityOutlineSourceRegistry.Registration ignored = registry.register(invalid)) {
			assertEquals(List.of(), registry.snapshot());
		}

		assertEquals(1, warnings.size());
		assertTrue(warnings.stream().noneMatch(warning -> warning.contains(invalidId)));
		assertThrows(IllegalArgumentException.class,
			() -> EntityOutlineSource.of(invalidId, entity -> false, ignored -> EntityBlockGeometryOutcome.EMPTY));
	}

	@Test
	void handlesAnyIsTrueWhenAnySourceClaimsTheEntity() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();

		try (EntityOutlineSourceRegistry.Registration ignored = registry.register(
			EntityOutlineSource.of("mod:rejects", e -> false, ctx -> EntityBlockGeometryOutcome.EMPTY));
			EntityOutlineSourceRegistry.Registration ignored2 = registry.register(
				EntityOutlineSource.of("mod:claims", e -> e == entity, ctx -> EntityBlockGeometryOutcome.EMPTY))) {
			assertTrue(registry.handlesAny(entity));
		}
	}

	@Test
	void handlesAnyIsFalseWhenNoSourceClaimsOrWhenEntityIsNull() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();
		TestEntitySupport.TestEntity other = TestEntitySupport.newEntity();

		try (EntityOutlineSourceRegistry.Registration ignored = registry.register(
			EntityOutlineSource.of("mod:rejects", e -> false, ctx -> EntityBlockGeometryOutcome.EMPTY))) {
			assertFalse(registry.handlesAny(entity));
			assertFalse(registry.handlesAny(other));
			assertFalse(registry.handlesAny(null));
		}
	}

	@Test
	void handlesAnyFailsSoftOnRecoverableHandlesFailuresAndKeepsDiagnosingOnce() {
		List<String> warnings = new ArrayList<>();
		EntityOutlineSourceRegistry registry = new EntityOutlineSourceRegistry(
			(message, failure) -> warnings.add(message));
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();

		EntityOutlineSource broken = EntityOutlineSource.of("mod:broken", e -> {
			throw new IllegalStateException("test-only handles failure");
		}, ctx -> EntityBlockGeometryOutcome.EMPTY);
		EntityOutlineSource claiming = EntityOutlineSource.of("mod:claiming", e -> e == entity,
			ctx -> EntityBlockGeometryOutcome.EMPTY);

		try (EntityOutlineSourceRegistry.Registration ignored = registry.register(broken);
			 EntityOutlineSourceRegistry.Registration ignored2 = registry.register(claiming)) {
			assertTrue(registry.handlesAny(entity));
		}

		// The broken source is diagnosed exactly once even across repeated probes.
		EntityOutlineSourceRegistry fresh = new EntityOutlineSourceRegistry(
			(message, failure) -> warnings.add(message));
		try (EntityOutlineSourceRegistry.Registration ignored = fresh.register(broken)) {
			fresh.handlesAny(entity);
			fresh.handlesAny(entity);
		}
		assertTrue(warnings.stream().filter(warning -> warning.contains("mod:broken")).count() >= 2);
	}

	@Test
	void handlesAnyPropagatesFatalErrors() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();

		try (EntityOutlineSourceRegistry.Registration ignored = registry.register(
			EntityOutlineSource.of("mod:fatal", e -> {
				throw new FatalOutlineError();
			}, ctx -> EntityBlockGeometryOutcome.EMPTY))) {
			assertThrows(FatalOutlineError.class, () -> registry.handlesAny(entity));
		}
	}

	@Test
	void linkageAndAssertionHandlesFailuresFailSoftAndDoNotStopLaterSources() {
		EntityOutlineSourceRegistry registry = quietRegistry();
		TestEntitySupport.TestEntity entity = TestEntitySupport.newEntity();

		try (EntityOutlineSourceRegistry.Registration ignored = registry.register(
			EntityOutlineSource.of("mod:linkage", e -> {
				throw new LinkageError("test-only linkage failure");
			}, ctx -> EntityBlockGeometryOutcome.EMPTY));
			EntityOutlineSourceRegistry.Registration ignored2 = registry.register(
				EntityOutlineSource.of("mod:assertion", e -> {
					throw new AssertionError("test-only assertion failure");
				}, ctx -> EntityBlockGeometryOutcome.EMPTY));
			EntityOutlineSourceRegistry.Registration ignored3 = registry.register(
				EntityOutlineSource.of("mod:claims", e -> e == entity, ctx -> EntityBlockGeometryOutcome.EMPTY))) {
			assertTrue(registry.handlesAny(entity));
		}
	}

	private static void assertSameFirst(EntityOutlineSource expected, EntityOutlineSourceRegistry registry) {
		assertEquals(1, registry.snapshot().size());
		assertEquals(expected, registry.snapshot().get(0));
	}

	private static EntityOutlineSourceRegistry quietRegistry() {
		return new EntityOutlineSourceRegistry(EntityOutlineSourceRegistry.WarningSink.noop());
	}

	private static EntityOutlineSource source(String id) {
		return EntityOutlineSource.of(id, entity -> false, ignored -> EntityBlockGeometryOutcome.EMPTY);
	}

	private static final class FatalOutlineError extends Error {
		private FatalOutlineError() {
			super("test-only fatal error");
		}
	}
}
