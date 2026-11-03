package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import nx.pingwheel.common.config.ConfigValidationException;
import nx.pingwheel.common.resolve.BlockEntityClassification;

/**
 * An immutable matcher for one client block glow list.
 *
 * <p>The same matcher is used for the client whitelist and shape blacklist.
 * The caller applies target-type safety gates before choosing a renderer. It
 * is not a datapack or sync system: entries are immutable after construction
 * and evaluated on demand through a stable, declaration-ordered list.
 *
 * <p>Grammar and semantics:
 * <ul>
	 *   <li>exact {@code namespace:block}, namespace wildcard {@code namespace:*},
	 *       global wildcard {@code *:*}, and block tag {@code #namespace:tag};</li>
 *   <li>union semantics with explicit declaration order;</li>
 *   <li>invalid entries fail false (never match) and are dropped from direct
 *       evaluation without throwing; configured lists use
 *       {@link #validateEntries(List, String)} and therefore reject them;</li>
 *   <li>unregistered blocks (missing/absent registry ids) fail false;</li>
 *   <li>optional-mod content is never referenced or loaded; an absent tag
 *       simply answers false through {@link BlockTagLookup}.</li>
 * </ul>
 *
 * <p>The core evaluation takes only stable, headless-safe inputs. The
 * {@link BlockState} overloads are thin adapters that extract those facts
 * from live game state.
 */
public final class BlockDisplayWhitelist {

	/**
	 * The default entries, in declaration order. The global wildcard keeps the
	 * historical vanilla behavior while allowing all registered namespaces.
	 */
	public static final List<String> DEFAULT_ENTRIES = List.of("*:*");

	private final List<String> rawEntries;
	private final List<BlockDisplayWhitelistEntry> parsedEntries;

	public BlockDisplayWhitelist(List<String> entries) {
		Objects.requireNonNull(entries, "entries");

		this.rawEntries = List.copyOf(entries);

		List<BlockDisplayWhitelistEntry> parsed = new ArrayList<>(rawEntries.size());

		for (String raw : rawEntries) {
			BlockDisplayWhitelistEntry.tryParse(raw).ifPresent(parsed::add);
		}

		// Valid entries keep their relative declaration order; invalid entries
		// are dropped from evaluation but remain visible via entries().
		this.parsedEntries = List.copyOf(parsed);
	}

	/**
	 * Validates a configured matcher list without silently discarding any
	 * element. Null lists, null/blank elements, and malformed grammar are
	 * configuration errors.
	 */
	public static void validateEntries(List<String> entries, String fieldName) {
		Objects.requireNonNull(fieldName, "fieldName");

		if (entries == null) {
			throw new ConfigValidationException(
				fieldName,
				-1,
				ConfigValidationException.Reason.NULL_LIST);
		}

		for (int index = 0; index < entries.size(); index++) {
			String raw = entries.get(index);

			if (raw == null || raw.isBlank()) {
				throw new ConfigValidationException(
					fieldName,
					index,
					ConfigValidationException.Reason.BLANK_ENTRY);
			}

			if (BlockDisplayWhitelistEntry.tryParse(raw).isEmpty()) {
				throw new ConfigValidationException(
					fieldName,
					index,
					ConfigValidationException.Reason.INVALID_GRAMMAR);
			}
		}
	}

	/**
	 * A matcher with the default entries.
	 */
	public static BlockDisplayWhitelist builtIn() {
		return new BlockDisplayWhitelist(DEFAULT_ENTRIES);
	}

	/**
	 * The raw entry strings in explicit declaration order.
	 */
	public List<String> entries() {
		return rawEntries;
	}

	/**
	 * The successfully parsed entries in declaration order (invalid raw
	 * entries are absent).
	 */
	public List<BlockDisplayWhitelistEntry> parsedEntries() {
		return parsedEntries;
	}

	/**
	 * Whether the registered block key for {@code state} matches this list,
 * using the production Minecraft tag lookup. Must be called on the game
 * thread. Target-type/render-shape gates are exposed separately through
 * {@link #matchesOrdinaryBlock(BlockState, BlockTagLookup)} and
 * {@link #matchesEntityBlock(BlockState, BlockTagLookup)}.
	 */
	public boolean matches(BlockState state) {
		return matches(state, BlockTagLookup.minecraft());
	}

	/**
	 * Whether {@code state} is a whitelisted ordinary block, using the given
	 * (possibly injected, headless-safe) tag lookup. Must be called on the
	 * game thread.
	 */
	public boolean matches(BlockState state, BlockTagLookup tagLookup) {
		Objects.requireNonNull(state, "state");

		return matches(
			BuiltInRegistries.BLOCK.getKey(state.getBlock()),
			tagLookup);
	}

	/**
	 * Whether {@code state} is safe for the ordinary {@code BlockDisplay}
	 * route and matches this list.
	 */
	public boolean matchesOrdinaryBlock(BlockState state, BlockTagLookup tagLookup) {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(tagLookup, "tagLookup");

		return !BlockEntityClassification.hasBlockEntity(state.getBlock())
			&& state.getRenderShape() == RenderShape.MODEL
			&& matches(BuiltInRegistries.BLOCK.getKey(state.getBlock()), tagLookup);
	}

	/**
	 * Whether {@code state} owns a block entity and matches this list. The
	 * actual renderer and block-entity instance are validated by the render
	 * pass before any native glow is recorded.
	 */
	public boolean matchesEntityBlock(BlockState state, BlockTagLookup tagLookup) {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(tagLookup, "tagLookup");

		return BlockEntityClassification.hasBlockEntity(state.getBlock())
			&& matches(BuiltInRegistries.BLOCK.getKey(state.getBlock()), tagLookup);
	}

	/**
	 * The headless-safe evaluation core: whether the registered candidate block
	 * key matches this list. The additional arguments are retained for source
	 * compatibility with the original headless test seam; target-type safety is
	 * deliberately applied by the policy's typed methods above.
	 *
	 * <p>A {@code null} blockKey (unregistered block) fails false immediately,
	 * before any exact, wildcard, or tag evaluation — even when a tag lookup
	 * would otherwise answer true.
	 */
	public boolean matches(ResourceLocation blockKey, boolean hasBlockEntity, RenderShape renderShape, BlockTagLookup tagLookup) {
		Objects.requireNonNull(renderShape, "renderShape");
		Objects.requireNonNull(tagLookup, "tagLookup");

		return matches(blockKey, tagLookup);
	}

	/**
	 * Matches a registered block key. A null key represents absent registry
	 * content and always fails softly.
	 */
	public boolean matches(ResourceLocation blockKey, BlockTagLookup tagLookup) {
		Objects.requireNonNull(tagLookup, "tagLookup");

		if (blockKey == null) {
			return false;
		}

		for (BlockDisplayWhitelistEntry entry : parsedEntries) {
			if (entry.matches(blockKey, tagLookup)) {
				return true;
			}
		}

		return false;
	}
}
