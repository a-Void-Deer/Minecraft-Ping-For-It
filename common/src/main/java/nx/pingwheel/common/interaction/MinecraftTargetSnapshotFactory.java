package nx.pingwheel.common.interaction;

import java.util.Objects;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Converts a vanilla {@link Level} + {@link HitResult} pair into a frozen
 * {@link TargetSnapshot} on the common side.
 *
 * <p>This adapter imports no client-only Minecraft types, so it can live in the
 * common source set. It must be invoked on the game/client thread because it
 * reads the level's block state; the token guard later used by
 * {@link PingCaptureCoordinator} is thread-safe regardless of the thread that
 * produces the snapshot.
 *
 * <p>Behavior:
 * <ul>
 *   <li>{@link EntityHitResult} -> entity snapshot keyed by dimension id + UUID,
 *       carrying the entity type id as match context (or no type when the
 *       registry key is unexpectedly absent, so the generic entity type can
 *       still match);</li>
 *   <li>{@link BlockHitResult} -> block snapshot keyed by dimension id +
 *       position + block registry id, independent of {@code BlockState}
 *       properties;</li>
 *   <li>unavailable/unloaded block data (including distant async hits that
 *       report as {@code MISS}) -> degrade to a location snapshot at the hit
 *       location rather than guessing a block identity;</li>
 *   <li>{@code MISS} / anything else -> location snapshot at the hit
 *       location.</li>
 * </ul>
 */
public final class MinecraftTargetSnapshotFactory {

	private MinecraftTargetSnapshotFactory() {}

	/**
	 * Builds a snapshot from a level and the current hit result.
	 *
	 * <p>Must be called on the game/client thread (see class javadoc).
	 */
	public static TargetSnapshot from(Level level, HitResult hitResult) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(hitResult, "hitResult");

		String dimensionId = level.dimension().location().toString();

		return switch (hitResult.getType()) {
			case ENTITY -> entitySnapshot(dimensionId, (EntityHitResult) hitResult);
			case BLOCK -> blockSnapshot(level, dimensionId, (BlockHitResult) hitResult);
			case MISS -> locationSnapshot(
				dimensionId, hitResult.getLocation().x, hitResult.getLocation().y, hitResult.getLocation().z);
		};
	}

	private static TargetSnapshot entitySnapshot(String dimensionId, EntityHitResult hitResult) {
		Entity entity = hitResult.getEntity();
		var entityTypeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());

		if (entityTypeKey == null) {
			return TargetSnapshotFactory.entity(dimensionId, entity.getUUID());
		}

		return TargetSnapshotFactory.entity(dimensionId, entity.getUUID(), entityTypeKey.toString());
	}

	private static TargetSnapshot blockSnapshot(Level level, String dimensionId, BlockHitResult hitResult) {
		var blockPos = hitResult.getBlockPos();

		if (level.isLoaded(blockPos)) {
			var block = level.getBlockState(blockPos).getBlock();
			var blockKey = BuiltInRegistries.BLOCK.getKey(block);

			// Air is never a meaningful ping target, so treat it like unavailable
			// data and degrade to a location instead of emitting an air block target.
			if (blockKey != null && block != Blocks.AIR) {
				return TargetSnapshotFactory.block(
					dimensionId, blockPos.getX(), blockPos.getY(), blockPos.getZ(), blockKey.toString());
			}
		}

		// Unavailable, unloaded, or air block data: degrade to a location rather
		// than guessing a block identity.
		return locationSnapshot(
			dimensionId, hitResult.getLocation().x, hitResult.getLocation().y, hitResult.getLocation().z);
	}

	private static TargetSnapshot locationSnapshot(String dimensionId, double x, double y, double z) {
		return TargetSnapshotFactory.location(dimensionId, x, y, z);
	}
}
