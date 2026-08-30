package nx.pingwheel.common.client.outline;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefinedStorageCableBlockMatcherTest {
	private static final ResourceLocation CABLE_ID =
		ResourceLocation.fromNamespaceAndPath("refinedstorage", "cable");

	@Test
	void acceptsBothSupportedCableClassNames() {
		assertTrue(RefinedStorageCableBlockMatcher.matches(
			CABLE_ID, List.of(RefinedStorageCableBlockMatcher.CABLE_BLOCK_CLASS)));
		assertTrue(RefinedStorageCableBlockMatcher.matches(
			CABLE_ID, List.of(RefinedStorageCableBlockMatcher.DIRECTIONAL_CABLE_BLOCK_CLASS)));
	}

	@Test
	void acceptsSupportedClassInTheMiddleOfAHierarchy() {
		assertTrue(RefinedStorageCableBlockMatcher.matches(
			CABLE_ID,
			List.of(
				"com.refinedmods.refinedstorage.common.networking.ImportCableBlock",
				RefinedStorageCableBlockMatcher.CABLE_BLOCK_CLASS,
				"net.minecraft.world.level.block.Block")));
	}

	@Test
	void rejectsOtherRefinedStorageBlocks() {
		assertFalse(RefinedStorageCableBlockMatcher.matches(
			CABLE_ID,
			List.of("com.refinedmods.refinedstorage.common.networking.ControllerBlock")));
		assertFalse(RefinedStorageCableBlockMatcher.matches(
			CABLE_ID,
			List.of("com.refinedmods.refinedstorage.common.grid.GridBlock", "net.minecraft.world.level.block.Block")));
	}

	@Test
	void requiresTheRefinedStorageNamespace() {
		assertFalse(RefinedStorageCableBlockMatcher.matches(
			ResourceLocation.fromNamespaceAndPath("minecraft", "cable"),
			List.of(RefinedStorageCableBlockMatcher.CABLE_BLOCK_CLASS)));
		assertFalse(RefinedStorageCableBlockMatcher.matches(
			ResourceLocation.fromNamespaceAndPath("refinedstorage2", "cable"),
			List.of(RefinedStorageCableBlockMatcher.DIRECTIONAL_CABLE_BLOCK_CLASS)));
	}
}
