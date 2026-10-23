package nx.pingwheel.neoforge.platform;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;

import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.diagnostics.Diagnostics;

/**
 * NeoForge item-handler provider for the platform inventory bridge.
 *
 * <p>The level capability resolves the block item handler, including modded
 * aggregate controllers that register through it. Pending loot is rejected
 * before the capability is resolved so no wrapper can trigger generation, and
 * the loaded-chunk gate runs before the position lookup. Slot reads detach one
 * single-item exemplar plus the exact count and never mutate the handler.
 */
public final class PlatformInventoryServiceImpl implements IPlatformInventoryService {

	@Override
	public Optional<Access> find(ServerLevel level, BlockPos pos, Direction side) {
		return find(level, pos, side, ignored -> true);
	}

	@Override
	public Optional<Access> find(ServerLevel level, BlockPos pos, Direction side,
		java.util.function.Predicate<BlockPos> memberGate) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(side, "side");
		Objects.requireNonNull(memberGate, "memberGate");
		if (!memberGate.test(pos) || !level.isLoaded(pos)) return Optional.empty();
		if (!memberGate.test(pos)) return Optional.empty();
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (nx.pingwheel.neoforge.integration.create.presentation.CreateVaultInventoryAccess.recognizes(blockEntity))
			return nx.pingwheel.neoforge.integration.create.presentation.CreateVaultInventoryAccess.find(level, pos, side, memberGate);
		if (blockEntity instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) {
			return Optional.empty();
		}
		try {
			if (!memberGate.test(pos)) return Optional.empty();
			IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
			if (!memberGate.test(pos)) return Optional.empty();
			return handler == null ? Optional.empty() : Optional.of(new HandlerAccess(handler, pos.immutable(), memberGate));
		} catch (RuntimeException | LinkageError failure) {
			if (!(failure instanceof InventorySourceUnavailableException))
				Diagnostics.warn("inventory-capability-find", "neoforge:item_handler",
					() -> "dimension=" + level.dimension().location() + " position=" + pos + " face=" + side
						+ " blockEntityClass=" + (blockEntity == null ? "none" : blockEntity.getClass().getName()), failure);
			return Optional.empty();
		}
	}

	static final class HandlerAccess implements Access {

		private final IItemHandler handler;
		private final BlockPos position;
		private final java.util.function.Predicate<BlockPos> memberGate;
		private final nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess raw;
		private final nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.Scratch scratch;
		private ResidentScope residentScope;

		HandlerAccess(IItemHandler handler, BlockPos position, java.util.function.Predicate<BlockPos> memberGate) {
			this(handler, position, memberGate, nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess.load());
		}
		HandlerAccess(IItemHandler handler, BlockPos position, java.util.function.Predicate<BlockPos> memberGate,
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess raw) {
			this.handler = Objects.requireNonNull(handler, "handler");
			this.position = position;
			this.memberGate = memberGate;
			this.raw = Objects.requireNonNull(raw);
			this.scratch = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.Scratch(this.raw);
		}
		@Override public Access scopedResidents(ResidentScope scope) {
			var access = new HandlerAccess(handler, position, memberGate, raw); access.residentScope = Objects.requireNonNull(scope); return access;
		}
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
				throw new InventorySourceUnavailableException("inventory wrapper delegate outside admitted source");
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.requireScope(container, residentScope::allows, scratch);
			if (handler instanceof SidedInvWrapper && ResidentReads.field(handler, SidedInvWrapper.class, "side", Direction.class) != residentScope.face())
				throw new InventorySourceUnavailableException("inventory wrapper changed frozen face");
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
					throw new InventorySourceUnavailableException("inventory wrapper changed frozen local mapping");
				return false;
			}
			boolean audited = residentScope.audited(container) && nativeWrapper;
			if (!nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.mappingEquals(container, residentScope.face(), expected)
				|| handler instanceof InvWrapper
				&& !nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.identityMapping(container, residentScope.face()))
				throw new InventorySourceUnavailableException("inventory wrapper changed frozen local mapping");
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
				throw new InventorySourceUnavailableException("inventory wrapper delegate outside admitted source");
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.requireScope(delegate, residentScope::allows, scratch);
			var expected = residentScope.slots(delegate);
			if (expected != null) {
				if (!nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.mappingEquals(delegate, residentScope.face(), expected))
					throw new InventorySourceUnavailableException("inventory wrapper changed frozen local mapping");
				return;
			}
			if (!nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.identityMapping(delegate, residentScope.face()))
				throw new InventorySourceUnavailableException("inventory wrapper changed frozen local mapping");
		}
		private void requireMember() {
			if (!memberGate.test(position)) throw new InventorySourceUnavailableException("inventory handler outside the read scope");
		}
		@Override public boolean valid() {
			try { return memberGate.test(position); }
			catch (RuntimeException | LinkageError unavailable) {
				if (!(unavailable instanceof InventorySourceUnavailableException))
					Diagnostics.warn("inventory-handler-valid", handler.getClass().getName(),
						() -> "position=" + position + " handlerClass=" + handler.getClass().getName()
							+ " gateClass=" + memberGate.getClass().getName(), unavailable);
				return false;
			}
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
				throw new InventorySourceUnavailableException("inventory wrapper delegate outside admitted source");
			nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.requireScope(delegate, residentScope::allows, scratch);
			boolean inheritedRead = ResidentReads.inherits(handler, InvWrapper.class, "getStackInSlot", int.class);
			var expected = residentScope.slots(delegate);
			if (expected != null) {
				if (!nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.mappingEquals(delegate, residentScope.face(), expected)
					|| !nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.identityMapping(delegate, residentScope.face()))
					throw new InventorySourceUnavailableException("inventory wrapper changed frozen local mapping");
				return delegate.getContainerSize();
			}
			if (inheritedRead && !nx.pingwheel.common.presentation.inventory.minecraft.InventoryContainerReads.identityMapping(delegate, residentScope.face()))
				throw new InventorySourceUnavailableException("inventory wrapper changed frozen local mapping");
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
			// Unknown read/helper graphs retain LIVE/EXTERNAL_TRUST, not certification.
			supportsDetachedSlotCapture();
			Objects.checkIndex(slot, slots());
			requireMember();
			ItemStack stack = content(slot);
			requireMember();
			if (residentScope != null && !residentScope.valid()) throw new InventorySourceUnavailableException("inventory source revoked during resident read");
			return ResidentReads.stack(stack);
		}

		@Override
		public ResidentVisit observeResident(int limit, ResidentVisitor visitor) {
			if (limit < 0) throw new IllegalArgumentException("negative observation limit");
			Objects.requireNonNull(visitor, "visitor");
			supportsDetachedSlotCapture();
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
			return count <= limit;
		}
	}
}
