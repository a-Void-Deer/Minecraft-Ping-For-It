package nx.pingwheel.neoforge.integration.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.math.EntityLocalGeometryResult;
import nx.pingwheel.common.math.EntityLocalGeometryResolver;
import nx.pingwheel.common.math.EntityLocalRaycastRequest;

/**
 * Typed Create boundary invoked only by the lazy Create-free ownership shell.
 *
 * <p>Every live Create read happens once while the synchronous press trace is
 * running. The subsequent native scan reads a private snapshot only; it never
 * consults the ordinary world at local contraption coordinates.</p>
 */
public final class CreateContraptionRaycastDelegate implements EntityLocalGeometryResolver {

	public CreateContraptionRaycastDelegate() {}

	@Override
	public EntityLocalGeometryResult trace(Entity candidate, EntityLocalRaycastRequest request) {
		if (!(candidate instanceof AbstractContraptionEntity contraptionEntity)
			|| !contraptionEntity.isAlive()
			|| contraptionEntity.isRemoved()
			|| !contraptionEntity.level().isClientSide()
			|| Minecraft.getInstance().level != contraptionEntity.level()) {
			return EntityLocalGeometryResult.unavailable();
		}

		Contraption contraption = contraptionEntity.getContraption();
		if (contraption == null || contraption.bounds == null) {
			return EntityLocalGeometryResult.unavailable();
		}
		Map<BlockPos, StructureBlockInfo> liveBlocks = contraption.getBlocks();
		if (liveBlocks == null || liveBlocks.isEmpty()) {
			return EntityLocalGeometryResult.unavailable();
		}

		Optional<CreateContraptionRaycastEngine.LocalRay> localRay =
			CreateContraptionRaycastEngine.transform(request,
				worldPosition -> contraptionEntity.toLocalVector(worldPosition, request.partialTick()));
		if (localRay.isEmpty()) {
			return EntityLocalGeometryResult.unavailable();
		}

		BlockGetter nativeBoundsView = nativeBoundsView(contraption);
		if (nativeBoundsView == null) {
			return EntityLocalGeometryResult.unavailable();
		}
		int minBuildHeight = nativeBoundsView.getMinBuildHeight();
		int height = nativeBoundsView.getHeight();
		List<CreateContraptionRaycastEngine.CaptureEntry> capturedEntries = new ArrayList<>(liveBlocks.size());

		for (Map.Entry<BlockPos, StructureBlockInfo> entry : liveBlocks.entrySet()) {
			BlockPos localPosition = entry.getKey();
			StructureBlockInfo blockInfo = entry.getValue();
			BlockState state = blockInfo == null ? null : blockInfo.state();
			BlockPos immutablePosition = CreateContraptionRaycastEngine.requireCaptureEntry(localPosition, state);
			boolean hiddenInPortal = contraption.isHiddenInPortal(immutablePosition);

			// This Create client-side accessor returns only an already-live client
			// block entity. It does not hydrate NBT or fall through to the ordinary
			// world, and its value is retained only by this transient press snapshot.
			BlockEntity blockEntity = contraption.getBlockEntityClientSide(immutablePosition);
			capturedEntries.add(new CreateContraptionRaycastEngine.CaptureEntry(
				immutablePosition, state, blockEntity, hiddenInPortal));
		}

		if (capturedEntries.isEmpty()) {
			return EntityLocalGeometryResult.unavailable();
		}

		boolean preserveCollisionFallback = request.collisionContext() == net.minecraft.world.phys.shapes.CollisionContext.empty();
		CreateContraptionRaycastEngine.Snapshot snapshot = CreateContraptionRaycastEngine.snapshotCaptured(
			localRay.orElseThrow(),
			capturedEntries,
			minBuildHeight,
			height,
			preserveCollisionFallback);
		return CreateContraptionRaycastEngine.trace(request, snapshot);
	}

	/**
	 * Create's ContraptionWorld is supplied by Catnip and is not on this
	 * project's compile-only classpath. Reflection keeps that implementation
	 * detail outside this typed source's static linkage while still taking the
	 * native contraption bounds from its own world exactly once per trace.
	 */
	private static BlockGetter nativeBoundsView(Contraption contraption) {
		try {
			Method method = Contraption.class.getMethod("getContraptionWorld");
			Object value = method.invoke(contraption);
			return value instanceof BlockGetter blockGetter ? blockGetter : null;
		} catch (NoSuchMethodException | IllegalAccessException failure) {
			return null;
		} catch (InvocationTargetException failure) {
			Throwable cause = failure.getCause();
			if (cause instanceof RuntimeException runtimeFailure) {
				throw runtimeFailure;
			}
			if (cause instanceof LinkageError linkageFailure) {
				throw linkageFailure;
			}
			if (cause instanceof AssertionError assertionFailure) {
				throw assertionFailure;
			}
			if (cause instanceof Error fatalFailure) {
				throw fatalFailure;
			}
			return null;
		}
	}
}
