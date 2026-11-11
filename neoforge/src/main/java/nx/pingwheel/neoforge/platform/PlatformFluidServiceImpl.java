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
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryProviderLimits;
import nx.pingwheel.common.presentation.inventory.minecraft.ProviderLimitSite;
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
	@Override public nx.pingwheel.common.presentation.inventory.InventoryDecision<Probe> probeDecision(Context context, Operation operation) {
		try { return probeAvailable(new MinecraftWorld(context), context.root(), context.face(), operation)
			.map(nx.pingwheel.common.presentation.inventory.InventoryDecision::accept)
			.orElseGet(() -> nx.pingwheel.common.presentation.inventory.InventoryDecision.reject(nx.pingwheel.common.presentation.inventory.InventoryFailureReason.SOURCE_UNAVAILABLE,
				nx.pingwheel.common.presentation.inventory.InventoryImpact.DEGRADED)); }
		catch (nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnsupportedException absent) {
			return nx.pingwheel.common.presentation.inventory.InventoryDecision.unsupported(nx.pingwheel.common.presentation.inventory.InventoryFailureReason.UNSUPPORTED_LANE,
				nx.pingwheel.common.presentation.inventory.InventoryImpact.DEGRADED);
		}
	}

	static Optional<Probe> probe(FluidCapabilityAccess.World world, BlockPos root, Direction face, Operation operation) {
		try { return probeAvailable(world, root, face, operation); }
		catch (nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnsupportedException absent) { return Optional.empty(); }
	}
	private static Optional<Probe> probeAvailable(FluidCapabilityAccess.World world, BlockPos root, Direction face, Operation operation) {
		Objects.requireNonNull(world); Objects.requireNonNull(root); Objects.requireNonNull(face);
		operation.allowance().debit(FluidCapabilityAccess.PROBE_PROFILE.ownedCost());
		root = root.immutable();
		// World.block owns its entry gate. No callback lies between that gate and this call,
		// so an additional immediately preceding world.require would duplicate the same fence.
		var block = world.block(root, operation);
		world.require(root, operation);
		if (CreateFluidTankAccess.recognizes(block)) {
			// A claimed but denied/malformed/unsupported tank never falls through to generic access.
			if (!world.createAvailable()) {
				FluidSourceDiagnostics.createProbeAbsent(root, face, block.blockId(), block.entityId());
				return Optional.empty();
			}
			return Optional.of(CreateFluidTankAccess.probe(world, root, face, block, operation));
		}
		return FluidCapabilityAccess.probeAvailable(new OrdinaryRoute(world, face, root, List.of(new FluidCapabilityAccess.Member(root, block))), operation);
	}
	/** Guard native strings before toString concatenates/copies an unbounded registry ID. */
	static String boundedId(@Nullable ResourceLocation id) {
		if (id == null) throw FluidSourceDiagnostics.unavailable("fluid registry identity bound",
			() -> "id=null;max=256");
		int namespaceLength = id.getNamespace().length(), pathLength = id.getPath().length();
		long totalLength = (long) namespaceLength + pathLength + 1;
		if (namespaceLength > 255 || pathLength > 255 || totalLength > 256)
			throw InventoryProviderLimits.exceeded(ProviderLimitSite.NEOFORGE_FLUID_REGISTRY_ID, totalLength, 256);
		return id.toString();
	}

	@NotNullByDefault
	private record OrdinaryRoute(FluidCapabilityAccess.World world, Direction face, BlockPos capabilityPosition,
		@Unmodifiable List<FluidCapabilityAccess.Member> members) implements FluidCapabilityAccess.Route {
		private OrdinaryRoute { capabilityPosition = capabilityPosition.immutable(); members = List.copyOf(members); }
		@Override public Optional<String> alias() { return Optional.empty(); }
		@Override public String description() { return "block-fluid;face=" + face.getName(); }
		@Override public boolean matches(Operation operation) {
			// world.block applies its entry gate; only the post-read fence before the pure route
			// classification remains explicit here.
			var current = world.block(capabilityPosition, operation);
			world.require(capabilityPosition, operation);
			return members.getFirst().block().same(current) && !CreateFluidTankAccess.recognizes(current);
		}
		@Override public OperationProfile checkProfile() { return world.requireProfile().plus(world.blockProfile()); }
		@Override public TopologyEvidence topology() { return TopologyEvidence.PUBLIC_ADDRESS_ONLY; }
	}

	@NotNullByDefault
	private record MinecraftWorld(Context context) implements FluidCapabilityAccess.World {
		@Override public void require(BlockPos position, Operation operation) {
			context.require(position, operation);
			if (!context.level().isLoaded(position)) throw FluidSourceDiagnostics.unavailable("fluid member unloaded",
				() -> "position=" + position.toShortString() + ";chunk=" + (position.getX() >> 4) + "," + (position.getZ() >> 4));
			context.require(position, operation);
		}
		@Override public void requireController(BlockPos position, Operation operation) {
			context.requireController(position, operation);
		}
		@Override public FluidCapabilityAccess.Block block(BlockPos position, Operation operation) {
			require(position, operation);
			var chunk = context.level().getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
			if (chunk == null) throw FluidSourceDiagnostics.unavailable("fluid member chunk unavailable",
				() -> "position=" + position.toShortString() + ";chunk=" + (position.getX() >> 4) + "," + (position.getZ() >> 4));
			require(position, operation);
			var state = chunk.getBlockState(position);
			if (state.getBlock() instanceof net.minecraft.world.level.block.AbstractCauldronBlock)
				throw FluidSourceDiagnostics.unavailable("cauldron fluid storage is unsupported",
					() -> "position=" + position.toShortString());
			require(position, operation);
			// Do not promote pending NBT: a Create member constructor can refresh its capability and
			// perform an unguarded controller lookup before the complete structure is admitted.
			@Nullable BlockEntity entity = chunk.getBlockEntities().get(position);
			if (state.hasBlockEntity() && entity == null) throw FluidSourceDiagnostics.unavailable("fluid member entity not instantiated",
				() -> "position=" + position.toShortString() + ";hasBlockEntity=true");
			require(position, operation);
			if (entity != null) {
				if (entity.isRemoved()) throw FluidSourceDiagnostics.unavailable("fluid member identity changed",
					() -> "position=" + position.toShortString() + ";removed=true");
				if (entity.getLevel() != context.level()) throw FluidSourceDiagnostics.unavailable("fluid member identity changed",
					() -> "position=" + position.toShortString() + ";levelMatch=false");
				BlockPos entityPosition = entity.getBlockPos();
				if (!position.equals(entityPosition)) throw FluidSourceDiagnostics.unavailable("fluid member identity changed",
					() -> "position=" + position.toShortString() + ";entityPosition=" + entityPosition.toShortString());
				if (entity.getBlockState().getBlock() != state.getBlock()) throw FluidSourceDiagnostics.unavailable("fluid member identity changed",
					() -> "position=" + position.toShortString() + ";blockMatch=false");
				require(position, operation);
				if (entity instanceof RandomizableContainer container && container.getLootTable() != null)
					throw FluidSourceDiagnostics.unavailable("fluid member has pending loot",
						() -> "position=" + position.toShortString() + ";lootTablePresent=true");
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
