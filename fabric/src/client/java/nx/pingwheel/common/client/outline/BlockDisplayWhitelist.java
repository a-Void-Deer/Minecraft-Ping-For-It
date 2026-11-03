package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import nx.pingwheel.common.config.ConfigValidationException;
import nx.pingwheel.common.resolve.BlockEntityClassification;

/**
 * A parsed block glow-list: the raw config strings plus the strict parsed
 * entries, with the matching helpers the block-presentation policy needs.
 *
 * <p>Restored from the 10/26 build (decompiled). The M3b placeholder only kept
 * an always-accepting {@code validateEntries}, so malformed entries silently
 * passed through and the matching helpers were gone. Parsing itself is strict
 * and headless-safe: registry ids and {@code Block} tag keys only.
 */
public final class BlockDisplayWhitelist {

	public static final List<String> DEFAULT_ENTRIES = List.of("*:*");

	private final List<String> rawEntries;
	private final List<BlockDisplayWhitelistEntry> parsedEntries;

	public BlockDisplayWhitelist(List<String> entries) {
		Objects.requireNonNull(entries, "entries");
		this.rawEntries = List.copyOf(entries);

		List<BlockDisplayWhitelistEntry> parsed = new ArrayList<>(this.rawEntries.size());

		for (String raw : this.rawEntries) {
			BlockDisplayWhitelistEntry.tryParse(raw).ifPresent(parsed::add);
		}

		this.parsedEntries = List.copyOf(parsed);
	}

	public static void validateEntries(List<String> entries, String fieldName) {
		Objects.requireNonNull(fieldName, "fieldName");

		if (entries == null) {
			throw new ConfigValidationException(fieldName, -1, ConfigValidationException.Reason.NULL_LIST);
		}

		for (int index = 0; index < entries.size(); index++) {
			String raw = entries.get(index);

			if (raw == null || raw.isBlank()) {
				throw new ConfigValidationException(fieldName, index, ConfigValidationException.Reason.BLANK_ENTRY);
			}

			if (BlockDisplayWhitelistEntry.tryParse(raw).isEmpty()) {
				throw new ConfigValidationException(fieldName, index, ConfigValidationException.Reason.INVALID_GRAMMAR);
			}
		}
	}

	public static BlockDisplayWhitelist builtIn() {
		return new BlockDisplayWhitelist(DEFAULT_ENTRIES);
	}

	public List<String> entries() {
		return this.rawEntries;
	}

	public List<BlockDisplayWhitelistEntry> parsedEntries() {
		return this.parsedEntries;
	}

	public boolean matches(BlockState state) {
		return this.matches(state, BlockTagLookup.minecraft());
	}

	public boolean matches(BlockState state, BlockTagLookup tagLookup) {
		Objects.requireNonNull(state, "state");

		return this.matches(BuiltInRegistries.BLOCK.getKey(state.getBlock()), tagLookup);
	}

	public boolean matchesOrdinaryBlock(BlockState state, BlockTagLookup tagLookup) {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(tagLookup, "tagLookup");

		return !BlockEntityClassification.hasBlockEntity(state.getBlock())
			&& state.getRenderShape() == RenderShape.MODEL
			&& this.matches(BuiltInRegistries.BLOCK.getKey(state.getBlock()), tagLookup);
	}

	public boolean matchesEntityBlock(BlockState state, BlockTagLookup tagLookup) {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(tagLookup, "tagLookup");

		return BlockEntityClassification.hasBlockEntity(state.getBlock())
			&& this.matches(BuiltInRegistries.BLOCK.getKey(state.getBlock()), tagLookup);
	}

	public boolean matches(
		Identifier blockKey,
		boolean hasBlockEntity,
		RenderShape renderShape,
		BlockTagLookup tagLookup
	) {
		Objects.requireNonNull(renderShape, "renderShape");
		Objects.requireNonNull(tagLookup, "tagLookup");

		return this.matches(blockKey, tagLookup);
	}

	public boolean matches(Identifier blockKey, BlockTagLookup tagLookup) {
		Objects.requireNonNull(tagLookup, "tagLookup");

		if (blockKey == null) {
			return false;
		}

		for (BlockDisplayWhitelistEntry entry : this.parsedEntries) {
			if (entry.matches(blockKey, tagLookup)) {
				return true;
			}
		}

		return false;
	}
}
