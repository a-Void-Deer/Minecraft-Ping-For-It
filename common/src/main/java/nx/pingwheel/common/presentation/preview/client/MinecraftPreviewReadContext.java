package nx.pingwheel.common.presentation.preview.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.mixin.ClientLevelPreviewAccessor;

/** Tick-local view of the current client world. Consumers retain detached observations only. */
public record MinecraftPreviewReadContext(ClientLevel level, long tick) implements MinecraftPreviewFieldAccess.WorldContext {
	public MinecraftPreviewReadContext {
		if (level == null || tick < 0) throw new IllegalArgumentException("preview world");
	}
	@Override public Object levelIdentity() { return level; }
	@Override public String dimensionId() { return level.dimension().location().toString(); }
	private boolean current() {
		Minecraft game = Minecraft.getInstance();
		return game != null && game.level == level && game.isSameThread();
	}
	@Override public Entity entity(EntityLocator locator) {
		if (!current()) return null;
		if (locator instanceof EntityLocator.RuntimeId runtime) {
			Entity entity = level.getEntity(runtime.value());
			return entity instanceof ExperienceOrb ? entity : null;
		}
		int scanned = 0;
		for (Entity entity : level.entitiesForRendering()) {
			if (++scanned > 4096) return null;
			if (entity.getUUID().equals(((EntityLocator.UUID) locator).value())) return entity;
		}
		return null;
	}
	@Override public BlockState blockState(Target.BlockTarget target) {
		if (!current() || !dimensionId().equals(target.dimensionId())) return null;
		BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
		if (level.isOutsideBuildHeight(pos)) return null;
		var chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
		if (chunk == null) return null;
		try {
			if (!((Object) level instanceof ClientLevelPreviewAccessor access)) return null;
			BlockStatePredictionHandler handler = access.pingforit$predictionHandler();
			if (!((Object) handler instanceof PreviewPredictionView predictions)
				|| predictions.pingforit$hasPendingPrediction(pos)) return null;
		} catch (RuntimeException | LinkageError unavailable) { return null; }
		BlockState state = chunk.getBlockState(pos);
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(target.blockRegistryId()) ? state : null;
	}
	@Override public BlockEntity blockEntity(Target.BlockTarget target) {
		if (blockState(target) == null) return null;
		BlockEntity entity = level.getBlockEntity(new BlockPos(target.x(), target.y(), target.z()));
		return entity == null || entity.isRemoved() || entity.getLevel() != level ? null : entity;
	}
	@Override public String encodeName(Component name) { return Component.Serializer.toJson(name, level.registryAccess()); }
}
