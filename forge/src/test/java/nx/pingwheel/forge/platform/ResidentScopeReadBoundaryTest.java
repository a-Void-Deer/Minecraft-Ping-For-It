package nx.pingwheel.forge.platform;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.wrapper.InvWrapper;
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

	@Test void knownInvWrapperRetainsEveryExplicitLoaderGate() {
		var container = new SimpleContainer(1);
		container.setItem(0, new ItemStack(Items.STONE, 3));
		var scope = new CountingScope(container);
		var access = new PlatformInventoryServiceImpl.HandlerAccess(new InvWrapper(container), () -> true)
			.scopedResidents(scope);
		assertEquals(3L, access.readResident(0).amount());
		assertEquals(4, scope.validCalls, "four explicit gates, excluding production scope's internal lookup checks");
		assertEquals(3, scope.allowsCalls);
		assertEquals(3, scope.slotsCalls);
	}
}
