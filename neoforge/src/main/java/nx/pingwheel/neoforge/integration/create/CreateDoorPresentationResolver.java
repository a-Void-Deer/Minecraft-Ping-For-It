package nx.pingwheel.neoforge.integration.create;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import nx.pingwheel.common.client.outline.BlockPresentationContext;
import nx.pingwheel.common.client.outline.BlockPresentationCoverageRelation;
import nx.pingwheel.common.client.outline.BlockPresentationRelation;
import nx.pingwheel.common.client.outline.BlockPresentationResolution;
import nx.pingwheel.common.client.outline.BlockPresentationResolver;
import nx.pingwheel.common.client.outline.BlockPresentationResolverRegistry;
import nx.pingwheel.common.client.outline.BlockRenderSubject;
import nx.pingwheel.common.client.outline.EntityBlockGeometryRunner;
import nx.pingwheel.common.client.outline.VanillaDoorBlockPresentationResolver;

/**
 * Adds Create's block-entity-backed doors to the common paired-door
 * presentation without coupling common code to Create's classes.
 *
 * <p>Create only creates the lower block entity, whose renderer owns the
 * complete two-block door geometry. The upper subject is therefore covered
 * only after that lower block-entity renderer has actually emitted geometry.
 * The ordinary common door resolver remains responsible for validating the
 * live pair and building the subjects.</p>
 */
public final class CreateDoorPresentationResolver implements BlockPresentationResolver {
	public static final String RESOLVER_ID = "pingforit:create_door_presentation";

	private static final Set<String> CREATE_DOOR_IDS = Set.of(
		"create:andesite_door",
		"create:copper_door",
		"create:brass_door",
		"create:train_door",
		"create:framed_glass_door");
	private static final VanillaDoorBlockPresentationResolver VANILLA_DOOR_RESOLVER =
		new VanillaDoorBlockPresentationResolver();

	private static BlockPresentationResolverRegistry.Registration registration;

	private final Predicate<BlockState> sourcePredicate;
	private final Predicate<String> sourceIdMatcher;

	/** Used only after NeoForge has confirmed that Create is loaded. */
	public CreateDoorPresentationResolver() {
		this(CreateDoorPresentationResolver::isProductionCreateDoor,
			CreateDoorPresentationResolver::matchesSupportedCreateDoorId);
	}

	/**
	 * Test seam for vanilla door stand-ins. Production registration always uses
	 * the no-argument constructor, preserving the strict Create id and
	 * {@link EntityBlock} predicates.
	 */
	CreateDoorPresentationResolver(
		Predicate<BlockState> sourcePredicate,
		Predicate<String> sourceIdMatcher
	) {
		this.sourcePredicate = Objects.requireNonNull(sourcePredicate, "sourcePredicate");
		this.sourceIdMatcher = Objects.requireNonNull(sourceIdMatcher, "sourceIdMatcher");
	}

	/**
	 * Called reflectively after NeoForge has confirmed Create is loaded. This
	 * stateless resolver deliberately retains its common-registry registration
	 * for the process lifetime.
	 */
	public static synchronized void register() {
		if (registration != null) {
			return;
		}

		registration = BlockPresentationResolverRegistry.INSTANCE.registerBefore(
			VanillaDoorBlockPresentationResolver.ID,
			new CreateDoorPresentationResolver());
	}

	/** Reflection-only lifecycle probe used by loader diagnostics. */
	public static synchronized String registrationState() {
		if (registration == null) {
			return "not-registered";
		}
		return registration.accepted() ? "registered" : "rejected";
	}

	@Override
	public String id() {
		return RESOLVER_ID;
	}

	@Override
	public BlockPresentationResolution resolve(BlockPresentationContext context) {
		BlockState sourceState = context.sourceState();
		if (sourceState == null
			|| !"entity_block".equals(context.sourceSpec().targetTypeId())
			|| !sourcePredicate.test(sourceState)
			|| !sourceIdMatcher.test(context.sourceBlockRegistryId())) {
			return BlockPresentationResolution.UNHANDLED;
		}

		BlockPresentationResolution resolution = VANILLA_DOOR_RESOLVER.resolve(context);
		if (!isCanonicalDoorComposite(resolution)) {
			return resolution;
		}

		BlockRenderSubject lower = resolution.subjects().get(0);
		BlockRenderSubject upper = resolution.subjects().get(1);
		return BlockPresentationResolution.handled(
			resolution.subjects(),
			List.of(new BlockPresentationCoverageRelation(
				lower.subjectId(),
				EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID,
				upper.subjectId())));
	}

	static boolean matchesSupportedCreateDoorId(String blockId) {
		return blockId != null && CREATE_DOOR_IDS.contains(blockId);
	}

	private static boolean isProductionCreateDoor(BlockState state) {
		return state != null
			&& state.getBlock() instanceof DoorBlock
			&& state.getBlock() instanceof EntityBlock;
	}

	private static boolean isCanonicalDoorComposite(BlockPresentationResolution resolution) {
		if (!resolution.handled() || !resolution.coverageRelations().isEmpty()
			|| resolution.subjects().size() != 2) {
			return false;
		}

		BlockRenderSubject lower = resolution.subjects().get(0);
		BlockRenderSubject upper = resolution.subjects().get(1);
		return "lower".equals(lower.subjectId())
			&& "upper".equals(upper.subjectId())
			&& lower.relation() == BlockPresentationRelation.COMPOSITE
			&& upper.relation() == BlockPresentationRelation.COMPOSITE
			&& lower.blockPos().above().equals(upper.blockPos())
			&& lower.expectedBlockRegistryId().equals(upper.expectedBlockRegistryId())
			&& lower.renderTargetTypeId().equals(upper.renderTargetTypeId());
	}
}
