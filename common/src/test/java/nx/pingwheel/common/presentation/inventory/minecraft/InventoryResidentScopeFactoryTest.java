package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryResidentScopeFactoryTest {
	private static final BlockPos OTHER = new BlockPos(1, 0, 0);
	private static final InventorySourceInput INPUT = new InventorySourceInput(
		new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:beehive"), new UUID(85, 1), BlockFace.NORTH);
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

	@NotNullByDefault
	private static final class World implements InventoryMinecraftSources.SourceWorld {
		final BlockEntity root = new BeehiveBlockEntity(BlockPos.ZERO, Blocks.BEEHIVE.defaultBlockState());
		final BlockEntity other = new BeehiveBlockEntity(OTHER, Blocks.BEEHIVE.defaultBlockState());
		boolean otherLoaded = true;
		int memberEntities;
		@Override public boolean isLoaded(BlockPos position) { return position.equals(BlockPos.ZERO) || position.equals(OTHER) && otherLoaded; }
		@Override public BlockState blockState(BlockPos position) { return Blocks.BEEHIVE.defaultBlockState(); }
		@Override public BlockEntity blockEntity(BlockPos position) { if (position.equals(OTHER)) memberEntities++; return position.equals(BlockPos.ZERO) ? root : other; }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
		@Override public HolderLookup.Provider registries() { return RegistryAccess.EMPTY; }
	}
	private static InventorySnapshotLayout layout() {
		var segment = new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, 1, List.of(0));
		return new InventorySnapshotLayout("test:scope", "two-members", BlockPos.ZERO, List.of(
			new InventorySnapshotLayout.Member(BlockPos.ZERO, "minecraft:beehive", "minecraft:beehive", "target", List.of(segment)),
			new InventorySnapshotLayout.Member(OTHER, "minecraft:beehive", "minecraft:beehive", "member", List.of(segment))));
	}
	@NotNullByDefault
	private static class Access implements IPlatformInventoryService.Access {
		final InventorySnapshotLayout layout = layout();
		@Override public Optional<InventorySnapshotLayout> snapshotLayout() { return Optional.of(layout); }
		@Override public int slots() { return 2; }
		@Override public boolean stableCursor() { return true; }
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public IPlatformInventoryService.Entry read(int slot) { throw new AssertionError("content getter during view construction"); }
		@Override public boolean visit(int limit, Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("content getter during view construction"); }
	}

	@Test void decliningScopeCannotDeferAnySourceOwnedMemberPrecheck() {
		var world = new World();
		var access = new Access() {
			int bindings;
			@Override public IPlatformInventoryService.Access scopedResidents(Supplier<? extends IPlatformInventoryService.ResidentScope> factory) {
				assertEquals(1, world.memberEntities, "all member identities and read conditions precede the optional scope");
				bindings++; return this;
			}
			@Override public IPlatformInventoryService.Access scopedResidents(IPlatformInventoryService.ResidentScope scope) {
				throw new AssertionError("a declining provider must not construct the legacy scope");
			}
		};
		assertSame(access, InventoryMinecraftSources.view(world, INPUT, (pos, face) -> Optional.of(access)).access());
		assertEquals(1, access.bindings);
		world.otherLoaded = false;
		assertThrows(InventorySourceUnavailableException.class, () -> InventoryMinecraftSources.view(world, INPUT, (pos, face) -> Optional.of(access)));
		assertEquals(1, access.bindings, "an unavailable member fails before the factory API is called");
	}

	@Test void legacyConsumerReceivesFreshScopeWithTheSameFrozenFaceAndDynamicAuthority() {
		var world = new World();
		var access = new Access() {
			IPlatformInventoryService.@Nullable ResidentScope scope;
			@Override public IPlatformInventoryService.Access scopedResidents(IPlatformInventoryService.ResidentScope scope) { this.scope = scope; return this; }
		};
		InventoryMinecraftSources.view(world, INPUT, (pos, face) -> Optional.of(access));
		var first = java.util.Objects.requireNonNull(access.scope);
		InventoryMinecraftSources.view(world, INPUT, (pos, face) -> Optional.of(access));
		var second = java.util.Objects.requireNonNull(access.scope);
		assertNotSame(first, second); assertEquals(net.minecraft.core.Direction.NORTH, first.face());
		assertTrue(first.valid()); assertTrue(second.valid());
		world.otherLoaded = false;
		assertFalse(first.valid()); assertFalse(second.valid(), "fresh scopes do not cache world validity");
	}
}
