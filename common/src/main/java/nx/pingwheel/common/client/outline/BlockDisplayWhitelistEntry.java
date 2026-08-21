package nx.pingwheel.common.client.outline;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * A parsed entry of the client block glow-list grammar:
 * <ul>
 *   <li>exact {@code namespace:block};</li>
 *   <li>namespace wildcard {@code namespace:*};</li>
 *   <li>global wildcard {@code *:*};</li>
 *   <li>block tag {@code #namespace:tag} (no wildcard allowed).</li>
 * </ul>
 *
 * <p>Parsing is strict and headless-safe: invalid strings (no colon, blank
 * namespace/path, extra colons, wildcard characters inside a tag or a
 * non-suffix path, a bare {@code *}) fail false and never throw. There is no
 * negation and no bare-star form other than the exact {@code *:*} wildcard.
 * Only {@code Block}-level {@link TagKey}s are produced, so evaluation can
 * never load optional-mod classes; an absent tag simply answers false through
 * the injected {@link BlockTagLookup}.
 *
 * <p>Entries are evaluated in explicit declaration order and unioned: the
 * first matching entry decides. Typed policy callers apply the
 * BlockEntity/non-MODEL render-shape gate where a renderer route requires it.
 */
public sealed interface BlockDisplayWhitelistEntry permits BlockDisplayWhitelistEntry.Any,
	BlockDisplayWhitelistEntry.Exact, BlockDisplayWhitelistEntry.NamespaceWildcard, BlockDisplayWhitelistEntry.Tag {

	/**
	 * Parses a raw entry string into the matching entry kind; empty when the
	 * string is not valid grammar (the entry then never matches).
	 */
	static Optional<BlockDisplayWhitelistEntry> tryParse(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}

		if (raw.charAt(0) == '#') {
			return parseTag(raw.substring(1));
		}

		return parseBlockId(raw);
	}

	/**
	 * Whether this entry matches the candidate block. {@code blockKey} is the
	 * registry id of the candidate block and is {@code null} for unregistered
	 * blocks (missing/absent registry ids fail false).
	 */
	boolean matches(ResourceLocation blockKey, BlockTagLookup tagLookup);

	/**
	 * The global block wildcard {@code *:*}. It matches every registered block
	 * key supplied by the caller, regardless of namespace or path.
	 */
	record Any() implements BlockDisplayWhitelistEntry {

		@Override
		public boolean matches(ResourceLocation blockKey, BlockTagLookup tagLookup) {
			return blockKey != null;
		}
	}

	/**
	 * An exact block match: {@code namespace:block}.
	 */
	record Exact(ResourceLocation location) implements BlockDisplayWhitelistEntry {

		public Exact {
			Objects.requireNonNull(location, "location");
		}

		@Override
		public boolean matches(ResourceLocation blockKey, BlockTagLookup tagLookup) {
			return blockKey != null && blockKey.equals(location);
		}
	}

	/**
	 * A namespace-wide match: {@code namespace:*}.
	 */
	record NamespaceWildcard(String namespace) implements BlockDisplayWhitelistEntry {

		public NamespaceWildcard {
			Objects.requireNonNull(namespace, "namespace");
		}

		@Override
		public boolean matches(ResourceLocation blockKey, BlockTagLookup tagLookup) {
			return blockKey != null && blockKey.getNamespace().equals(namespace);
		}
	}

	/**
	 * A block tag membership match: {@code #namespace:tag}, evaluated through
	 * the modern holder-backed {@link TagKey} API via the injected
	 * {@link BlockTagLookup}.
	 */
	record Tag(TagKey<Block> tagKey) implements BlockDisplayWhitelistEntry {

		public Tag {
			Objects.requireNonNull(tagKey, "tagKey");
		}

		@Override
		public boolean matches(ResourceLocation blockKey, BlockTagLookup tagLookup) {
			return tagLookup.contains(tagKey, blockKey);
		}
	}

	private static Optional<BlockDisplayWhitelistEntry> parseTag(String tag) {
		int colon = tag.indexOf(':');

		if (colon <= 0 || tag.indexOf(':', colon + 1) >= 0) {
			return Optional.empty();
		}

		String namespace = tag.substring(0, colon);
		String path = tag.substring(colon + 1);

		// No wildcard form exists for tags; '*' is also not a valid path char.
		if (path.isEmpty() || path.equals("*") || !ResourceLocation.isValidNamespace(namespace)
			|| !ResourceLocation.isValidPath(path)) {
			return Optional.empty();
		}

		return Optional.of(new Tag(TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(namespace, path))));
	}

	private static Optional<BlockDisplayWhitelistEntry> parseBlockId(String raw) {
		if ("*:*".equals(raw)) {
			return Optional.of(new Any());
		}

		int colon = raw.indexOf(':');

		if (colon <= 0 || raw.indexOf(':', colon + 1) >= 0) {
			return Optional.empty();
		}

		String namespace = raw.substring(0, colon);
		String path = raw.substring(colon + 1);

		if (!ResourceLocation.isValidNamespace(namespace)) {
			return Optional.empty();
		}

		if (path.equals("*")) {
			return Optional.of(new NamespaceWildcard(namespace));
		}

		// '*' anywhere else in the path is invalid grammar.
		if (path.isEmpty() || path.indexOf('*') >= 0 || !ResourceLocation.isValidPath(path)) {
			return Optional.empty();
		}

		return Optional.of(new Exact(ResourceLocation.fromNamespaceAndPath(namespace, path)));
	}
}
