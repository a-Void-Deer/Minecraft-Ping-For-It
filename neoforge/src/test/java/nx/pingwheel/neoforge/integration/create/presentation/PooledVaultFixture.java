package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import org.jetbrains.annotations.NotNullByDefault;

/** Default 3 x 3 x 5, optionally up to 3 x 3 x 9; twenty exact native local slots per member. */
@NotNullByDefault
public final class PooledVaultFixture implements CreateVaultInventoryAccess.World {
	public final Map<BlockPos, Part> members = new LinkedHashMap<>();
	public boolean available = true;
	private final int length;
	public long factories, loadedCalls, routeCalls, controllerCalls, controllerChecks, widths, lengths, axes, locals;
	public PooledVaultFixture() {
		this(5, ordinal -> new ItemStack(Items.STONE, 3_000_003 + ordinal));
	}
	/** Retains the exact native declaration owners; counting a resident getter would invalidate certification. */
	public PooledVaultFixture(int length, IntFunction<ItemStack> contents) {
		if (length < 1 || length > 9) throw new IllegalArgumentException("bounded 3-wide Vault length");
		this.length = length;
		int ordinal = 0;
		for (int x = 0; x < length; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) {
			var storage = new ItemStackHandler(20);
			for (int slot = 0; slot < 20; slot++) storage.setStackInSlot(slot, contents.apply(ordinal++));
			members.put(new BlockPos(x, y, z), new Part(storage));
		}
	}
	@Override public String dimension() { return "minecraft:overworld"; }
	@Override public boolean currentRoute() { routeCalls++; return available; }
	@Override public Part loaded(BlockPos position) { loadedCalls++; return java.util.Objects.requireNonNull(members.get(position)); }
	public Optional<IPlatformInventoryService.Access> find(BlockPos pos, Direction face) { factories++; return CreateVaultInventoryAccess.find(this, pos, face); }
	public void resetCounters() { factories = loadedCalls = routeCalls = controllerCalls = controllerChecks = widths = lengths = axes = locals = 0; }
	public void replaceStack(int address, ItemStack replacement) {
		var pos = new BlockPos(address / 180, address / 60 % 3, address / 20 % 3);
		java.util.Objects.requireNonNull(members.get(pos)).storage.setStackInSlot(address % 20, replacement);
	}
	@NotNullByDefault
	public final class Part implements CreateVaultInventoryAccess.Member {
		final ItemStackHandler storage;
		Part(ItemStackHandler storage) { this.storage = storage; }
		@Override public String blockId() { return "create:item_vault"; }
		@Override public BlockPos controller() { controllerCalls++; return BlockPos.ZERO; }
		@Override public boolean isController() { controllerChecks++; return this == members.get(BlockPos.ZERO); }
		@Override public int width() { widths++; return 3; }
		@Override public int length() { lengths++; return length; }
		@Override public Direction.Axis axis() { axes++; return Direction.Axis.X; }
		@Override public IItemHandler local() { locals++; return storage; }
	}
}
