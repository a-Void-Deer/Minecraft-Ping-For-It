package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import nx.pingwheel.common.resolve.BlockEntityClassification;

/**
 * The fixed, code-defined ordinary-block display whitelist.
 *
 * <p>This whitelist decides the renderer choice for <em>non-BlockEntity</em>
 * blocks only: a whitelisted ordinary block may use the vanilla model glow,
 * while a non-whitelisted ordinary block uses the VoxelShape outline.
 * {@code entity_block} targets never use this whitelist — they render through
 * their actual {@code BlockEntityRenderer} glow. It is not a config, datapack,
 * or sync system: entries are hard-coded (default exactly
 * {@link #DEFAULT_ENTRIES}) and evaluated on demand.
 *
 * <p>Grammar and semantics:
 * <ul>
 *   <li>exact {@code namespace:block}, namespace wildcard {@code namespace:*},
 *       and block tag {@code #namespace:tag};</li>
 *   <li>union semantics with explicit declaration order;</li>
 *   <li>invalid entries fail false (never match) and are dropped from
 *       evaluation without throwing;</li>
 *   <li>blocks that own a {@code BlockEntity} or whose render shape is not
 *       {@link RenderShape#MODEL} never whitelist-match: they take the
 *       {@code entity_block} renderer or the VoxelShape fallback;</li>
 *   <li>unregistered blocks (missing/absent registry ids) fail false;</li>
 *   <li>optional-mod content is never referenced or loaded; an absent tag
 *       simply answers false through {@link BlockTagLookup}.</li>
 * </ul>
 *
 * <p>The core evaluation
 * ({@link #matches(ResourceLocation, boolean, RenderShape, BlockTagLookup)})
 * takes only stable, headless-safe inputs — a registry key, the derived
 * BlockEntity classification, the render shape, and the tag lookup — so the
 * whole grammar and gate are unit-testable without constructing vanilla
 * blocks. The {@link BlockState} overloads are thin adapters that extract
 * those facts from live game state.
 */
public final class BlockDisplayWhitelist {

	/**
	 * The confirmed default entries, in declaration order.
	 */
	public static final List<String> DEFAULT_ENTRIES = List.of("minecraft:*");

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
	 * The whitelist with the confirmed default entries.
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
	 * Whether {@code state} is a whitelisted ordinary block, using the
	 * production Minecraft tag lookup. Must be called on the game thread.
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
			BlockEntityClassification.hasBlockEntity(state.getBlock()),
			state.getRenderShape(),
			tagLookup);
	}

	/**
	 * The headless-safe evaluation core: whether the candidate block described
	 * by the stable facts {@code blockKey} (registry id, {@code null} when
	 * unregistered), {@code hasBlockEntity}, and {@code renderShape} is a
	 * whitelisted ordinary block.
	 *
	 * <p>Applies the fixed gate first: a block that owns a {@code BlockEntity}
	 * is an {@code entity_block} target and a block whose render shape is not
	 * {@code MODEL} cannot use the ordinary model-glow path — both must take
	 * the {@code entity_block} renderer or the VoxelShape fallback.
	 *
	 * <p>A {@code null} blockKey (unregistered block) fails false immediately,
	 * before any exact, wildcard, or tag evaluation — even when a tag lookup
	 * would otherwise answer true.
	 */
	public boolean matches(ResourceLocation blockKey, boolean hasBlockEntity, RenderShape renderShape, BlockTagLookup tagLookup) {
		Objects.requireNonNull(renderShape, "renderShape");
		Objects.requireNonNull(tagLookup, "tagLookup");

		if (blockKey == null) {
			return false;
		}

		if (hasBlockEntity) {
			return false;
		}

		if (renderShape != RenderShape.MODEL) {
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
