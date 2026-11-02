package nx.pingwheel.neoforge.platform;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.neoforge.integration.create.presentation.CreateFluidTankAccess;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Read-only block fluid capabilities; Create adds structure/mode safety, never a second fluid API. */
@NotNullByDefault
public final class PlatformFluidServiceImpl implements IPlatformFluidService {
	@Override public OperationProfile probeProfile() { return FluidCapabilityAccess.PROBE_PROFILE; }
	@Override public Optional<Probe> probe(Context context, Operation operation) {
		Objects.requireNonNull(context);
		return probe(new MinecraftWorld(context), context.root(), context.face(), operation);
	}

	static Optional<Probe> probe(FluidCapabilityAccess.World world, BlockPos root, Direction face, Operation operation) {
		Objects.requireNonNull(world); Objects.requireNonNull(root); Objects.requireNonNull(face);
		operation.allowance().debit(FluidCapabilityAccess.PROBE_PROFILE.ownedCost());
		root = root.immutable(); world.require(root, operation);
		var block = world.block(root, operation);
		world.require(root, operation);
		if (CreateFluidTankAccess.recognizes(block)) {
			// A claimed but denied/malformed/unsupported tank never falls through to generic access.
			if (!world.createAvailable()) return Optional.empty();
			return Optional.of(CreateFluidTankAccess.probe(world, root, face, block, operation));
		}
		return FluidCapabilityAccess.probe(new OrdinaryRoute(world, face, root, List.of(new FluidCapabilityAccess.Member(root, block))), operation);
	}
	/** Guard native strings before toString concatenates/copies an unbounded registry ID. */
	static String boundedId(@Nullable ResourceLocation id) {
		if (id == null || id.getNamespace().length() > 255 || id.getPath().length() > 255
			|| (long) id.getNamespace().length() + id.getPath().length() + 1 > 256)
			throw new InventorySourceUnavailableException("fluid registry identity bound");
		return id.toString();
	}

	@NotNullByDefault
	private record OrdinaryRoute(FluidCapabilityAccess.World world, Direction face, BlockPos capabilityPosition,
		@Unmodifiable List<FluidCapabilityAccess.Member> members) implements FluidCapabilityAccess.Route {
		private OrdinaryRoute { capabilityPosition = capabilityPosition.immutable(); members = List.copyOf(members); }
		@Override public Optional<String> alias() { return Optional.empty(); }
		@Override public String description() { return "block-fluid;face=" + face.getName(); }
		@Override public boolean matches(Operation operation) {
			world.require(capabilityPosition, operation);
			var current = world.block(capabilityPosition, operation);
			world.require(capabilityPosition, operation);
			return members.getFirst().block().same(current) && !CreateFluidTankAccess.recognizes(current);
		}
		@Override public OperationProfile checkProfile() { return world.requireProfile().times(2).plus(world.blockProfile()); }
		@Override public TopologyEvidence topology() { return TopologyEvidence.PUBLIC_ADDRESS_ONLY; }
	}

	@NotNullByDefault
	private record MinecraftWorld(Context context) implements FluidCapabilityAccess.World {
		@Override public void require(BlockPos position, Operation operation) {
			context.require(position, operation);
			if (!context.level().isLoaded(position)) throw new InventorySourceUnavailableException("fluid member unloaded");
			context.require(position, operation);
		}
		@Override public FluidCapabilityAccess.Block block(BlockPos position, Operation operation) {
			require(position, operation);
			var chunk = context.level().getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
			if (chunk == null) throw new InventorySourceUnavailableException("fluid member chunk unavailable");
			require(position, operation);
			var state = chunk.getBlockState(position);
			if (state.getBlock() instanceof net.minecraft.world.level.block.AbstractCauldronBlock)
				throw new InventorySourceUnavailableException("cauldron fluid storage is unsupported");
			require(position, operation);
			// Do not promote pending NBT: a Create member constructor can refresh its capability and
			// perform an unguarded controller lookup before the complete structure is admitted.
			@Nullable BlockEntity entity = chunk.getBlockEntities().get(position);
			if (state.hasBlockEntity() && entity == null) throw new InventorySourceUnavailableException("fluid member entity not instantiated");
			require(position, operation);
			if (entity != null) {
				if (entity.isRemoved() || entity.getLevel() != context.level() || !position.equals(entity.getBlockPos())
					|| entity.getBlockState().getBlock() != state.getBlock())
					throw new InventorySourceUnavailableException("fluid member identity changed");
				require(position, operation);
				if (entity instanceof RandomizableContainer container && container.getLootTable() != null)
					throw new InventorySourceUnavailableException("fluid member has pending loot");
			}
			require(position, operation);
			String blockId = boundedId(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
			require(position, operation);
			String entityId = entity == null ? "pingforit:no_block_entity" : boundedId(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()));
			return new FluidCapabilityAccess.Block(state.getBlock(), entity, blockId, entityId);
		}
		@Override public @Nullable IFluidHandler capability(BlockPos position, Direction face, Operation operation) {
			require(position, operation);
			return context.level().getCapability(Capabilities.FluidHandler.BLOCK, position, face);
		}
		@Override public boolean held(FluidCapabilityAccess.Member member, Operation operation) {
			require(member.position(), operation);
			var chunk = context.level().getChunkSource().getChunkNow(member.position().getX() >> 4, member.position().getZ() >> 4);
			if (chunk == null) return false;
			require(member.position(), operation);
			if (chunk.getBlockState(member.position()).getBlock() != member.block().block()) return false;
			require(member.position(), operation);
			@Nullable BlockEntity entity = chunk.getBlockEntities().get(member.position());
			if (entity != member.block().entity()) return false;
			require(member.position(), operation);
			return entity == null || !entity.isRemoved() && entity.getLevel() == context.level() && member.position().equals(entity.getBlockPos());
		}
		@Override public String dimension(Operation operation) { context.require(context.root(), operation); return boundedId(context.level().dimension().location()); }
		@Override public boolean createAvailable() { return CreateFluidTankAccess.available(); }
	}
}
