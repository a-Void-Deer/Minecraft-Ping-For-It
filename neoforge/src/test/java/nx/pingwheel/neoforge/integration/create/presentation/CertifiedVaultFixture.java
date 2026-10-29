package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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

/** Independent typed fixture for production Segmented certificates; never changes the benchmark's read graph. */
@NotNullByDefault
public final class CertifiedVaultFixture implements CreateVaultInventoryAccess.World {
	public final Map<BlockPos, Part> members = new HashMap<>();
	public boolean route = true;
	public int audits, locals;
	public Runnable metadata = () -> {}, afterAudit = () -> {};
	public CertifiedVaultFixture() {
		members.put(BlockPos.ZERO, new Part()); members.put(new BlockPos(1, 0, 0), new Part());
	}
	@Override public String dimension() { return "minecraft:overworld"; }
	@Override public boolean currentRoute() { return route; }
	@Override public Part loaded(BlockPos position) {
		@Nullable Part part = members.get(position);
		if (part == null) throw new InventorySourceUnavailableException("unloaded fixture member");
		return part;
	}
	public Optional<IPlatformInventoryService.Access> find(BlockPos position, Direction face) { return CreateVaultInventoryAccess.find(this, position, face); }
	@NotNullByDefault
	public final class Part implements CreateVaultInventoryAccess.Member {
		public IItemHandler storage = new ItemStackHandler(1);
		public Part() { ((ItemStackHandler) storage).setStackInSlot(0, new ItemStack(Items.STONE, 2_000_000_001)); }
		@Override public String blockId() { return "create:item_vault"; }
		@Override public BlockPos controller() { return BlockPos.ZERO; }
		@Override public boolean isController() { return this == members.get(BlockPos.ZERO); }
		@Override public int width() { return 1; }
		@Override public int length() { return 2; }
		@Override public Direction.Axis axis() { return Direction.Axis.X; }
		@Override public IItemHandler local() { locals++; metadata.run(); return storage; }
		@Override public boolean auditedLocal(IItemHandler handler) {
			audits++; boolean audited = CreateVaultInventoryAccess.Member.super.auditedLocal(handler); afterAudit.run(); return audited;
		}
	}
}
