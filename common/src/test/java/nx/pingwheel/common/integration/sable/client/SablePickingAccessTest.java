package nx.pingwheel.common.integration.sable.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.math.EntityParentFrame;
import nx.pingwheel.common.math.SableBlockHitFrame;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.junit.jupiter.api.BeforeAll;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import it.unimi.dsi.fastutil.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.math.PickingFrameContext;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class SablePickingAccessTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void frozenProofCaptureKeepsNativeBindingAndFaceAndRejectsForeignOrOffRayProof() {
		UUID id = UUID.randomUUID();
		BlockHitResult hit = new BlockHitResult(new Vec3(30_000_000, .5, .5), Direction.WEST,
			new BlockPos(30_000_000, 0, 0), false);
		Matrix4d live = new Matrix4d().translation(-29_999_996, 0, 0);
		SableBlockHitFrame proof = new SableBlockHitFrame(id, hit.getBlockPos(), hit.getLocation(),
			EntityParentFrame.copyOf(live, new Quaterniond()));
		live.translation(-29_999_900, 0, 0);
		Vec3 start = new Vec3(0, .5, .5), end = new Vec3(10, .5, .5);
		var snapshot = SableClientCompanionAccess.captureFrozenBlock("minecraft:overworld", hit, start, end,
			proof, id, Blocks.STONE.defaultBlockState()).orElseThrow();
		Target.ExternalBlockTarget target = assertInstanceOf(Target.ExternalBlockTarget.class, snapshot.target());
		assertEquals("", target.stableTargetId());
		assertEquals(BlockFace.WEST, snapshot.blockHitFace().orElseThrow());
		assertEquals(4, snapshot.candidateHit().orElseThrow().worldHit().x());
		var locator = nx.pingwheel.common.integration.sable.server.SableExternalBlockLocator.parse(target.providerLocator()).orElseThrow();
		assertEquals(id, locator.subLevelId()); assertEquals(hit.getBlockPos(), locator.blockPos());
		assertTrue(SableClientCompanionAccess.captureFrozenBlock("minecraft:overworld", hit, start, end,
			proof, UUID.randomUUID(), Blocks.STONE.defaultBlockState()).isEmpty());
		assertTrue(SableClientCompanionAccess.captureFrozenBlock("minecraft:overworld", hit, Vec3.ZERO, new Vec3(0, 10, 0),
			proof, id, Blocks.STONE.defaultBlockState()).isEmpty());
		assertTrue(SableClientCompanionAccess.captureFrozenBlock("minecraft:overworld", hit, start, end,
			proof, id, Blocks.AIR.defaultBlockState()).isEmpty());
	}
	@Test void localHitFaceAndFrozenLogicalProofSurviveLivePoseChange() {
		FakeSubLevel subLevel = new FakeSubLevel();
		subLevel.pose.rotationPoint().set(30_000_000, 0, 0);
		subLevel.pose.position().set(4, 0, 0);
		Deque<Function<Object, Pose3dc>> stack = new ArrayDeque<>();
		stack.push(value -> new Pose3d());
		var access = new SablePickingAccess(position -> subLevel, stack::push, stack::pop);
		BlockHitResult nativeHit = new BlockHitResult(new Vec3(30_000_000.5, .5, .5), Direction.WEST,
			new BlockPos(30_000_000, 0, 0), false);
		try (var scope = access.openScope()) {
			Pose3dc used = stack.peek().apply(subLevel);
			subLevel.pose.position().set(100, 0, 0);
			var selection = access.normalize(nativeHit);
			assertSame(nativeHit, selection.hitResult());
			assertEquals(Direction.WEST, ((BlockHitResult) selection.hitResult()).getDirection());
			assertEquals(new Vec3(4.5, .5, .5), selection.worldPoint().orElseThrow());
			assertEquals(subLevel.id, selection.blockFrame().orElseThrow().subLevelId());
			assertEquals(4, used.position().x());
			assertTrue(selection.blockFrame().orElseThrow().binds(nativeHit));
			assertFalse(selection.blockFrame().orElseThrow().binds(new BlockHitResult(new Vec3(30_000_000.75, .5, .5),
				Direction.WEST, nativeHit.getBlockPos(), false)));
		}
		assertEquals(1, stack.size());
	}

	@Test void nestedScopesUseIndependentLogicalCopiesAndRestorePreviousSupplierEvenOnFailure() {
		FakeSubLevel subLevel = new FakeSubLevel(); subLevel.pose.position().set(1, 0, 0);
		Deque<Function<Object, Pose3dc>> stack = new ArrayDeque<>();
		Function<Object, Pose3dc> render = value -> new Pose3d(); stack.push(render);
		var outer = new SablePickingAccess(position -> subLevel, stack::push, stack::pop);
		var inner = new SablePickingAccess(position -> subLevel, stack::push, stack::pop);
		try (var outerScope = outer.openScope()) {
			Function<Object, Pose3dc> outerSupplier = stack.peek();
			assertEquals(1, outerSupplier.apply(subLevel).position().x());
			subLevel.pose.position().set(2, 0, 0);
			assertThrows(IllegalStateException.class, () -> {
				try (var innerScope = inner.openScope()) {
					assertEquals(2, stack.peek().apply(subLevel).position().x());
					throw new IllegalStateException("native clip failed");
				}
			});
			assertSame(outerSupplier, stack.peek());
			assertEquals(1, stack.peek().apply(subLevel).position().x());
		}
		assertSame(render, stack.peek()); assertEquals(1, stack.size());
	}

	@Test void unobservedOrUnusablePoseRetainsNativeHitWithoutInventingWorldPoint() {
		FakeSubLevel subLevel = new FakeSubLevel();
		Deque<Function<Object, Pose3dc>> stack = new ArrayDeque<>();
		var access = new SablePickingAccess(position -> subLevel, stack::push, stack::pop);
		BlockHitResult nativeHit = new BlockHitResult(new Vec3(30_000_000, 0, 0), Direction.NORTH,
			new BlockPos(30_000_000, 0, 0), false);
		try (var scope = access.openScope()) {
			assertTrue(access.normalize(nativeHit).worldPoint().isEmpty(), "containment alone is not clip provenance");
			subLevel.pose.scale().set(0, 1, 1);
			assertThrows(PickingFrameContext.Unavailable.class, () -> stack.peek().apply(subLevel));
			var selection = access.normalize(nativeHit);
			assertSame(nativeHit, selection.hitResult());
			assertTrue(selection.worldPoint().isEmpty()); assertTrue(selection.blockFrame().isEmpty());
		}
		assertTrue(stack.isEmpty());
	}

	@NotNullByDefault
	private static final class FakeSubLevel implements SubLevelAccess {
		private final UUID id = UUID.randomUUID();
		private final Pose3d pose = new Pose3d();
		@Override public Pose3dc logicalPose() { return pose; }
		@Override public Pose3dc lastPose() { throw new AssertionError("logical picking must not sample interpolation"); }
		@Override public BoundingBox3dc boundingBox() { throw new AssertionError("native membership must not use host bounds"); }
		@Override public UUID getUniqueId() { return id; }
		@Override public @Nullable String getName() { return null; }
	}
}
