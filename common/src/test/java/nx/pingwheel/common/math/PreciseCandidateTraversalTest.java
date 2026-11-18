package nx.pingwheel.common.math;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.CandidateBlockCapture;
import nx.pingwheel.common.interaction.candidate.PreciseTargetType;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class PreciseCandidateTraversalTest {
	private static final RaycastPolicy POLICY = RaycastPolicy.from(false, false, true);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void supplementalTraversalFindsDistantOrdinaryEntityAfterDuplicateRawVisits() {
		ProbeClientLevel level = DoubleChestFixture.allocate(ProbeClientLevel.class);
		ProbeEntity camera = entity(EntityType.PIG, level, 0, .5, 0);
		ProbeEntity offRay = entity(EntityType.COW, level, 0, .5, 50);
		ProbeEntity target = entity(EntityType.ZOMBIE, level, 8, .5, 0);

		// The duplicated off-ray entry makes each raw loaded visit distinct work
		// without constructing thousands of entities. The only on-ray target is
		// beyond the ordinary native segment (5) but inside the Ping range (10).
		List<Entity> loaded = new ArrayList<>(2_049);
		for (int index = 0; index < 2_048; index++) loaded.add(offRay);
		loaded.add(target);
		level.loaded = List.copyOf(loaded);

		// No LOADED_EXACT owner is registered; the target must be found by the
		// loaded supplement rather than the ordinary native query.
		Raycast.SelectorTrace trace = Raycast.traceDirectionalCandidates(
			camera,
			new ActiveInteraction().begin(),
			new CapturedRay(new WorldVector(0, .5, 0), new WorldVector(1, 0, 0)),
			5,
			10,
			POLICY,
			CandidateBlockCapture.nativeBlocks())
			.orElseThrow();

		assertEquals(HitResult.Type.MISS, trace.ordinarySelection().hitResult().getType(),
			"the target is beyond the ordinary native segment and the native query is empty");

		var acquisition = trace.candidates().orElseThrow();
		assertTrue(acquisition.certifiedTypes().contains(PreciseTargetType.ENTITY),
			"the completed supplemental traversal must certify the entity class");
		assertEquals(1, acquisition.evidence().size(), "the off-ray duplicates must not become candidates");
		assertEquals(new Target.EntityTarget("minecraft:overworld", target.getUUID()),
			acquisition.evidence().getFirst().snapshot().target(),
			"the certified evidence must carry the exact on-ray entity identity");
	}

	private static ProbeEntity entity(EntityType<?> type, Level level, double x, double y, double z) {
		ProbeEntity entity = new ProbeEntity(type);
		entity.attach(level);
		entity.setPos(x, y, z);
		return entity;
	}

	@NotNullByDefault
	private static final class ProbeEntity extends Entity {
		private ProbeEntity(EntityType<?> type) {
			super(type, null);
		}

		private void attach(Level level) {
			setLevel(level);
		}

		@Override
		protected void defineSynchedData(SynchedEntityData.Builder builder) {}

		@Override
		protected void readAdditionalSaveData(CompoundTag tag) {}

		@Override
		protected void addAdditionalSaveData(CompoundTag tag) {}
	}

	/**
	 * Constructor-bypassed client level for the public raycast path. The native
	 * query is empty; only the loaded rendering view contains the distant target.
	 */
	@NotNullByDefault
	private static final class ProbeClientLevel extends ClientLevel {
		private @Unmodifiable List<Entity> loaded = List.of();

		private ProbeClientLevel() {
			super(null, null, null, null, 0, 0, null, null, false, 0);
		}

		@Override
		public @Unmodifiable List<Entity> getEntities(@Nullable Entity except, AABB area,
			java.util.function.Predicate<? super Entity> predicate) {
			return List.of();
		}

		@Override
		public Iterable<Entity> entitiesForRendering() {
			return loaded;
		}

		@Override
		public BlockHitResult clip(ClipContext context) {
			return BlockHitResult.miss(context.getTo(), Direction.UP, BlockPos.ZERO);
		}

		@Override
		public ResourceKey<Level> dimension() {
			return Level.OVERWORLD;
		}

		@Override
		public boolean isLoaded(BlockPos pos) {
			return true;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return Blocks.AIR.defaultBlockState();
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return Fluids.EMPTY.defaultFluidState();
		}
	}
}
