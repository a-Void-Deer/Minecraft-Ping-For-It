package nx.pingwheel.common.interaction;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.candidate.UnobservedFaceBlockHitResult;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftSyntheticBlockFaceTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final Vec3 POINT = new Vec3(9.25, 6, 3.5);
	private static final BlockPos POS = new BlockPos(9, 6, 3);

	@Test
	void loadedSyntheticDhBlockRemainsSameConcreteTargetButHasNoInventedUpFace() {
		BlockHitResult dh = new UnobservedFaceBlockHitResult(POINT, Direction.UP, POS, true);
		assertEquals(HitResult.Type.BLOCK, dh.getType());
		TargetSnapshot legacy = MinecraftTargetSnapshotFactory.fromHitResult(DIMENSION, dh,
			Optional.of("minecraft:chest"), true);
		TargetSnapshot candidate = MinecraftTargetSnapshotFactory.fromCandidateHitResult(DIMENSION, dh,
			Optional.of("minecraft:chest"), true);
		assertEquals(new Target.BlockTarget(DIMENSION, 9, 6, 3, "minecraft:chest"), legacy.target());
		assertEquals(legacy.target(), candidate.target());
		assertEquals(Optional.of(true), candidate.matchContext().blockHasBlockEntity());
		assertTrue(legacy.blockHitFace().isEmpty());
		assertTrue(candidate.blockHitFace().isEmpty());
		assertEquals(POINT.x, candidate.candidateHit().orElseThrow().worldHit().x());
		assertEquals(POINT.y, candidate.candidateHit().orElseThrow().worldHit().y());
		assertEquals(POINT.z, candidate.candidateHit().orElseThrow().worldHit().z());
	}

	@Test
	void actualNativeUpIsStillRetainedIncludingCandidatePathAndCoordinator() {
		BlockHitResult nativeHit = new BlockHitResult(POINT, Direction.UP, POS, true);
		TargetSnapshot snapshot = MinecraftTargetSnapshotFactory.fromCandidateHitResult(DIMENSION, nativeHit,
			Optional.of("minecraft:chest"), true);
		assertEquals(Optional.of(BlockFace.UP), snapshot.blockHitFace());
		ActiveInteraction active = new ActiveInteraction();
		var coordinator = new PingCaptureCoordinator(nx.pingwheel.common.resolve.DefaultTargetResolver.builtIn(
			nx.pingwheel.common.resolve.TargetResolutionLogger.noop()), active, PingCaptureLogger.noop());
		assertEquals(Optional.of(BlockFace.UP), coordinator.complete(coordinator.begin(), snapshot).orElseThrow().blockHitFace());
	}

	@Test
	void syntheticProvenanceSurvivesVanillaDirectionAndPositionCopiesAndCoordinator() {
		BlockHitResult copied = new UnobservedFaceBlockHitResult(POINT, Direction.UP, POS, true)
			.withDirection(Direction.NORTH).withPosition(POS.above());
		TargetSnapshot snapshot = MinecraftTargetSnapshotFactory.fromCandidateHitResult(DIMENSION, copied,
			Optional.of("minecraft:chest"), true);
		assertTrue(snapshot.blockHitFace().isEmpty());
		ActiveInteraction active = new ActiveInteraction();
		var coordinator = new PingCaptureCoordinator(nx.pingwheel.common.resolve.DefaultTargetResolver.builtIn(
			nx.pingwheel.common.resolve.TargetResolutionLogger.noop()), active, PingCaptureLogger.noop());
		CapturedPingContext context = coordinator.complete(coordinator.begin(), snapshot).orElseThrow();
		assertTrue(context.blockHitFace().isEmpty());
		assertEquals("entity_block", context.resolvedTarget().targetType().id());
	}
}
