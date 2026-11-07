package nx.pingwheel.fabric.platform;

import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.base.SingleStackStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import nx.pingwheel.common.platform.IPlatformInventoryService.ResidentScope;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Counts explicit loader gates only; production scope lookups retain their own current checks. */
@org.jetbrains.annotations.NotNullByDefault
class ResidentScopeReadBoundaryTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@org.jetbrains.annotations.NotNullByDefault
	private static final class CountingScope implements ResidentScope {
		final Container admitted;
		int validCalls, allowsCalls, slotsCalls;
		CountingScope(Container admitted) { this.admitted = admitted; }
		@Override public Direction face() { return Direction.NORTH; }
		@Override public boolean valid() { validCalls++; return true; }
		@Override public boolean allows(Container value) { allowsCalls++; return value == admitted; }
		@Override public boolean audited(Container value) { return value == admitted; }
		@Override public @Nullable @org.jetbrains.annotations.Unmodifiable List<Integer> slots(Container value) { slotsCalls++; return value == admitted ? List.of(0) : null; }
	}

	@org.jetbrains.annotations.NotNullByDefault
	private static final class Raw implements FabricResidentStorageAccess {
		private final Container inventory;
		Raw(Container inventory) { this.inventory = inventory; }
		@Override public @Nullable SingleStackStorage mappedSlot(StorageView<?> value) {
			try {
				var field = value.getClass().getDeclaredField("slotWrapper");
				field.setAccessible(true);
				return field.get(value) instanceof SingleStackStorage storage ? storage : null;
			} catch (ReflectiveOperationException failure) { return null; }
		}
		@Override public Container inventory(SingleStackStorage value) { return inventory; }
		@Override public int localSlot(SingleStackStorage value) {
			try {
				var field = value.getClass().getDeclaredField("slot");
				field.setAccessible(true);
				return field.getInt(value);
			} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
		@Override public ItemStack stack(SingleStackStorage value) { return inventory.getItem(localSlot(value)); }
	}

	@Test void mappedNativeLeafRetainsBothExplicitLoaderGates() {
		var inventory = new SimpleContainer(1);
		inventory.setItem(0, new ItemStack(Items.STONE, 3));
		var scope = new CountingScope(inventory);
		var storage = InventoryStorage.of(inventory, Direction.NORTH);
		var access = new PlatformInventoryServiceImpl.SlottedAccess(storage, () -> true, new Raw(inventory)).scopedResidents(scope);
		assertEquals(3L, access.readResident(0).amount());
		assertEquals(2, scope.validCalls, "two explicit gates, excluding production scope's internal lookup checks");
		assertEquals(1, scope.allowsCalls);
		assertEquals(1, scope.slotsCalls);
	}
}
