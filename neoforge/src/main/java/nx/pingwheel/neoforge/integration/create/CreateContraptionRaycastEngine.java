package nx.pingwheel.neoforge.integration.create;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import nx.pingwheel.common.math.EntityLocalGeometryResult;
import nx.pingwheel.common.math.EntityLocalRaycastRequest;
import nx.pingwheel.common.math.EntityParentFrame;
import nx.pingwheel.common.math.EntityRaycastWork;
import nx.pingwheel.common.math.LocalGeometryHit;
import nx.pingwheel.common.math.NativeLocalShapeRaycaster;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * Create-free native-shape execution and frozen local-view implementation.
 *
 * <p>The typed Create boundary captures its transient contraption state into a
 * {@link Snapshot}; this class subsequently uses only that state. Keeping this
 * portion Create-free makes both the shape path and its collision semantics
 * executable by NeoForge tests without optional runtime jars.</p>
 */
@NotNullByDefault
final class CreateContraptionRaycastEngine {

	private CreateContraptionRaycastEngine() {}

	static Optional<LocalRay> transform(EntityLocalRaycastRequest request, LocalTransform transform) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(transform, "transform");
		@Nullable Vec3 localStart = transform.toLocal(request.start());
		@Nullable Vec3 localEnd = transform.toLocal(request.end());
		@Nullable Vec3 localCameraFeet = transform.toLocal(request.cameraFeetPosition());

		if (!finite(localStart) || !finite(localEnd) || !finite(localCameraFeet)) {
			return Optional.empty();
		}

		return Optional.of(new LocalRay(localStart, localEnd, localCameraFeet));
	}

	static EntityLocalGeometryResult trace(EntityLocalRaycastRequest request, Snapshot snapshot) {
		return trace(request, snapshot, EntityRaycastWork.unlimited());
	}

	static EntityLocalGeometryResult trace(
		EntityLocalRaycastRequest request, Snapshot snapshot, EntityRaycastWork work
	) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(snapshot, "snapshot");
		if (!work.complete()) throw new EntityRaycastWork.Exhausted();

		if (!request.hasNonDegenerateSegment()
			|| snapshot.localStart().distanceToSqr(snapshot.localEnd()) == 0.0) {
			return EntityLocalGeometryResult.miss();
		}

		Optional<LocalGeometryHit> hit = NativeLocalShapeRaycaster.trace(
			snapshot.localStart(),
			snapshot.localEnd(),
			request.policy(),
			snapshot.view(),
			snapshot.positions(),
			snapshot.hiddenPositions()::contains,
			new LocalCollisionContext(
				request.collisionContext(), snapshot.localCameraFeet(), snapshot.preserveCollisionFallback()), work);
		return hit.map(EntityLocalGeometryResult::hit).orElseGet(EntityLocalGeometryResult::miss);
	}

	/**
	 * Capture the actual virtual local-model operation, including gyro pivot
	 * translations absent from inherited toLocalVector. Only local coordinates
	 * enter the float pose stack; the native anchor and parent placement stay double.
	 */
	static Optional<LocalTransform> modelTransform(
		EntityParentFrame parentFrame, Vec3 nativeAnchor, LocalModelTransform localModelTransform
	) {
		PoseStack stack = new PoseStack();
		localModelTransform.apply(stack, 1.0F);
		Matrix4f localModel = new Matrix4f(stack.last().pose());
		if (!localModel.isFinite()) return Optional.empty();
		Matrix4d modelToWorld = parentFrame.modelToWorld(nativeAnchor, localModel);
		if (!modelToWorld.isFinite() || modelToWorld.m03() != 0 || modelToWorld.m13() != 0
			|| modelToWorld.m23() != 0 || modelToWorld.m33() != 1) return Optional.empty();
		double determinant = modelToWorld.determinant();
		if (!Double.isFinite(determinant) || determinant == 0) return Optional.empty();
		Matrix4d worldToModel = modelToWorld.invert();
		if (!worldToModel.isFinite()) return Optional.empty();
		return Optional.of(position -> {
			Vector3d local = worldToModel.transformPosition(position.x, position.y, position.z, new Vector3d());
			return new Vec3(local.x, local.y, local.z);
		});
	}

	static Snapshot snapshot(
		LocalRay localRay,
		Map<BlockPos, CapturedBlock> capturedBlocks,
		Set<BlockPos> hiddenPositions,
		int minBuildHeight,
		int height,
		boolean preserveCollisionFallback
	) {
		Objects.requireNonNull(localRay, "localRay");
		Objects.requireNonNull(capturedBlocks, "capturedBlocks");
		Objects.requireNonNull(hiddenPositions, "hiddenPositions");

		if (height <= 0) {
			throw new IllegalArgumentException("height must be positive");
		}

		Map<BlockPos, CapturedBlock> blocks = new HashMap<>();
		for (Map.Entry<BlockPos, CapturedBlock> entry : capturedBlocks.entrySet()) {
			BlockPos pos = immutable(entry.getKey(), "capturedBlocks contains null position");
			blocks.put(pos, Objects.requireNonNull(entry.getValue(), "capturedBlocks contains null block"));
		}

		Set<BlockPos> hidden = new HashSet<>();
		for (BlockPos position : hiddenPositions) {
			hidden.add(immutable(position, "hiddenPositions contains null position"));
		}

		@Unmodifiable Map<BlockPos, CapturedBlock> frozenBlocks = Map.copyOf(blocks);
		@Unmodifiable Set<BlockPos> frozenHidden = Set.copyOf(hidden);
		return new Snapshot(
			localRay.start(),
			localRay.end(),
			localRay.cameraFeet(),
			new SnapshotBlockGetter(frozenBlocks, frozenHidden, minBuildHeight, height),
			List.copyOf(frozenBlocks.keySet()),
			frozenHidden,
			preserveCollisionFallback);
	}

	static Snapshot snapshotCaptured(
		LocalRay localRay,
		Iterable<@Nullable CaptureEntry> capturedEntries,
		int minBuildHeight,
		int height,
		boolean preserveCollisionFallback
	) {
		return snapshotCaptured(localRay, capturedEntries, minBuildHeight, height,
			preserveCollisionFallback, EntityRaycastWork.unlimited());
	}

	static Snapshot snapshotCaptured(
		LocalRay localRay,
		Iterable<@Nullable CaptureEntry> capturedEntries,
		int minBuildHeight,
		int height,
		boolean preserveCollisionFallback,
		EntityRaycastWork work
	) {
		Objects.requireNonNull(capturedEntries, "capturedEntries");
		Map<BlockPos, CapturedBlock> blocks = new HashMap<>();
		Set<BlockPos> hiddenPositions = new HashSet<>();
		int index = 0;
		Iterator<@Nullable CaptureEntry> entries = capturedEntries.iterator();
		while (entries.hasNext()) {
			// The live iterable maps its next native entry only after admission.
			work.readLocalBlock();
			@Nullable CaptureEntry entry = entries.next();
			if (entry == null) {
				throw new IllegalStateException(
					"Create contraption capture contains a null entry at index " + index);
			}
			BlockPos position = requireCaptureEntry(entry.position(), entry.state());
			blocks.put(position, new CapturedBlock(entry.state(), entry.blockEntity()));
			if (entry.hidden()) {
				hiddenPositions.add(position);
			}
			index++;
		}
		if (!work.complete()) throw new EntityRaycastWork.Exhausted();
		return snapshot(localRay, blocks, hiddenPositions, minBuildHeight, height, preserveCollisionFallback);
	}

	/**
	 * Validates one live-map entry before any field on it is consumed. A
	 * malformed Create capture invalidates the whole resolver attempt rather
	 * than allowing an earlier child hit to escape from a partial snapshot.
	 */
	static BlockPos requireCaptureEntry(@Nullable BlockPos position, @Nullable BlockState state) {
		if (position == null) {
			throw new IllegalStateException("Create contraption capture contains a null local position");
		}
		if (state == null) {
			throw new IllegalStateException(
				"Create contraption capture contains a null block info/state at local position " + position);
		}
		return position.immutable();
	}

	private static BlockPos immutable(BlockPos position, String message) {
		Objects.requireNonNull(position, message);
		return position.immutable();
	}

	private static boolean finite(@Nullable Vec3 vector) {
		return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y)
			&& Double.isFinite(vector.z);
	}

	@FunctionalInterface
	interface LocalTransform {
		@Nullable Vec3 toLocal(Vec3 worldPosition);
	}

	@FunctionalInterface
	interface LocalModelTransform {
		void apply(PoseStack stack, float partialTick);
	}

	record LocalRay(Vec3 start, Vec3 end, Vec3 cameraFeet) {
		LocalRay {
			if (!finite(start) || !finite(end) || !finite(cameraFeet)) {
				throw new IllegalArgumentException("local ray coordinates must be finite");
			}
		}
	}

	record CapturedBlock(BlockState state, @Nullable BlockEntity blockEntity) {
		CapturedBlock {
			Objects.requireNonNull(state, "state");
		}
	}

	record CaptureEntry(@Nullable BlockPos position, @Nullable BlockState state,
		@Nullable BlockEntity blockEntity, boolean hidden) {}

	record Snapshot(
		Vec3 localStart,
		Vec3 localEnd,
		Vec3 localCameraFeet,
		BlockGetter view,
		@Unmodifiable List<BlockPos> positions,
		@Unmodifiable Set<BlockPos> hiddenPositions,
		boolean preserveCollisionFallback
	) {
		Snapshot {
			if (!finite(localStart) || !finite(localEnd) || !finite(localCameraFeet)) {
				throw new IllegalArgumentException("local snapshot coordinates must be finite");
			}
			Objects.requireNonNull(view, "view");
			positions = List.copyOf(Objects.requireNonNull(positions, "positions"));
			hiddenPositions = Set.copyOf(Objects.requireNonNull(hiddenPositions, "hiddenPositions"));
		}
	}

	static CollisionContext localCollisionContext(
		CollisionContext originalContext,
		Vec3 localCameraFeet,
		boolean preserveFallback
	) {
		return new LocalCollisionContext(originalContext, localCameraFeet, preserveFallback);
	}

	private static final class SnapshotBlockGetter implements BlockGetter {
		private final @Unmodifiable Map<BlockPos, CapturedBlock> blocks;
		private final @Unmodifiable Set<BlockPos> hiddenPositions;
		private final int minBuildHeight;
		private final int height;

		private SnapshotBlockGetter(
			@Unmodifiable Map<BlockPos, CapturedBlock> blocks,
			@Unmodifiable Set<BlockPos> hiddenPositions,
			int minBuildHeight,
			int height
		) {
			this.blocks = blocks;
			this.hiddenPositions = hiddenPositions;
			this.minBuildHeight = minBuildHeight;
			this.height = height;
		}

		@Override
		public @Nullable BlockEntity getBlockEntity(BlockPos position) {
			if (hiddenPositions.contains(position)) {
				return null;
			}
			@Nullable CapturedBlock block = blocks.get(position);
			return block == null ? null : block.blockEntity();
		}

		@Override
		public BlockState getBlockState(BlockPos position) {
			if (hiddenPositions.contains(position)) {
				return Blocks.AIR.defaultBlockState();
			}
			@Nullable CapturedBlock block = blocks.get(position);
			return block == null ? Blocks.AIR.defaultBlockState() : block.state();
		}

		@Override
		public FluidState getFluidState(BlockPos position) {
			return getBlockState(position).getFluidState();
		}

		@Override
		public int getHeight() {
			return height;
		}

		@Override
		public int getMinBuildHeight() {
			return minBuildHeight;
		}
	}

	static final class LocalCollisionContext implements CollisionContext {
		private final CollisionContext originalContext;
		private final Vec3 localCameraFeet;
		private final boolean preserveFallback;

		LocalCollisionContext(
			CollisionContext originalContext,
			Vec3 localCameraFeet,
			boolean preserveFallback
		) {
			this.originalContext = Objects.requireNonNull(originalContext, "originalContext");
			this.localCameraFeet = localCameraFeet;
			this.preserveFallback = preserveFallback;
		}

		@Override
		public boolean isDescending() {
			return originalContext.isDescending();
		}

		@Override
		public boolean isAbove(@Nullable VoxelShape shape, @Nullable BlockPos position, boolean defaultValue) {
			if (preserveFallback || shape == null || shape.isEmpty() || position == null
				|| !finite(localCameraFeet)) {
				return originalContext.isAbove(shape, position, defaultValue);
			}

			return localCameraFeet.y > position.getY() + shape.max(Direction.Axis.Y) - 1.0E-5F;
		}

		@Override
		public boolean isHoldingItem(Item item) {
			return originalContext.isHoldingItem(item);
		}

		@Override
		public boolean canStandOnFluid(FluidState fluidState, FluidState flowingFluidState) {
			return originalContext.canStandOnFluid(fluidState, flowingFluidState);
		}
	}
}
