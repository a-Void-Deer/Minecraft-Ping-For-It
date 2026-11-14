package nx.pingwheel.common.interaction;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.outline.TestEntitySupport;
import nx.pingwheel.common.client.outline.TestEntitySupport.TestEntity;
import nx.pingwheel.common.math.EntityLocalHit;
import nx.pingwheel.common.math.LocalGeometryHit;
import nx.pingwheel.common.math.LocalGeometryKind;
import nx.pingwheel.common.math.RaycastSelection;
import nx.pingwheel.common.interaction.cancel.WorldVector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftTargetSnapshotFactoryDetailedTest {

	@BeforeAll
	static void bootStrap() {
		TestEntitySupport.bootStrap();
	}

	@Test
	void detailedFactoryRetainsMetadataOnlyForTheExactEntityHitResultOwner() {
		TestEntity entityA = TestEntitySupport.newEntity(EntityTypes.PIG);
		TestEntity entityB = TestEntitySupport.newEntity(EntityTypes.PIG);
		LocalGeometryHit geometry = new LocalGeometryHit(
			0.25, BlockPos.ZERO, LocalGeometryKind.BLOCK, Blocks.STONE.defaultBlockState(),
			"minecraft:stone", Optional.empty(), new Vec3(1, 2, 3));

		RaycastSelection matching = new RaycastSelection(
			new EntityHitResult(entityA, new Vec3(4, 5, 6)),
			Optional.of(new EntityLocalHit(entityA, "test:owner", geometry)));
		TargetSnapshot retained = MinecraftTargetSnapshotFactory.fromEntitySelection(
			"minecraft:overworld", matching);

		assertTrue(retained.entityLocalGeometryMetadata().isPresent());
		assertEquals("test:owner", retained.entityLocalGeometryMetadata().orElseThrow().sourceId());
		assertEquals(new WorldVector(4, 5, 6),
			retained.entityLocalGeometryMetadata().orElseThrow().worldWorldVector());

		RaycastSelection fabricatedMismatch = new RaycastSelection(
			new EntityHitResult(entityB, new Vec3(7, 8, 9)),
			Optional.of(new EntityLocalHit(entityA, "test:owner", geometry)));
		TargetSnapshot dropped = MinecraftTargetSnapshotFactory.fromEntitySelection(
			"minecraft:overworld", fabricatedMismatch);

		assertTrue(dropped.entityLocalGeometryMetadata().isEmpty());
	}
}
