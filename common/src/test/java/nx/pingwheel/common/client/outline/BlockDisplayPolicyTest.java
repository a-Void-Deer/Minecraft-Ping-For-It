package nx.pingwheel.common.client.outline;

import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.RenderShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockDisplayPolicyTest {
	private static final ResourceLocation STONE = ResourceLocation.fromNamespaceAndPath("minecraft", "stone");
	private static final ResourceLocation CHEST = ResourceLocation.fromNamespaceAndPath("minecraft", "chest");

	@Test
	void whitelistAndBlacklistTruthTableCoversBothBlockTargetTypes() {
		BlockTagLookup noTags = (tag, key) -> false;

		assertTruthTable("block", STONE, false, RenderShape.MODEL, noTags);
		assertTruthTable("entity_block", CHEST, true, RenderShape.ENTITYBLOCK_ANIMATED, noTags);
		assertFalse(BlockDisplayPolicy.compile(List.of("*:*"), List.of())
			.shouldUseNativeGlow("location", STONE, false, RenderShape.MODEL, noTags));
	}

	private static void assertTruthTable(
		String targetType,
		ResourceLocation block,
		boolean hasBlockEntity,
		RenderShape renderShape,
		BlockTagLookup tagLookup) {
		for (boolean whitelisted : new boolean[] {false, true}) {
			for (boolean blacklisted : new boolean[] {false, true}) {
				BlockDisplayPolicy policy = BlockDisplayPolicy.compile(
					whitelisted ? List.of("*:*") : List.of(),
					blacklisted ? List.of("*:*") : List.of());
				assertEquals(
					whitelisted && !blacklisted,
					policy.shouldUseNativeGlow(targetType, block, hasBlockEntity, renderShape, tagLookup),
					() -> targetType + " truth table W=" + whitelisted + ", B=" + blacklisted);
			}
		}
	}

	@Test
	void targetSafetyStillPreventsOrdinaryNativeGlowForEntityBlocksAndNonModelStates() {
		BlockDisplayPolicy policy = BlockDisplayPolicy.compile(List.of("*:*"), List.of());
		BlockTagLookup noTags = (tag, key) -> false;

		assertFalse(policy.shouldUseNativeGlow("block", CHEST, true, RenderShape.MODEL, noTags));
		assertFalse(policy.shouldUseNativeGlow("block", STONE, false, RenderShape.INVISIBLE, noTags));
		assertFalse(policy.shouldUseNativeGlow("entity_block", STONE, false, RenderShape.MODEL, noTags));
	}

	@Test
	void tagsRemainUnionedAndMissingTagsFailSoft() {
		BlockDisplayPolicy policy = BlockDisplayPolicy.compile(List.of("#minecraft:test_tag"), List.of());

		assertTrue(policy.shouldUseNativeGlow(
			"block", STONE, false, RenderShape.MODEL,
			(tag, key) -> tag.location().getPath().equals("test_tag")));
		assertFalse(policy.shouldUseNativeGlow("block", STONE, false, RenderShape.MODEL, (tag, key) -> false));
	}
}
