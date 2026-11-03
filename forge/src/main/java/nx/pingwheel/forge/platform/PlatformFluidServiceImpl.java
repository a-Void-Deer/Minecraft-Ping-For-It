package nx.pingwheel.forge.platform;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

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
import nx.pingwheel.common.platform.FluidAddressWitness;
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

	// Probe owns fixed wrappers/witnesses/profiles only. Native capability/stack getter and VM class
	// metadata internals are external-trusted; source callback work is composed separately.
	private static final OperationProfile PROBE = profile(new InventoryValidationBudget.Cost(8192, 8192, 1024), 8, 0);
	// Includes cold fixed-field metadata bookkeeping, primitive amount access and failure wrapping.
	// VM reflection internals, like the native getter internals, remain external-trusted.
	private static final InventoryValidationBudget.Cost READ_OVERHEAD = new InventoryValidationBudget.Cost(1024, 1024, 128);
	private static final int MAX_REGISTRY_ID_CHARS = 256;
	private static final OperationProfile WITNESS_CAPTURE = FluidAddressWitness.profile(WitnessUse.CAPTURE, 3);
	private static final OperationProfile WITNESS_COMPARE = FluidAddressWitness.profile(WitnessUse.COMPARE, 3);

	/** Face/punctuation headroom plus at most ten nonnegative decimal digits and a comma per tank. */
	private static int capacityDescriptionBound(int tanks) { return Math.addExact(32, Math.multiplyExact(11, tanks)); }
	private static InventoryValidationBudget.Cost capacityDescriptionCost(int tanks) {
		long chars = capacityDescriptionBound(tanks);
		// Builder/storage, decimal append, exact output String/copy and conservative coder overlap.
		return new InventoryValidationBudget.Cost(512 + 6 * chars, 512 + 6 * chars, 128 + 8 * chars);
	}

	@Override public OperationProfile probeProfile() { return PROBE; }
	@Override public Optional<Probe> probe(Context context, Operation operation) {
		Objects.requireNonNull(context);
		Objects.requireNonNull(operation).allowance().debit(PROBE.ownedCost());
		return probeAdmitted(context, new MinecraftWorld(context.level()), operation);
	}
	private static OperationProfile profile(InventoryValidationBudget.Cost cost, long gates, long retained) {
		return new OperationProfile(cost, gates, gates, retained);
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

	/** Same gated production probe, with replaceable native lookup for headless tests. */
	static Optional<Probe> probe(Context context, World world, Operation operation) {
		Objects.requireNonNull(context); Objects.requireNonNull(world);
		Objects.requireNonNull(operation).allowance().debit(PROBE.ownedCost());
		return probeAdmitted(context, world, operation);
	}
	private static Optional<Probe> probeAdmitted(Context context, World world, Operation operation) {
		BlockPos root = context.root();
		context.require(root, operation);
		boolean loaded = world.loaded(root);
		context.require(root, operation);
		if (!loaded) return Optional.empty();
		BlockState state = world.state(root);
		context.require(root, operation);
		@Nullable BlockEntity entity = world.blockEntity(root);
		context.require(root, operation);
		if (entity == null || entity.isRemoved()) return Optional.empty();
		context.require(root, operation);
		LazyOptional<IFluidHandler> capability = Objects.requireNonNull(world.capability(entity, context.face()));
		context.require(root, operation);
		Optional<IFluidHandler> resolved = capability.resolve();
		context.require(root, operation);
		if (resolved.isEmpty()) return Optional.empty();
		IFluidHandler handler = resolved.orElseThrow();
		int tanks = handler.getTanks();
		context.require(root, operation);
		if (tanks < 0 || tanks > InventorySourceAccess.MAX_SLOTS)
			throw new IllegalStateException("fluid tank address bound");
		return Optional.of(new HandlerProbe(new Route(context, world, state, entity, capability, handler), tanks, operation));
	}

	@NotNullByDefault
	private static final class HandlerProbe implements Probe {
		private @Nullable Route route;
		private @Nullable Operation origin;
		private final Shape shape;
		private final OperationProfile finishProfile;
		private HandlerProbe(Route route, int tanks, Operation origin) {
			this.route = route; this.origin = origin; shape = new Shape(1, OptionalInt.of(tanks), true);
			// capacities, ordered boxed mapping/copies/seen-array, bounded strings, DTOs and cached access
			// profiles. Only the known N contributes; no whole-slot-limit reservation or eager contents.
			long bytes = 8192L + 256L * tanks;
			finishProfile = profile(new InventoryValidationBudget.Cost(bytes, bytes, 4096L + 64L * tanks)
				.overlap(capacityDescriptionCost(tanks)), 27L + 4L * tanks,
				8192L + 64L * tanks + 128L + 2L * capacityDescriptionBound(tanks));
		}
		@Override public Shape shape() { return shape; }
		@Override public OperationProfile finishProfile() { return finishProfile; }
		@Override public Optional<Access> finish(Admission admission) {
			if (route == null || origin == null) throw new IllegalStateException("fluid probe closed or consumed");
			Route held = route;
			Operation original = origin;
			route = null; origin = null; // single use even when admission or a callback fails
			var allowance = original.allowance(); // probe must finish while its operation is still live
			try (var operation = Objects.requireNonNull(admission).enter(finishProfile)) {
				if (operation.allowance() != allowance) throw new IllegalStateException("fluid probe changed caller allowance");
				operation.allowance().debit(finishProfile.ownedCost());
				int tanks = shape.indexedSlots().orElseThrow();
				if (!held.current(operation)) throw new InventorySourceUnavailableException("fluid capability route changed before finish");
				int count = held.handler().getTanks(); held.gate(operation);
				if (count != tanks) throw new InventorySourceUnavailableException("fluid tank count changed before finish");
				int descriptionBound = capacityDescriptionBound(tanks);
				if (descriptionBound > 65536) throw new InventorySourceUnavailableException("fluid capacity description bound");
				var description = new StringBuilder(descriptionBound).append(held.context().face().getName()).append(";capacities=");
				int[] capacities = new int[tanks];
				var addresses = new ArrayList<Integer>(tanks);
				for (int tank = 0; tank < tanks; tank++) {
					held.gate(operation); capacities[tank] = capacity(held.handler(), tank); held.gate(operation);
					addresses.add(tank);
					description.append(capacities[tank]).append(',');
				}
				held.gate(operation);
				String blockId = boundedRegistryId(BuiltInRegistries.BLOCK.getKey(held.state().getBlock()));
				held.gate(operation);
				@Nullable ResourceLocation entityId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(held.entity().getType());
				held.gate(operation);
				if (entityId == null) throw new InventorySourceUnavailableException("unregistered fluid block entity");
				String blockEntityId = boundedRegistryId(entityId);
				String provider = held.handler().getClass().getName();
				if (provider.length() > 1024) throw new InventorySourceUnavailableException("fluid provider class length bound");
				var routeInfo = new RouteInfo(TopologyEvidence.PUBLIC_ADDRESS_ONLY, provider);
				@Unmodifiable List<Integer> mapping = List.copyOf(addresses);
				var layout = new InventorySnapshotLayout("forge:fluid_handler", description.toString(), null, List.of(
					new InventorySnapshotLayout.Member(held.context().root(), blockId, blockEntityId, "root", List.of(
						new InventorySnapshotLayout.Segment("tanks", ADDRESS_SCHEMA, tanks, mapping)))));
				var access = new HandlerAccess(held, capacities, layout, routeInfo);
				access.requireCurrent(held, operation);
				return Optional.of(access);
			}
		}
		@Override public void close() { route = null; origin = null; }
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
		// Native constructors/NBT/setAmount retain negatives, but getAmount normalizes them to zero.
		InventoryQuantities.require(retainedAmount(stack));
		// Keep public getter overrides and native empty normalization for otherwise valid amounts.
		int amount = stack.getAmount();
		InventoryQuantities.require(amount);
		if (amount == 0) return Entry.empty();
		return borrowed(stack.getFluid(), stack.getTag(), amount);
	}

	private static int retainedAmount(FluidStack stack) {
		var metadata = RetainedAmount.METADATA;
		@Nullable Field field = metadata.field();
		if (field == null) throw new IllegalStateException("native fluid amount field unavailable", metadata.failure());
		try { return field.getInt(stack); }
		catch (IllegalAccessException | RuntimeException | LinkageError unavailable) {
			throw new IllegalStateException("native fluid amount read unavailable", unavailable);
		}
	}
	/** One fixed native declaration/failure per loader; no stack, handler, world or amount is cached. */
	@NotNullByDefault
	private record RetainedAmount(@Nullable Field field, @Nullable Throwable failure) {
		private static final RetainedAmount METADATA = load();
		private static RetainedAmount load() {
			try {
				Field field = FluidStack.class.getDeclaredField("amount");
				if (field.getType() != int.class || Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible())
					throw new IllegalStateException("unsupported native fluid amount declaration");
				return new RetainedAmount(field, null);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
				// Do not poison class initialization or retry metadata discovery at every address.
				return new RetainedAmount(null, unavailable);
			}
		}
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
	private record Route(Context context, World world, BlockState state, BlockEntity entity,
		LazyOptional<IFluidHandler> capability, IFluidHandler handler) {
		private void gate(Operation operation) { context.require(context.root(), operation); }
		private boolean current(Operation operation) {
			BlockPos root = context.root();
			gate(operation); boolean loaded = world.loaded(root); gate(operation);
			if (!loaded) return false;
			BlockState currentState = world.state(root); gate(operation);
			if (currentState != state) return false;
			@Nullable BlockEntity currentEntity = world.blockEntity(root); gate(operation);
			if (currentEntity != entity || entity.isRemoved()) return false;
			gate(operation); LazyOptional<IFluidHandler> currentCapability = world.capability(entity, context.face()); gate(operation);
			if (currentCapability != capability) return false;
			Optional<IFluidHandler> currentHandler = capability.resolve(); gate(operation);
			return currentHandler.orElse(null) == handler;
		}
	}

	@NotNullByDefault
	private static final class HandlerAccess implements Access {
		private @Nullable Route route;
		private final int @Unmodifiable [] capacities;
		private final InventorySnapshotLayout layout;
		private final RouteInfo routeInfo;
		private final OperationProfile validationProfile, readProfile;
		private final InventoryValidationBudget.Cost validationCost;

		/** capacities is exclusively transferred from finish; never exposed or written again. */
		private HandlerAccess(Route route, int @Unmodifiable [] capacities, InventorySnapshotLayout layout, RouteInfo routeInfo) {
			this.route = route; this.capacities = capacities; this.layout = layout; this.routeInfo = routeInfo;
			// One current root/route and all ordered metadata addresses, with no descriptor rebuild.
			validationCost = new InventoryValidationBudget.Cost(2048, 2048, 272L + 16L * capacities.length);
			validationProfile = profile(validationCost, 16L + 2L * capacities.length, 0);
			readProfile = profile(validationCost.times(2).plus(READ_OVERHEAD), 33L + 4L * capacities.length, 0);
		}
		@Override public InventorySnapshotLayout layout() { return layout; }
		@Override public Optional<String> alias() { return Optional.empty(); }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return capacities.length; }
		@Override public RouteInfo routeInfo() { return routeInfo; }
		@Override public OperationProfile witnessProfile(WitnessUse use) {
			return Objects.requireNonNull(use) == WitnessUse.CAPTURE ? WITNESS_CAPTURE : WITNESS_COMPARE;
		}
		@Override public FluidAddressWitness captureWitness(Operation operation) {
			Route held = requireOpen();
			Objects.requireNonNull(operation).allowance().requireAvailable(WITNESS_CAPTURE.ownedCost());
			return FluidAddressWitness.capture(FluidAddressWitness.Schema.FORGE_CAPABILITY_V1,
				new Object[] {held.state(), held.capability(), held.handler()}, operation);
		}
		@Override public WitnessMatch compareWitness(FluidAddressWitness expected, Operation operation) {
			Objects.requireNonNull(expected); Route held = requireOpen();
			Objects.requireNonNull(operation).allowance().requireAvailable(WITNESS_COMPARE.ownedCost());
			return expected.compare(FluidAddressWitness.Schema.FORGE_CAPABILITY_V1,
				new Object[] {held.state(), held.capability(), held.handler()}, operation);
		}
		@Override public OperationProfile validationProfile() { return validationProfile; }
		@Override public OperationProfile readProfile() { return readProfile; }
		@Override public OperationProfile observationProfile(int limit) {
			return validationProfile.plus(Access.super.observationProfile(limit));
		}
		private Route requireOpen() {
			if (route == null) throw new IllegalStateException("fluid access closed");
			return route;
		}
		@Override public boolean valid(Operation operation) {
			Route held = requireOpen(); operation.allowance().debit(validationCost);
			boolean valid = current(held, operation);
			return route == held && valid;
		}
		private boolean current(Route held, Operation operation) {
			if (!held.current(operation)) return false;
			int count = held.handler().getTanks(); held.gate(operation);
			if (count != capacities.length) return false;
			for (int tank = 0; tank < capacities.length; tank++) {
				held.gate(operation); int currentCapacity = capacity(held.handler(), tank); held.gate(operation);
				if (currentCapacity != capacities[tank]) return false;
			}
			if (!held.current(operation)) return false; // A metadata callback may have replaced the public route too.
			// Capacity and closing-route callbacks can change the count without changing earlier values.
			// Recheck once, not another capacity walk, before accepting this frozen address graph.
			int closingCount = held.handler().getTanks(); held.gate(operation);
			return closingCount == capacities.length;
		}
		private void requireCurrent(Route held, Operation operation) {
			if (route != held || !current(held, operation) || route != held)
				throw new InventorySourceUnavailableException("fluid capability route/layout changed");
		}

		@Override public boolean consume(int slot, Visitor visitor, Operation operation) {
			Objects.requireNonNull(visitor); Objects.checkIndex(slot, capacities.length);
			Route held = requireOpen(); operation.allowance().debit(readProfile.ownedCost());
			requireCurrent(held, operation); held.gate(operation);
			if (route != held) throw new InventorySourceUnavailableException("fluid access closed during read");
			// No authority or provider metadata callback may reuse the borrowed resident before this.
			boolean more = visitor.visit(held.world().content(held.handler(), slot));
			requireCurrent(held, operation);
			return more;
		}

		@Override public Visit observe(int limit, Visitor visitor, Operation operation) {
			if (limit < 0 || limit > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
			Objects.requireNonNull(visitor);
			if (!valid(operation)) throw new InventorySourceUnavailableException("fluid capability route/layout changed");
			return Access.super.observe(limit, visitor, operation);
		}
		@Override public void close() { route = null; }
	}
}
