package nx.pingwheel.common.math;

import java.util.Optional;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import nx.pingwheel.common.integration.sable.client.SablePickingAccess;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.CandidateCollector;
import nx.pingwheel.common.interaction.candidate.PreciseTargetType;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class PickingFrameContextTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@ParameterizedTest
	@ValueSource(strings = {"missing", "push-failing", "singular"})
	void unavailablePoseScopeStillLetsOrdinaryWorldWallOccludeAndAcquireCandidates(String failure) throws Exception {
		AtomicInteger containment = new AtomicInteger(), clips = new AtomicInteger();
		Deque<it.unimi.dsi.fastutil.Function<Object, Pose3dc>> stack = new ArrayDeque<>();
		FakeSubLevel subLevel = new FakeSubLevel(); subLevel.pose.scale().set(0, 1, 1);
		BlockHitResult wall = new BlockHitResult(new Vec3(2, .5, 0), Direction.WEST, new BlockPos(2, 0, 0), false);
		Function<Position, @Nullable SubLevelAccess> containing = position -> {
			containment.incrementAndGet();
			assertEquals(2.5, position.x(), "containment must examine the returned native block, not an unrelated queried sublevel");
			return null;
		};
		SablePickingAccess access = sableAccess(containing, failure.equals("missing") ? null : supplier -> {
			if (failure.equals("push-failing")) throw new IllegalStateException("optional push unavailable");
			stack.push(supplier);
		}, stack::pop);
		try (var frames = new PickingFrameContext(context -> {
			int attempt = clips.incrementAndGet();
			if (failure.equals("singular") && attempt == 1) {
				assertEquals(1, stack.size());
				stack.peek().apply(subLevel);
				fail("singular queried pose must invalidate the scoped clip");
			}
			assertTrue(stack.isEmpty(), "ordinary native retry must run after supplier restoration");
			return wall;
		}, access)) {
			RaycastSelection normalized = frames.clip(context());
			assertEquals(1, containment.get(), "ordinary-world provenance is independent of pose-stack failure");
			assertSame(wall, normalized.hitResult());
			assertEquals(wall.getLocation(), normalized.worldPoint().orElseThrow());
			assertTrue(normalized.blockFrame().isEmpty());
			Vec3 eye = new Vec3(0, .5, 0);
			RaycastSelection entity = RaycastSelection.withoutLocalGeometry(new EntityHitResult(new TestEntity(), new Vec3(6, .5, 0)));
			RaycastSelection ordinary = Raycast.selectNearestWorldOrEntity(eye, normalized, Optional.of(entity)).orElseThrow();
			assertSame(wall, ordinary.hitResult(), "optional failure must not select the farther entity through the wall");
			var token = new ActiveInteraction().begin();
			var ray = new CapturedRay(new WorldVector(0, .5, 0), new WorldVector(1, 0, 0));
			var reference = TargetSnapshotFactory.block("minecraft:overworld", 2, 0, 0, "minecraft:stone", false);
			var resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop());
			var trace = Raycast.selectorTrace(token, ray, 10, ordinary, new CandidateCollector(), Set.of(PreciseTargetType.BLOCK));
			var acquired = trace.candidates().orElseThrow();
			assertEquals(new WorldVector(2, .5, 0), acquired.nativeOrdinaryPoint());
			var completed = acquired.finish(reference, resolver.resolve(reference.target(), reference.matchContext()), resolver);
			var location = completed.candidate(completed.slot(PreciseTargetType.LOCATION).candidateId().orElseThrow()).orElseThrow();
			assertEquals(new Target.LocationTarget("minecraft:overworld", 2, .5, 0), location.resolvedTarget().target());
			RaycastSelection withoutEntity = Raycast.selectNearestWorldOrEntity(eye, normalized, Optional.empty()).orElseThrow();
			assertTrue(Raycast.selectorTrace(token, ray, 10, withoutEntity, new CandidateCollector(), Set.of()).candidates().isPresent());
		}
		assertEquals(failure.equals("singular") ? 2 : 1, clips.get()); assertTrue(stack.isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "push-failing", "singular", "containment-failing"})
	void uncertainNativeBlockSurvivesWithoutWorldPointOrArbitraryEntityReplacement(String failure) throws Exception {
		AtomicInteger containment = new AtomicInteger(), clips = new AtomicInteger();
		Deque<it.unimi.dsi.fastutil.Function<Object, Pose3dc>> stack = new ArrayDeque<>();
		FakeSubLevel subLevel = new FakeSubLevel(); subLevel.pose.scale().set(0, 1, 1);
		Function<Position, @Nullable SubLevelAccess> containing = position -> {
			containment.incrementAndGet();
			if (failure.equals("containment-failing")) throw new IllegalStateException("containment unavailable");
			return subLevel;
		};
		var access = sableAccess(containing, failure.equals("missing") ? null : supplier -> {
			if (failure.equals("push-failing")) throw new IllegalStateException("push unavailable");
			stack.push(supplier);
		}, stack::pop);
		try (var frames = new PickingFrameContext(context -> {
			if (clips.incrementAndGet() == 1 && failure.equals("singular")) stack.peek().apply(subLevel);
			return hit();
		}, access)) {
			var nativeSelection = frames.clip(context());
			assertEquals(1, containment.get()); assertTrue(nativeSelection.worldPoint().isEmpty());
			assertTrue(nativeSelection.blockFrame().isEmpty());
			var entity = RaycastSelection.withoutLocalGeometry(new EntityHitResult(new TestEntity(), new Vec3(6, .5, 0)));
			var selected = Raycast.selectNearestWorldOrEntity(new Vec3(0, .5, 0), nativeSelection, Optional.of(entity)).orElseThrow();
			assertSame(nativeSelection, selected, "unknown block distance cannot authorize replacing the native result");
			assertEquals(hit().getLocation(), selected.hitResult().getLocation());
			assertTrue(Raycast.selectorTrace(new ActiveInteraction().begin(), CapturedRay.defaultRay(), 10,
				selected, new CandidateCollector(), Set.of(PreciseTargetType.values())).candidates().isEmpty());
		}
		assertTrue(stack.isEmpty());
	}

	/** Uses the optional adapter's existing deterministic constructor seams across its package boundary. */
	private static SablePickingAccess sableAccess(Function<Position, @Nullable SubLevelAccess> containing,
		@Nullable Consumer<it.unimi.dsi.fastutil.Function<Object, Pose3dc>> push, Runnable pop) throws ReflectiveOperationException {
		if (push == null) {
			var constructor = SablePickingAccess.class.getDeclaredConstructor(Function.class);
			constructor.setAccessible(true); return constructor.newInstance(containing);
		}
		var constructor = SablePickingAccess.class.getDeclaredConstructor(Function.class, Consumer.class, Runnable.class);
		constructor.setAccessible(true); return constructor.newInstance(containing, push, pop);
	}
	@Test void scopeClosesAfterNativeExceptionAndOptionalFailureRetriesOnlyAfterRestoration() {
		AtomicInteger depth = new AtomicInteger(), clips = new AtomicInteger();
		BlockHitResult nativeHit = hit();
		var access = new TestAccess(depth);
		try (var frames = new PickingFrameContext(context -> {
			if (clips.incrementAndGet() == 1) {
				assertEquals(1, depth.get());
				throw new PickingFrameContext.Unavailable(new IllegalStateException("optional pose"));
			}
			assertEquals(0, depth.get(), "failed logical scope must be restored before unscoped native fallback");
			return nativeHit;
		}, access)) {
			var selected = frames.clip(context());
			assertSame(nativeHit, selected.hitResult()); assertEquals(nativeHit.getLocation(), selected.worldPoint().orElseThrow());
		}
		assertEquals(2, clips.get()); assertEquals(0, depth.get());
		try (var frames = new PickingFrameContext(context -> { throw new IllegalArgumentException("native failure"); }, access)) {
			assertThrows(IllegalArgumentException.class, () -> frames.clip(context()));
		}
		assertEquals(0, depth.get());
	}

	@Test void detachedProofCopiesMutablePositionAndLegacyConstructorStillTrustsWorldHits() {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(30_000_000, 0, 0);
		EntityParentFrame frame = EntityParentFrame.copyOf(new Matrix4d().translation(-29_999_996, 0, 0), new Quaterniond());
		var proof = new SableBlockHitFrame(UUID.randomUUID(), pos, hit().getLocation(), frame);
		pos.set(0, 0, 0);
		assertEquals(hit().getBlockPos(), proof.localPos());
		var frozen = new RaycastSelection(hit(), Optional.empty(), Optional.of(new Vec3(4, .5, .5)), Optional.of(proof));
		assertEquals(new Vec3(4, .5, .5), frozen.worldPoint().orElseThrow());
		assertEquals(hit().getLocation(), new RaycastSelection(hit(), Optional.empty()).worldPoint().orElseThrow());
	}

	private static BlockHitResult hit() { return new BlockHitResult(new Vec3(30_000_000, .5, .5), Direction.WEST, new BlockPos(30_000_000, 0, 0), false); }
	private static ClipContext context() { return PingClipContext.create(Vec3.ZERO, new Vec3(10, 0, 0), RaycastPolicy.from(false, false, false), CollisionContext.empty()); }
	@NotNullByDefault
	private record TestAccess(AtomicInteger depth) implements PickingFrameContext.Access {
		@Override public PickingFrameContext.Scope openScope() { depth.incrementAndGet(); return depth::decrementAndGet; }
		@Override public RaycastSelection normalize(BlockHitResult hit) { return RaycastSelection.withoutLocalGeometry(hit); }
		@Override public EntityFrameResolution entityFrame(Entity entity, EntityRaycastWork work) { return EntityFrameResolution.world(); }
	}
	@NotNullByDefault
	private static final class FakeSubLevel implements SubLevelAccess {
		private final UUID id = UUID.randomUUID();
		private final Pose3d pose = new Pose3d();
		@Override public Pose3dc logicalPose() { return pose; }
		@Override public Pose3dc lastPose() { throw new AssertionError("logical clip must not sample interpolation"); }
		@Override public BoundingBox3dc boundingBox() { throw new AssertionError("containment must not depend on host bounds"); }
		@Override public UUID getUniqueId() { return id; }
		@Override public @Nullable String getName() { return null; }
	}
	@NotNullByDefault
	private static final class TestEntity extends Entity {
		TestEntity() { super(EntityType.COW, null); }
		@Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
		@Override protected void readAdditionalSaveData(CompoundTag tag) {}
		@Override protected void addAdditionalSaveData(CompoundTag tag) {}
	}
}
