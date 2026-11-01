package nx.pingwheel.forge.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryQuantities;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Read-only, exact-owner/face Forge block capability route. No item or multiblock discovery. */
@NotNullByDefault
public final class PlatformFluidServiceImpl implements IPlatformFluidService {

	// One member, bounded identity strings, native-index mapping/boxing, capacity witnesses,
	// DTO validation/copies, access/wrapper and cached profiles, including a closing route check.
	// Native capability/stack getter internals are external-trusted, not modeled owned allocation.
	private static final InventoryValidationBudget.Cost RESOLUTION = new InventoryValidationBudget.Cost(
		65536L + 512L * InventorySourceAccess.MAX_SLOTS,
		65536L + 512L * InventorySourceAccess.MAX_SLOTS,
		32768L + 64L * InventorySourceAccess.MAX_SLOTS);
	private static final InventoryValidationBudget.Cost READ_OVERHEAD = new InventoryValidationBudget.Cost(1024, 1024, 128);
	private static final int MAX_REGISTRY_ID_CHARS = 256;

	@Override public InventoryValidationBudget.Cost resolutionCost() { return RESOLUTION; }
	@Override public Optional<Access> resolve(Context context) {
		Objects.requireNonNull(context);
		return resolve(context, new MinecraftWorld(context.level()));
	}

	/** The production lookup sequence, replaceable without replacing its current Context gates. */
	@NotNullByDefault
	interface World {
		boolean loaded(BlockPos position);
		BlockState state(BlockPos position);
		@Nullable BlockEntity blockEntity(BlockPos position);
		LazyOptional<IFluidHandler> capability(BlockEntity entity, Direction face);
		/** Borrowed native read; the caller immediately enters the visitor, before any later callback. */
		default Entry content(IFluidHandler handler, int tank) { return nativeEntry(handler, tank); }
	}

	@NotNullByDefault
	private record MinecraftWorld(ServerLevel level) implements World {
		@Override public boolean loaded(BlockPos position) { return level.isLoaded(position); }
		@Override public BlockState state(BlockPos position) { return level.getBlockState(position); }
		@Override public @Nullable BlockEntity blockEntity(BlockPos position) { return level.getBlockEntity(position); }
		@Override public LazyOptional<IFluidHandler> capability(BlockEntity entity, Direction face) {
			return entity.getCapability(ForgeCapabilities.FLUID_HANDLER, face);
		}
	}

	/** Enter only under resolutionCost admission, just like the public resolve hook. */
	static Optional<Access> resolve(Context context, World world) {
		Objects.requireNonNull(context); Objects.requireNonNull(world);
		BlockPos root = context.root();
		context.require(root);
		boolean loaded = world.loaded(root);
		context.require(root);
		if (!loaded) return Optional.empty();
		BlockState state = world.state(root);
		context.require(root);
		@Nullable BlockEntity entity = world.blockEntity(root);
		context.require(root);
		if (entity == null || entity.isRemoved()) return Optional.empty();
		context.require(root);
		LazyOptional<IFluidHandler> capability = Objects.requireNonNull(world.capability(entity, context.face()));
		context.require(root);
		Optional<IFluidHandler> resolved = capability.resolve();
		context.require(root);
		if (resolved.isEmpty()) return Optional.empty();
		IFluidHandler handler = resolved.orElseThrow();
		int tanks = handler.getTanks();
		context.require(root);
		if (tanks < 0 || tanks > InventorySourceAccess.MAX_SLOTS)
			throw new IllegalStateException("fluid tank address bound");
		int[] capacities = new int[tanks];
		var addresses = new ArrayList<Integer>(tanks);
		for (int tank = 0; tank < tanks; tank++) {
			context.require(root);
			capacities[tank] = capacity(handler, tank);
			context.require(root);
			addresses.add(tank);
		}
		context.require(root);
		String blockId = boundedRegistryId(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
		context.require(root);
		@Nullable ResourceLocation entityId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
		context.require(root);
		if (entityId == null) throw new InventorySourceUnavailableException("unregistered fluid block entity");
		String blockEntityId = boundedRegistryId(entityId);
		@Unmodifiable List<Integer> mapping = List.copyOf(addresses);
		var layout = new InventorySnapshotLayout("forge:fluid_handler", context.face().getName(), null, List.of(
			new InventorySnapshotLayout.Member(root, blockId, blockEntityId, "root", List.of(
				new InventorySnapshotLayout.Segment("tanks", ADDRESS_SCHEMA, tanks, mapping)))));
		var access = new HandlerAccess(context, world, state, entity, capability, handler, capacities, layout);
		access.requireCurrent();
		return Optional.of(access);
	}

	/** Inspect existing strings before ResourceLocation.toString allocates a combined ID. */
	static String boundedRegistryId(ResourceLocation id) {
		int namespace = id.getNamespace().length();
		// Subtraction after the first comparison avoids overflowing namespace + ':' + path.
		if (namespace >= MAX_REGISTRY_ID_CHARS || id.getPath().length() > MAX_REGISTRY_ID_CHARS - 1 - namespace)
			throw new InventorySourceUnavailableException("fluid registry identity length bound");
		return id.toString();
	}

	private static int capacity(IFluidHandler handler, int tank) {
		int capacity = handler.getTankCapacity(tank);
		if (capacity < 0) throw new IllegalStateException("negative fluid tank capacity");
		return capacity;
	}

	private static Entry nativeEntry(IFluidHandler handler, int tank) {
		@Nullable FluidStack stack = handler.getFluidInTank(tank);
		if (stack == null) throw new IllegalStateException("null native fluid stack");
		// Do not call isEmpty first: an invalid negative authoritative getter must fail, not vanish.
		int amount = stack.getAmount();
		InventoryQuantities.require(amount);
		if (amount == 0) return Entry.empty();
		return borrowed(stack.getFluid(), stack.getTag(), amount);
	}

	/** Only the scalar is converted here. The native tag is neither copied nor pruned before freezing. */
	static Entry borrowed(Fluid fluid, @Nullable CompoundTag tag, int millibuckets) {
		long amount = InventoryQuantities.fromMillibuckets(millibuckets);
		if (amount == 0) return Entry.empty();
		if (fluid == Fluids.EMPTY) throw new IllegalStateException("positive amount without native fluid");
		return new Entry(new Nbt(fluid, tag), amount);
	}

	/**
	 * Frozen public addressing evidence: exact state/BE, sided capability token and handler identity,
	 * plus every ordered tank capacity. Counts alone cannot keep an old route alive. An opaque handler
	 * may internally reorder equal-capacity tanks without changing any public metadata; that remains
	 * the native mixed-time placement limitation, not a certificate of atomicity or pooling.
	 */
	@NotNullByDefault
	private static final class HandlerAccess implements Access {
		private final Context context;
		private final World world;
		private final BlockState state;
		private final BlockEntity entity;
		private final LazyOptional<IFluidHandler> capability;
		private final IFluidHandler handler;
		private final int @Unmodifiable [] capacities;
		private final InventorySnapshotLayout layout;
		private final InventoryValidationBudget.Cost validationCost, readCost;

		/** capacities is exclusively transferred from resolve; it is never exposed or written again. */
		private HandlerAccess(Context context, World world, BlockState state, BlockEntity entity,
			LazyOptional<IFluidHandler> capability, IFluidHandler handler, int @Unmodifiable [] capacities,
			InventorySnapshotLayout layout) {
			this.context = context; this.world = world; this.state = state; this.entity = entity;
			this.capability = capability; this.handler = handler; this.capacities = capacities; this.layout = layout;
			// One current root/route and all ordered metadata addresses, with no descriptor rebuild.
			validationCost = new InventoryValidationBudget.Cost(2048, 2048, 272L + 16L * capacities.length);
			readCost = validationCost.times(2).plus(READ_OVERHEAD);
		}
		private void gate() { context.require(context.root()); }
		@Override public InventorySnapshotLayout layout() { gate(); return layout; }
		@Override public Optional<String> alias() { gate(); return Optional.empty(); }
		@Override public boolean stableCursor() { gate(); return true; }
		@Override public int slots() { gate(); return capacities.length; }
		@Override public InventoryValidationBudget.Cost validationCost() { return validationCost; }
		@Override public InventoryValidationBudget.Cost readCost() { return readCost; }

		private boolean currentRoute() {
			BlockPos root = context.root();
			gate(); boolean loaded = world.loaded(root); gate();
			if (!loaded) return false;
			BlockState currentState = world.state(root); gate();
			if (currentState != state) return false;
			@Nullable BlockEntity currentEntity = world.blockEntity(root); gate();
			if (currentEntity != entity || entity.isRemoved()) return false;
			gate(); LazyOptional<IFluidHandler> currentCapability = world.capability(entity, context.face()); gate();
			if (currentCapability != capability) return false;
			Optional<IFluidHandler> currentHandler = capability.resolve(); gate();
			return currentHandler.orElse(null) == handler;
		}
		@Override public boolean valid() {
			if (!currentRoute()) return false;
			int count = handler.getTanks(); gate();
			if (count != capacities.length) return false;
			for (int tank = 0; tank < capacities.length; tank++) {
				gate(); int currentCapacity = capacity(handler, tank); gate();
				if (currentCapacity != capacities[tank]) return false;
			}
			if (!currentRoute()) return false; // A metadata callback may have replaced the public route too.
			// Capacity and closing-route callbacks can change the count without changing earlier values.
			// Recheck once, not another capacity walk, before accepting this frozen address graph.
			int closingCount = handler.getTanks(); gate();
			return closingCount == capacities.length;
		}
		private void requireCurrent() {
			if (!valid()) throw new InventorySourceUnavailableException("fluid capability route/layout changed");
		}

		@Override public boolean consume(int slot, Visitor visitor) {
			Objects.requireNonNull(visitor); Objects.checkIndex(slot, capacities.length);
			requireCurrent(); gate();
			// No authority or provider metadata callback may reuse the borrowed resident before this.
			boolean more = visitor.visit(world.content(handler, slot));
			requireCurrent();
			return more;
		}

		@Override public Visit observe(int limit, Visitor visitor) {
			if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
			Objects.requireNonNull(visitor);
			requireCurrent(); // Even a zero-tank/zero-limit pass needs fresh admitted evidence.
			int bound = Math.min(capacities.length, limit);
			for (int tank = 0; tank < bound; tank++) if (!consume(tank, visitor)) return new Visit(tank + 1, false);
			return new Visit(bound, capacities.length <= limit);
		}
	}
}
