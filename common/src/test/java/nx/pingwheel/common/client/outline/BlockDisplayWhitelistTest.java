package nx.pingwheel.common.client.outline;

import java.util.List;
import java.util.Optional;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.RenderShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the fixed ordinary-block display whitelist: entry
 * grammar (exact / namespace wildcard / tag / invalid), union semantics with
 * explicit declaration order, the BlockEntity and non-MODEL render-shape
 * gates, and fail-soft behavior for invalid, missing, and unregistered
 * content.
 *
 * <p>Vanilla {@code Block} construction is impossible in this headless JVM
 * ({@code SoundType} initialization fails), so evaluation goes through the
 * headless-safe core
 * ({@link BlockDisplayWhitelist#matches(ResourceLocation, boolean, RenderShape, BlockTagLookup)})
 * with stable facts and an injectable {@link BlockTagLookup}; no fake
 * Minecraft language or resource files are used.
 */
class BlockDisplayWhitelistTest {

	private static final ResourceLocation STONE = ResourceLocation.fromNamespaceAndPath("minecraft", "stone");
	private static final ResourceLocation DIRT = ResourceLocation.fromNamespaceAndPath("minecraft", "dirt");
	private static final ResourceLocation CHEST = ResourceLocation.fromNamespaceAndPath("minecraft", "chest");

	private static boolean matches(BlockDisplayWhitelist whitelist, ResourceLocation key) {
		return whitelist.matches(key, false, RenderShape.MODEL, (tag, k) -> false);
	}

	private static boolean matches(BlockDisplayWhitelist whitelist, ResourceLocation key, BlockTagLookup tagLookup) {
		return whitelist.matches(key, false, RenderShape.MODEL, tagLookup);
	}

	// --- default entries ---

	@Test
	void defaultEntriesAreExactlyMinecraftNamespaceWildcard() {
		BlockDisplayWhitelist whitelist = BlockDisplayWhitelist.builtIn();

		assertEquals(List.of("minecraft:*"), whitelist.entries());
		assertEquals(1, whitelist.parsedEntries().size());
		assertTrue(matches(whitelist, STONE));
		assertTrue(matches(whitelist, DIRT));
	}

	// --- parsing ---

	@Test
	void parsesExactEntry() {
		BlockDisplayWhitelistEntry entry = BlockDisplayWhitelistEntry.tryParse("minecraft:stone").orElseThrow();

		assertTrue(entry instanceof BlockDisplayWhitelistEntry.Exact);
		assertEquals("stone", ((BlockDisplayWhitelistEntry.Exact) entry).location().getPath());
	}

	@Test
	void parsesNamespaceWildcardEntry() {
		BlockDisplayWhitelistEntry entry = BlockDisplayWhitelistEntry.tryParse("modded:*").orElseThrow();

		assertTrue(entry instanceof BlockDisplayWhitelistEntry.NamespaceWildcard);
		assertEquals("modded", ((BlockDisplayWhitelistEntry.NamespaceWildcard) entry).namespace());
	}

	@Test
	void parsesTagEntryAgainstModernBlockTagKey() {
		BlockDisplayWhitelistEntry entry = BlockDisplayWhitelistEntry.tryParse("#minecraft:planks").orElseThrow();

		assertTrue(entry instanceof BlockDisplayWhitelistEntry.Tag);
		assertEquals(BlockTags.PLANKS, ((BlockDisplayWhitelistEntry.Tag) entry).tagKey());
	}

	@Test
	void invalidGrammarFailsFalseWithoutThrowing() {
		for (String invalid : List.of(
			"", " ", "stone", "*", "minecraft", "minecraft:", ":stone", "minecraft:foo:bar",
			"#", "#planks", "#minecraft:", "#minecraft:planks:*", "#minecraft:*", "#*:planks",
			"minecraft:foo*", "minecraft:*bar", "minecraft:planks:*", "minecraft::*"
		)) {
			assertTrue(BlockDisplayWhitelistEntry.tryParse(invalid).isEmpty(), "should reject: '" + invalid + "'");
		}
	}

	@Test
	void invalidRawEntriesAreDroppedFromEvaluationButKeptInDeclarationOrder() {
		BlockDisplayWhitelist whitelist = new BlockDisplayWhitelist(List.of("nonsense", "minecraft:stone", "minecraft:*", "also:nonsense:bad"));

		assertEquals(List.of("nonsense", "minecraft:stone", "minecraft:*", "also:nonsense:bad"), whitelist.entries());
		assertEquals(2, whitelist.parsedEntries().size());
		assertTrue(whitelist.parsedEntries().get(0) instanceof BlockDisplayWhitelistEntry.Exact);
		assertTrue(whitelist.parsedEntries().get(1) instanceof BlockDisplayWhitelistEntry.NamespaceWildcard);
		// the valid exact entry still matches stone, proving invalid entries are
		// dropped rather than poisoning evaluation
		assertTrue(matches(whitelist, STONE));
	}

	@Test
	void entriesListIsImmutable() {
		assertThrows(UnsupportedOperationException.class, () -> BlockDisplayWhitelist.builtIn().entries().add("x"));
	}

	// --- evaluation ---

	@Test
	void exactEntryMatchesOnlyTheNamedBlock() {
		BlockDisplayWhitelist whitelist = new BlockDisplayWhitelist(List.of("minecraft:stone"));

		assertTrue(matches(whitelist, STONE));
		assertFalse(matches(whitelist, DIRT));
	}

	@Test
	void namespaceWildcardMatchesAnyBlockInTheNamespace() {
		BlockDisplayWhitelist whitelist = new BlockDisplayWhitelist(List.of("minecraft:*"));

		assertTrue(matches(whitelist, STONE));
		assertTrue(matches(whitelist, DIRT));
		assertFalse(matches(whitelist, ResourceLocation.fromNamespaceAndPath("modded", "anything")));
	}

	@Test
	void tagEntryUsesTheInjectedModernTagLookup() {
		BlockDisplayWhitelist whitelist = new BlockDisplayWhitelist(List.of("#minecraft:test_tag"));

		assertFalse(matches(whitelist, STONE, (tag, key) -> false));
		assertTrue(matches(whitelist, STONE, (tag, key) -> true));
		assertTrue(matches(whitelist, STONE, (tag, key) -> tag.location().getPath().equals("test_tag")));
		assertFalse(matches(whitelist, STONE, (tag, key) -> tag.location().getPath().equals("other_tag")));
	}

	@Test
	void unionSemanticsWithExplicitDeclarationOrder() {
		BlockDisplayWhitelist whitelist = new BlockDisplayWhitelist(List.of("minecraft:dirt", "#minecraft:test_tag"));

		// dirt matches the exact entry directly; stone matches via the tag entry.
		assertTrue(matches(whitelist, DIRT, (tag, key) -> false));
		assertTrue(matches(whitelist, STONE, (tag, key) -> true));
		assertFalse(matches(whitelist, STONE, (tag, key) -> false));
	}

	@Test
	void blockEntityBlocksNeverWhitelistMatch() {
		BlockDisplayWhitelist whitelist = new BlockDisplayWhitelist(List.of("minecraft:chest", "minecraft:*"));

		// A chest-like candidate owns a BlockEntity: the gate rejects it even
		// though both entries would otherwise match.
		assertFalse(whitelist.matches(CHEST, true, RenderShape.MODEL, (tag, key) -> false));
		assertFalse(whitelist.matches(CHEST, true, RenderShape.ENTITYBLOCK_ANIMATED, (tag, key) -> false));
	}

	@Test
	void nonModelRenderShapesNeverWhitelistMatch() {
		BlockDisplayWhitelist whitelist = new BlockDisplayWhitelist(List.of("minecraft:*"));

		// Lava-like candidates render as INVISIBLE and own no BlockEntity: the
		// gate must reject them on the render-shape criterion alone.
		assertFalse(whitelist.matches(STONE, false, RenderShape.INVISIBLE, (tag, key) -> false));
		assertFalse(whitelist.matches(STONE, false, RenderShape.ENTITYBLOCK_ANIMATED, (tag, key) -> false));
	}

	@Test
	void missingRegistryIdFailsSoftWithoutMatching() {
		BlockDisplayWhitelist whitelist = new BlockDisplayWhitelist(List.of("minecraft:*"));

		// An unregistered/absent block has no registry key: fail false.
		assertFalse(whitelist.matches(null, false, RenderShape.MODEL, (tag, key) -> true));
	}

	@Test
	void nullRegistryIdFailsFalseEvenWhenATagLookupWouldMatch() {
		BlockDisplayWhitelist whitelist = new BlockDisplayWhitelist(List.of("#minecraft:test_tag"));

		// The tag lookup answers true even for a null key, but an unregistered
		// block has no identity: the core must fail false before any exact,
		// wildcard, or tag evaluation.
		assertFalse(whitelist.matches(null, false, RenderShape.MODEL, (tag, key) -> true));
	}

	@Test
	void parsedEntriesCarryTheExactLocation() {
		Optional<BlockDisplayWhitelistEntry> parsed = BlockDisplayWhitelistEntry.tryParse("minecraft:stone");

		assertTrue(parsed.isPresent());
		assertEquals("minecraft:stone", ((BlockDisplayWhitelistEntry.Exact) parsed.orElseThrow()).location().toString());
	}
}
