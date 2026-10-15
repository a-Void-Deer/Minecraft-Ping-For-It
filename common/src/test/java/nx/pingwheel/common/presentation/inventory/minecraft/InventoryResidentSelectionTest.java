package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventorySelection;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** UNRUN: production source witness, not Backend/C integration. */
class InventoryResidentSelectionTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@Test void layoutlessRootCanAuthorizeNonWorldBackingButNeverAForeignBlockEntity() {
		var state = Blocks.BEEHIVE.defaultBlockState(); var entity = new BeehiveBlockEntity(BlockPos.ZERO, state);
		var capturedScope = new IPlatformInventoryService.ResidentScope[1];
		var access = new IPlatformInventoryService.Access() {
			@Override public IPlatformInventoryService.Access scopedResidents(IPlatformInventoryService.ResidentScope scope) { capturedScope[0] = scope; return this; }
			@Override public int slots() { return 1; }
			@Override public boolean stableCursor() { return true; }
			@Override public OptionalLong version() { return OptionalLong.empty(); }
			@Override public IPlatformInventoryService.Entry read(int slot) { throw new AssertionError("detached bridge"); }
			@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("detached bridge"); }
		};
		boolean[] loaded = {true};
		var world = new InventoryMinecraftSources.SourceWorld() {
			@Override public boolean isLoaded(BlockPos pos) { return loaded[0] && pos.equals(BlockPos.ZERO); }
			@Override public BlockState blockState(BlockPos pos) { return state; }
			@Override public BlockEntity blockEntity(BlockPos pos) { return entity; }
			@Override public boolean onServerThread() { return true; }
			@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
			@Override public HolderLookup.Provider registries() { return RegistryAccess.EMPTY; }
		};
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:beehive"), new UUID(11, 11), BlockFace.NORTH);
		InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.of(access));
		var backing = new net.minecraft.world.SimpleContainer(1);
		assertTrue(capturedScope[0].allows(backing)); assertNull(capturedScope[0].slots(backing), "root trust never invents a detached layout");
		var foreign = new net.minecraft.world.level.block.entity.ChestBlockEntity(new BlockPos(1, 0, 0), Blocks.CHEST.defaultBlockState());
		assertFalse(capturedScope[0].allows(foreign));
		assertThrows(IllegalStateException.class, () -> InventoryContainerReads.requireScope(new net.minecraft.world.CompoundContainer(backing, foreign), capturedScope[0]::allows));
		loaded[0] = false; assertFalse(capturedScope[0].allows(backing));
	}
	@Test void aggregateWitnessNeedsOnlyIdAndPositiveLongAmountAndUnsafeIdentityCannotMatchExact() {
		var calls = new AtomicInteger(); Codec<Object> codec = Codec.INT.xmap(i -> new Object(), value -> {
			calls.incrementAndGet(); throw new IllegalStateException("codec trap");
		});
		Object value = new Object(); assertThrows(IllegalStateException.class, () -> codec.encodeStart(NbtOps.INSTANCE, value)); calls.set(0);
		var type = DataComponentType.builder().persistent(codec).build(); var patch = DataComponentPatch.builder().set(type, value).build();
		var entry = new IPlatformInventoryService.ResidentEntry(new InventoryResidentItem.Patch(Items.STONE, patch), 9007199254740993L);
		var reads = new AtomicInteger();
		var access = new IPlatformInventoryService.Access() {
			@Override public int slots() { return 1; }
			@Override public boolean stableCursor() { return true; }
			@Override public OptionalLong version() { return OptionalLong.empty(); }
			@Override public IPlatformInventoryService.Entry read(int slot) { throw new AssertionError("detached bridge"); }
			@Override public IPlatformInventoryService.ResidentEntry readResident(int slot) { reads.incrementAndGet(); return entry; }
			@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("detached bridge"); }
		};
		var state = Blocks.BEEHIVE.defaultBlockState(); var entity = new BeehiveBlockEntity(BlockPos.ZERO, state);
		var world = new InventoryMinecraftSources.SourceWorld() {
			@Override public boolean isLoaded(BlockPos pos) { return pos.equals(BlockPos.ZERO); }
			@Override public BlockState blockState(BlockPos pos) { return state; }
			@Override public BlockEntity blockEntity(BlockPos pos) { return entity; }
			@Override public boolean onServerThread() { return true; }
			@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
			@Override public HolderLookup.Provider registries() { return RegistryAccess.EMPTY; }
		};
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:beehive"), new UUID(10, 10), BlockFace.NORTH);
		var view = InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.of(access));
		try (var source = new InventoryMinecraftSources.Source(input, view, () -> true,
			() -> InventoryMinecraftSources.view(world, input, (pos, face) -> Optional.of(access)), ignored -> { throw new AssertionError("legacy codec"); }, world, null)) {
			var aggregate = new InventorySelection(null, "minecraft:stone", "Stone", null, true, "pingforit:look");
			assertTrue(source.matchesSelection(0, aggregate)); assertEquals(1, reads.get()); assertEquals(0, calls.get());
			var exact = new InventorySelection(new InventoryScanner.Key("minecraft:stone", "not-a-fallback-digest"), "minecraft:stone", "Stone", null, false, "pingforit:look");
			assertFalse(source.matchesSelection(0, exact)); assertEquals(2, reads.get()); assertEquals(0, calls.get());
			assertTrue(source.snapshotPlan().isEmpty(), "external getter is not automatically atomic");
		}
	}
}
