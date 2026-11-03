package nx.pingwheel.common.client.outline;

import java.util.Objects;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Headless-safe seam for block tag membership lookups.
 *
 * <p>Production uses the modern 1.21.1 holder-backed API
 * ({@code BuiltInRegistries.BLOCK.wrapAsHolder(block).is(TagKey)}); tests
 * inject a deterministic fake because datapack tag data is not loaded in a
 * headless JVM and vanilla blocks cannot be constructed there. Only Minecraft
 * block tag keys are used, so optional-mod content can never be loaded through
 * this seam; an absent registry entry or tag simply answers false.
 */
@FunctionalInterface
public interface BlockTagLookup {

	/**
	 * Whether the block registered under {@code blockKey} is a member of
	 * {@code tagKey}. An absent/unknown registry id must answer false.
	 */
	boolean contains(TagKey<Block> tagKey, ResourceLocation blockKey);

	/**
	 * The production lookup backed by the vanilla 1.21.1 holder-based block
	 * tag API.
	 */
	static BlockTagLookup minecraft() {
		return (tagKey, blockKey) ->
			BuiltInRegistries.BLOCK.getOptional(blockKey)
				.map(block -> BuiltInRegistries.BLOCK.wrapAsHolder(block).is(tagKey))
				.orElse(false);
	}

	/**
	 * Validates a non-null lookup (mainly for defensive constructors).
	 */
	static BlockTagLookup requireNonNull(BlockTagLookup lookup) {
		return Objects.requireNonNull(lookup, "tagLookup");
	}
}
