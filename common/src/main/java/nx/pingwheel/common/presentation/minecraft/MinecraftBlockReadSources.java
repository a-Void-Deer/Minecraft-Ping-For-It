package nx.pingwheel.common.presentation.minecraft;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.ExternalBlockServerProviders;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.integration.externalblock.ResolvedBlockReadSource;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.presentation.PresentationAdapter;

/** Read-only source routing, separate from marker validation/materialization and render shapes. */
public final class MinecraftBlockReadSources {
	private MinecraftBlockReadSources() {}

	public static Optional<ResolvedBlockReadSource> resolvePreviewReadSource(ServerLevel level, Target target) {
		if (target instanceof Target.ExternalBlockTarget external) {
			return ExternalBlockServerProviders.registry().resolvePreviewReadSource(level, external);
		}
		return target instanceof Target.BlockTarget block ? resolveOrdinary(level, block) : Optional.empty();
	}

	public static Optional<ResolvedBlockReadSource> resolveCommittedReadSource(ServerLevel level, Target target) {
		if (target instanceof Target.ExternalBlockTarget external) {
			return ExternalBlockServerProviders.registry().resolveCommittedReadSource(level, external);
		}
		return target instanceof Target.BlockTarget block ? resolveOrdinary(level, block) : Optional.empty();
	}

	/** Client callers additionally apply their prediction/receipt guard before reading content. */
	public static Optional<ResolvedBlockReadSource> resolveOrdinary(Level level, Target.BlockTarget target) {
		if (level == null || target == null || !target.dimensionId().equals(level.dimension().location().toString())) {
			return Optional.empty();
		}
		if (level instanceof ServerLevel serverLevel
			&& (serverLevel.getServer() == null || !serverLevel.getServer().isSameThread())) return Optional.empty();
		try {
			BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
			if (level.isOutsideBuildHeight(pos) || !level.isLoaded(pos)) return Optional.empty();
			var state = level.getBlockState(pos);
			var id = state == null ? null : BuiltInRegistries.BLOCK.getKey(state.getBlock());
			if (state == null || state.isAir() || id == null || !target.blockRegistryId().equals(id.toString())) {
				return Optional.empty();
			}
			var descriptor = new BlockReadSource(target, BlockReadSource.MINECRAFT_PROVIDER_ID, "", target,
				new MarkerAnchor(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5));
			return Optional.of(new ResolvedBlockReadSource(level, descriptor,
				member -> !level.isOutsideBuildHeight(member) && level.isLoaded(member)));
		} catch (RuntimeException | LinkageError unavailable) { return Optional.empty(); }
	}

	/** Preserve the original external metadata and normalized coordinates, not physical read coordinates. */
	public static PresentationAdapter.DetachedTarget detached(Target target) {
		return PresentationServer.detached(target);
	}

	public static PresentationAdapter.DetachedTarget detached(BlockReadSource source) {
		return detached(source.target());
	}

	public static boolean sameReadBinding(Target first, Target second) {
		return BlockReadSource.sameTargetBinding(first, second);
	}

	public static boolean sameReadBinding(BlockReadSource first, BlockReadSource second) {
		return first != null && second != null && sameReadBinding(first.target(), second.target())
			&& first.providerId().equals(second.providerId()) && first.subLevelId().equals(second.subLevelId())
			&& first.blockTarget().equals(second.blockTarget());
	}
}
