package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Independent object-graph lower bounds; queries must use cached fields, never provider callbacks. */
@NotNullByDefault
class InventorySourceRetentionTest {
	private static InventorySnapshotLayout layout(String block, int members) {
		var entries = new java.util.ArrayList<InventorySnapshotLayout.Member>();
		for (int member = 0; member < members; member++) entries.add(new InventorySnapshotLayout.Member(new BlockPos(member, 0, 0), block, block, "member", List.of(
			new InventorySnapshotLayout.Segment("Items", "minecraft:items", 512, List.of(511, 128, 0)))));
		return new InventorySnapshotLayout("test:layout", "north", BlockPos.ZERO, entries);
	}
	@Test void multipartSidedMappingsAndVaultAuxiliaryStateArePaidOnceAndExternalRetainsNoView() {
		var ordinary = layout("minecraft:chest", 2);
		// Header/fields, detached position, four bounded text objects, segment/list
		// backings and three boxed mapping indices for each independent member.
		long memberFloor = 80 + 52 + 4 * 80 + 2 * 80 + 68 + 80 + 3 * (8 + 44);
		assertTrue(InventorySourceRetention.layout(ordinary) >= 2 * memberFloor);
		var vault = layout("create:item_vault", 81);
		long auxiliary = InventorySourceRetention.provider(vault) - InventorySourceRetention.provider(ordinary);
		// Layout record, member and count reference lists, boxed per-member counts,
		// and int offsets. Positions are borrowed from the one paid snapshot DTO.
		long vaultFloor = 92 + 2 * (80 + 81 * 8) + 81 * 44 + 40 + 82 * 4;
		assertEquals(vaultFloor, auxiliary);
		var view = new InventoryMinecraftSources.View("test:alias", List.of(), List.of(), null, vault, false);
		var target = Target.ExternalBlockTarget.candidate("minecraft:overworld", "sable", "create:item_vault", "locator", true);
		var descriptor = new BlockReadSource(target, "sable", "sublevel", new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "create:item_vault"), new MarkerAnchor(0, 0, 0));
		assertTrue(InventorySourceRetention.external(view, descriptor) >= InventorySourceRetention.layout(vault));
		assertTrue(InventorySourceRetention.external(view, descriptor) < InventorySourceRetention.layout(vault) + vaultFloor,
			"external evidence never retains Vault access/offsets or resident scope");
	}
	@Test void sourceMetadataIsCachedAndReacquiredAccessDoesNotOutliveOperation() throws Exception {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:beehive"), new UUID(91, 1), BlockFace.NORTH);
		int[] callbacks = {0}; boolean[] trap = {false};
		@NotNullByDefault class Access implements IPlatformInventoryService.Access {
			private void callback() { if (trap[0]) throw new AssertionError("unfunded provider callback"); callbacks[0]++; }
			@Override public boolean valid() { callback(); return true; }
			@Override public int slots() { callback(); return 1; }
			@Override public boolean stableCursor() { callback(); return true; }
			@Override public java.util.OptionalLong version() { callback(); return java.util.OptionalLong.empty(); }
			@Override public IPlatformInventoryService.Entry read(int slot) { callback(); return IPlatformInventoryService.Entry.empty(); }
			@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError(); }
		}
		var original = new Access(); var reacquired = new Access();
		var view = new InventoryMinecraftSources.View("test:alias", List.of(), List.of(), original);
		try (var source = new InventoryMinecraftSources.Source(input, view, () -> true,
			() -> new InventoryMinecraftSources.View("test:alias", List.of(), List.of(), reacquired), entry -> null)) {
			trap[0] = true; int before = callbacks[0]; long bytes = source.sourceRetainedBytes();
			assertTrue(bytes > 0); assertEquals(bytes, source.sourceRetainedBytes()); assertEquals(before, callbacks[0]);
			trap[0] = false; assertTrue(source.valid()); assertNull(source.read(0));
			var held = source.getClass().getDeclaredField("liveAccess"); held.setAccessible(true);
			assertSame(original, held.get(source), "no independently built access/layout escapes its validation scratch");
		}
	}
}
