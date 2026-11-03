package nx.pingwheel.forge.platform;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryFluidCodec;
import nx.pingwheel.common.presentation.inventory.InventoryFluidValue;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidFreezer;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real Forge capability extraction composed with the production freezer and production registry SPI. */
@NotNullByDefault
class NativeFluidRegistryFreezerTest {
	@BeforeAll static void bootstrap() throws ReflectiveOperationException { PlatformFluidRegistryServiceTest.bootstrap(); }
	@AfterAll static void restore() throws ReflectiveOperationException { PlatformFluidRegistryServiceTest.restore(); }
	@Test void nativePositiveHandlerEntryFreezesThroughActualWrapper() throws ReflectiveOperationException {
		try (var fixture = new Fixture(); var admission = fixture.admission(); var probeOperation = admission.enter(new PlatformFluidServiceImpl().probeProfile());
			var probe = PlatformFluidServiceImpl.probe(fixture.context, fixture, probeOperation).orElseThrow();
			var access = probe.finish(admission).orElseThrow(); var operation = admission.enter(access.readProfile())) {
			@Nullable InventoryFluidValue[] result = new InventoryFluidValue[1];
			assertTrue(access.consume(0, entry -> {
				result[0] = InventoryFluidFreezer.freeze(entry, RegistryAccess.EMPTY, 1024, new InventoryFluidCodec.Catalog(),
					new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(1024), InventoryCatalogAdmission.workUpperBound(1024)));
				return true;
			}, operation));
			var value = Objects.requireNonNull(result[0]);
			assertEquals("minecraft:water", value.resourceId()); assertFalse(value.aggregateRequired());
			assertEquals(173_946_175_407L, value.amount()); assertEquals(1, fixture.reads);
		}
	}
	@NotNullByDefault
	private static final class Fixture implements PlatformFluidServiceImpl.World, IFluidHandler, AutoCloseable {
		private final ServerLevel level = allocate(ServerLevel.class);
		private final DedicatedServer server = allocate(DedicatedServer.class);
		private final BlockState state = Blocks.BEEHIVE.defaultBlockState();
		private final BlockEntity entity = allocate(BeehiveBlockEntity.class);
		private final LazyOptional<IFluidHandler> capability = LazyOptional.of(() -> this);
		private final IPlatformFluidService.Context context;
		private int reads;
		private Fixture() throws ReflectiveOperationException {
			set(ServerLevel.class, "server", level, server); set(MinecraftServer.class, "serverThread", server, Thread.currentThread());
			set(BlockEntity.class, "type", entity, BlockEntityType.BEEHIVE); set(BlockEntity.class, "worldPosition", entity, BlockPos.ZERO);
			set(BlockEntity.class, "blockState", entity, state); set(BlockEntity.class, "level", entity, level);
			context = new IPlatformFluidService.Context(level, BlockPos.ZERO, Direction.WEST, new UUID(1, 2),
				(position, operation) -> position.equals(BlockPos.ZERO), operation -> true);
		}
		private InventoryFluidAdmission admission() {
			var work = new CostLedger.Counter("native-freezer", CostLedger.Unit.WORK);
			return new InventoryFluidAdmission(new CostLedger(Map.of(work, 10_000_000L)), work,
				new RetainedMemoryLedger(64L << 20), InventoryValidationBudget.NONE, InventoryValidationBudget.NONE);
		}
		@Override public boolean loaded(BlockPos position) { return true; }
		@Override public BlockState state(BlockPos position) { return state; }
		@Override public @Nullable BlockEntity blockEntity(BlockPos position) { return entity; }
		@Override public LazyOptional<IFluidHandler> capability(BlockEntity current, Direction face) { return capability; }
		@Override public int getTanks() { return 1; }
		@Override public int getTankCapacity(int tank) { return Integer.MAX_VALUE; }
		@Override public FluidStack getFluidInTank(int tank) { reads++; return new FluidStack(Fluids.WATER, Integer.MAX_VALUE); }
		@Override public boolean isFluidValid(int tank, FluidStack value) { throw new AssertionError("read only"); }
		@Override public int fill(FluidStack value, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(FluidStack value, FluidAction action) { throw new AssertionError("read only"); }
		@Override public FluidStack drain(int amount, FluidAction action) { throw new AssertionError("read only"); }
		@Override public void close() { capability.invalidate(); }
	}
	private static <T> T allocate(Class<T> type) {
		try {
			var unsafe = Class.forName("sun.misc.Unsafe"); var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
			return Objects.requireNonNull(type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type)));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static void set(Class<?> owner, String name, Object target, Object value) throws ReflectiveOperationException {
		var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
	}
}
