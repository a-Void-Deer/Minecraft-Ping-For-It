package nx.pingwheel.common.math;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityLocalGeometryRegistryTest {

	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void lowerPriorityWinsIndependentOfRegistrationOrderAndDuplicateCannotReplaceIt() {
		EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
		AtomicInteger winnerCalls = new AtomicInteger();
		ResourceLocation pig = ResourceLocation.withDefaultNamespace("pig");

		EntityLocalGeometryRegistry.Registration later = registry.register(
			ResourceLocation.fromNamespaceAndPath("test", "later"), 200, List.of(pig),
			(entity, request) -> EntityLocalGeometryResult.hit(hit(0.2)));
		EntityLocalGeometryRegistry.Registration earlier = registry.register(
			ResourceLocation.fromNamespaceAndPath("test", "earlier"), 100, List.of(pig),
			(entity, request) -> {
				winnerCalls.incrementAndGet();
				return EntityLocalGeometryResult.hit(hit(0.1));
			});
		EntityLocalGeometryRegistry.Registration duplicate = registry.register(
			ResourceLocation.fromNamespaceAndPath("test", "earlier"), 0, List.of(pig),
			(entity, request) -> EntityLocalGeometryResult.hit(hit(0.0)));

		TestEntity pigEntity = new TestEntity(EntityType.PIG, null);
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
		ResourceLocation pig = ResourceLocation.withDefaultNamespace("pig");
		AtomicInteger fallbackCalls = new AtomicInteger();

		registry.register(ResourceLocation.fromNamespaceAndPath("test", "broken"), 1, List.of(pig),
			(entity, request) -> {
				throw new AssertionError("recoverable test failure");
			});
		registry.register(ResourceLocation.fromNamespaceAndPath("test", "fallback"), 2, List.of(pig),
			(entity, request) -> {
				fallbackCalls.incrementAndGet();
				return EntityLocalGeometryResult.hit(hit(0.1));
			});

		TestEntity pigEntity = new TestEntity(EntityType.PIG, null);
		EntityLocalGeometryResult result = registry.snapshot().firstOwner(pigEntity).trace(pigEntity, request());

		assertEquals(EntityLocalGeometryResult.Outcome.FAILED, result.outcome());
		assertEquals(0, fallbackCalls.get());
	}

	@Test
	void invalidFluidHitIsContainedAsFailedAtTheOwnerBoundary() {
		EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
		ResourceLocation pig = ResourceLocation.withDefaultNamespace("pig");
		TestEntity pigEntity = new TestEntity(EntityType.PIG, null);

		registry.register(ResourceLocation.fromNamespaceAndPath("test", "invalid-fluid"), 1, List.of(pig),
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

	private static final class TestEntity extends Entity {

		private TestEntity(EntityType<?> type, Level level) {
			super(type, level);
		}

		@Override
		protected void defineSynchedData(SynchedEntityData.Builder builder) {
			// no entity data is needed by these ownership tests
		}

		@Override
		protected void readAdditionalSaveData(CompoundTag tag) {
			// no persisted state is needed by these ownership tests
		}

		@Override
		protected void addAdditionalSaveData(CompoundTag tag) {
			// no persisted state is needed by these ownership tests
		}
	}
}
