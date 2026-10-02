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

import nx.pingwheel.common.platform.IPlatformInventoryService;

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
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(side, "side");
		if (!level.isLoaded(pos)) return Optional.empty();
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (nx.pingwheel.neoforge.integration.create.presentation.CreateVaultInventoryAccess.recognizes(blockEntity))
			return nx.pingwheel.neoforge.integration.create.presentation.CreateVaultInventoryAccess.find(level, pos, side);
		if (blockEntity instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) {
			return Optional.empty();
		}
		try {
			IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
			return handler == null ? Optional.empty() : Optional.of(new HandlerAccess(handler));
		} catch (RuntimeException | LinkageError failure) {
			return Optional.empty();
		}
	}

	private static final class HandlerAccess implements Access {

		private final IItemHandler handler;

		HandlerAccess(IItemHandler handler) {
			this.handler = Objects.requireNonNull(handler, "handler");
		}

		@Override
		public int slots() {
			int slots = handler.getSlots();
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
			ItemStack stack = handler.getStackInSlot(slot);
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
			return count <= limit;
		}
	}
}
