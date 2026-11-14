package nx.pingwheel.common.math;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import nx.pingwheel.common.client.outline.TestEntitySupport;
import nx.pingwheel.common.client.outline.TestEntitySupport.TestEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityLocalGeometryRegistryTest {

	@BeforeAll
	static void bootStrap() {
		TestEntitySupport.bootStrap();
	}

	@Test
	void lowerPriorityWinsIndependentOfRegistrationOrderAndDuplicateCannotReplaceIt() {
		EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
		AtomicInteger winnerCalls = new AtomicInteger();
		Identifier pig = Identifier.withDefaultNamespace("pig");

		EntityLocalGeometryRegistry.Registration later = registry.register(
			Identifier.fromNamespaceAndPath("test", "later"), 200, List.of(pig),
			(entity, request) -> EntityLocalGeometryResult.hit(hit(0.2)));
		EntityLocalGeometryRegistry.Registration earlier = registry.register(
			Identifier.fromNamespaceAndPath("test", "earlier"), 100, List.of(pig),
			(entity, request) -> {
				winnerCalls.incrementAndGet();
				return EntityLocalGeometryResult.hit(hit(0.1));
			});
		EntityLocalGeometryRegistry.Registration duplicate = registry.register(
			Identifier.fromNamespaceAndPath("test", "earlier"), 0, List.of(pig),
			(entity, request) -> EntityLocalGeometryResult.hit(hit(0.0)));

		TestEntity pigEntity = TestEntitySupport.newEntity();
		EntityLocalGeometryRegistry.Claim claim = registry.snapshot().firstOwner(pigEntity);
		EntityLocalGeometryResult result = claim.trace(pigEntity, request());

		assertTrue(later.isActive());
		assertTrue(earlier.isActive());
		assertFalse(duplicate.isActive());
		assertEquals("test:earlier", claim.sourceId());
		assertEquals(EntityLocalGeometryResult.Outcome.HIT, result.outcome());
		assertEquals(1, winnerCalls.get());

		duplicate.close();
		earlier.close();
		earlier.close();
		assertEquals("test:later", registry.snapshot().firstOwner(pigEntity).sourceId());
	}

	@Test
	void recoverableCallbackFailureBecomesFailedWithoutTryingAnotherOwner() {
		EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
		Identifier pig = Identifier.withDefaultNamespace("pig");
		AtomicInteger fallbackCalls = new AtomicInteger();

		registry.register(Identifier.fromNamespaceAndPath("test", "broken"), 1, List.of(pig),
			(entity, request) -> {
				throw new AssertionError("recoverable test failure");
			});
		registry.register(Identifier.fromNamespaceAndPath("test", "fallback"), 2, List.of(pig),
			(entity, request) -> {
				fallbackCalls.incrementAndGet();
				return EntityLocalGeometryResult.hit(hit(0.1));
			});

		TestEntity pigEntity = TestEntitySupport.newEntity();
		EntityLocalGeometryResult result = registry.snapshot().firstOwner(pigEntity).trace(pigEntity, request());

		assertEquals(EntityLocalGeometryResult.Outcome.FAILED, result.outcome());
		assertEquals(0, fallbackCalls.get());
	}

	@Test
	void invalidFluidHitIsContainedAsFailedAtTheOwnerBoundary() {
		EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
		Identifier pig = Identifier.withDefaultNamespace("pig");
		TestEntity pigEntity = TestEntitySupport.newEntity();

		registry.register(Identifier.fromNamespaceAndPath("test", "invalid-fluid"), 1, List.of(pig),
			(entity, request) -> EntityLocalGeometryResult.hit(new LocalGeometryHit(
				0.2, BlockPos.ZERO, LocalGeometryKind.FLUID, Blocks.STONE.defaultBlockState(),
				"minecraft:stone", Optional.empty(), new Vec3(2, 0, 0))));

		EntityLocalGeometryResult result = registry.snapshot().firstOwner(pigEntity).trace(pigEntity, request());

		assertEquals(EntityLocalGeometryResult.Outcome.FAILED, result.outcome());
		assertTrue(result.localHit().isEmpty());
	}

	@Test
	void localHitRequiresFluidIdentityExactlyForFluidKind() {
		assertThrows(IllegalArgumentException.class, () -> new LocalGeometryHit(
			0.2, BlockPos.ZERO, LocalGeometryKind.FLUID, Blocks.STONE.defaultBlockState(),
			"minecraft:stone", Optional.empty(), new Vec3(2, 0, 0)));
		assertThrows(IllegalArgumentException.class, () -> new LocalGeometryHit(
			0.2, BlockPos.ZERO, LocalGeometryKind.BLOCK, Blocks.STONE.defaultBlockState(),
			"minecraft:stone", Optional.of("minecraft:water"), new Vec3(2, 0, 0)));
	}

	private static EntityLocalRaycastRequest request() {
		return new EntityLocalRaycastRequest(
			new Vec3(0, 0, 0), new Vec3(10, 0, 0),
			RaycastPolicy.from(false, false, false), 1.0f,
			CollisionContext.empty(), Vec3.ZERO);
	}

	private static LocalGeometryHit hit(double t) {
		return new LocalGeometryHit(
			t, BlockPos.ZERO, LocalGeometryKind.BLOCK, Blocks.STONE.defaultBlockState(),
			"minecraft:stone", Optional.empty(), new Vec3(t * 10.0, 0, 0));
	}

}
