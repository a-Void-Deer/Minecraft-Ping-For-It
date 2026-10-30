package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Dedicated callback fixture; never changes the certified measurement fixture or native getter declarations. */
@NotNullByDefault
public final class CoordinatedVaultFixture implements CreateVaultInventoryAccess.World {
	public final Map<BlockPos, Part> members = new LinkedHashMap<>();
	public boolean route = true;
	public int loads, locals, audits, passes, held;
	public Consumer<BlockPos> onLoad = ignored -> {}, onLocal = ignored -> {}, onAudit = ignored -> {};
	public CoordinatedVaultFixture() {
		members.put(BlockPos.ZERO, new Part(BlockPos.ZERO));
		members.put(new BlockPos(1, 0, 0), new Part(new BlockPos(1, 0, 0)));
	}
	@Override public String dimension() { return "minecraft:overworld"; }
	@Override public boolean currentRoute() { return route; }
	@Override public Part loaded(BlockPos position) {
		loads++; onLoad.accept(position); @Nullable Part part = members.get(position);
		if (part == null) throw new InventorySourceUnavailableException("unloaded coordinated fixture member"); return part;
	}
	public Optional<IPlatformInventoryService.Access> find(BlockPos position, Direction face) { return CreateVaultInventoryAccess.find(this, position, face); }
	@NotNullByDefault
	public final class Part implements CreateVaultInventoryAccess.Member {
		public final BlockPos position;
		public IItemHandler storage = new ItemStackHandler(1);
		public BlockPos controller = BlockPos.ZERO;
		public Direction.Axis axis = Direction.Axis.X;
		Part(BlockPos position) { this.position = position; ((ItemStackHandler) storage).setStackInSlot(0, new ItemStack(Items.STONE, 37)); }
		@Override public String blockId() { return "create:item_vault"; }
		@Override public BlockPos controller() { return controller; }
		@Override public boolean isController() { passes++; return position.equals(BlockPos.ZERO); }
		@Override public int width() { return 1; }
		@Override public int length() { return 2; }
		@Override public Direction.Axis axis() { return axis; }
		@Override public IItemHandler local() { locals++; onLocal.accept(position); return storage; }
		@Override public boolean auditedLocal(IItemHandler handler) {
			audits++; boolean result = CreateVaultInventoryAccess.Member.super.auditedLocal(handler); onAudit.accept(position); return result;
		}
	}
}
