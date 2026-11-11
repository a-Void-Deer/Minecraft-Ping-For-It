package nx.pingwheel.common.math;

import java.util.Objects;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Selection-oriented {@link ClipContext} shared by ordinary, Precise and
 * entity-local raycasts.
 *
 * <p>Exact optional compatibility block IDs can expose non-empty visual shapes
 * that should not act as selectable surfaces. Those IDs therefore pass through
 * while {@link ClipContext.Block#VISUAL} is active. {@link ClipContext.Block#OUTLINE}
 * still delegates to the block's native selectable shape, and every other block
 * or fluid query is delegated unchanged.</p>
 */
@NotNullByDefault
public final class PingClipContext extends ClipContext {

	private static final @Unmodifiable Set<ResourceLocation> VISUAL_PASS_THROUGH_BLOCK_IDS = Set.of(
		ResourceLocation.fromNamespaceAndPath("cyclic", "glass_connected"),
		ResourceLocation.fromNamespaceAndPath("cyclic", "glass_dark"),
		ResourceLocation.fromNamespaceAndPath("cyclic", "glass_dark_connected"));

	private static final BlockIdResolver REGISTRY_BLOCK_ID_RESOLVER =
		state -> BuiltInRegistries.BLOCK.getKey(state.getBlock());

	private final ClipContext.Block blockMode;
	private final BlockIdResolver blockIdResolver;

	private PingClipContext(
		Vec3 from,
		Vec3 to,
		ClipContext.Block blockMode,
		ClipContext.Fluid fluidMode,
		CollisionContext collisionContext,
		BlockIdResolver blockIdResolver
	) {
		super(from, to, blockMode, fluidMode, collisionContext);
		this.blockMode = Objects.requireNonNull(blockMode, "blockMode");
		this.blockIdResolver = Objects.requireNonNull(blockIdResolver, "blockIdResolver");
	}

	private PingClipContext(
		Vec3 from,
		Vec3 to,
		ClipContext.Block blockMode,
		ClipContext.Fluid fluidMode,
		Entity entity,
		BlockIdResolver blockIdResolver
	) {
		super(from, to, blockMode, fluidMode, entity);
		this.blockMode = Objects.requireNonNull(blockMode, "blockMode");
		this.blockIdResolver = Objects.requireNonNull(blockIdResolver, "blockIdResolver");
	}

	/** Builds the shared selection context for a frozen policy and collision context. */
	public static PingClipContext create(
		Vec3 from,
		Vec3 to,
		RaycastPolicy policy,
		CollisionContext collisionContext
	) {
		Objects.requireNonNull(collisionContext, "collisionContext");
		return create(from, to, policy, collisionContext, REGISTRY_BLOCK_ID_RESOLVER);
	}

	/** Builds the shared selection context for a frozen policy and camera entity. */
	public static PingClipContext create(Vec3 from, Vec3 to, RaycastPolicy policy, Entity entity) {
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(entity, "entity");

		return new PingClipContext(
			from,
			to,
			blockMode(policy),
			fluidMode(policy),
			entity,
			REGISTRY_BLOCK_ID_RESOLVER);
	}

	static PingClipContext create(
		Vec3 from,
		Vec3 to,
		RaycastPolicy policy,
		CollisionContext collisionContext,
		BlockIdResolver blockIdResolver
	) {
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(collisionContext, "collisionContext");
		Objects.requireNonNull(blockIdResolver, "blockIdResolver");

		return new PingClipContext(
			from,
			to,
			blockMode(policy),
			fluidMode(policy),
			collisionContext,
			blockIdResolver);
	}

	@Override
	public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
		if (blockMode == ClipContext.Block.VISUAL
			&& isVisualPassThroughBlockId(blockIdResolver.resolve(state))) {
			return Shapes.empty();
		}

		return super.getBlockShape(state, level, pos);
	}

	static boolean isVisualPassThroughBlockId(@Nullable ResourceLocation blockId) {
		return blockId != null && VISUAL_PASS_THROUGH_BLOCK_IDS.contains(blockId);
	}

	private static ClipContext.Block blockMode(RaycastPolicy policy) {
		return policy.blockMode() == RaycastPolicy.BlockMode.OUTLINE
			? ClipContext.Block.OUTLINE
			: ClipContext.Block.VISUAL;
	}

	private static ClipContext.Fluid fluidMode(RaycastPolicy policy) {
		return policy.fluidMode() == RaycastPolicy.FluidMode.ANY
			? ClipContext.Fluid.ANY
			: ClipContext.Fluid.NONE;
	}

	/** Package-visible registry-id seam for deterministic selection tests. */
	@FunctionalInterface
	interface BlockIdResolver {
		@Nullable ResourceLocation resolve(BlockState state);
	}
}
