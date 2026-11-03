package nx.pingwheel.common.client.outline;

import java.util.List;
import java.util.Objects;

import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The compiled block-display (glow) policy: a whitelist and a shape blacklist
 * of {@link BlockDisplayWhitelist} entries, plus the per-target-type routing
 * decision.
 *
 * <p>Restored from the 10/26 build (decompiled): the M3b placeholder only kept
 * the source entries, so the native-glow routing decision had silently
 * disappeared. Whitelist/blacklist evaluation is pure and headless-safe: it
 * resolves block registry ids and {@code Block} tag keys only, never optional
 * mod classes.
 */
public final class BlockDisplayPolicy {

	private final BlockDisplayWhitelist whitelist;
	private final BlockDisplayWhitelist blacklist;

	private BlockDisplayPolicy(BlockDisplayWhitelist whitelist, BlockDisplayWhitelist blacklist) {
		this.whitelist = Objects.requireNonNull(whitelist, "whitelist");
		this.blacklist = Objects.requireNonNull(blacklist, "blacklist");
	}

	public static BlockDisplayPolicy compile(List<String> whitelistEntries, List<String> blacklistEntries) {
		BlockDisplayWhitelist.validateEntries(whitelistEntries, "blockDisplayWhitelist");
		BlockDisplayWhitelist.validateEntries(blacklistEntries, "blockShapeBlacklist");

		return new BlockDisplayPolicy(
			new BlockDisplayWhitelist(whitelistEntries),
			new BlockDisplayWhitelist(blacklistEntries));
	}

	public static BlockDisplayPolicy defaults() {
		return compile(BlockDisplayWhitelist.DEFAULT_ENTRIES, List.of());
	}

	public List<String> whitelistEntries() {
		return this.whitelist.entries();
	}

	public List<String> blacklistEntries() {
		return this.blacklist.entries();
	}

	public boolean shouldUseNativeGlow(String targetTypeId, BlockState state) {
		Objects.requireNonNull(targetTypeId, "targetTypeId");
		Objects.requireNonNull(state, "state");

		boolean whitelistMatches = switch (targetTypeId) {
			case "block" -> this.whitelist.matchesOrdinaryBlock(state, BlockTagLookup.minecraft());
			case "entity_block" -> this.whitelist.matchesEntityBlock(state, BlockTagLookup.minecraft());
			default -> false;
		};

		return whitelistMatches && !this.blacklist.matches(state, BlockTagLookup.minecraft());
	}

	public boolean shouldUseNativeGlow(
		String targetTypeId,
		net.minecraft.resources.Identifier blockKey,
		boolean hasBlockEntity,
		RenderShape renderShape,
		BlockTagLookup tagLookup
	) {
		Objects.requireNonNull(targetTypeId, "targetTypeId");
		Objects.requireNonNull(renderShape, "renderShape");
		Objects.requireNonNull(tagLookup, "tagLookup");

		boolean whitelistMatches = switch (targetTypeId) {
			case "block" -> !hasBlockEntity
				&& renderShape == RenderShape.MODEL
				&& this.whitelist.matches(blockKey, tagLookup);
			case "entity_block" -> hasBlockEntity && this.whitelist.matches(blockKey, tagLookup);
			default -> false;
		};

		return whitelistMatches && !this.blacklist.matches(blockKey, tagLookup);
	}
}
