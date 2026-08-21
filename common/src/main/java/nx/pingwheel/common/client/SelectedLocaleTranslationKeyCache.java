package nx.pingwheel.common.client;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import nx.pingwheel.common.Global;

import static nx.pingwheel.common.Global.LOGGER;

/**
 * Caches the translation keys supplied by the currently selected locale's own
 * resource stack.
 *
 * <p>{@link Language} is a merged view: it includes the selected locale's
 * entries and the {@code en_us} fallback entries. That is the right value
 * lookup behavior, but not the right presence test for a locale-specific
 * override. This cache deliberately scans only the selected locale resource
 * stack and uses the identity of the merged language as a cheap reload
 * boundary.
 *
 * <p>This class is intended for client-main-thread use. The snapshot loader
 * seam keeps the cache's invalidation and selection behavior testable without
 * manipulating the Minecraft singleton.
 */
public final class SelectedLocaleTranslationKeyCache {

	private static final String STACK_LOOKUP_FAILED =
		"selected locale translation stack lookup failed";
	private static final String RESOURCE_PARSE_FAILED =
		"selected locale translation resource parse failed";

	@FunctionalInterface
	public interface SnapshotLoader {
		Set<String> load(String selectedLanguageCode, ResourceManager resourceManager);
	}

	@FunctionalInterface
	interface TranslationResourceParser {
		Set<String> parse(InputStream inputStream) throws Exception;
	}

	@FunctionalInterface
	interface TranslationResourceOpener<T> {
		InputStream open(T resource) throws Exception;
	}

	private final SnapshotLoader snapshotLoader;
	private Object cachedLanguageIdentity;
	private String cachedLanguageCode;
	private Set<String> cachedKeys = Set.of();
	private boolean initialized;

	public SelectedLocaleTranslationKeyCache() {
		this(SelectedLocaleTranslationKeyCache::loadSelectedLocaleKeys);
	}

	public SelectedLocaleTranslationKeyCache(SnapshotLoader snapshotLoader) {
		this.snapshotLoader = Objects.requireNonNull(snapshotLoader, "snapshotLoader");
	}

	/**
	 * Returns whether the selected locale itself supplies {@code key}.
	 *
	 * <p>The merged {@link Language} object is used only as an identity-based
	 * reload signal. Template values must still be read from that merged object
	 * after this predicate selects the key.
	 */
	public boolean contains(
		String selectedLanguageCode,
		Language language,
		ResourceManager resourceManager,
		String key
	) {
		return contains(selectedLanguageCode, (Object) language, resourceManager, key);
	}

	/**
	 * Identity-oriented seam for tests. Production callers should pass the
	 * current {@link Language} through the overload above.
	 */
	public boolean contains(
		String selectedLanguageCode,
		Object languageIdentity,
		ResourceManager resourceManager,
		String key
	) {
		Objects.requireNonNull(selectedLanguageCode, "selectedLanguageCode");
		Objects.requireNonNull(languageIdentity, "languageIdentity");
		Objects.requireNonNull(key, "key");

		if (!initialized
			|| cachedLanguageIdentity != languageIdentity
			|| !selectedLanguageCode.equals(cachedLanguageCode)) {
			rebuild(selectedLanguageCode, languageIdentity, resourceManager);
		}

		return cachedKeys.contains(key);
	}

	private void rebuild(
		String selectedLanguageCode,
		Object languageIdentity,
		ResourceManager resourceManager
	) {
		Set<String> loadedKeys;

		try {
			loadedKeys = snapshotLoader.load(selectedLanguageCode, resourceManager);
			loadedKeys = loadedKeys == null ? Set.of() : Set.copyOf(loadedKeys);
		} catch (RuntimeException exception) {
			logFailure(STACK_LOOKUP_FAILED, exception);
			loadedKeys = Set.of();
		}

		cachedLanguageCode = selectedLanguageCode;
		cachedLanguageIdentity = languageIdentity;
		cachedKeys = loadedKeys;
		initialized = true;
	}

	private static Set<String> loadSelectedLocaleKeys(
		String selectedLanguageCode,
		ResourceManager resourceManager
	) {
		Objects.requireNonNull(resourceManager, "resourceManager");

		List<Resource> resources;
		try {
			ResourceLocation languageId = ResourceLocation.fromNamespaceAndPath(
				Global.MOD_ID, "lang/" + selectedLanguageCode + ".json");
			resources = resourceManager.getResourceStack(languageId);
		} catch (RuntimeException exception) {
			logFailure(STACK_LOOKUP_FAILED, exception);
			return Set.of();
		}

		if (resources == null || resources.isEmpty()) {
			return Set.of();
		}

		return collectResourceKeys(resources, Resource::open, SelectedLocaleTranslationKeyCache::parseTranslationKeys);
	}

	/**
	 * Collects keys from resources through a lazy opener, applying the same
	 * low-to-high stack accumulation used by the default resource loader while
	 * making malformed individual resources independent. Opening, parsing, and
	 * closing each resource are isolated to that resource.
	 */
	static <T> Set<String> collectResourceKeys(
		Iterable<? extends T> resources,
		TranslationResourceOpener<? super T> opener,
		TranslationResourceParser parser
	) {
		Objects.requireNonNull(resources, "resources");
		Objects.requireNonNull(opener, "opener");
		Objects.requireNonNull(parser, "parser");

		Set<String> keys = new HashSet<>();
		for (T resource : resources) {
			if (resource == null) {
				logFailure(RESOURCE_PARSE_FAILED, new NullPointerException("resource"));
				continue;
			}

			try (InputStream inputStream = opener.open(resource)) {
				Set<String> parsedKeys = parser.parse(inputStream);
				if (parsedKeys != null) {
					keys.addAll(parsedKeys);
				}
			} catch (Exception exception) {
				logFailure(RESOURCE_PARSE_FAILED, exception);
			}
		}

		return keys.isEmpty() ? Set.of() : Set.copyOf(keys);
	}

	private static Set<String> parseTranslationKeys(InputStream inputStream) {
		Set<String> keys = new HashSet<>();
		Language.loadFromJson(inputStream, (key, ignoredValue) -> keys.add(key));
		return keys;
	}

	private static void logFailure(String reason, Throwable exception) {
		String exceptionClass = exception == null ? "unknown" : exception.getClass().getName();
		LOGGER.debug(reason + ": " + exceptionClass);
	}
}
