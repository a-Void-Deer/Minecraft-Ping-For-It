package nx.pingwheel.common.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.interaction.candidate.UnobservedFaceBlockHitResult;

import static org.junit.jupiter.api.Assertions.*;

class DistantTerrainHitProvenanceTest {
	@Test
	void productionDhConversionKeepsCoordinatesAndBlockTypeWithExplicitUnknownFace() {
		Vec3 point = new Vec3(-1.25, 43.5, 10.75);
		var result = DistantHorizonsIntegration.terrainHit(point);
		assertInstanceOf(UnobservedFaceBlockHitResult.class, result);
		assertEquals(point, result.getLocation());
		assertEquals(new BlockPos(-1, 43, 10), result.getBlockPos(), "preserve existing DH integer-coordinate conversion");
		assertEquals(HitResult.Type.BLOCK, result.getType());
		assertEquals(Direction.UP, result.getDirection(), "legacy compatibility direction, not observed face");
		assertTrue(result.isInside());
	}
}
