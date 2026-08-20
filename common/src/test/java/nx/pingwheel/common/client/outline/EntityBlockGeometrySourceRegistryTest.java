package nx.pingwheel.common.client.outline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityBlockGeometrySourceRegistryTest {
	@Test
	void registrationOrderIsStableAndSnapshotsAreImmutable() {
		EntityBlockGeometrySourceRegistry registry = quietRegistry();
		EntityBlockGeometrySource first = source("mod:first");
		EntityBlockGeometrySource second = source("mod:second");

		try (EntityBlockGeometrySourceRegistry.Registration ignoredFirst = registry.register(first);
			 EntityBlockGeometrySourceRegistry.Registration ignoredSecond = registry.register(second)) {
			List<EntityBlockGeometrySource> snapshot = registry.snapshot();

			assertEquals(List.of(first, second), snapshot);
			assertThrows(UnsupportedOperationException.class, () -> snapshot.add(source("mod:third")));
			assertEquals(List.of(first, second), registry.snapshot());
		}
	}

	@Test
	void duplicateIdKeepsTheFirstSourceAndWarnsWithoutChangingSnapshot() {
		List<String> warnings = new ArrayList<>();
		EntityBlockGeometrySourceRegistry registry = new EntityBlockGeometrySourceRegistry(
			(sourceId, reason) -> warnings.add(sourceId + ":" + reason));
		EntityBlockGeometrySource first = source("mod:duplicate");
		EntityBlockGeometrySource second = source("mod:duplicate");

		EntityBlockGeometrySourceRegistry.Registration firstRegistration = registry.register(first);
		EntityBlockGeometrySourceRegistry.Registration rejectedDuplicate = registry.register(second);
		rejectedDuplicate.close();
		assertSame(first, registry.snapshot().get(0));
		assertEquals(1, warnings.size());
		assertTrue(warnings.get(0).startsWith("mod:duplicate:"));
		firstRegistration.close();
		assertEquals(List.of(), registry.snapshot());
	}

	@Test
	void registrationHandleRemovesOnlyItsOwnRegistrationAndIsIdempotent() {
		EntityBlockGeometrySourceRegistry registry = quietRegistry();
		EntityBlockGeometrySource first = source("mod:first");
		EntityBlockGeometrySource second = source("mod:second");
		EntityBlockGeometrySourceRegistry.Registration firstRegistration = registry.register(first);
		EntityBlockGeometrySourceRegistry.Registration secondRegistration = registry.register(second);

		firstRegistration.close();
		firstRegistration.close();
		assertEquals(1, registry.snapshot().size());
		assertSame(second, registry.snapshot().get(0));
		secondRegistration.close();
		assertEquals(List.of(), registry.snapshot());
	}

	@Test
	void invalidSourceIdsAreRejectedWithoutEchoingTheUnvalidatedValue() {
		List<String> warnings = new ArrayList<>();
		EntityBlockGeometrySourceRegistry registry = new EntityBlockGeometrySourceRegistry(
			(sourceId, category) -> warnings.add(sourceId + ":" + category));
		String invalidId = "not a resource id at x=1,y=2,z=3";
		EntityBlockGeometrySource invalid = new EntityBlockGeometrySource() {
			@Override
			public String id() {
				return invalidId;
			}

			@Override
			public EntityBlockGeometryOutcome attempt(EntityBlockGeometryContext context) {
				return EntityBlockGeometryOutcome.EMPTY;
			}
		};

		try (EntityBlockGeometrySourceRegistry.Registration ignored = registry.register(invalid)) {
			assertEquals(List.of(), registry.snapshot());
		}

		assertEquals(List.of("<invalid>:invalid-source-id"), warnings);
		assertTrue(warnings.stream().noneMatch(warning -> warning.contains(invalidId)));
		assertThrows(IllegalArgumentException.class,
			() -> EntityBlockGeometrySource.of(invalidId, ignored -> EntityBlockGeometryOutcome.EMPTY));
	}

	private static EntityBlockGeometrySourceRegistry quietRegistry() {
		return new EntityBlockGeometrySourceRegistry(EntityBlockGeometrySourceRegistry.WarningSink.noop());
	}

	private static EntityBlockGeometrySource source(String id) {
		return EntityBlockGeometrySource.of(id, ignored -> EntityBlockGeometryOutcome.EMPTY);
	}
}
