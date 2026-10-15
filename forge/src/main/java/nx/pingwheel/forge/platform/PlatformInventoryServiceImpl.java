package nx.pingwheel.forge.platform;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.SidedInvWrapper;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;

import nx.pingwheel.common.platform.IPlatformInventoryService;

/**
 * Forge item-handler provider for the platform inventory bridge.
 *
 * <p>The block entity resolves its own item-handler capability; there is no
 * level-level capability assumption. Pending loot is rejected before the
 * capability is resolved so no wrapper can trigger generation, and the
 * loaded-chunk gate runs before the block entity lookup. Slot reads detach one
 * single-item exemplar plus the exact count and never mutate the handler.
 */
public final class PlatformInventoryServiceImpl implements IPlatformInventoryService {

	@Override
	public Optional<Access> find(ServerLevel level, BlockPos pos, Direction side) {
		return find(level, pos, side, ignored -> true);
	}

	@Override
	public Optional<Access> find(ServerLevel level, BlockPos pos, Direction side, Predicate<BlockPos> memberGate) {
		Objects.requireNonNull(level, "level");
		return find(new MinecraftWorld(level), pos, side, memberGate);
	}

	/** Synchronous native-lookup port; the same gated path is used in production and tests. */
	interface World {
		boolean loaded(BlockPos pos);
		BlockEntity blockEntity(BlockPos pos);
		Optional<IItemHandler> handler(BlockEntity entity, Direction side);
	}
	private record MinecraftWorld(ServerLevel level) implements World {
		@Override public boolean loaded(BlockPos pos) { return level.isLoaded(pos); }
		@Override public BlockEntity blockEntity(BlockPos pos) { return level.getBlockEntity(pos); }
		@Override public Optional<IItemHandler> handler(BlockEntity entity, Direction side) {
			return entity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).resolve();
		}
	}
	static Optional<Access> find(World world, BlockPos pos, Direction side, Predicate<BlockPos> memberGate) {
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(side, "side");
		Objects.requireNonNull(memberGate, "memberGate");
		if (!memberGate.test(pos) || !world.loaded(pos) || !memberGate.test(pos)) return Optional.empty();
		BlockEntity blockEntity = world.blockEntity(pos);
		if (!memberGate.test(pos)) return Optional.empty();
		if (blockEntity == null) return Optional.empty();
		if (blockEntity instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) {
			return Optional.empty();
		}
		try {
			if (!memberGate.test(pos)) return Optional.empty();
			Optional<IItemHandler> handler = world.handler(blockEntity, side);
			if (!memberGate.test(pos)) return Optional.empty();
			BlockPos position = pos.immutable();
			return handler.map(found -> new HandlerAccess(found, () -> memberGate.test(position)));
		} catch (RuntimeException | LinkageError failure) {
			return Optional.empty();
		}
	}

	static final class HandlerAccess implements Access {

		private final IItemHandler handler;
		private final BooleanSupplier memberGate;
		private final nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess raw;
		private final nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.Scratch scratch;
		private ResidentScope residentScope;

		HandlerAccess(IItemHandler handler) {
			this(handler, () -> true);
		}
		HandlerAccess(IItemHandler handler, BooleanSupplier memberGate) {
			this(handler, memberGate, nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess.load());
		}
		HandlerAccess(IItemHandler handler, BooleanSupplier memberGate,
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess raw) {
			this.handler = Objects.requireNonNull(handler, "handler"); this.memberGate = Objects.requireNonNull(memberGate);
			this.raw = Objects.requireNonNull(raw);
			this.scratch = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.Scratch(this.raw);
		}
		private void requireMember() { if (!memberGate.getAsBoolean()) throw new IllegalStateException("inventory handler outside the read scope"); }
		@Override public Access guardedBy(BooleanSupplier gate) { var access = new HandlerAccess(handler, () -> memberGate.getAsBoolean() && gate.getAsBoolean(), raw); access.residentScope = residentScope; return access; }
		@Override public Access scopedResidents(ResidentScope scope) { var access = new HandlerAccess(handler, memberGate, raw); access.residentScope = Objects.requireNonNull(scope); return access; }
		private Container wrapper() {
			if (handler instanceof InvWrapper) {
				// Either native getter -- slot count or indexed content -- forwards
				// through the virtual getInv(); proof about one never certifies the
				// other. The bounded lookup may be unable to classify an
				// out-of-bound concrete name, and that refusal is not an override
				// proof: a scoped known wrapper with any unclassified getter
				// refuses before the raw field or any handler metadata/content call.
				var slotOwnership = ResidentReads.methodOwnership(handler, InvWrapper.class, "getStackInSlot", int.class);
				var slotsOwnership = ResidentReads.methodOwnership(handler, InvWrapper.class, "getSlots");
				if (residentScope != null && (slotOwnership == ResidentReads.MethodOwnership.UNCLASSIFIED
					|| slotsOwnership == ResidentReads.MethodOwnership.UNCLASSIFIED))
					throw new IllegalStateException("inventory wrapper ownership unclassified");
				Object value = ResidentReads.field(handler, InvWrapper.class, "inv", Container.class);
				if (!(value instanceof Container container)) throw new IllegalStateException("inventory wrapper delegate unavailable");
				// A proven inherited declaration requires the virtual delegate to be
				// exactly the raw field this scope admitted. The scoped size getter
				// is included because its native body forwards through getInv() too;
				// unscoped policy keeps the original indexed-getter check only.
				if ((slotOwnership == ResidentReads.MethodOwnership.PROVEN_INHERITED
					|| residentScope != null && slotsOwnership == ResidentReads.MethodOwnership.PROVEN_INHERITED)
					&& ((InvWrapper) handler).getInv() != container)
					throw new IllegalStateException("known inventory wrapper delegate changed");
				return container;
			}
			if (handler instanceof SidedInvWrapper) {
				// The native SidedInvWrapper reads its fixed protected fields
				// directly; there is no virtual delegate getter to redirect.
				Object value = ResidentReads.field(handler, SidedInvWrapper.class, "inv", WorldlyContainer.class);
				if (!(value instanceof Container container)) throw new IllegalStateException("inventory wrapper delegate unavailable");
				return container;
			}
			return null;
		}
		private boolean auditedWrapper() {
			Container container = wrapper(); if (container == null) return false;
			if (residentScope == null) return false; // compatibility-only LIVE/EXTERNAL_TRUST
			if (!residentScope.valid())
				throw new IllegalStateException("inventory wrapper delegate outside admitted source");
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.requireScope(container, residentScope::allows, scratch);
			if (handler instanceof SidedInvWrapper && ResidentReads.field(handler, SidedInvWrapper.class, "side", Direction.class) != residentScope.face())
				throw new IllegalStateException("inventory wrapper changed frozen face");
			boolean inheritedRead = handler instanceof InvWrapper
				? ResidentReads.inherits(handler, InvWrapper.class, "getStackInSlot", int.class)
				: ResidentReads.inherits(handler, SidedInvWrapper.class, "getStackInSlot", int.class);
			boolean nativeWrapper = handler instanceof InvWrapper
				? inheritedRead && ResidentReads.inherits(handler, InvWrapper.class, "getSlots") && ResidentReads.inherits(handler, InvWrapper.class, "getInv")
				: inheritedRead && ResidentReads.inherits(handler, SidedInvWrapper.class, "getSlots");
			var expected = residentScope.slots(container);
			if (expected == null) {
				// No frozen member layout: the selected face itself is the witness.
				// An inherited unsided InvWrapper indexes local slots directly, so a
				// face that does not expose exactly local order would widen the
				// frozen face; refuse it before content. The layout-less route
				// otherwise stays LIVE and uncertified.
				if (handler instanceof InvWrapper && inheritedRead
					&& !nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.identityMapping(container, residentScope.face()))
					throw new IllegalStateException("inventory wrapper changed frozen local mapping");
				return false;
			}
			boolean audited = residentScope.audited(container) && nativeWrapper;
			if (!nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.mappingEquals(container, residentScope.face(), expected)
				|| handler instanceof InvWrapper
				&& !nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.identityMapping(container, residentScope.face()))
				throw new IllegalStateException("inventory wrapper changed frozen local mapping");
			return audited;
		}
		/**
		 * Resolve the inherited read delegate exactly once for the content
		 * operation, authorize that saved delegate and the current member/face
		 * witness only after this last virtual dispatch, then read the saved
		 * delegate without another {@code getInv} dispatch. A proven subclass
		 * override stays an arbitrary EXTERNAL_TRUST graph and is read directly.
		 * Ownership that the bounded lookup cannot classify refuses here, before
		 * the native wrapper getter can dispatch a foreign delegate; a bounded
		 * refusal is never an override proof.
		 */
		private ItemStack content(int slot) {
			if (residentScope != null && handler instanceof InvWrapper) {
				var ownership = ResidentReads.methodOwnership(handler, InvWrapper.class, "getStackInSlot", int.class);
				if (ownership == ResidentReads.MethodOwnership.UNCLASSIFIED)
					throw new IllegalStateException("inventory wrapper ownership unclassified");
				if (ownership == ResidentReads.MethodOwnership.PROVEN_INHERITED) {
					Container delegate = wrapper();
					authorizeResolved(delegate);
					return delegate.getItem(slot);
				}
			}
			return handler.getStackInSlot(slot);
		}
		/** Current admitted member and selected-face witness for a resolved delegate. */
		private void authorizeResolved(Container delegate) {
			requireMember();
			if (!residentScope.valid())
				throw new IllegalStateException("inventory wrapper delegate outside admitted source");
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.requireScope(delegate, residentScope::allows, scratch);
			var expected = residentScope.slots(delegate);
			if (expected != null) {
				if (!nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.mappingEquals(delegate, residentScope.face(), expected))
					throw new IllegalStateException("inventory wrapper changed frozen local mapping");
				return;
			}
			if (!nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.identityMapping(delegate, residentScope.face()))
				throw new IllegalStateException("inventory wrapper changed frozen local mapping");
		}
		@Override public boolean valid() {
			try { requireMember(); return true; }
			catch (RuntimeException | LinkageError unavailable) { return false; }
		}

		/** Scoped known wrapper whose concrete size getter is the inherited native forwarding method. */
		private boolean inheritedSizeForwarding() {
			return residentScope != null && handler instanceof InvWrapper
				&& ResidentReads.methodOwnership(handler, InvWrapper.class, "getSlots")
					== ResidentReads.MethodOwnership.PROVEN_INHERITED;
		}
		/**
		 * Resolve the wrapper delegate exactly once for the scoped size
		 * operation, authorize that final member/face witness after the last
		 * getInv callback, then read the saved container's size directly instead
		 * of native {@code handler.getSlots()}, which would dispatch the virtual
		 * getInv() again. The both-inherited layout-less identity and
		 * frozen-mapping refusals of {@link #auditedWrapper()} are preserved on
		 * the saved delegate; a genuine size override never enters here.
		 */
		private int savedDelegateSize() {
			Container delegate = wrapper();
			requireMember();
			if (!residentScope.valid())
				throw new IllegalStateException("inventory wrapper delegate outside admitted source");
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.requireScope(delegate, residentScope::allows, scratch);
			boolean inheritedRead = ResidentReads.inherits(handler, InvWrapper.class, "getStackInSlot", int.class);
			var expected = residentScope.slots(delegate);
			if (expected != null) {
				if (!nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.mappingEquals(delegate, residentScope.face(), expected)
					|| !nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.identityMapping(delegate, residentScope.face()))
					throw new IllegalStateException("inventory wrapper changed frozen local mapping");
				return delegate.getContainerSize();
			}
			if (inheritedRead && !nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.identityMapping(delegate, residentScope.face()))
				throw new IllegalStateException("inventory wrapper changed frozen local mapping");
			return delegate.getContainerSize();
		}
		@Override
		public int slots() {
			requireMember();
			int slots;
			if (inheritedSizeForwarding()) {
				slots = savedDelegateSize();
			} else {
				auditedWrapper(); // known delegates must be scoped before their metadata getter
				slots = handler.getSlots();
			}
			requireMember();
			if (slots < 0) throw new IllegalStateException("provider reported a negative slot count");
			return slots;
		}

		@Override
		public boolean stableCursor() {
			return true;
		}

		@Override
		public OptionalLong version() {
			return OptionalLong.empty();
		}

		@Override
		public Entry read(int slot) {
			Objects.checkIndex(slot, slots());
			requireMember();
			// Resolve the inherited delegate once at the actual content boundary
			// and authorize the resolved witness before reading it.
			ItemStack stack = content(slot);
			requireMember();
			if (stack == null || stack.isEmpty()) return Entry.empty();
			int count = stack.getCount();
			if (count < 0) throw new IllegalStateException("provider reported a negative item count");
			if (count == 0) return Entry.empty();
			return new Entry(stack.copyWithCount(1), count);
		}

		@Override
		public boolean supportsDetachedSlotCapture() {
			requireMember();
			boolean audited = handler instanceof ItemStackHandler
				&& ResidentReads.inherits(handler, ItemStackHandler.class, "getSlots")
				&& ResidentReads.inherits(handler, ItemStackHandler.class, "getStackInSlot", int.class)
				&& ResidentReads.inherits(handler, ItemStackHandler.class, "validateSlotIndex", int.class)
				&& ResidentReads.nativeStacks(ResidentReads.field(handler, ItemStackHandler.class, "stacks", net.minecraft.core.NonNullList.class), raw);
			if (!audited) audited = auditedWrapper();
			requireMember(); return audited;
		}

		@Override
		public ResidentEntry readResident(int slot) {
			// Classify before any provider getter. False remains LIVE/EXTERNAL_TRUST;
			// an altered helper is never certified or bypassed via raw stacks.
			supportsDetachedSlotCapture();
			Objects.checkIndex(slot, slots());
			requireMember();
			ItemStack stack = content(slot);
			requireMember();
			if (residentScope != null && !residentScope.valid()) throw new IllegalStateException("inventory source revoked during resident read");
			return ResidentReads.stack(stack);
		}

		@Override
		public ResidentVisit observeResident(int limit, ResidentVisitor visitor) {
			if (limit < 0) throw new IllegalArgumentException("negative observation limit");
			Objects.requireNonNull(visitor, "visitor");
			supportsDetachedSlotCapture(); // classify before the indexed default's first getter
			ResidentVisit observed = Access.super.observeResident(limit, visitor);
			requireMember(); return observed;
		}

		@Override
		public boolean visit(int limit, Consumer<Entry> consumer) {
			Objects.requireNonNull(consumer, "consumer");
			if (limit < 0) throw new IllegalArgumentException("negative visit limit");
			int count = slots();
			int bound = Math.min(count, limit);
			for (int slot = 0; slot < bound; slot++) {
				Entry entry = read(slot);
				if (!entry.isEmpty()) consumer.accept(entry);
			}
			requireMember();
			return count <= limit;
		}
	}
}
