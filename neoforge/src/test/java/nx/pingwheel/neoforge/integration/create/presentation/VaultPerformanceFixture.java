package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Typed headless world only: discovery, Segmented, mappings and native-local classification are production code. */
@NotNullByDefault
public final class VaultPerformanceFixture implements CreateVaultInventoryAccess.World {

	@NotNullByDefault
	public enum Contents { EMPTY, REPEATED, MIXED }
	public final Map<BlockPos, Part> members = new HashMap<>();
	public long factories, loaded, controllerChecks, localCalls, localAudits, getters;
	public boolean available = true;

	public VaultPerformanceFixture(Contents contents) {
		// A legal default Create topology: length 5 along X, width/height 3; 45 native locals of 20 slots.
		for (int x = 0; x < 5; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) {
			var position = new BlockPos(x, y, z);
			var part = new Part(position);
			for (int slot = 0; slot < 20; slot++) {
				int absolute = ((x * 3 + y) * 3 + z) * 20 + slot;
				part.storage.setStackInSlot(slot, stack(contents, absolute));
			}
			members.put(position, part);
		}
	}

	public static ItemStack stack(Contents contents, int absolute) {
		if (contents == Contents.EMPTY) return ItemStack.EMPTY;
		var stack = new ItemStack(contents == Contents.MIXED && absolute % 3 == 2 ? Items.DIRT : Items.STONE, 3_000_003 + absolute);
		if (contents == Contents.MIXED) {
			if (absolute % 3 < 2) stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(absolute % 3));
			else {
				var tag = new CompoundTag(); tag.putString("fixture", "native-vault"); tag.putInt("variant", 2);
				stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
			}
		}
		return stack;
	}

	public Optional<IPlatformInventoryService.Access> find(BlockPos position, Direction face) {
		factories++;
		return CreateVaultInventoryAccess.find(this, position, face);
	}

	@Override public String dimension() { return "minecraft:overworld"; }
	@Override public Part loaded(BlockPos position) {
		loaded++;
		@Nullable Part part = members.get(position);
		if (!available || part == null) throw new InventorySourceUnavailableException("headless Vault member unavailable");
		return part;
	}

	public void resetCounters() { factories = loaded = controllerChecks = localCalls = localAudits = getters = 0; }

	@NotNullByDefault
	public final class Part implements CreateVaultInventoryAccess.Member {
		public ItemStackHandler storage = new Local();
		private final CountingHandler observed = new CountingHandler(this);
		private final BlockPos position;
		Part(BlockPos position) { this.position = position; }
		@Override public String blockId() { return "create:item_vault"; }
		@Override public BlockPos controller() { return BlockPos.ZERO; }
		@Override public boolean isController() { controllerChecks++; return position.equals(BlockPos.ZERO); }
		@Override public int width() { return 3; }
		@Override public int length() { return 5; }
		@Override public Direction.Axis axis() { return Direction.Axis.X; }
		@Override public IItemHandler local() { localCalls++; return observed; }
		@Override public boolean auditedLocal(IItemHandler handler) {
			localAudits++;
			// Unwrap ONLY the fixture's observer; the storage still runs production nativeLocal (including load).
			if (handler != observed || !CreateVaultInventoryAccess.Member.super.auditedLocal(storage)) throw new AssertionError("fixture is not native local storage");
			return true;
		}
	}

	/** One prebuilt observer per member; no proxies, item construction or list/log writes in a read. */
	@NotNullByDefault
	private final class CountingHandler implements IItemHandler {
		private final Part part;
		CountingHandler(Part part) { this.part = part; }
		@Override public int getSlots() { return part.storage.getSlots(); }
		@Override public ItemStack getStackInSlot(int slot) { getters++; return part.storage.getStackInSlot(slot); }
		@Override public int getSlotLimit(int slot) { return part.storage.getSlotLimit(slot); }
		@Override public boolean isItemValid(int slot, ItemStack stack) { return part.storage.isItemValid(slot, stack); }
		@Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { throw new AssertionError("read-only"); }
		@Override public ItemStack extractItem(int slot, int amount, boolean simulate) { throw new AssertionError("read-only"); }
	}

	/** No getter override: preserve the same native ownership certificate as Create's local handler. */
	@NotNullByDefault
	private static final class Local extends ItemStackHandler {
		Local() { super(20); }
		@Override protected void onContentsChanged(int slot) { /* Create-style write notification, irrelevant to reads. */ }
		@Override public CompoundTag serializeNBT(net.minecraft.core.HolderLookup.Provider registries) {
			throw new AssertionError("inventory-only measurement must not serialize the block entity or handler");
		}
	}
}
