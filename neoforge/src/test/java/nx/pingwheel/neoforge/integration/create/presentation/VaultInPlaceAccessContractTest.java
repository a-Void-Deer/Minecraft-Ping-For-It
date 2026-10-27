package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class VaultInPlaceAccessContractTest {
	@BeforeAll static void bootstrap() { CreateVaultInventoryAccessTest.bootstrap(); }
	@Test void currentRouteAndFrozenStructureAreFreshWhileDecoratorsCannotGrantCapability() {
		var delegate = CreateVaultInventoryAccessTest.World.pair(); boolean[] route = {true};
		var world = new CreateVaultInventoryAccess.World() {
			@Override public String dimension() { return delegate.dimension(); }
			@Override public CreateVaultInventoryAccess.Member loaded(BlockPos position) { return delegate.loaded(position); }
			@Override public boolean currentRoute() { return route[0]; }
		};
		var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		var capable = assertInstanceOf(IPlatformInventoryService.InPlaceRevalidatingAccess.class, access);
		var layout = access.snapshotLayout().orElseThrow(); assertTrue(capable.revalidateCurrent(layout));
		route[0] = false; assertTrue(access.valid(), "old topology alone cannot prove current provider availability");
		assertFalse(capable.revalidateCurrent(layout)); route[0] = true;
		assertTrue(capable.revalidateCurrent(layout));
		assertFalse(access.guardedBy(() -> true) instanceof IPlatformInventoryService.InPlaceRevalidatingAccess,
			"a generic guard has not established the complete current route contract");
		var other = new nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout(
			layout.layoutId(), "different ordering", layout.controller(), layout.members().reversed());
		assertFalse(capable.revalidateCurrent(other));
	}
	@Test void equalShapeLocalReplacementReadsCurrentButNativeBackingReplacementFailsBeforeGetter() throws ReflectiveOperationException {
		var world = CreateVaultInventoryAccessTest.World.pair();
		for (var part : world.members.values()) part.handler = new ItemStackHandler(1);
		var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
		var capable = assertInstanceOf(IPlatformInventoryService.InPlaceRevalidatingAccess.class, access);
		var layout = access.snapshotLayout().orElseThrow();
		var current = new ItemStackHandler(1); current.setStackInSlot(0, new ItemStack(Items.DIAMOND, 41));
		world.members.get(BlockPos.ZERO).handler = current;
		assertTrue(capable.revalidateCurrent(layout)); assertEquals(41, access.readResident(0).amount());
		var field = ItemStackHandler.class.getDeclaredField("stacks"); field.setAccessible(true);
		var stacks = (net.minecraft.core.NonNullList<?>) field.get(current);
		var list = net.minecraft.core.NonNullList.class.getDeclaredField("list"); list.setAccessible(true);
		list.set(stacks, new java.util.LinkedList<>(List.of(new ItemStack(Items.DIAMOND, 41))));
		assertTrue(access.valid(), "same-size unsafe backing is not a topology change and must reach the fresh ownership audit");
		var refused = assertThrows(InventorySourceUnavailableException.class,
			() -> access.consumeResident(0, item -> { fail("invalid backing reached content"); return true; }));
		assertEquals("Vault native local backing changed", refused.getMessage());
	}
	@Test void controllerAxisAndLocalRedistributionCannotInstallNewLayoutInOldAccess() {
		for (String change : List.of("controller", "axis", "redistribute", "missing")) {
			var world = CreateVaultInventoryAccessTest.World.pair();
			var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH).orElseThrow();
			var capable = assertInstanceOf(IPlatformInventoryService.InPlaceRevalidatingAccess.class, access);
			var layout = access.snapshotLayout().orElseThrow();
			switch (change) {
				case "controller" -> world.members.get(BlockPos.ZERO).controller = new BlockPos(9, 0, 0);
				case "axis" -> world.members.get(BlockPos.ZERO).axis = Direction.Axis.Z;
				case "redistribute" -> {
					world.members.get(BlockPos.ZERO).handler = new CreateVaultInventoryAccessTest.Handler(0);
					world.members.get(new BlockPos(1, 0, 0)).handler = new CreateVaultInventoryAccessTest.Handler(2);
				}
				case "missing" -> world.members.remove(new BlockPos(1, 0, 0));
				default -> throw new AssertionError(change);
			}
			assertFalse(capable.revalidateCurrent(layout), change); assertTrue(access.snapshotLayout().isEmpty());
		}
	}
	@Test void metadataGetterAndDetachmentRevocationEachRejectTheResidentOperation() {
		for (String boundary : List.of("metadata", "getter", "detachment")) {
			var world = CreateVaultInventoryAccessTest.World.pair(); var sibling = new BlockPos(1, 0, 0);
			var allowed = new java.util.HashSet<>(world.members.keySet()); boolean[] armed = {false}; int[] getters = {0}, visits = {0};
			world.members.get(BlockPos.ZERO).handler = new CreateVaultInventoryAccessTest.Handler(1) {
				@Override public int getSlots() {
					if (armed[0] && boundary.equals("metadata")) allowed.remove(sibling);
					return super.getSlots();
				}
				@Override public ItemStack getStackInSlot(int slot) {
					getters[0]++; if (boundary.equals("getter")) allowed.remove(sibling);
					return new ItemStack(Items.STONE, 3);
				}
			};
			var access = CreateVaultInventoryAccess.find(world, BlockPos.ZERO, Direction.NORTH, allowed::contains).orElseThrow();
			armed[0] = true;
			assertThrows(InventorySourceUnavailableException.class, () -> access.consumeResident(0, item -> {
				visits[0]++; if (boundary.equals("detachment")) allowed.remove(sibling); return true;
			}));
			assertEquals(boundary.equals("metadata") ? 0 : 1, getters[0]); assertEquals(getters[0], visits[0]);
		}
	}
}
