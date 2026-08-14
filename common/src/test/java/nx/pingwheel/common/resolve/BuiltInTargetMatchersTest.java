package nx.pingwheel.common.resolve;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;
import nx.pingwheel.common.domain.TargetTypeCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInTargetMatchersTest {

	private static final String OVERWORLD = "minecraft:overworld";

	private final DefaultTargetResolver resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());

	@Test
	void droppedItemWinsOverGenericEntity() {
		Target.EntityTarget item = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());

		String resolved = resolver.resolve(item, TargetMatchContext.entityType("minecraft:item")).targetType().id();

		assertEquals("dropped_item", resolved);
	}

	@Test
	void genericEntityResolvesAsEntity() {
		Target.EntityTarget zombie = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());

		String resolved = resolver.resolve(zombie, TargetMatchContext.entityType("minecraft:zombie")).targetType().id();

		assertEquals("entity", resolved);
	}

	@Test
	void entityWithoutTypeInfoResolvesAsEntity() {
		Target.EntityTarget entity = new Target.EntityTarget(OVERWORLD, UUID.randomUUID());

		String resolved = resolver.resolve(entity, TargetMatchContext.none()).targetType().id();

		assertEquals("entity", resolved);
	}

	@Test
	void blockResolvesAsBlock() {
		Target.BlockTarget block = new Target.BlockTarget(OVERWORLD, 1, 2, 3, "minecraft:stone");

		String resolved = resolver.resolve(block, TargetMatchContext.none()).targetType().id();

		assertEquals("block", resolved);
	}

	@Test
	void locationResolvesAsLocationFallback() {
		Target.LocationTarget location = new Target.LocationTarget(OVERWORLD, 1.0, 2.0, 3.0);

		String resolved = resolver.resolve(location, TargetMatchContext.none()).targetType().id();

		assertEquals("location", resolved);
	}

	@Test
	void builtInCatalogHasAMatcherForEveryType() {
		TargetTypeCatalog catalog = TargetTypeCatalog.builtIn();
		TargetMatcherRegistry registry = BuiltInTargetMatchers.registry();

		for (var targetType : catalog.entries()) {
			assertTrue(registry.find(targetType.id()).isPresent(), "missing matcher for " + targetType.id());
		}
	}
}
