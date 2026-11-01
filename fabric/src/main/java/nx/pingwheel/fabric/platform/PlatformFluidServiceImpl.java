package nx.pingwheel.fabric.platform;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Exact-face, read-only Fabric Transfer fluid access. Native lookup, iterator and getter internals
 * are external-trusted, not certified arbitrary handler work or a multipart discovery mechanism.
 * Indexed continuity additionally requires the original route and EVERY ordered slot's bounded
 * native underlying-address identity; equal wrappers, counts or content versions cannot
 * prove that mapping. Underlying views supply metadata only, never a bypass for wrapper contents.
 */
@NotNullByDefault
public final class PlatformFluidServiceImpl implements IPlatformFluidService {

	private static final int MAX_SLOTS = InventorySourceAccess.MAX_SLOTS;
	/** Includes the terminal self-address. Cycles and longer/expanding metadata chains fail closed. */
	static final int MAX_UNDERLYING_VIEWS = 32;
	// Owned layout copies, boxing/seen arrays, access/wrapper records and one closing route check.
	// Callback internals and source-owned Context authority work have their separate trust/admission.
	private static final InventoryValidationBudget.Cost RESOLUTION = cost(16384L + 256L * MAX_SLOTS,
		4096L + (32L + 24L * MAX_UNDERLYING_VIEWS) * MAX_SLOTS);
	private final Lookup lookup;

	public PlatformFluidServiceImpl() {
		this((level, position, face) -> FluidStorage.SIDED.find(level, position, face));
	}

	/** The native lookup is injected only to exercise the production gates without a loader launch. */
	PlatformFluidServiceImpl(Lookup lookup) { this.lookup = Objects.requireNonNull(lookup); }

	@NotNullByDefault
	@FunctionalInterface
	interface Lookup {
		@Nullable Storage<FluidVariant> find(ServerLevel level, BlockPos position, Direction face);
	}

	@Override public InventoryValidationBudget.Cost resolutionCost() { return RESOLUTION; }

	@Override
	public Optional<Access> resolve(Context context) {
		Objects.requireNonNull(context);
		context.require(context.root());
		boolean loaded = context.level().isLoaded(context.root());
		context.require(context.root());
		if (!loaded) return Optional.empty();
		BlockState state = context.level().getBlockState(context.root());
		context.require(context.root());
		if (state.getBlock() instanceof AbstractCauldronBlock) return Optional.empty();
		@Nullable BlockEntity entity = context.level().getBlockEntity(context.root());
		context.require(context.root());
		if (entity == null || pendingLoot(context, entity)) return Optional.empty();
		context.require(context.root());
		@Nullable Storage<FluidVariant> storage = lookup.find(context.level(), context.root(), context.face());
		context.require(context.root());
		if (storage == null) return Optional.empty();
		var access = new TransferAccess(context, lookup, state, entity, storage);
		return access.valid() ? Optional.of(access) : Optional.empty();
	}

	private static InventoryValidationBudget.Cost cost(long bytes, long work) {
		return new InventoryValidationBudget.Cost(bytes, bytes, work);
	}

	private static boolean pendingLoot(Context context, BlockEntity entity) {
		context.require(context.root());
		boolean pending = entity instanceof RandomizableContainer container && container.getLootTable() != null;
		context.require(context.root());
		return pending;
	}

	static String registryId(@Nullable ResourceLocation id) {
		if (id == null || (long) id.getNamespace().length() + id.getPath().length() + 1 > 256)
			throw new InventorySourceUnavailableException("fluid source registry identity unavailable");
		return id.toString();
	}

	@NotNullByDefault
	private static final class TransferAccess implements Access {
		private final Context context;
		private final Lookup lookup;
		private final BlockState state;
		private final BlockEntity entity;
		private final Storage<FluidVariant> storage;
		// Server-local ADDRESS witnesses only. Wrappers may be replaced, but the ordered physical
		// addresses must not change. Reads use the current acquired wrapper, never these witnesses.
		private final @Unmodifiable List<StorageView<FluidVariant>> underlyingAddresses;
		private final boolean indexed;
		private final InventorySnapshotLayout layout;
		private final InventoryValidationBudget.Cost validation, read;

		private TransferAccess(Context context, Lookup lookup, BlockState state, BlockEntity entity, Storage<FluidVariant> storage) {
			this.context = context; this.lookup = lookup; this.state = state; this.entity = entity; this.storage = storage;
			indexed = storage instanceof SlottedStorage<?>;
			var underlying = new ArrayList<StorageView<FluidVariant>>();
			var mapping = new ArrayList<Integer>();
			if (storage instanceof SlottedStorage<FluidVariant> slotted) {
				gate(); int count = slotted.getSlotCount(); gate();
				if (count < 0 || count > MAX_SLOTS) throw new InventorySourceUnavailableException("fluid slot count outside bound");
				underlying.ensureCapacity(count); mapping.ensureCapacity(count);
				for (int slot = 0; slot < count; slot++) {
					gate(); @Nullable StorageView<FluidVariant> view = slotted.getSlot(slot); gate();
					if (view == null) throw new InventorySourceUnavailableException("null fluid slot address");
					underlying.add(underlyingAddress(view)); mapping.add(slot);
				}
			}
			underlyingAddresses = List.copyOf(underlying);
			gate(); String blockId = registryId(BuiltInRegistries.BLOCK.getKey(state.getBlock())); gate();
			String entityId = registryId(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType())); gate();
			var segment = new InventorySnapshotLayout.Segment(indexed ? "fabric:slots" : "fabric:views", ADDRESS_SCHEMA, underlyingAddresses.size(), mapping);
			layout = new InventorySnapshotLayout("fabric:fluid_transfer", context.face().getSerializedName(), context.root(),
				List.of(new InventorySnapshotLayout.Member(context.root(), blockId, entityId, "root", List.of(segment))));
			// A changed chain can grow to the finite bound even when the admitted one was shallow.
			// No chain array/set is allocated during validation, and no content version is consulted.
			validation = cost(2048, 128L + (12L + 12L * MAX_UNDERLYING_VIEWS) * underlyingAddresses.size());
			// Two complete route/layout checks, one content sequence, iterator metadata and wrapper gates.
			read = validation.times(2).plus(cost(512, 128L + 12L * MAX_UNDERLYING_VIEWS));
		}

		private void gate() { context.require(context.root()); }

		private StorageView<FluidVariant> underlyingAddress(StorageView<FluidVariant> view) {
			for (int depth = 0; depth < MAX_UNDERLYING_VIEWS; depth++) {
				gate(); @Nullable StorageView<FluidVariant> underlying = view.getUnderlyingView(); gate();
				if (underlying == null) throw new InventorySourceUnavailableException("null fluid underlying address");
				if (underlying == view) return view;
				view = underlying;
			}
			throw new InventorySourceUnavailableException("fluid underlying address chain outside bound");
		}
		@Override public InventorySnapshotLayout layout() { gate(); return layout; }
		@Override public Optional<String> alias() { gate(); return Optional.empty(); }
		@Override public boolean stableCursor() { gate(); return indexed; }
		@Override public int slots() { gate(); return underlyingAddresses.size(); }
		@Override public InventoryValidationBudget.Cost validationCost() { return validation; }
		@Override public InventoryValidationBudget.Cost readCost() { return read; }
		@Override public InventoryValidationBudget.Cost observationCost(int limit) {
			checkLimit(limit);
			if (indexed) {
				int bound = Math.min(underlyingAddresses.size(), limit);
				return (bound == 0 ? validation : read.times(bound)).plus(cost(128, 32));
			}
			// Includes start/closure even when no view can be attempted. No final unadmitted peek.
			return validation.times(2).plus(cost(512, 64)).plus(read.times(limit));
		}

		@Override
		public boolean valid() { return check(-1, null).valid(); }

		/** No result allocation for a pure check; a read keeps only its current selected wrapper. */
		private Check check(int selected, @Nullable StorageView<FluidVariant> consumed) {
			gate(); boolean loaded = context.level().isLoaded(context.root()); gate();
			if (!loaded) return Check.INVALID;
			BlockState currentState = context.level().getBlockState(context.root()); gate();
			if (currentState.getBlock() != state.getBlock()) return Check.INVALID;
			@Nullable BlockEntity currentEntity = context.level().getBlockEntity(context.root()); gate();
			if (currentEntity != entity || entity.isRemoved() || pendingLoot(context, entity)) return Check.INVALID;
			gate(); @Nullable Storage<FluidVariant> current = lookup.find(context.level(), context.root(), context.face()); gate();
			if (current != storage) return Check.INVALID;
			@Nullable StorageView<FluidVariant> selectedView = null;
			if (storage instanceof SlottedStorage<FluidVariant> slotted) {
				gate(); int count = slotted.getSlotCount(); gate();
				if (count != underlyingAddresses.size()) return Check.INVALID;
				for (int slot = 0; slot < count; slot++) {
					// Acquire a read's selected wrapper LAST so no other provider metadata callback
					// can redirect that ephemeral wrapper between its address check and contents.
					if (slot == selected && consumed == null) continue;
					gate(); @Nullable StorageView<FluidVariant> view = slotted.getSlot(slot); gate();
					if (view == null || underlyingAddress(view) != underlyingAddresses.get(slot)) return Check.INVALID;
					// A new wrapper may replace the one just read. The consumed wrapper's own
					// address must still agree; do not let a fresh replacement hide its remap.
					if (slot == selected && consumed != null && view != consumed
						&& underlyingAddress(consumed) != underlyingAddresses.get(slot)) return Check.INVALID;
				}
				if (selected >= 0 && consumed == null) {
					gate(); selectedView = slotted.getSlot(selected); gate();
					if (selectedView == null || underlyingAddress(selectedView) != underlyingAddresses.get(selected)) return Check.INVALID;
				}
			}
			gate();
			return selected < 0 || consumed != null ? Check.VALID : new Check(true, Objects.requireNonNull(selectedView));
		}

		@NotNullByDefault
		private record Check(boolean valid, @Nullable StorageView<FluidVariant> selected) {
			private static final Check VALID = new Check(true, null), INVALID = new Check(false, null);
		}

		private void requireCurrent() {
			if (!valid()) throw new InventorySourceUnavailableException("fluid route or address mapping changed");
		}

		@Override
		public boolean consume(int slot, Visitor visitor) {
			Objects.requireNonNull(visitor);
			if (!indexed) throw new UnsupportedOperationException("fluid storage has no indexed cursor");
			Objects.checkIndex(slot, underlyingAddresses.size());
			Check current = check(slot, null);
			if (!current.valid()) throw new InventorySourceUnavailableException("fluid route or address mapping changed");
			StorageView<FluidVariant> selected = Objects.requireNonNull(current.selected());
			boolean more = consumeView(selected, visitor);
			if (!check(slot, selected).valid()) throw new InventorySourceUnavailableException("fluid route or address mapping changed");
			return more;
		}

		private boolean consumeView(@Nullable StorageView<FluidVariant> view, Visitor visitor) {
			gate();
			if (view == null) throw new InventorySourceUnavailableException("null fluid storage view");
			long amount = view.getAmount();
			if (amount < 0) throw new InventorySourceUnavailableException("negative fluid amount");
			if (amount == 0) return visitor.visit(Entry.empty());
			gate(); boolean blank = view.isResourceBlank();
			if (blank) throw new InventorySourceUnavailableException("positive fluid amount has a blank resource");
			gate(); @Nullable FluidVariant variant = view.getResource();
			if (variant == null) throw new InventorySourceUnavailableException("null fluid variant");
			gate(); @Nullable var fluid = variant.getFluid();
			if (fluid == null || fluid == Fluids.EMPTY) throw new InventorySourceUnavailableException("positive amount requires a fluid");
			gate(); @Nullable var patch = variant.getComponents();
			if (patch == null) throw new InventorySourceUnavailableException("null fluid component patch");
			// Fabric droplets already ARE U. Never convert through buckets, mB or an item exemplar.
			// No subsequent provider metadata callback may run before synchronous source detachment.
			return visitor.visit(new Entry(new Components(fluid, patch), amount));
		}

		@Override
		public Visit observe(int limit, Visitor visitor) {
			checkLimit(limit); Objects.requireNonNull(visitor);
			if (indexed) {
				int count = underlyingAddresses.size(), bound = Math.min(count, limit);
				// Each consume owns its fresh full pre/post topology check. No extra identical
				// opening/closing walk around the same indexed pass, and no retained wrapper page.
				if (bound == 0) requireCurrent();
				for (int slot = 0; slot < bound; slot++) if (!consume(slot, visitor)) return new Visit(slot + 1, false);
				return new Visit(bound, count <= limit);
			}
			requireCurrent();
			// Zero visits grants neither iterator creation nor an absence/completion claim.
			if (limit == 0) return new Visit(0, false);
			gate(); @Nullable Iterator<StorageView<FluidVariant>> iterator = storage.iterator(); gate();
			if (iterator == null) throw new InventorySourceUnavailableException("null fluid iterator");
			for (int visited = 0; visited < limit; visited++) {
				// hasNext/next may peek internally. Both belong to THIS admitted view attempt.
				gate(); boolean next = iterator.hasNext(); gate();
				if (!next) { requireCurrent(); return new Visit(visited, true); }
				gate(); @Nullable StorageView<FluidVariant> view = iterator.next(); gate();
				boolean more = consumeView(view, visitor);
				requireCurrent();
				if (!more) return new Visit(visited + 1, false);
			}
			// A final hasNext here could read the next view after the last paid visit.
			return new Visit(limit, false);
		}
	}

	private static void checkLimit(int limit) {
		if (limit < 0 || limit > MAX_SLOTS) throw new IllegalArgumentException("fluid observation limit");
	}
}
