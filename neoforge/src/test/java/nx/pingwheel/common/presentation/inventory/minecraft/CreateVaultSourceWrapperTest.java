package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Composes the real lazy segmented Vault provider with the production common wrapper and capture handle. */
class CreateVaultSourceWrapperTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	static final class World {
		final Class<?> worldPort, memberPort;
		final Object port;
		final Method find;
		final Map<BlockPos, Object> members = new HashMap<>();
		Direction.Axis axis = Direction.Axis.X;
		int reads, acquisitions;
		World() throws ReflectiveOperationException {
			String provider = "nx.pingwheel.neoforge.integration.create.presentation.CreateVaultInventoryAccess";
			worldPort = Class.forName(provider + "$World"); memberPort = Class.forName(provider + "$Member");
			find = Class.forName(provider).getDeclaredMethod("find", worldPort, BlockPos.class, Direction.class); find.setAccessible(true);
			port = Proxy.newProxyInstance(worldPort.getClassLoader(), new Class<?>[] {worldPort}, (proxy, method, args) -> switch (method.getName()) {
				case "dimension" -> "minecraft:overworld";
				case "loaded" -> { var member = members.get(args[0]); if (member == null) throw new IllegalStateException("unloaded"); yield member; }
				default -> throw new AssertionError(method);
			});
			layout();
		}
		void layout() {
			members.clear(); int xBound = axis == Direction.Axis.X ? 3 : 2, zBound = axis == Direction.Axis.Z ? 3 : 2;
			for (int x = 0; x < xBound; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < zBound; z++) {
				var pos = new BlockPos(x, y, z);
				IItemHandler local = new IItemHandler() {
					@Override public int getSlots() { return 1; }
					@Override public ItemStack getStackInSlot(int slot) { reads++; return new ItemStack(Items.STONE, axis == Direction.Axis.X ? 7 : 9); }
					@Override public int getSlotLimit(int slot) { return 64; }
					@Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
					@Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { throw new AssertionError("read-only"); }
					@Override public ItemStack extractItem(int slot, int amount, boolean simulate) { throw new AssertionError("read-only"); }
				};
				members.put(pos, Proxy.newProxyInstance(memberPort.getClassLoader(), new Class<?>[] {memberPort}, (proxy, method, args) -> switch (method.getName()) {
					case "blockId" -> "create:item_vault";
					case "blockEntityId" -> "create:item_vault";
					case "controller" -> BlockPos.ZERO;
					case "isController" -> pos.equals(BlockPos.ZERO);
					case "width" -> 2;
					case "length" -> 3;
					case "axis" -> axis;
					case "local" -> local;
					default -> throw new AssertionError(method);
				}));
			}
		}
		IPlatformInventoryService.Access acquire() {
			try {
				acquisitions++;
				@SuppressWarnings("unchecked") var result = (Optional<IPlatformInventoryService.Access>) find.invoke(null, port, BlockPos.ZERO, Direction.NORTH);
				return result.orElseThrow();
			} catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
		}
		InventoryMinecraftSources.View view() {
			var access = acquire(); return new InventoryMinecraftSources.View(access.alias().orElseThrow(), List.of(), List.of(), access);
		}
	}
	static CostLedger grant(int slots) { return new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) slots, InventorySourceAccess.PROBES, 8L, InventorySourceAccess.PROVIDER_WORK, 32768L)); }
	static SourceAccess.Handle open(World world, InventorySourceInput input) {
		var access = new InventorySourceAccess(input, i -> Optional.of(new InventoryMinecraftSources.Source(i, world.view(), () -> true, world::view, entry ->
			new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), entry.amount(), "Stone", null, false))));
		var target = input.ordinaryTarget().orElseThrow(); var scope = new SourceAccess.ReadScope(input.viewKey(), Set.of("pingforit:inventory.items"));
		var descriptor = ((SourceAccess.ResolveResult.Available) access.resolve(new PresentationAdapter.DetachedTarget(target.dimensionId(), "block", target.blockRegistryId(), target.x(), target.y(), target.z(), ""), scope, grant(0))).descriptor();
		return ((SourceAccess.OpenResult.Started) access.open(descriptor, scope, grant(0))).handle();
	}
	@Test void sameControllerTwelveSlotVaultRotatedFromXToZInvalidatesProductionWrapperBeforeCursorContinues() throws ReflectiveOperationException {
		var world = new World(); var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "create:item_vault"), new UUID(1, 1), BlockFace.NORTH);
		var old = world.acquire(); var alias = old.alias();
		try (var handle = open(world, input)) {
			var prefix = ((SourceAccess.StepOutcome.Captured) handle.step(grant(1))).result(); assertEquals(CaptureResult.Completeness.CONTINUE, prefix.completeness()); assertEquals(1, world.reads); assertTrue(world.acquisitions > 2);
			world.axis = Direction.Axis.Z; world.layout(); var newAccess = world.acquire();
			assertEquals(alias, newAccess.alias()); assertEquals(12, newAccess.slots()); assertTrue(newAccess.valid()); assertFalse(old.valid());
			var invalid = ((SourceAccess.StepOutcome.Captured) handle.step(grant(1))).result(); assertEquals(CaptureResult.Availability.INVALID, invalid.availability()); assertTrue(invalid.payload().isEmpty()); assertEquals(1, world.reads);
		}
		try (var recovered = open(world, input)) {
			var complete = ((SourceAccess.StepOutcome.Captured) recovered.step(grant(12))).result(); assertEquals(CaptureResult.Completeness.COMPLETE, complete.completeness());
			var values = ((CaptureResult.OpaqueKeyedFragment) complete.payload().orElseThrow()).entries(); assertEquals(12, values.size()); assertEquals(108, values.values().stream().mapToLong(v -> InventoryDomainCodec.decode(v).count()).sum());
		}
	}
}
