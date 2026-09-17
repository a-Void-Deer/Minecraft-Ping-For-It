package nx.pingwheel.common.math;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaycastCandidateFlowTest {

	private static final Vec3 START = new Vec3(0, 0.5, 0);
	private static final Vec3 END = new Vec3(10, 0.5, 0);
	private static final RaycastPolicy POLICY = RaycastPolicy.from(false, false, false);

	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void unownedEntityUsesLegacyAabbWhileOwnedHitUsesPrecisePointAndSource() {
		TestEntity unowned = entity(EntityType.COW, 3);
		RaycastSelection legacy = trace(List.of(unowned), new EntityLocalGeometryRegistry()).orElseThrow();

		assertSame(unowned, ((EntityHitResult) legacy.hitResult()).getEntity());
		assertTrue(legacy.entityLocalHit().isEmpty());
		assertTrue(legacy.hitResult().getLocation().x < 3.0);

		EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
		TestEntity owned = entity(EntityType.PIG, 5);
		registry.register(id("precise"), 10, List.of(vanilla("pig")),
			(entity, request) -> EntityLocalGeometryResult.hit(hit(0.6)));

		RaycastSelection precise = trace(List.of(owned), registry).orElseThrow();

		assertEquals(new Vec3(6, 0.5, 0), precise.hitResult().getLocation());
		assertEquals("test:precise", precise.entityLocalHit().orElseThrow().sourceId());
		assertSame(owned, precise.entityLocalHit().orElseThrow().owner());
	}

	@Test
	void everyOwnedNonHitOutcomeSuppressesLegacyAabbFallback() {
		List<EntityLocalGeometryResult> outcomes = List.of(
			EntityLocalGeometryResult.miss(),
			EntityLocalGeometryResult.unavailable(),
			EntityLocalGeometryResult.failed());

		for (int index = 0; index < outcomes.size(); index++) {
			EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
			EntityLocalGeometryResult outcome = outcomes.get(index);
			registry.register(id("owned-" + index), 10, List.of(vanilla("pig")),
				(entity, request) -> outcome);

			assertTrue(trace(List.of(entity(EntityType.PIG, 3)), registry).isEmpty(), outcome.outcome().name());
		}
	}

	@Test
	void callbackFailureDoesNotReviveOwnerButASeparateEntityCanStillWin() {
		EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
		TestEntity brokenOwner = entity(EntityType.PIG, 2);
		TestEntity laterUnowned = entity(EntityType.COW, 5);
		registry.register(id("broken"), 10, List.of(vanilla("pig")), (entity, request) -> {
			throw new IllegalStateException("contained callback failure");
		});

		RaycastSelection selection = trace(List.of(brokenOwner, laterUnowned), registry).orElseThrow();

		assertSame(laterUnowned, ((EntityHitResult) selection.hitResult()).getEntity());
		assertTrue(selection.entityLocalHit().isEmpty());
	}

	@Test
	void finalWorldComparisonUsesPreciseEntitySurfaceInsteadOfOwnerAabb() {
		TestEntity owner = entity(EntityType.PIG, 3);
		EntityLocalHit localHit = new EntityLocalHit(owner, "test:precise", hit(0.6));
		RaycastSelection preciseEntity = new RaycastSelection(
			new EntityHitResult(owner, new Vec3(6, 0.5, 0)), Optional.of(localHit));
		BlockHitResult wall = new BlockHitResult(
			new Vec3(4, 0.5, 0), Direction.WEST, new BlockPos(4, 0, 0), false);

		RaycastSelection selected = Raycast.selectNearestWorldOrEntity(
			START, wall, Optional.of(preciseEntity)).orElseThrow();

		assertSame(wall, selected.hitResult());
		assertTrue(selected.entityLocalHit().isEmpty());
	}

	@Test
	void registryMutationDuringCandidateIterationChangesOnlyTheNextSnapshot() {
		EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
		AtomicBoolean registered = new AtomicBoolean();
		AtomicInteger ownerCalls = new AtomicInteger();
		TestEntity fartherPig = entity(EntityType.PIG, 8);
		TestEntity nearerCow = entity(EntityType.COW, 3);
		Iterable<Entity> mutatingCandidates = () -> new Iterator<>() {
			private final Iterator<Entity> delegate = List.<Entity>of(fartherPig, nearerCow).iterator();

			@Override
			public boolean hasNext() {
				return delegate.hasNext();
			}

			@Override
			public Entity next() {
				Entity next = delegate.next();
				if (next == nearerCow && registered.compareAndSet(false, true)) {
					registry.register(id("late-cow"), 10, List.of(vanilla("cow")), (entity, request) -> {
						ownerCalls.incrementAndGet();
						return EntityLocalGeometryResult.hit(hit(0.4));
					});
				}
				return next;
			}
		};

		RaycastSelection currentCapture = trace(mutatingCandidates, registry.snapshot()).orElseThrow();
		assertSame(nearerCow, ((EntityHitResult) currentCapture.hitResult()).getEntity());
		assertTrue(currentCapture.entityLocalHit().isEmpty());
		assertEquals(0, ownerCalls.get());

		RaycastSelection nextCapture = trace(List.of(nearerCow), registry).orElseThrow();
		assertEquals("test:late-cow", nextCapture.entityLocalHit().orElseThrow().sourceId());
		assertEquals(1, ownerCalls.get());
	}

	@Test
	void candidateFlowUsesPriorityThenLexicalSourceAndNeverCallsShadowedOwners() {
		EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
		AtomicInteger winnerCalls = new AtomicInteger();
		AtomicInteger shadowCalls = new AtomicInteger();
		registry.register(id("z-priority-shadow"), 20, List.of(vanilla("pig")), (entity, request) -> {
			shadowCalls.incrementAndGet();
			return EntityLocalGeometryResult.hit(hit(0.1));
		});
		registry.register(id("z-lex-shadow"), 10, List.of(vanilla("pig")), (entity, request) -> {
			shadowCalls.incrementAndGet();
			return EntityLocalGeometryResult.hit(hit(0.2));
		});
		registry.register(id("a-winner"), 10, List.of(vanilla("pig")), (entity, request) -> {
			winnerCalls.incrementAndGet();
			return EntityLocalGeometryResult.hit(hit(0.3));
		});

		RaycastSelection selection = trace(List.of(entity(EntityType.PIG, 3)), registry).orElseThrow();

		assertEquals("test:a-winner", selection.entityLocalHit().orElseThrow().sourceId());
		assertEquals(1, winnerCalls.get());
		assertEquals(0, shadowCalls.get());
	}

	@Test
	void nativeBlockWinsAnExactBlockFluidTie() {
		LocalGeometryHit block = hit(0.5);
		LocalGeometryHit fluid = new LocalGeometryHit(
			0.5, BlockPos.ZERO, LocalGeometryKind.FLUID, Blocks.STONE.defaultBlockState(),
			"minecraft:stone", Optional.of("minecraft:water"), new Vec3(5, 0.5, 0));

		assertTrue(NativeLocalShapeRaycaster.compareHits(block, fluid) < 0);
		assertTrue(NativeLocalShapeRaycaster.compareHits(fluid, block) > 0);
	}

	private static Optional<RaycastSelection> trace(
		Iterable<Entity> candidates,
		EntityLocalGeometryRegistry registry
	) {
		return trace(candidates, registry.snapshot());
	}

	private static Optional<RaycastSelection> trace(
		Iterable<Entity> candidates,
		EntityLocalGeometryRegistry.Snapshot snapshot
	) {
		return Raycast.traceEntityCandidates(candidates, START, END, POLICY, snapshot, request());
	}

	private static EntityLocalRaycastRequest request() {
		return new EntityLocalRaycastRequest(
			START, END, POLICY, 1.0f, CollisionContext.empty(), Vec3.ZERO);
	}

	private static LocalGeometryHit hit(double t) {
		return new LocalGeometryHit(
			t, BlockPos.ZERO, LocalGeometryKind.BLOCK, Blocks.STONE.defaultBlockState(),
			"minecraft:stone", Optional.empty(), new Vec3(t * 10, 0.5, 0));
	}

	private static TestEntity entity(EntityType<?> type, double x) {
		TestEntity entity = new TestEntity(type, null);
		entity.setPos(x, 0, 0);
		return entity;
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("test", path);
	}

	private static ResourceLocation vanilla(String path) {
		return ResourceLocation.withDefaultNamespace(path);
	}

	private static final class TestEntity extends Entity {

		private TestEntity(EntityType<?> type, Level level) {
			super(type, level);
		}

		@Override
		protected void defineSynchedData(SynchedEntityData.Builder builder) {}

		@Override
		protected void readAdditionalSaveData(CompoundTag tag) {}

		@Override
		protected void addAdditionalSaveData(CompoundTag tag) {}
	}
}
