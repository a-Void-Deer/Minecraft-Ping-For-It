package nx.pingwheel.neoforge.integration.create;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import nx.pingwheel.common.interaction.candidate.CandidateWorkBudget;
import nx.pingwheel.common.interaction.candidate.CandidateWorkLimits;
import nx.pingwheel.common.math.EntityFrameResolution;
import nx.pingwheel.common.math.EntityLocalGeometryRegistry;
import nx.pingwheel.common.math.EntityLocalGeometryResolver;
import nx.pingwheel.common.math.EntityLocalGeometryResult;
import nx.pingwheel.common.math.EntityLocalRaycastRequest;
import nx.pingwheel.common.math.EntityParentFrame;
import nx.pingwheel.common.math.EntityRaycastWork;
import nx.pingwheel.common.math.LocalGeometryKind;
import nx.pingwheel.common.math.RaycastPolicy;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Actual production matrix/capture/native-shape seams, without optional runtime jars. */
@NotNullByDefault
class AeronauticsPropellerRaycastEngineTest {

	private static final RaycastPolicy POLICY = RaycastPolicy.from(false, false, false);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void normalAndGyroUseTheActualVirtualLocalMatrixAndIndependentSurfaceOracle() {
		for (boolean gyro : List.of(false, true)) {
			NormalModel model = gyro ? new GyroModel() : new NormalModel();
			Vec3 anchor = new Vec3(10, 20, 30);
			var transform = CreateContraptionRaycastEngine.modelTransform(EntityParentFrame.identity(), anchor,
				model::applyLocalTransforms).orElseThrow();
			Vec3 start = expectedWorld(new Vec3(-1, .5, .5), anchor, gyro);
			Vec3 end = expectedWorld(new Vec3(3, .5, .5), anchor, gyro);
			EntityLocalRaycastRequest request = request(start, end, expectedWorld(new Vec3(0, 2, 0), anchor, gyro));
			var localRay = CreateContraptionRaycastEngine.transform(request, transform).orElseThrow();
			assertVector(new Vec3(-1, .5, .5), localRay.start(), 1.0E-6);
			assertVector(new Vec3(0, 2, 0), localRay.cameraFeet(), 1.0E-6);
			var result = CreateContraptionRaycastEngine.trace(request, snapshot(localRay, core()), EntityRaycastWork.unlimited());
			assertEquals(BlockPos.ZERO, result.localHit().orElseThrow().localPos());
			assertEquals(.25, result.localHit().orElseThrow().t(), 1.0E-6);
			assertVector(expectedWorld(new Vec3(0, .5, .5), anchor, gyro),
				request.pointAt(result.localHit().orElseThrow().t()), 1.0E-6);
			assertEquals(1, model.calls); assertEquals(1.0F, model.partialTick);
		}
	}

	@Test void retainedParentTranslationRotationAndScaleAffectAnchorButNeverScaleTheRotor() {
		Vec3 nativeAnchor = new Vec3(10, 20, 30);
		// Independently: scale anchor -> (30,40,120), rotate z -> (-40,30,120), translate -> (60,20,320).
		EntityParentFrame parent = EntityParentFrame.copyOf(new Matrix4d().translation(100, -10, 200)
			.rotateZ(Math.PI / 2).scale(3, 2, 4), new Quaterniond().rotationZ(Math.PI / 2));
		for (boolean gyro : List.of(false, true)) {
			NormalModel model = gyro ? new GyroModel() : new NormalModel();
			var transform = CreateContraptionRaycastEngine.modelTransform(parent, nativeAnchor, model::applyLocalTransforms).orElseThrow();
			Vec3 start = retainedWorld(new Vec3(-1, .5, .5), gyro), end = retainedWorld(new Vec3(3, .5, .5), gyro);
			EntityLocalRaycastRequest request = request(start, end, retainedWorld(new Vec3(0, 2, 0), gyro));
			var localRay = CreateContraptionRaycastEngine.transform(request, transform).orElseThrow();
			var hit = CreateContraptionRaycastEngine.trace(request, snapshot(localRay, core()), EntityRaycastWork.unlimited()).localHit().orElseThrow();
			assertEquals(.25, hit.t(), 1.0E-6);
			assertVector(retainedWorld(new Vec3(0, .5, .5), gyro), request.pointAt(hit.t()), 1.0E-6);
			assertVector(new Vec3(0, 2, 0), localRay.cameraFeet(), 1.0E-6);
		}
	}

	@Test void translatedParentAndRemoteFractionalNativeAnchorsNeverRoundTripThroughFloats() {
		Vec3 anchor = new Vec3(30_000_000.125, -12.375, -30_000_000.625);
		EntityParentFrame parent = EntityParentFrame.copyOf(new Matrix4d().translation(-30_000_000, 20, 30_000_000), new Quaterniond());
		var transform = CreateContraptionRaycastEngine.modelTransform(parent, anchor, (stack, partialTick) -> {}).orElseThrow();
		EntityLocalRaycastRequest request = request(new Vec3(-.875, 8.125, -.125), new Vec3(3.125, 8.125, -.125),
			new Vec3(.125, 9.625, -.625));
		var ray = CreateContraptionRaycastEngine.transform(request, transform).orElseThrow();
		assertVector(new Vec3(-1, .5, .5), ray.start(), 0);
		assertVector(new Vec3(0, 2, 0), ray.cameraFeet(), 0);
		var hit = CreateContraptionRaycastEngine.trace(request, snapshot(ray, core())).localHit().orElseThrow();
		assertEquals(.25, hit.t()); assertEquals(new Vec3(.125, 8.125, -.125), request.pointAt(hit.t()));

		var world = CreateContraptionRaycastEngine.modelTransform(EntityParentFrame.identity(), anchor, (stack, partialTick) -> {}).orElseThrow();
		assertVector(new Vec3(.03125, .0625, .125), world.toLocal(anchor.add(.03125, .0625, .125)), 0);
	}

	@Test void copiedVirtualPoseAndCopiedSnapshotCannotFollowLaterMutableInputs() {
		List<Matrix4f> matrices = new ArrayList<>();
		var transform = CreateContraptionRaycastEngine.modelTransform(EntityParentFrame.identity(), new Vec3(10, 0, 0),
			(stack, tick) -> { stack.translate(.25, 0, 0); matrices.add(stack.last().pose()); }).orElseThrow();
		matrices.getFirst().translation(999, 999, 999);
		assertVector(Vec3.ZERO, transform.toLocal(new Vec3(10.25, 0, 0)), 0);

		BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(0, 0, 0);
		Map<BlockPos, CreateContraptionRaycastEngine.CapturedBlock> blocks = new HashMap<>(); blocks.put(mutable, block());
		Set<BlockPos> hidden = new HashSet<>();
		var request = request(new Vec3(-1, .5, .5), new Vec3(3, .5, .5), Vec3.ZERO);
		var snapshot = CreateContraptionRaycastEngine.snapshot(new CreateContraptionRaycastEngine.LocalRay(
			request.start(), request.end(), Vec3.ZERO), blocks, hidden, -64, 384, false);
		blocks.clear(); mutable.set(20, 20, 20); hidden.add(BlockPos.ZERO);
		assertSame(Blocks.STONE, snapshot.view().getBlockState(BlockPos.ZERO).getBlock());
		assertEquals(BlockPos.ZERO, CreateContraptionRaycastEngine.trace(request, snapshot).localHit().orElseThrow().localPos());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.positions().clear());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.hiddenPositions().clear());
	}

	@Test void coreAndBladesHitButSweptBoundsAndTheSpacesBetweenBladesAreMisses() {
		Map<BlockPos, CreateContraptionRaycastEngine.CapturedBlock> blocks = new HashMap<>(core());
		blocks.put(new BlockPos(3, 0, 0), block()); blocks.put(new BlockPos(-3, 0, 0), block());
		for (double x : List.of(.5, 3.5, -2.5, 1.5, 2.5, 4.5)) {
			var request = request(new Vec3(x, .5, -2), new Vec3(x, .5, 2), Vec3.ZERO);
			var snapshot = snapshot(new CreateContraptionRaycastEngine.LocalRay(request.start(), request.end(), Vec3.ZERO), blocks);
			var result = CreateContraptionRaycastEngine.trace(request, snapshot, EntityRaycastWork.unlimited());
			boolean hit = x == .5 || x == 3.5 || x == -2.5;
			assertEquals(hit ? EntityLocalGeometryResult.Outcome.HIT : EntityLocalGeometryResult.Outcome.MISS, result.outcome(), "x=" + x);
			if (hit) assertEquals(.5, result.localHit().orElseThrow().t());
		}
	}

	@Test void frozenHiddenPositionsBlockEntitiesFluidsAndNativePolicyRemainTheCreateView() {
		BlockPos hidden = new BlockPos(0, 0, 0), water = new BlockPos(1, 0, 0);
		BlockEntity chest = new ChestBlockEntity(water, Blocks.CHEST.defaultBlockState());
		var request = request(new Vec3(-1, .5, .5), new Vec3(4, .5, .5), Vec3.ZERO);
		var ray = new CreateContraptionRaycastEngine.LocalRay(request.start(), request.end(), Vec3.ZERO);
		var snapshot = CreateContraptionRaycastEngine.snapshot(ray, Map.of(hidden, block(), water,
			new CreateContraptionRaycastEngine.CapturedBlock(Blocks.WATER.defaultBlockState(), chest)), Set.of(hidden), -64, 384, false);
		assertSame(Blocks.AIR, snapshot.view().getBlockState(hidden).getBlock());
		assertNull(snapshot.view().getBlockEntity(hidden)); assertSame(chest, snapshot.view().getBlockEntity(water));
		assertTrue(snapshot.view().getFluidState(hidden).isEmpty()); assertFalse(snapshot.view().getFluidState(water).isEmpty());
		assertEquals(EntityLocalGeometryResult.Outcome.MISS, CreateContraptionRaycastEngine.trace(request, snapshot).outcome());
		var fluids = new EntityLocalRaycastRequest(request.start(), request.end(),
			new RaycastPolicy(RaycastPolicy.BlockMode.VISUAL, RaycastPolicy.FluidMode.ANY, false), 1, CollisionContext.empty(), Vec3.ZERO);
		var fluidHit = CreateContraptionRaycastEngine.trace(fluids, snapshot, bounded(20, 0, 20)).localHit().orElseThrow();
		assertEquals(LocalGeometryKind.FLUID, fluidHit.kind()); assertEquals(water, fluidHit.localPos());
		assertEquals("minecraft:water", fluidHit.blockRegistryId()); assertEquals(Optional.of("minecraft:water"), fluidHit.fluidRegistryId());
		assertEquals(.4, fluidHit.t(), 1.0E-6);
	}

	@Test void transformedLocalFeetControlPositionalCollisionWhileOtherDecisionsDelegate() {
		var transform = CreateContraptionRaycastEngine.modelTransform(EntityParentFrame.identity(), new Vec3(10, 20, 30),
			new GyroModel()::applyLocalTransforms).orElseThrow();
		var request = request(expectedWorld(new Vec3(-1, .5, .5), new Vec3(10, 20, 30), true),
			expectedWorld(new Vec3(3, .5, .5), new Vec3(10, 20, 30), true),
			expectedWorld(new Vec3(0, 2, 0), new Vec3(10, 20, 30), true));
		var ray = CreateContraptionRaycastEngine.transform(request, transform).orElseThrow();
		CollisionContext original = new DelegatingContext();
		CollisionContext local = CreateContraptionRaycastEngine.localCollisionContext(original, ray.cameraFeet(), false);
		assertTrue(local.isAbove(Shapes.block(), BlockPos.ZERO, false));
		assertTrue(local.isDescending()); assertTrue(local.isHoldingItem(Items.STICK)); assertFalse(local.isHoldingItem(Items.BONE));
		var water = Blocks.WATER.defaultBlockState().getFluidState(); assertTrue(local.canStandOnFluid(water, water));
		assertFalse(CreateContraptionRaycastEngine.localCollisionContext(original, ray.cameraFeet(), true)
			.isAbove(Shapes.block(), BlockPos.ZERO, true));
	}

	@Test void captureChargesEveryEntryBeforeNextAndNeverAllocatesAnUnadmittedPrefix() {
		AtomicInteger reads = new AtomicInteger();
		Iterable<CreateContraptionRaycastEngine.CaptureEntry> entries = () -> new Iterator<>() {
			private int index;
			@Override public boolean hasNext() { return index < 100; }
			@Override public CreateContraptionRaycastEngine.CaptureEntry next() {
				reads.incrementAndGet(); return new CreateContraptionRaycastEngine.CaptureEntry(new BlockPos(index++, 0, 0), Blocks.STONE.defaultBlockState(), null, false);
			}
		};
		var ray = new CreateContraptionRaycastEngine.LocalRay(new Vec3(-1, .5, .5), new Vec3(3, .5, .5), Vec3.ZERO);
		var zero = bounded(0, 10, 10);
		assertThrows(EntityRaycastWork.Exhausted.class, () -> CreateContraptionRaycastEngine.snapshotCaptured(ray, entries, -64, 384, false, zero));
		assertEquals(0, reads.get()); assertFalse(zero.complete());
		var two = bounded(2, 10, 10);
		assertThrows(EntityRaycastWork.Exhausted.class, () -> CreateContraptionRaycastEngine.snapshotCaptured(ray, entries, -64, 384, false, two));
		assertEquals(2, reads.get()); assertFalse(two.complete());
		assertThrows(EntityRaycastWork.Exhausted.class, two::readLocalBlock);
	}

	@Test void captureFailureAndShapeExhaustionRejectTheWholeRegistryAttemptAfterAProvisionalHit() throws Exception {
		var request = request(new Vec3(-1, .5, .5), new Vec3(4, .5, .5), Vec3.ZERO);
		var first = new BlockPos(0, 0, 0); var second = new BlockPos(2, 0, 0);
		var snapshot = new CreateContraptionRaycastEngine.Snapshot(request.start(), request.end(), Vec3.ZERO,
			snapshot(new CreateContraptionRaycastEngine.LocalRay(request.start(), request.end(), Vec3.ZERO), Map.of(first, block(), second, block())).view(),
			List.of(first, second), Set.of(), false);
		var work = bounded(20, 20, 1);
		assertEquals(EntityLocalGeometryResult.Outcome.FAILED, throughRegistry((entity, ray) ->
			CreateContraptionRaycastEngine.trace(ray, snapshot, ray.work()), withWork(request, work)).outcome());
		assertFalse(work.complete(), "first box hit cannot escape when a later box exhausts");

		var captureWork = bounded(1, 20, 20);
		assertEquals(EntityLocalGeometryResult.Outcome.FAILED, throughRegistry((entity, ray) -> {
			var captured = CreateContraptionRaycastEngine.snapshotCaptured(new CreateContraptionRaycastEngine.LocalRay(
				ray.start(), ray.end(), Vec3.ZERO), List.of(new CreateContraptionRaycastEngine.CaptureEntry(first, Blocks.STONE.defaultBlockState(), null, false),
				new CreateContraptionRaycastEngine.CaptureEntry(second, Blocks.STONE.defaultBlockState(), null, false)), -64, 384, false, ray.work());
			return CreateContraptionRaycastEngine.trace(ray, captured, ray.work());
		}, withWork(request, captureWork)).outcome());
		assertFalse(captureWork.complete());

		AtomicInteger consumed = new AtomicInteger();
		assertEquals(EntityLocalGeometryResult.Outcome.FAILED, throughRegistry((entity, ray) -> {
			Iterable<CreateContraptionRaycastEngine.CaptureEntry> changed = () -> new Iterator<>() {
				@Override public boolean hasNext() { return true; }
				@Override public CreateContraptionRaycastEngine.CaptureEntry next() {
					if (consumed.getAndIncrement() != 0) throw new java.util.ConcurrentModificationException("changed native map");
					return new CreateContraptionRaycastEngine.CaptureEntry(first, Blocks.STONE.defaultBlockState(), null, false);
				}
			};
			var captured = CreateContraptionRaycastEngine.snapshotCaptured(new CreateContraptionRaycastEngine.LocalRay(
				ray.start(), ray.end(), Vec3.ZERO), changed, -64, 384, false, ray.work());
			return CreateContraptionRaycastEngine.trace(ray, captured, ray.work());
		}, request).outcome());
		assertEquals(2, consumed.get(), "a valid captured core cannot escape a subsequent failed live entry");
	}

	@Test void boundedScannerChargesStateReadsAndShapeBoxesWithoutDuplicatingCaptureCharges() {
		var request = request(new Vec3(-1, .5, .5), new Vec3(3, .5, .5), Vec3.ZERO);
		var ray = new CreateContraptionRaycastEngine.LocalRay(request.start(), request.end(), Vec3.ZERO);
		var work = bounded(2, 0, 1); // one live entry plus one frozen state read, exactly one native box
		var snapshot = CreateContraptionRaycastEngine.snapshotCaptured(ray,
			List.of(new CreateContraptionRaycastEngine.CaptureEntry(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null, false)), -64, 384, false, work);
		assertEquals(EntityLocalGeometryResult.Outcome.HIT, CreateContraptionRaycastEngine.trace(request, snapshot, work).outcome());
		assertTrue(work.complete());
		assertThrows(EntityRaycastWork.Exhausted.class, work::readLocalBlock);
	}

	@Test void legacyCreateTraceKeepsItsUnlimitedCostAndExistingLocalTransformSemantics() {
		var request = withWork(request(new Vec3(-1, .5, .5), new Vec3(3, .5, .5), Vec3.ZERO), bounded(0, 0, 0));
		var ray = CreateContraptionRaycastEngine.transform(request, point -> point).orElseThrow();
		assertEquals(EntityLocalGeometryResult.Outcome.HIT, CreateContraptionRaycastEngine.trace(request, snapshot(ray, core())).outcome());
		assertTrue(request.work().complete(), "legacy overload must not consume the newer bounded capability");
	}

	@Test void invalidOrSingularVirtualMatricesDoNotAcquireAWorldIdentityFallback() {
		assertTrue(CreateContraptionRaycastEngine.modelTransform(EntityParentFrame.identity(), Vec3.ZERO,
			(stack, tick) -> stack.last().pose().scaling(0)).isEmpty());
		assertTrue(CreateContraptionRaycastEngine.modelTransform(EntityParentFrame.identity(), Vec3.ZERO,
			(stack, tick) -> stack.last().pose().m00(Float.NaN)).isEmpty());
	}

	private static EntityLocalGeometryResult throughRegistry(EntityLocalGeometryResolver resolver, EntityLocalRaycastRequest request) throws Exception {
		var registry = new EntityLocalGeometryRegistry();
		registry.register(ResourceLocation.parse(AeronauticsPropellerRaycastAdapter.SOURCE_ID), 100,
			List.of(ResourceLocation.withDefaultNamespace("armor_stand")), EntityLocalGeometryRegistry.CandidateAdmission.LOADED_EXACT, resolver);
		var snapshotMethod = EntityLocalGeometryRegistry.class.getDeclaredMethod("snapshot"); snapshotMethod.setAccessible(true);
		Object snapshot = snapshotMethod.invoke(registry);
		var ownerMethod = snapshot.getClass().getDeclaredMethod("firstOwner", Entity.class); ownerMethod.setAccessible(true);
		Entity entity = VaultOptionalHarness.allocate(FixtureEntity.class);
		Object owner = ownerMethod.invoke(snapshot, entity);
		var trace = owner.getClass().getDeclaredMethod("trace", Entity.class, EntityLocalRaycastRequest.class); trace.setAccessible(true);
		return (EntityLocalGeometryResult) trace.invoke(owner, entity, request);
	}
	private static EntityLocalRaycastRequest withWork(EntityLocalRaycastRequest request, EntityRaycastWork work) {
		return new EntityLocalRaycastRequest(request.start(), request.end(), request.policy(), 1, request.collisionContext(), request.cameraFeetPosition(),
			work, (entity, queryWork) -> EntityFrameResolution.world());
	}
	private static EntityRaycastWork bounded(int blocks, int providers, int boxes) {
		return EntityRaycastWork.bounded(new CandidateWorkBudget(new CandidateWorkLimits(blocks, 0, providers, boxes)));
	}
	private static CreateContraptionRaycastEngine.CapturedBlock block() { return new CreateContraptionRaycastEngine.CapturedBlock(Blocks.STONE.defaultBlockState(), null); }
	private static @Unmodifiable Map<BlockPos, CreateContraptionRaycastEngine.CapturedBlock> core() {
		return Map.of(BlockPos.ZERO, block());
	}
	private static CreateContraptionRaycastEngine.Snapshot snapshot(CreateContraptionRaycastEngine.LocalRay ray,
		Map<BlockPos, CreateContraptionRaycastEngine.CapturedBlock> blocks) {
		return CreateContraptionRaycastEngine.snapshot(ray, blocks, Set.of(), -64, 384, false);
	}
	private static EntityLocalRaycastRequest request(Vec3 start, Vec3 end, Vec3 feet) {
		return new EntityLocalRaycastRequest(start, end, POLICY, .125F, CollisionContext.empty(), feet);
	}
	private static Vec3 expectedWorld(Vec3 local, Vec3 anchor, boolean gyro) {
		// Independent z-quarter-turn oracle. Gyro adds Q*n-n for n=(.75,0,0).
		return anchor.add((gyro ? .25 : 1) - local.y, local.x + (gyro ? .75 : 0), local.z);
	}
	private static Vec3 retainedWorld(Vec3 local, boolean gyro) {
		// Parent z-quarter-turn rotates the local model, with anchor fixed at (60,20,320).
		return new Vec3(60 - local.x - (gyro ? .75 : 0), 20 + (gyro ? .25 : 1) - local.y, 320 + local.z);
	}
	private static void assertVector(Vec3 expected, Vec3 actual, double epsilon) {
		assertEquals(expected.x, actual.x, epsilon); assertEquals(expected.y, actual.y, epsilon); assertEquals(expected.z, actual.z, epsilon);
	}

	@NotNullByDefault
	private static class NormalModel {
		int calls;
		float partialTick;
		void applyLocalTransforms(PoseStack stack, float tick) {
			calls++; partialTick = tick;
			stack.translate(.5, .5, .5); stack.mulPose(new Quaternionf().rotationZ((float) (Math.PI / 2))); stack.translate(-.5, -.5, -.5);
		}
	}
	@NotNullByDefault
	private static final class GyroModel extends NormalModel {
		@Override void applyLocalTransforms(PoseStack stack, float tick) {
			stack.translate(-.75, .75, 0); super.applyLocalTransforms(stack, tick);
		}
	}
	@NotNullByDefault
	private static final class DelegatingContext implements CollisionContext {
		@Override public boolean isDescending() { return true; }
		@Override public boolean isAbove(VoxelShape shape, BlockPos position, boolean defaultValue) { return false; }
		@Override public boolean isHoldingItem(Item item) { return item == Items.STICK; }
		@Override public boolean canStandOnFluid(FluidState state, FluidState flowing) { return true; }
	}
	@NotNullByDefault
	private static final class FixtureEntity extends Entity {
		FixtureEntity() { super(EntityType.ARMOR_STAND, null); }
		@Override public EntityType<?> getType() { return EntityType.ARMOR_STAND; }
		@Override protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {}
		@Override protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {}
		@Override protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {}
	}
}
