package nx.pingwheel.common.interaction;

import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void detailedFactoryRetainsMetadataOnlyForTheExactEntityHitResultOwner() {
		TestEntity entityA = new TestEntity(EntityType.PIG, null);
		TestEntity entityB = new TestEntity(EntityType.PIG, null);
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
