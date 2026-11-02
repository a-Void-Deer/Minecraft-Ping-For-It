package nx.pingwheel.fabric.platform;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import nx.pingwheel.common.platform.FluidAddressWitness;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Exact-face read-only Transfer access. Native lookup/getter/iterator internals are external-trusted.
 * The original route and every ordered public underlying address must remain continuous. Underlying
 * views supply metadata only; contents always use the current admitted wrapper. No hidden structure,
 * cross-owner equivalence, content version or atomicity is certified by this generic route.
 */
@NotNullByDefault
public final class PlatformFluidServiceImpl implements IPlatformFluidService {
	private static final int MAX_SLOTS = InventorySourceAccess.MAX_SLOTS;
	/** Includes the terminal self-address; cycles/expanding chains stop at this finite bound. */
	static final int MAX_UNDERLYING_VIEWS = 32;
	private static final OperationProfile PROBE = profile(cost(4096, 4096), 12, 1024);
	private final Lookup lookup;

	public PlatformFluidServiceImpl() { this((level, position, face) -> FluidStorage.SIDED.find(level, position, face)); }
	PlatformFluidServiceImpl(Lookup lookup) { this.lookup = Objects.requireNonNull(lookup); }

	@NotNullByDefault
	@FunctionalInterface
	interface Lookup { @Nullable Storage<FluidVariant> find(ServerLevel level, BlockPos position, Direction face); }

	@Override public OperationProfile probeProfile() { return PROBE; }
	@Override public Optional<Probe> probe(Context context, Operation operation) {
		Objects.requireNonNull(context); operation.allowance().debit(PROBE.ownedCost());
		context.require(context.root(), operation);
		boolean loaded = context.level().isLoaded(context.root()); context.require(context.root(), operation);
		if (!loaded) return Optional.empty();
		BlockState state = context.level().getBlockState(context.root()); context.require(context.root(), operation);
		if (state.getBlock() instanceof AbstractCauldronBlock) return Optional.empty();
		@Nullable BlockEntity entity = context.level().getBlockEntity(context.root()); context.require(context.root(), operation);
		if (entity == null || pendingLoot(context, entity, operation)) return Optional.empty();
		context.require(context.root(), operation);
		@Nullable Storage<FluidVariant> storage = lookup.find(context.level(), context.root(), context.face()); context.require(context.root(), operation);
		if (storage == null) return Optional.empty();
		boolean indexed = storage instanceof SlottedStorage<?>;
		int count = 0;
		if (storage instanceof SlottedStorage<FluidVariant> slotted) {
			context.require(context.root(), operation); count = slotted.getSlotCount(); context.require(context.root(), operation);
			if (count < 0 || count > MAX_SLOTS) throw unavailable("fluid slot count outside bound");
		}
		context.require(context.root(), operation);
		int tick = context.level().getServer().getTickCount(); context.require(context.root(), operation);
		// Class.getName is VM metadata, not a provider callback or reflective graph discovery.
		// Refuse before scans/copies or diagnostics can expand an unbounded class name.
		String name = storage.getClass().getName();
		if (name.length() > 1024) throw unavailable("fluid provider class name outside bound");
		var route = new RouteInfo(TopologyEvidence.PUBLIC_ADDRESS_ONLY, name);
		var shape = new Shape(1, indexed ? OptionalInt.of(count) : OptionalInt.empty(), indexed);
		return Optional.of(new TransferProbe(new Binding(context, lookup, state, entity, storage), shape, route, operation, tick));
	}

	private static InventoryValidationBudget.Cost cost(long bytes, long work) { return new InventoryValidationBudget.Cost(bytes, bytes, work); }
	private static OperationProfile profile(InventoryValidationBudget.Cost owned, long gates, long retained) {
		return new OperationProfile(owned, gates, gates, retained);
	}
	private static InventorySourceUnavailableException unavailable(String message) { return new InventorySourceUnavailableException(message); }
	private static boolean pendingLoot(Context context, BlockEntity entity, Operation operation) {
		context.require(context.root(), operation);
		boolean pending = entity instanceof RandomizableContainer container && container.getLootTable() != null;
		context.require(context.root(), operation); return pending;
	}
	static String registryId(@Nullable ResourceLocation id) {
		if (id == null || (long) id.getNamespace().length() + id.getPath().length() + 1 > 256)
			throw unavailable("fluid source registry identity unavailable");
		return id.toString();
	}

	@NotNullByDefault
	private record Binding(Context context, Lookup lookup, BlockState state, BlockEntity entity, Storage<FluidVariant> storage) {
		void gate(Operation operation) { context.require(context.root(), operation); }
		/** Root/route/count only. No slot/underlying traversal or replacement layout allocation. */
		boolean current(Shape shape, Operation operation) {
			gate(operation); boolean loaded = context.level().isLoaded(context.root()); gate(operation);
			if (!loaded) return false;
			BlockState currentState = context.level().getBlockState(context.root()); gate(operation);
			if (currentState.getBlock() != state.getBlock()) return false;
			@Nullable BlockEntity currentEntity = context.level().getBlockEntity(context.root()); gate(operation);
			if (currentEntity != entity || entity.isRemoved() || pendingLoot(context, entity, operation)) return false;
			gate(operation); @Nullable Storage<FluidVariant> current = lookup.find(context.level(), context.root(), context.face()); gate(operation);
			if (current != storage) return false;
			if (storage instanceof SlottedStorage<FluidVariant> slotted) {
				gate(operation); int count = slotted.getSlotCount(); gate(operation);
				if (count != shape.indexedSlots().orElseThrow()) return false;
			}
			return true;
		}
		StorageView<FluidVariant> underlyingAddress(StorageView<FluidVariant> view, Operation operation) {
			for (int depth = 0; depth < MAX_UNDERLYING_VIEWS; depth++) {
				gate(operation); @Nullable StorageView<FluidVariant> underlying = view.getUnderlyingView(); gate(operation);
				if (underlying == null) throw unavailable("null fluid underlying address");
				if (underlying == view) return view;
				view = underlying;
			}
			throw unavailable("fluid underlying address chain outside bound");
		}
	}

	@NotNullByDefault
	private static final class TransferProbe implements Probe {
		private @Nullable Binding binding;
		private @Nullable Operation original;
		private final Shape shape;
		private final RouteInfo route;
		private final OperationProfile finish;
		private final int tick;
		private @Nullable Thread thread;
		private TransferProbe(Binding binding, Shape shape, RouteInfo route, Operation original, int tick) {
			this.binding = binding; this.shape = shape; this.route = route; this.original = original; this.tick = tick; thread = Thread.currentThread();
			int n = shape.indexedSlots().orElse(0);
			// Known-N DTO/boxing/seen arrays, both complete address walks and their gate dispatch.
			finish = profile(cost(8192L + 192L * n, 4096L + 792L * n), 27L + 132L * n, 4096L + 56L * n);
		}
		@Override public Shape shape() { return shape; }
		@Override public OperationProfile finishProfile() { return finish; }
		@Override public Optional<Access> finish(Admission admission) {
			if (binding == null || original == null || thread == null) throw new IllegalStateException("fluid probe closed or consumed");
			Binding held = Objects.requireNonNull(binding, "fluid probe closed or consumed");
			Operation origin = Objects.requireNonNull(original, "fluid probe closed or consumed");
			Thread originalThread = Objects.requireNonNull(thread, "fluid probe closed or consumed");
			close(); // single use, including admission refusal and exceptional partial construction
			InventoryValidationBudget.Allowance originalAllowance = origin.allowance();
			try (Operation operation = Objects.requireNonNull(admission).enter(finish)) {
				if (operation.allowance() != originalAllowance) throw new IllegalStateException("fluid finish changed caller allowance");
				operation.allowance().debit(finish.ownedCost());
				if (Thread.currentThread() != originalThread) throw unavailable("fluid probe crossed server operation");
				held.gate(operation); int currentTick = held.context().level().getServer().getTickCount(); held.gate(operation);
				if (currentTick != tick) throw unavailable("fluid probe crossed server tick");
				if (!held.current(shape, operation)) throw unavailable("fluid probe route or shape changed");
				held.gate(operation);
				var access = new TransferAccess(held, shape, route, operation);
				try {
					if (!access.check(-1, null, operation).valid()) { access.close(); throw unavailable("fluid route or address mapping changed"); }
					return Optional.of(access);
				} catch (RuntimeException | Error failure) { access.close(); throw failure; }
			}
		}
		@Override public void close() { binding = null; original = null; thread = null; }
	}

	@NotNullByDefault
	private static final class TransferAccess implements Access {
		private @Nullable Binding binding;
		private @Unmodifiable List<StorageView<FluidVariant>> underlyingAddresses;
		private final Shape shape;
		private final InventorySnapshotLayout layout;
		private final RouteInfo route;
		private final OperationProfile validation, read;
		private TransferAccess(Binding binding, Shape shape, RouteInfo route, Operation operation) {
			this.binding = binding; this.shape = shape; this.route = route;
			int count = shape.indexedSlots().orElse(0);
			var underlying = new ArrayList<StorageView<FluidVariant>>(count);
			var mapping = new ArrayList<Integer>(count);
			if (binding.storage() instanceof SlottedStorage<FluidVariant> slotted) {
				for (int slot = 0; slot < count; slot++) {
					binding.gate(operation); @Nullable StorageView<FluidVariant> view = slotted.getSlot(slot); binding.gate(operation);
					if (view == null) throw unavailable("null fluid slot address");
					underlying.add(binding.underlyingAddress(view, operation)); mapping.add(slot);
				}
			}
			underlyingAddresses = List.copyOf(underlying);
			binding.gate(operation); String blockId = registryId(BuiltInRegistries.BLOCK.getKey(binding.state().getBlock())); binding.gate(operation);
			String entityId = registryId(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(binding.entity().getType())); binding.gate(operation);
			var segment = new InventorySnapshotLayout.Segment(shape.stableCursor() ? "fabric:slots" : "fabric:views", ADDRESS_SCHEMA, count, mapping);
			layout = new InventorySnapshotLayout("fabric:fluid_transfer", binding.context().face().getSerializedName(), binding.context().root(),
				List.of(new InventorySnapshotLayout.Member(binding.context().root(), blockId, entityId, "root", List.of(segment))));
			validation = profile(cost(2048, 128L + 396L * count), 11L + 66L * count, 0);
			read = validation.times(2).plus(profile(cost(512, 512), 5L + 2L * MAX_UNDERLYING_VIEWS, 0));
		}
		@Override public InventorySnapshotLayout layout() { return layout; }
		@Override public Optional<String> alias() { return Optional.empty(); }
		@Override public boolean stableCursor() { return shape.stableCursor(); }
		@Override public int slots() { return shape.indexedSlots().orElse(0); }
		@Override public RouteInfo routeInfo() { return route; }
		@Override public OperationProfile witnessProfile(WitnessUse use) { return FluidAddressWitness.profile(use, slots() + 1); }
		private Object[] witnessVector(WitnessUse use, Operation operation) {
			Binding held = held(operation);
			// The helper pays for this temporary vector too, and performs the single owned debit.
			operation.allowance().requireAvailable(witnessProfile(use).ownedCost());
			Object[] current = new Object[slots() + 1];
			current[0] = held.storage();
			for (int slot = 0; slot < slots(); slot++) current[slot + 1] = underlyingAddresses.get(slot);
			return current;
		}
		@Override public FluidAddressWitness captureWitness(Operation operation) {
			return FluidAddressWitness.capture(FluidAddressWitness.Schema.FABRIC_TRANSFER_V1, witnessVector(WitnessUse.CAPTURE, operation), operation);
		}
		@Override public WitnessMatch compareWitness(FluidAddressWitness expected, Operation operation) {
			Objects.requireNonNull(expected);
			return expected.compare(FluidAddressWitness.Schema.FABRIC_TRANSFER_V1, witnessVector(WitnessUse.COMPARE, operation), operation);
		}
		@Override public OperationProfile validationProfile() { return validation; }
		@Override public OperationProfile readProfile() { return read; }
		@Override public OperationProfile observationProfile(int limit) {
			checkLimit(limit);
			if (stableCursor()) {
				int bound = Math.min(slots(), limit);
				return (bound == 0 ? validation : read.times(bound)).plus(profile(cost(128, 32), 0, 0));
			}
			return validation.times(limit + 1L).plus(profile(cost(512L + 128L * limit, 64L + 128L * limit),
				2L + 9L * limit, 0));
		}
		private Binding held(Operation operation) {
			operation.allowance();
			if (binding == null) throw new IllegalStateException("fluid access closed");
			return binding;
		}
		@Override public boolean valid(Operation operation) {
			held(operation); operation.allowance().debit(validation.ownedCost()); return check(-1, null, operation).valid();
		}
		/** Pure checks allocate no result; a read retains only its current selected wrapper on the stack. */
		private Check check(int selected, @Nullable StorageView<FluidVariant> consumed, Operation operation) {
			Binding held = held(operation);
			if (!held.current(shape, operation)) return Check.INVALID;
			@Nullable StorageView<FluidVariant> selectedView = null;
			if (held.storage() instanceof SlottedStorage<FluidVariant> slotted) {
				for (int slot = 0; slot < slots(); slot++) {
					// Acquire the selected wrapper LAST, before contents, not a retained old wrapper.
					if (slot == selected && consumed == null) continue;
					held.gate(operation); @Nullable StorageView<FluidVariant> view = slotted.getSlot(slot); held.gate(operation);
					if (view == null || held.underlyingAddress(view, operation) != underlyingAddresses.get(slot)) return Check.INVALID;
					if (slot == selected && consumed != null && view != consumed
						&& held.underlyingAddress(consumed, operation) != underlyingAddresses.get(slot)) return Check.INVALID;
				}
				if (selected >= 0 && consumed == null) {
					held.gate(operation); selectedView = slotted.getSlot(selected); held.gate(operation);
					if (selectedView == null || held.underlyingAddress(selectedView, operation) != underlyingAddresses.get(selected)) return Check.INVALID;
				}
			}
			held.gate(operation);
			return selected < 0 || consumed != null ? Check.VALID : new Check(true, Objects.requireNonNull(selectedView));
		}
		@NotNullByDefault
		private record Check(boolean valid, @Nullable StorageView<FluidVariant> selected) {
			private static final Check VALID = new Check(true, null), INVALID = new Check(false, null);
		}
		private void requireCurrent(Operation operation) { if (!check(-1, null, operation).valid()) throw unavailable("fluid route or address mapping changed"); }
		@Override public boolean consume(int slot, Visitor visitor, Operation operation) {
			Objects.requireNonNull(visitor); held(operation);
			if (!stableCursor()) throw new UnsupportedOperationException("fluid storage has no indexed cursor");
			Objects.checkIndex(slot, slots()); operation.allowance().debit(read.ownedCost());
			Check current = check(slot, null, operation);
			if (!current.valid()) throw unavailable("fluid route or address mapping changed");
			StorageView<FluidVariant> selected = Objects.requireNonNull(current.selected());
			boolean more = consumeView(selected, visitor, operation);
			if (!check(slot, selected, operation).valid()) throw unavailable("fluid route or address mapping changed");
			return more;
		}
		private boolean consumeView(@Nullable StorageView<FluidVariant> view, Visitor visitor, Operation operation) {
			Binding held = held(operation); held.gate(operation);
			if (view == null) throw unavailable("null fluid storage view");
			long amount = view.getAmount();
			if (amount < 0) throw unavailable("negative fluid amount");
			if (amount == 0) return visitor.visit(Entry.empty());
			held.gate(operation); boolean blank = view.isResourceBlank();
			if (blank) throw unavailable("positive fluid amount has a blank resource");
			held.gate(operation); @Nullable FluidVariant variant = view.getResource();
			if (variant == null) throw unavailable("null fluid variant");
			held.gate(operation); @Nullable var fluid = variant.getFluid();
			if (fluid == null || fluid == Fluids.EMPTY) throw unavailable("positive amount requires a fluid");
			held.gate(operation); @Nullable var patch = variant.getComponents();
			if (patch == null) throw unavailable("null fluid component patch");
			// Fabric droplets ARE U. The source must detach before any later provider/gate callback.
			return visitor.visit(new Entry(new Components(fluid, patch), amount));
		}
		@Override public Visit observe(int limit, Visitor visitor, Operation operation) {
			checkLimit(limit); Objects.requireNonNull(visitor); Binding held = held(operation);
			if (stableCursor()) {
				operation.allowance().debit(cost(128, 32));
				int bound = Math.min(slots(), limit);
				if (bound == 0) { operation.allowance().debit(validation.ownedCost()); requireCurrent(operation); }
				for (int slot = 0; slot < bound; slot++) if (!consume(slot, visitor, operation)) return new Visit(slot + 1, false);
				return new Visit(bound, slots() <= limit);
			}
			operation.allowance().debit(observationProfile(limit).ownedCost()); requireCurrent(operation);
			if (limit == 0) return new Visit(0, false);
			held.gate(operation); @Nullable Iterator<StorageView<FluidVariant>> iterator = held.storage().iterator(); held.gate(operation);
			if (iterator == null) throw unavailable("null fluid iterator");
			for (int visited = 0; visited < limit; visited++) {
				held.gate(operation); boolean next = iterator.hasNext(); held.gate(operation);
				if (!next) { requireCurrent(operation); return new Visit(visited, true); }
				held.gate(operation); @Nullable StorageView<FluidVariant> view = iterator.next(); held.gate(operation);
				boolean more = consumeView(view, visitor, operation); requireCurrent(operation);
				if (!more) return new Visit(visited + 1, false);
			}
			// No final unpaid hasNext: it can peek at a view beyond this admission.
			return new Visit(limit, false);
		}
		@Override public void close() { binding = null; underlyingAddresses = List.of(); }
	}
	private static void checkLimit(int limit) {
		if (limit < 0 || limit > MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
	}
}
