package nx.pingwheel.common.interaction;

import java.util.Optional;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the capture-only ordinary-block face on
 * {@link MinecraftTargetSnapshotFactory}: an actual resolved block hit copies
 * its direction as exactly one of the six faces, while a synthetic
 * {@code MISS} direction and a location fallback never invent one. The
 * level-free seam is used because building a mutable level is not part of this
 * boundary.
 */
class MinecraftTargetSnapshotFactoryFaceTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final Vec3 HIT_LOCATION = new Vec3(0.5, 1.0, 0.5);
	private static final BlockPos HIT_POS = new BlockPos(0, 1, 0);

	@ParameterizedTest(name = "block hit from {0} captures {1}")
	@MethodSource("blockFaceCases")
	void resolvedOrdinaryBlockHitCopiesItsDirectionAsTheCapturedFace(Direction direction, BlockFace expectedFace) {
		BlockHitResult hit = new BlockHitResult(HIT_LOCATION, direction, HIT_POS, false);

		TargetSnapshot snapshot = MinecraftTargetSnapshotFactory.fromHitResult(
			OVERWORLD, hit, Optional.of("minecraft:stone"), true);

		assertTrue(snapshot.target() instanceof Target.BlockTarget);
		assertEquals(Optional.of(expectedFace), snapshot.blockHitFace());
	}

	@Test
	void syntheticMissDirectionIsNeverInventedAsAFace() {
		BlockHitResult miss = BlockHitResult.miss(HIT_LOCATION, Direction.UP, HIT_POS);

		TargetSnapshot snapshot = MinecraftTargetSnapshotFactory.fromHitResult(
			OVERWORLD, miss, Optional.empty(), false);

		assertTrue(snapshot.target() instanceof Target.LocationTarget);
		assertTrue(snapshot.blockHitFace().isEmpty());
	}

	@Test
	void unavailableOrdinaryBlockHitFallsBackToLocationWithoutAFace() {
		BlockHitResult hit = new BlockHitResult(HIT_LOCATION, Direction.NORTH, HIT_POS, false);

		TargetSnapshot snapshot = MinecraftTargetSnapshotFactory.fromHitResult(
			OVERWORLD, hit, Optional.empty(), false);

		assertTrue(snapshot.target() instanceof Target.LocationTarget);
		assertTrue(snapshot.blockHitFace().isEmpty());
	}

	private static Stream<Arguments> blockFaceCases() {
		return Stream.of(
			Arguments.of(Direction.DOWN, BlockFace.DOWN),
			Arguments.of(Direction.UP, BlockFace.UP),
			Arguments.of(Direction.NORTH, BlockFace.NORTH),
			Arguments.of(Direction.SOUTH, BlockFace.SOUTH),
			Arguments.of(Direction.WEST, BlockFace.WEST),
			Arguments.of(Direction.EAST, BlockFace.EAST));
	}
}
