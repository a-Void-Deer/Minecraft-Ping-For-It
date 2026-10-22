package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VanillaInventorySourceTest {

	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void chestAliasIsSymmetricPositionOrderedAndIdempotent() {
		BlockPos first = new BlockPos(10, 64, -3);
		BlockPos second = new BlockPos(10, 64, -4);
		String forward = VanillaInventorySource.chestAlias("minecraft:overworld", first, second, "minecraft:chest");

		assertEquals(forward,
			VanillaInventorySource.chestAlias("minecraft:overworld", second, first, "minecraft:chest"));
		assertEquals(forward,
			VanillaInventorySource.chestAlias("minecraft:overworld", first, second, "minecraft:chest"));
		assertTrue(forward.contains("minecraft:overworld"));
		assertTrue(forward.contains("minecraft:chest"));
		assertNotEquals(forward,
			VanillaInventorySource.chestAlias("minecraft:the_nether", first, second, "minecraft:chest"));
		assertNotEquals(forward,
			VanillaInventorySource.chestAlias("minecraft:overworld", first, second, "minecraft:trapped_chest"));
	}

	@Test
	void blockAndEntityIdentitiesAreDeterministicAndDistinct() {
		BlockPos pos = new BlockPos(1, 2, 3);
		String barrel = VanillaInventorySource.simpleBlockId("minecraft:overworld", pos, "minecraft:barrel");
		assertEquals(barrel,
			VanillaInventorySource.simpleBlockId("minecraft:overworld", new BlockPos(1, 2, 3), "minecraft:barrel"));
		assertNotEquals(barrel,
			VanillaInventorySource.simpleBlockId("minecraft:overworld", pos, "minecraft:chest"));
		assertNotEquals(barrel,
			VanillaInventorySource.simpleBlockId("minecraft:the_nether", pos, "minecraft:barrel"));

		UUID uuid = UUID.fromString("f2c7c5b0-9b1a-4c3d-8e2f-0a1b2c3d4e5f");
		String minecart = VanillaInventorySource.entityStableId("minecraft:overworld", uuid, "minecraft:chest_minecart");
		assertEquals(minecart,
			VanillaInventorySource.entityStableId("minecraft:overworld", uuid, "minecraft:chest_minecart"));
		assertNotEquals(minecart, VanillaInventorySource.entityStableId("minecraft:overworld",
			UUID.fromString("00000000-0000-0000-0000-000000000001"), "minecraft:chest_minecart"));
	}

	@Test
	void doubleChestHalfPositionsShareOneStableIdentity() {
		BlockPos first = new BlockPos(4, 64, 4);
		BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT);
		BlockPos second = first.relative(ChestBlock.getConnectedDirection(left));
		BlockState right = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT);

		assertEquals(
			VanillaInventorySource.blockStableId("minecraft:overworld", first, left),
			VanillaInventorySource.blockStableId("minecraft:overworld", second, right));
	}

	@Test
	void chestHalvesCoverBothHalvesOnlyForDoubleChests() {
		BlockPos pos = new BlockPos(4, 64, 4);
		BlockState single = Blocks.CHEST.defaultBlockState();
		assertEquals(List.of(pos), VanillaInventorySource.chestHalves(single, pos));

		BlockState left = single.setValue(ChestBlock.TYPE, ChestType.LEFT);
		List<BlockPos> halves = VanillaInventorySource.chestHalves(left, pos);
		assertEquals(2, halves.size());
		assertEquals(pos, halves.get(0));
		assertEquals(pos.relative(ChestBlock.getConnectedDirection(left)), halves.get(1));
	}
}
