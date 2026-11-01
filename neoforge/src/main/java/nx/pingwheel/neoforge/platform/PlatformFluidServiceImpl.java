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
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.neoforge.integration.create.presentation.CreateFluidTankAccess;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Read-only block fluid capabilities; Create adds structure/mode safety, never a second fluid API. */
@NotNullByDefault
public final class PlatformFluidServiceImpl implements IPlatformFluidService {
	@Override public InventoryValidationBudget.Cost resolutionCost() { return FluidCapabilityAccess.RESOLUTION_COST; }
	@Override public Optional<Access> resolve(Context context) {
		Objects.requireNonNull(context);
		return resolve(new MinecraftWorld(context), context.root(), context.face());
	}

	static Optional<Access> resolve(FluidCapabilityAccess.World world, BlockPos root, Direction face) {
		Objects.requireNonNull(world); Objects.requireNonNull(root); Objects.requireNonNull(face);
		world.require(root);
		var block = world.block(root);
		world.require(root);
		if (CreateFluidTankAccess.recognizes(block)) {
			// A claimed but denied/malformed/unsupported tank never falls through to generic access.
			if (!world.createAvailable()) return Optional.empty();
			return FluidCapabilityAccess.find(CreateFluidTankAccess.resolve(world, root, face, block));
		}
		return FluidCapabilityAccess.find(new OrdinaryRoute(world, face, root, List.of(new FluidCapabilityAccess.Member(root, block))));
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
		@Override public boolean matches() {
			world.require(capabilityPosition);
			var current = world.block(capabilityPosition);
			world.require(capabilityPosition);
			return members.getFirst().block().same(current) && !CreateFluidTankAccess.recognizes(current);
		}
		@Override public InventoryValidationBudget.Cost checkCost() { return new InventoryValidationBudget.Cost(2048, 2048, 256); }
	}

	@NotNullByDefault
	private record MinecraftWorld(Context context) implements FluidCapabilityAccess.World {
		@Override public void require(BlockPos position) {
			context.require(position);
			if (!context.level().isLoaded(position)) throw new InventorySourceUnavailableException("fluid member unloaded");
			context.require(position);
		}
		@Override public FluidCapabilityAccess.Block block(BlockPos position) {
			require(position);
			var chunk = context.level().getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
			if (chunk == null) throw new InventorySourceUnavailableException("fluid member chunk unavailable");
			require(position);
			var state = chunk.getBlockState(position);
			if (state.getBlock() instanceof net.minecraft.world.level.block.AbstractCauldronBlock)
				throw new InventorySourceUnavailableException("cauldron fluid storage is unsupported");
			require(position);
			// Do not promote pending NBT: a Create member constructor can refresh its capability and
			// perform an unguarded controller lookup before the complete structure is admitted.
			@Nullable BlockEntity entity = chunk.getBlockEntities().get(position);
			if (state.hasBlockEntity() && entity == null) throw new InventorySourceUnavailableException("fluid member entity not instantiated");
			require(position);
			if (entity != null) {
				if (entity.isRemoved() || entity.getLevel() != context.level() || !position.equals(entity.getBlockPos())
					|| entity.getBlockState().getBlock() != state.getBlock())
					throw new InventorySourceUnavailableException("fluid member identity changed");
				require(position);
				if (entity instanceof RandomizableContainer container && container.getLootTable() != null)
					throw new InventorySourceUnavailableException("fluid member has pending loot");
			}
			require(position);
			String blockId = boundedId(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
			require(position);
			String entityId = entity == null ? "pingforit:no_block_entity" : boundedId(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()));
			return new FluidCapabilityAccess.Block(state.getBlock(), entity, blockId, entityId);
		}
		@Override public @Nullable IFluidHandler capability(BlockPos position, Direction face) {
			require(position);
			return context.level().getCapability(Capabilities.FluidHandler.BLOCK, position, face);
		}
		@Override public boolean held(FluidCapabilityAccess.Member member) {
			require(member.position());
			var chunk = context.level().getChunkSource().getChunkNow(member.position().getX() >> 4, member.position().getZ() >> 4);
			if (chunk == null) return false;
			require(member.position());
			if (chunk.getBlockState(member.position()).getBlock() != member.block().block()) return false;
			require(member.position());
			@Nullable BlockEntity entity = chunk.getBlockEntities().get(member.position());
			if (entity != member.block().entity()) return false;
			require(member.position());
			return entity == null || !entity.isRemoved() && entity.getLevel() == context.level() && member.position().equals(entity.getBlockPos());
		}
		@Override public String dimension() { context.require(context.root()); return boundedId(context.level().dimension().location()); }
		@Override public boolean createAvailable() { return CreateFluidTankAccess.available(); }
	}
}
