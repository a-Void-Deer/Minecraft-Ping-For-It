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

		HandlerAccess(IItemHandler handler) {
			this(handler, () -> true);
		}
		HandlerAccess(IItemHandler handler, BooleanSupplier memberGate) {
			this.handler = Objects.requireNonNull(handler, "handler"); this.memberGate = Objects.requireNonNull(memberGate);
		}
		private void requireMember() { if (!memberGate.getAsBoolean()) throw new IllegalStateException("inventory handler outside the read scope"); }
		@Override public Access guardedBy(BooleanSupplier gate) { return new HandlerAccess(handler, () -> memberGate.getAsBoolean() && gate.getAsBoolean()); }
		@Override public boolean valid() {
			try { requireMember(); return true; }
			catch (RuntimeException | LinkageError unavailable) { return false; }
		}

		@Override
		public int slots() {
			requireMember();
			int slots = handler.getSlots();
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
			ItemStack stack = handler.getStackInSlot(slot);
			requireMember();
			if (stack == null || stack.isEmpty()) return Entry.empty();
			int count = stack.getCount();
			if (count < 0) throw new IllegalStateException("provider reported a negative item count");
			if (count == 0) return Entry.empty();
			return new Entry(stack.copyWithCount(1), count);
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
