package nx.pingwheel.common.client.outline;

import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import static nx.pingwheel.common.client.outline.BlockModelOutlineRoute.TARGET_TYPE_BLOCK;
import static nx.pingwheel.common.client.outline.BlockModelOutlineRoute.TARGET_TYPE_ENTITY_BLOCK;

/**
 * Immutable, compiled client policy for native block glow versus the
 * shape-based outline. The parser is run when this object is constructed, not
 * during a render frame.
 */
public final class BlockDisplayPolicy {
	private final BlockDisplayWhitelist whitelist;
	private final BlockDisplayWhitelist blacklist;

	private BlockDisplayPolicy(BlockDisplayWhitelist whitelist, BlockDisplayWhitelist blacklist) {
		this.whitelist = Objects.requireNonNull(whitelist, "whitelist");
		this.blacklist = Objects.requireNonNull(blacklist, "blacklist");
	}

	/**
	 * Compiles and strictly validates both configured lists.
	 */
	public static BlockDisplayPolicy compile(List<String> whitelistEntries, List<String> blacklistEntries) {
		BlockDisplayWhitelist.validateEntries(whitelistEntries, "blockDisplayWhitelist");
		BlockDisplayWhitelist.validateEntries(blacklistEntries, "blockShapeBlacklist");

		return new BlockDisplayPolicy(
			new BlockDisplayWhitelist(whitelistEntries),
			new BlockDisplayWhitelist(blacklistEntries));
	}

	/**
	 * The default policy used before a client file is loaded.
	 */
	public static BlockDisplayPolicy defaults() {
		return compile(BlockDisplayWhitelist.DEFAULT_ENTRIES, List.of());
	}

	public List<String> whitelistEntries() {
		return whitelist.entries();
	}

	public List<String> blacklistEntries() {
		return blacklist.entries();
	}

	/**
	 * Decides whether the current block may use its native glow route. A
	 * blacklist match always wins. Entity-block classification is checked here
	 * as a target-safety gate; the renderer still validates the live
	 * BlockEntity/renderer and falls back when that native path fails.
	 */
	public boolean shouldUseNativeGlow(String targetTypeId, BlockState state) {
		Objects.requireNonNull(targetTypeId, "targetTypeId");
		Objects.requireNonNull(state, "state");

		boolean whitelistMatches = switch (targetTypeId) {
			case TARGET_TYPE_BLOCK -> whitelist.matchesOrdinaryBlock(state, BlockTagLookup.minecraft());
			case TARGET_TYPE_ENTITY_BLOCK -> whitelist.matchesEntityBlock(state, BlockTagLookup.minecraft());
			default -> false;
		};

		return whitelistMatches && !blacklist.matches(state, BlockTagLookup.minecraft());
	}

	/**
	 * Headless truth-table seam for callers that already have stable block
	 * facts and a deterministic tag lookup.
	 */
	public boolean shouldUseNativeGlow(
		String targetTypeId,
		ResourceLocation blockKey,
		boolean hasBlockEntity,
		RenderShape renderShape,
		BlockTagLookup tagLookup) {
		Objects.requireNonNull(targetTypeId, "targetTypeId");
		Objects.requireNonNull(renderShape, "renderShape");
		Objects.requireNonNull(tagLookup, "tagLookup");

		boolean whitelistMatches = switch (targetTypeId) {
			case TARGET_TYPE_BLOCK -> !hasBlockEntity
				&& renderShape == RenderShape.MODEL
				&& whitelist.matches(blockKey, tagLookup);
			case TARGET_TYPE_ENTITY_BLOCK -> hasBlockEntity && whitelist.matches(blockKey, tagLookup);
			default -> false;
		};

		return whitelistMatches && !blacklist.matches(blockKey, tagLookup);
	}
}
