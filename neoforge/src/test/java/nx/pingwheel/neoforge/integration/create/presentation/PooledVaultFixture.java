package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import org.jetbrains.annotations.NotNullByDefault;

/** 3 x 3 x 5 members, twenty real native local slots each; no getter overrides or controller capability. */
@NotNullByDefault
public final class PooledVaultFixture implements CreateVaultInventoryAccess.World {
	public final Map<BlockPos, Part> members = new LinkedHashMap<>();
	public boolean available = true;
	public PooledVaultFixture() {
		int ordinal = 0;
		for (int x = 0; x < 5; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) {
			var storage = new ItemStackHandler(20);
			for (int slot = 0; slot < 20; slot++) storage.setStackInSlot(slot, new ItemStack(Items.STONE, 3_000_003 + ordinal++));
			members.put(new BlockPos(x, y, z), new Part(storage));
		}
	}
	@Override public String dimension() { return "minecraft:overworld"; }
	@Override public boolean currentRoute() { return available; }
	@Override public Part loaded(BlockPos position) { return java.util.Objects.requireNonNull(members.get(position)); }
	public Optional<IPlatformInventoryService.Access> find(BlockPos pos, Direction face) { return CreateVaultInventoryAccess.find(this, pos, face); }
	@NotNullByDefault
	public final class Part implements CreateVaultInventoryAccess.Member {
		final ItemStackHandler storage;
		Part(ItemStackHandler storage) { this.storage = storage; }
		@Override public String blockId() { return "create:item_vault"; }
		@Override public BlockPos controller() { return BlockPos.ZERO; }
		@Override public boolean isController() { return this == members.get(BlockPos.ZERO); }
		@Override public int width() { return 3; }
		@Override public int length() { return 5; }
		@Override public Direction.Axis axis() { return Direction.Axis.X; }
		@Override public IItemHandler local() { return storage; }
	}
}
