package nx.pingwheel.neoforge.integration.simulated;

import java.util.List;
import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import nx.pingwheel.common.client.outline.BlockPresentationContext;
import nx.pingwheel.common.client.outline.BlockPresentationRelation;
import nx.pingwheel.common.client.outline.BlockPresentationResolution;
import nx.pingwheel.common.client.outline.BlockPresentationResolver;
import nx.pingwheel.common.client.outline.BlockPresentationResolverRegistry;
import nx.pingwheel.common.client.outline.BlockRenderSubject;

/**
 * Presents Simulated's invisible paired docking connector through its owner.
 *
 * <p>The optional integration has no Simulated class dependency. Registry
 * entries are looked up only while resolving a claimed source, after the
 * loader has confirmed that Simulated is present.</p>
 */
public final class SimulatedDockingConnectorPresentationResolver implements BlockPresentationResolver {
	public static final String RESOLVER_ID = "pingforit:simulated_docking_connector_presentation";
	private static final ResourceLocation PAIRED_BLOCK_ID =
		ResourceLocation.parse("simulated:paired_docking_connector");
	private static final ResourceLocation OWNER_BLOCK_ID =
		ResourceLocation.parse("simulated:docking_connector");
	private static final ResourceLocation OWNER_BLOCK_ENTITY_ID =
		ResourceLocation.parse("simulated:docking_connector");

	private static BlockPresentationResolverRegistry.Registration registration;
	private final RegistryEntries injectedEntries;

	public SimulatedDockingConnectorPresentationResolver() {
		injectedEntries = null;
	}

	/**
	 * Test-only seam for registered vanilla stand-ins. Production registration
	 * uses the no-argument constructor and the optional registry lookups below.
	 */
	SimulatedDockingConnectorPresentationResolver(
		Block pairedBlock,
		Block ownerBlock,
		BlockEntityType<?> ownerBlockEntityType
	) {
		this.injectedEntries = new RegistryEntries(
			pairedBlock,
			registeredBlockId(pairedBlock),
			ownerBlock,
			registeredBlockId(ownerBlock),
			ownerBlockEntityType,
			registeredBlockEntityId(ownerBlockEntityType));
	}

	/**
	 * Called reflectively after NeoForge has confirmed that Simulated is loaded.
	 * The stateless resolver retains this registration for the process lifetime.
	 */
	public static synchronized void register() {
		if (registration != null) {
			return;
		}

		registration = BlockPresentationResolverRegistry.INSTANCE.register(
			new SimulatedDockingConnectorPresentationResolver());
	}

	/** Closes this resolver's retained common-registry registration. */
	public static synchronized void close() {
		if (registration != null) {
			registration.close();
			registration = null;
		}
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
		RegistryEntries entries = injectedEntries == null ? lookupEntries() : injectedEntries;
		BlockState pairedState = context.sourceState();
		if (pairedState == null || entries == null
			|| pairedState.getBlock() != entries.pairedBlock()
			|| !entries.pairedBlockId().equals(BuiltInRegistries.BLOCK.getKey(pairedState.getBlock()))) {
			return BlockPresentationResolution.UNHANDLED;
		}

		if (!pairedState.hasProperty(BlockStateProperties.FACING)) {
			return BlockPresentationResolution.handled(List.of());
		}

		Direction towardOwner = pairedState.getValue(BlockStateProperties.FACING);
		BlockPos pairedPos = context.sourcePos();
		BlockPos ownerPos = pairedPos.relative(towardOwner);
		BlockState ownerState = context.world().getBlockState(ownerPos);
		if (ownerState == null || entries.ownerBlock() == null || entries.ownerBlockEntityType() == null
			|| ownerState.getBlock() != entries.ownerBlock()
			|| !entries.ownerBlockId().equals(BuiltInRegistries.BLOCK.getKey(ownerState.getBlock()))
			|| !ownerState.hasProperty(BlockStateProperties.FACING)
			|| ownerState.getValue(BlockStateProperties.FACING) != towardOwner.getOpposite()
			|| !ownerState.hasProperty(BlockStateProperties.POWERED)
			|| !ownerState.getValue(BlockStateProperties.POWERED)) {
			return BlockPresentationResolution.handled(List.of());
		}

		BlockEntity ownerBlockEntity = context.world().getBlockEntity(ownerPos);
		if (ownerBlockEntity == null || ownerBlockEntity.getType() != entries.ownerBlockEntityType()
			|| !entries.ownerBlockEntityId().equals(
				BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(ownerBlockEntity.getType()))) {
			return BlockPresentationResolution.handled(List.of());
		}

		return BlockPresentationResolution.handled(new BlockRenderSubject(
			"owner",
			ownerPos,
			ownerState,
			entries.ownerBlockId().toString(),
			"entity_block",
			BlockPresentationRelation.PROXY_TO_OWNER));
	}

	private static RegistryEntries lookupEntries() {
		Block pairedBlock = lookupBlock(PAIRED_BLOCK_ID);
		if (pairedBlock == null) {
			return null;
		}

		return new RegistryEntries(
			pairedBlock,
			PAIRED_BLOCK_ID,
			lookupBlock(OWNER_BLOCK_ID),
			OWNER_BLOCK_ID,
			lookupBlockEntityType(OWNER_BLOCK_ENTITY_ID),
			OWNER_BLOCK_ENTITY_ID);
	}

	private static ResourceLocation registeredBlockId(Block block) {
		return Objects.requireNonNull(BuiltInRegistries.BLOCK.getKey(block), "paired/owner block registry id");
	}

	private static ResourceLocation registeredBlockEntityId(BlockEntityType<?> blockEntityType) {
		return Objects.requireNonNull(
			BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntityType), "block entity registry id");
	}

	private record RegistryEntries(
		Block pairedBlock,
		ResourceLocation pairedBlockId,
		Block ownerBlock,
		ResourceLocation ownerBlockId,
		BlockEntityType<?> ownerBlockEntityType,
		ResourceLocation ownerBlockEntityId
	) {}

	private static Block lookupBlock(ResourceLocation id) {
		try {
			return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
		} catch (Exception | LinkageError | AssertionError ignored) {
			return null;
		}
	}

	private static BlockEntityType<?> lookupBlockEntityType(ResourceLocation id) {
		try {
			return BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).orElse(null);
		} catch (Exception | LinkageError | AssertionError ignored) {
			return null;
		}
	}
}
