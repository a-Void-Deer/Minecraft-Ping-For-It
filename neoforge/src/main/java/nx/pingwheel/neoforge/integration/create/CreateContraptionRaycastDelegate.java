package nx.pingwheel.neoforge.integration.create;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.math.EntityLocalGeometryResult;
import nx.pingwheel.common.math.EntityLocalGeometryResolver;
import nx.pingwheel.common.math.EntityLocalRaycastRequest;
import nx.pingwheel.common.math.EntityRaycastWork;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Typed Create boundary invoked only by the lazy Create-free ownership shell.
 *
 * <p>Every live Create read happens once while the synchronous press trace is
 * running. The subsequent native scan reads a private snapshot only; it never
 * consults the ordinary world at local contraption coordinates.</p>
 */
@NotNullByDefault
public final class CreateContraptionRaycastDelegate implements EntityLocalGeometryResolver {

	public CreateContraptionRaycastDelegate() {}

	@Override
	public EntityLocalGeometryResult trace(@Nullable Entity candidate, EntityLocalRaycastRequest request) {
		if (!(candidate instanceof AbstractContraptionEntity contraptionEntity)
			|| !contraptionEntity.isAlive()
			|| contraptionEntity.isRemoved()
			|| !contraptionEntity.level().isClientSide()
			|| Minecraft.getInstance().level != contraptionEntity.level()) {
			return EntityLocalGeometryResult.unavailable();
		}

		return traceCaptured(contraptionEntity, request,
			worldPosition -> contraptionEntity.toLocalVector(worldPosition, request.partialTick()),
			EntityRaycastWork.unlimited());
	}

	/** Shared transient capture; legacy Create deliberately supplies unlimited work. */
	static EntityLocalGeometryResult traceCaptured(
		AbstractContraptionEntity contraptionEntity,
		EntityLocalRaycastRequest request,
		CreateContraptionRaycastEngine.LocalTransform transform,
		EntityRaycastWork work
	) {
		work.callProvider();
		@Nullable Contraption contraption = contraptionEntity.getContraption();
		if (contraption == null || contraption.bounds == null) {
			return EntityLocalGeometryResult.unavailable();
		}
		work.callProvider();
		@Nullable Map<BlockPos, StructureBlockInfo> liveBlocks = contraption.getBlocks();
		if (liveBlocks == null || liveBlocks.isEmpty()) {
			return EntityLocalGeometryResult.unavailable();
		}

		Optional<CreateContraptionRaycastEngine.LocalRay> localRay =
			CreateContraptionRaycastEngine.transform(request, transform);
		if (localRay.isEmpty()) {
			return EntityLocalGeometryResult.unavailable();
		}

		work.callProvider();
		@Nullable BlockGetter nativeBoundsView = nativeBoundsView(contraption);
		if (nativeBoundsView == null) {
			return EntityLocalGeometryResult.unavailable();
		}
		work.callProvider();
		int minBuildHeight = nativeBoundsView.getMinBuildHeight();
		work.callProvider();
		int height = nativeBoundsView.getHeight();
		// Do not size or copy the live map before admission. snapshotCaptured
		// charges each visit before next() consumes and copies the native entry.
		Iterable<CreateContraptionRaycastEngine.CaptureEntry> capturedEntries = () -> new Iterator<>() {
			private final Iterator<Map.Entry<BlockPos, StructureBlockInfo>> entries = liveBlocks.entrySet().iterator();
			@Override public boolean hasNext() { return entries.hasNext(); }
			@Override public CreateContraptionRaycastEngine.CaptureEntry next() {
				Map.Entry<BlockPos, StructureBlockInfo> entry = entries.next();
				@Nullable BlockPos localPosition = entry.getKey();
				@Nullable StructureBlockInfo blockInfo = entry.getValue();
				@Nullable BlockState state = blockInfo == null ? null : blockInfo.state();
				BlockPos position = CreateContraptionRaycastEngine.requireCaptureEntry(localPosition, state);
				work.callProvider();
				boolean hiddenInPortal = contraption.isHiddenInPortal(position);
				// Existing client state only; never hydrate NBT or query the world.
				work.callProvider();
				@Nullable BlockEntity blockEntity = contraption.getBlockEntityClientSide(position);
				return new CreateContraptionRaycastEngine.CaptureEntry(position, state, blockEntity, hiddenInPortal);
			}
		};

		boolean preserveCollisionFallback = request.collisionContext() == net.minecraft.world.phys.shapes.CollisionContext.empty();
		CreateContraptionRaycastEngine.Snapshot snapshot = CreateContraptionRaycastEngine.snapshotCaptured(
			localRay.orElseThrow(),
			capturedEntries,
			minBuildHeight,
			height,
			preserveCollisionFallback, work);
		if (snapshot.positions().isEmpty()) return EntityLocalGeometryResult.unavailable();
		return CreateContraptionRaycastEngine.trace(request, snapshot, work);
	}

	/**
	 * Create's ContraptionWorld is supplied by Catnip and is not on this
	 * project's compile-only classpath. Reflection keeps that implementation
	 * detail outside this typed source's static linkage while still taking the
	 * native contraption bounds from its own world exactly once per trace.
	 */
	private static @Nullable BlockGetter nativeBoundsView(Contraption contraption) {
		return CreateContraptionNativeBounds.lookup(Contraption.class, contraption, Diagnostics.client());
	}
}
