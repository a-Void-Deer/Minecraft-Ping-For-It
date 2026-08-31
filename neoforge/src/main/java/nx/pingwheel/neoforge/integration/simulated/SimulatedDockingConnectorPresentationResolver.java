package nx.pingwheel.neoforge.integration.simulated;

import java.util.List;

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

	public SimulatedDockingConnectorPresentationResolver() {}

	/** Called reflectively after NeoForge has confirmed that Simulated is loaded. */
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
		BlockState pairedState = context.sourceState();
		Block pairedBlock = lookupBlock(PAIRED_BLOCK_ID);
		if (pairedState == null || pairedBlock == null
			|| pairedState.getBlock() != pairedBlock
			|| !PAIRED_BLOCK_ID.equals(BuiltInRegistries.BLOCK.getKey(pairedState.getBlock()))) {
			return BlockPresentationResolution.UNHANDLED;
		}

		if (!pairedState.hasProperty(BlockStateProperties.FACING)) {
			return BlockPresentationResolution.handled(List.of());
		}

		Direction towardOwner = pairedState.getValue(BlockStateProperties.FACING);
		BlockPos pairedPos = context.sourcePos();
		BlockPos ownerPos = pairedPos.relative(towardOwner);
		BlockState ownerState = context.world().getBlockState(ownerPos);
		Block ownerBlock = lookupBlock(OWNER_BLOCK_ID);
		BlockEntityType<?> ownerBlockEntityType = lookupBlockEntityType(OWNER_BLOCK_ENTITY_ID);
		if (ownerState == null || ownerBlock == null || ownerBlockEntityType == null
			|| ownerState.getBlock() != ownerBlock
			|| !OWNER_BLOCK_ID.equals(BuiltInRegistries.BLOCK.getKey(ownerState.getBlock()))
			|| !ownerState.hasProperty(BlockStateProperties.FACING)
			|| ownerState.getValue(BlockStateProperties.FACING) != towardOwner.getOpposite()
			|| !ownerState.hasProperty(BlockStateProperties.POWERED)
			|| !ownerState.getValue(BlockStateProperties.POWERED)) {
			return BlockPresentationResolution.handled(List.of());
		}

		BlockEntity ownerBlockEntity = context.world().getBlockEntity(ownerPos);
		if (ownerBlockEntity == null || ownerBlockEntity.getType() != ownerBlockEntityType
			|| !OWNER_BLOCK_ENTITY_ID.equals(
				BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(ownerBlockEntity.getType()))) {
			return BlockPresentationResolution.handled(List.of());
		}

		return BlockPresentationResolution.handled(new BlockRenderSubject(
			"owner",
			ownerPos,
			ownerState,
			OWNER_BLOCK_ID.toString(),
			"entity_block",
			BlockPresentationRelation.PROXY_TO_OWNER));
	}

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
