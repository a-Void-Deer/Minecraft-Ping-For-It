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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
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
	void originInsideInflatedPickBoxMakesFortyFiveDegreeEntityCompeteAtTheOrigin() {
		TestEntity diagonal = entityWithPickCenter(new Vec3(0.25, 0.75, 0));
		TestEntity centered = entityWithPickCenter(START);
		TestEntity farther = entity(EntityType.COW, 3);
		var owners = new EntityLocalGeometryRegistry();

		assertFalse(diagonal.getBoundingBox().contains(START), "only the inflated pick box contains the origin");
		RaycastSelection hit = trace(List.of(farther, diagonal, centered), owners).orElseThrow();
		assertSame(diagonal, ((EntityHitResult) hit.hitResult()).getEntity(), "first origin contact wins the tie");
		assertEquals(START, hit.hitResult().getLocation());
		assertTrue(hit.entityLocalHit().isEmpty());

		RaycastSelection apex = trace(List.of(centered, diagonal), owners).orElseThrow();
		assertSame(centered, ((EntityHitResult) apex.hitResult()).getEntity());
		assertEquals(START, apex.hitResult().getLocation(), "a center at the cone's apex is eligible");
	}

	@Test
	void originInsidePickBoxIncludesSixtyDegreeEdgeButRejectsOutsideAndBehind() {
		// (1,1,0) and (1,0,1) have a 60-degree separation. Increase only Z to leave the cone.
		Vec3 endpoint = START.add(10, 10, 0);
		TestEntity edge = entityWithPickCenter(new Vec3(0.25, 0.5, 0.25));
		TestEntity outside = entityWithPickCenter(new Vec3(0.25, 0.5, 0.250_000_01));
		TestEntity behind = entityWithPickCenter(new Vec3(-0.25, 0.25, 0));

		assertEquals(START, traceTo(edge, endpoint).orElseThrow().hitResult().getLocation());
		assertTrue(traceTo(outside, endpoint).isEmpty());
		assertTrue(traceTo(behind, endpoint).isEmpty());
	}

	@Test
	void originOutsidePickBoxUsesNativeEntryRatherThanCenterCone() {
		TestEntity centerOutsideCone = entity(EntityType.PIG, 0);
		centerOutsideCone.setBoundingBox(new AABB(1.25, 0.5, -0.25, 2.25, 7.5, 0.25));
		TestEntity centerInsideCone = entityWithPickCenter(new Vec3(3.0, 0.5, 1.0));

		assertEquals(new Vec3(1, 0.5, 0), traceTo(centerOutsideCone, END).orElseThrow().hitResult().getLocation());
		assertTrue(traceTo(centerInsideCone, END).isEmpty(), "the cone cannot pick an exterior box the ray misses");
	}

	@Test
	void startingOnPickFaceEndingOnPickFaceAndZeroLengthStillMiss() {
		TestEntity lowerFace = entityWithPickCenter(new Vec3(0.375, 0.5, 0));
		TestEntity upperFace = entityWithPickCenter(new Vec3(-0.375, 0.5, 0));
		TestEntity farFace = entityWithPickCenter(new Vec3(10.375, 0.5, 0));
		TestEntity centered = entityWithPickCenter(START);

		assertTrue(traceTo(lowerFace, END).isEmpty());
		assertTrue(traceTo(upperFace, new Vec3(-10, 0.5, 0)).isEmpty());
		assertTrue(traceTo(farFace, END).isEmpty());
		assertTrue(traceTo(centered, START).isEmpty());
	}

	@Test
	void everyOwnedNonHitOutcomeSuppressesLegacyAabbFallback() {
		@Unmodifiable List<EntityLocalGeometryResult> outcomes = List.of(
			EntityLocalGeometryResult.miss(),
			EntityLocalGeometryResult.unavailable(),
			EntityLocalGeometryResult.failed());

		for (int index = 0; index < outcomes.size(); index++) {
			EntityLocalGeometryRegistry registry = new EntityLocalGeometryRegistry();
			EntityLocalGeometryResult outcome = outcomes.get(index);
			AtomicInteger ownerCalls = new AtomicInteger();
			registry.register(id("owned-" + index), 10, List.of(vanilla("pig")), (entity, request) -> {
				ownerCalls.incrementAndGet();
				return outcome;
			});

			assertTrue(trace(List.of(entity(EntityType.PIG, 3)), registry).isEmpty(), outcome.outcome().name());
			assertTrue(trace(List.of(entityWithPickCenter(new Vec3(0.25, 0.75, 0))), registry).isEmpty(),
				"containing pick box must not revive owned " + outcome.outcome().name());
			assertEquals(2, ownerCalls.get(), "both relevant native boxes must still reach the owner");
		}
	}

	@Test
	void ownedHitCanUseItsExactSurfaceWhenOriginIsInsideAndBoxCenterIsBehind() {
		TestEntity owned = entityWithPickCenter(new Vec3(-0.25, 0.5, 0));
		var owners = new EntityLocalGeometryRegistry();
		owners.register(id("precise"), 10, List.of(vanilla("pig")),
			(entity, request) -> EntityLocalGeometryResult.hit(hit(0.02)));

		RaycastSelection exact = trace(List.of(owned), owners).orElseThrow();
		assertEquals(new Vec3(0.2, 0.5, 0), exact.hitResult().getLocation());
		assertSame(owned, exact.entityLocalHit().orElseThrow().owner());
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
	void projectedNativeBlockCompetesInWorldSpaceWithoutReplacingItsLocalFaceOrProof() {
		TestEntity owner = entity(EntityType.PIG, 3);
		RaycastSelection entity = RaycastSelection.withoutLocalGeometry(new EntityHitResult(owner, new Vec3(6, .5, 0)));
		BlockHitResult nativeBlock = new BlockHitResult(new Vec3(30_000_000, .5, 0), Direction.WEST,
			new BlockPos(30_000_000, 0, 0), false);
		EntityParentFrame frame = EntityParentFrame.copyOf(new org.joml.Matrix4d().translation(-29_999_996, 0, 0), new org.joml.Quaterniond());
		SableBlockHitFrame proof = new SableBlockHitFrame(java.util.UUID.randomUUID(), nativeBlock.getBlockPos(), nativeBlock.getLocation(), frame);
		RaycastSelection block = new RaycastSelection(nativeBlock, Optional.empty(), Optional.of(new Vec3(4, .5, 0)), Optional.of(proof));
		RaycastSelection selected = Raycast.selectNearestWorldOrEntity(START, block, Optional.of(entity)).orElseThrow();
		assertSame(nativeBlock, selected.hitResult()); assertSame(proof, selected.blockFrame().orElseThrow());
		assertEquals(Direction.WEST, ((BlockHitResult) selected.hitResult()).getDirection());
		RaycastSelection unknown = new RaycastSelection(nativeBlock, Optional.empty(), Optional.empty(), Optional.empty());
		assertSame(unknown, Raycast.selectNearestWorldOrEntity(START, unknown, Optional.of(entity)).orElseThrow());
		assertSame(unknown, Raycast.selectNearestWorldOrEntity(START, unknown, Optional.empty()).orElseThrow());
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

	private static Optional<RaycastSelection> traceTo(TestEntity candidate, Vec3 end) {
		var request = new EntityLocalRaycastRequest(START, end, POLICY, 1.0f, CollisionContext.empty(), Vec3.ZERO);
		return Raycast.traceEntityCandidates(List.of(candidate), START, end, POLICY,
			new EntityLocalGeometryRegistry().snapshot(), request);
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

	private static TestEntity entityWithPickCenter(Vec3 center) {
		TestEntity entity = entity(EntityType.PIG, center.x);
		entity.setBoundingBox(AABB.ofSize(center, 0.25, 0.25, 0.25));
		return entity;
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("test", path);
	}

	private static ResourceLocation vanilla(String path) {
		return ResourceLocation.withDefaultNamespace(path);
	}

	private static final class TestEntity extends Entity {

		private TestEntity(EntityType<?> type, @Nullable Level level) {
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
