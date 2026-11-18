package nx.pingwheel.common.client.outline;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.marker.TargetKey;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4dc;

/** The shared external outline snapshot: one bound read scope, ordered subjects, and a frozen render transform. */
@NotNullByDefault
public final class ExternalBlockFramePresentation {
	private static final DiagnosticChannel SHAPE_FAILURES = DiagnosticChannel.interval(
		"block-outline/external-shape", Duration.ofSeconds(10));
	private final ExternalBlockOutlineSpec spec;
	private final ExternalBlockReadView view;
	private final EntityBlockGeometryTransform transform;
	private final BlockPresentation presentation;

	private ExternalBlockFramePresentation(ExternalBlockOutlineSpec spec, ExternalBlockReadView view,
		EntityBlockGeometryTransform transform, BlockPresentation presentation) {
		this.spec = spec;
		this.view = view;
		this.transform = transform;
		this.presentation = presentation;
	}

	public static Optional<ExternalBlockFramePresentation> resolve(ExternalBlockOutlineSpec spec,
		BlockPos rootPos, ExternalBlockReadView view, Matrix4dc renderPose) {
		return resolve(spec, rootPos, view, renderPose, BlockPresentationResolverRegistry.INSTANCE);
	}

	static Optional<ExternalBlockFramePresentation> resolve(ExternalBlockOutlineSpec spec,
		BlockPos rootPos, ExternalBlockReadView view, Matrix4dc renderPose, BlockPresentationResolverRegistry registry) {
		@Nullable BlockState root = view.loadedState(rootPos);
		if (root == null || root.isAir() || !spec.target().expectedBlockRegistryId()
			.equals(BlockPresentationContext.blockRegistryId(root))) {
			return Optional.empty();
		}

		EntityBlockGeometryTransform transform = new EntityBlockGeometryTransform(renderPose);
		// Resolver metadata only: never use the localized key as marker identity
		// or as a model/fallback success key, even if its numbers match another block.
		var localKey = new TargetKey.BlockKey(spec.blockKey().dimensionId(), rootPos.getX(), rootPos.getY(),
			rootPos.getZ(), spec.target().expectedBlockRegistryId());
		var resolverSpec = new BlockOutlineSpec(spec.markerId(), localKey, spec.targetTypeId(), spec.pingTypeId(), spec.argbColor());
		var presentation = registry.resolve(new BlockPresentationContext(view, resolverSpec));
		return Optional.of(new ExternalBlockFramePresentation(spec, view, transform, presentation));
	}

	ExternalBlockOutlineSpec spec() { return spec; }
	ExternalBlockReadView view() { return view; }
	EntityBlockGeometryTransform transform() { return transform; }
	BlockPresentation presentation() { return presentation; }

	boolean isFor(BlockGetter level, String dimensionId) {
		return view.isFor(level) && spec.blockKey().dimensionId().equals(dimensionId);
	}

	SuccessKey successKey(BlockRenderSubject subject) {
		return new SuccessKey(spec.blockKey(), subject.subjectId());
	}

	@Nullable BlockState liveState(BlockRenderSubject subject) {
		@Nullable BlockState state = view.loadedState(subject.blockPos());
		return state != null && BlockPresentationSubjectValidation.hasExpectedRegistryId(subject, state) ? state : null;
	}

	boolean allCoveredBy(Set<SuccessKey> succeeded) {
		for (BlockRenderSubject subject : presentation.renderSubjects()) {
			if (!succeeded.contains(successKey(subject))) return false;
		}
		return true;
	}

	void dispatchModels(BlockModelOutlineState frameState, BlockPresentationSubjectDispatcher.SubjectGeometryRenderer render) {
		BlockPresentationSubjectDispatcher.dispatch(presentation,
			subject -> liveState(subject) != null,
			subject -> frameState.addExternalSuccess(successKey(subject)), render);
	}

	void visitFallbackShapes(Set<SuccessKey> succeeded, CollisionContext context,
		BiConsumer<BlockRenderSubject, VoxelShape> emit) {
		for (BlockRenderSubject subject : presentation.renderSubjects()) {
			if (succeeded.contains(successKey(subject))) continue;
			@Nullable BlockState state = liveState(subject);
			if (state == null) continue;
			@Nullable VoxelShape shape;
			try {
				shape = state.getShape(view, subject.blockPos(), context);
			} catch (Exception | LinkageError | AssertionError failure) {
				Diagnostics.client().atNormal().rateLimited(SHAPE_FAILURES).failure(Level.WARN,
					"block-outline/external-shape", "shape-unavailable",
					() -> "target=" + spec.blockKey() + "; subject=" + subject.subjectId(), failure);
				continue;
			}
			if (shape != null && !shape.isEmpty()) emit.accept(subject, shape);
		}
	}

	PoseStack subjectPose(BlockRenderSubject subject, Vec3 cameraPosition) {
		return transform.createPoseStack(subject.blockPos(), cameraPosition);
	}

	@NotNullByDefault
	public record SuccessKey(TargetKey.ExternalBlockKey sourceKey, String subjectId) {
		public SuccessKey {
			Objects.requireNonNull(sourceKey);
			Objects.requireNonNull(subjectId);
		}
	}
}
