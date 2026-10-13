package nx.pingwheel.common.presentation.inventory.client;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import nx.pingwheel.common.chat.ContentChatTemplate;
import nx.pingwheel.common.network.InventoryS2CPacket;

/**
 * Shared client-side projection of one server-bounded inventory display value.
 *
 * <p>The server sends a single-count {@link ItemStack#SINGLE_ITEM_CODEC} JSON
 * form per variant plus its registry translation key. This helper is the one
 * place that decodes that encoded value with the current client registry
 * context and derives the display name, so the inventory list rows and the
 * tracking annotations cannot drift apart. Decoding never reads a world,
 * chest, entity or server object, and a missing, malformed, oversized or
 * registry-unknown value fails softly to the native bare stack plus the
 * registry translation key.
 *
 * <p>Decoding is identity-aware: the decoded registry ID must equal the
 * supplied opaque item ID, otherwise the value fails soft to the supplied item
 * and label instead of showing an icon that disagrees with the selection
 * identity. The codec-only {@link #decode} stays available without an expected
 * ID for the chat path.
 *
 * <p>Decoded count-one values are retained privately as an immutable
 * transmitted snapshot — the registry item holder plus the exact persistent
 * component patch — for the render paths, because the tracking annotation
 * resolves once per world-render frame. The snapshot is decoded directly with
 * the same public grammar as {@link ItemStack#SINGLE_ITEM_CODEC}
 * ({@link ItemStack#ITEM_NON_AIR_CODEC} plus {@link DataComponentPatch#CODEC}),
 * so no {@link ItemStack} exists while the transmitted values are captured and
 * the item load-verification hook cannot replace an unresolved player-head
 * PROFILE inside retained state, even when a warmed profile cache lets the
 * hook's completed future run inline. Every resolve then composes the display
 * name from a separate non-verifying naming stack built from the same
 * transmitted patch and builds an independently owned count-one render working
 * stack through the real constructor, where the native callback may resolve a
 * PROFILE as usual. The transmitted identity and the name derived from it
 * therefore stay stable across resolves and cache hits.
 *
 * <p>The cache is main-thread confined, access-ordered so a hit refreshes
 * recency and the least recently resolved snapshot is evicted first, bounded
 * by retained entries and payload bytes, keyed by the encoded item id and
 * display JSON (so same-id component variants and changed JSON never alias),
 * and isolated by registry identity: a different registry provider or
 * {@link #reset()} clears it. A metadata revision that carries identical JSON
 * reuses the same snapshot without re-decoding the payload.
 */
public final class ClientItemDisplay {

	/**
	 * Resolved display projection: an independently owned count-one working
	 * stack plus its localized name. Mutating or resolving the stack never
	 * changes the helper's retained snapshot, a later resolution or another
	 * caller's result.
	 */
	public record Display(ItemStack stack, Component name) {
		public Display {
			Objects.requireNonNull(stack, "stack");
			Objects.requireNonNull(name, "name");
		}
	}

	/** Aligned with the preview entry quota; also covers the smaller tracking metadata map. */
	public static final int MAX_CACHE_ENTRIES = ClientInventory.MAX_PREVIEW_ENTRIES;
	/** Aligned with the preview byte bound; the payload guard itself stays the wire bound. */
	public static final long MAX_CACHE_BYTES = ClientInventory.MAX_PREVIEW_BYTES;

	private record CacheKey(String itemId, String displayJson) {}

	/**
	 * The exact {@link ItemStack#SINGLE_ITEM_CODEC} grammar — non-air item
	 * holder under {@code id} plus the optional persistent component patch under
	 * {@code components} — decoded straight into the retained snapshot. No
	 * {@link ItemStack} is constructed while decoding, so the item
	 * load-verification hook cannot run before the transmitted values are
	 * captured. Registry-aware patch decoding, component removals, transient
	 * rejection, unknown-type failure and non-air validation all stay owned by
	 * the shared constituent codecs.
	 */
	private static final Codec<Snapshot> SNAPSHOT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
		ItemStack.ITEM_NON_AIR_CODEC.fieldOf("id").forGetter(Snapshot::item),
		DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(Snapshot::patch)
	).apply(instance, Snapshot::new));

	/**
	 * Immutable transmitted identity of one decoded variant: the registry item
	 * holder plus the exact component patch the server encoded, removals
	 * included. No constructed stack is retained, so the item
	 * load-verification callback cannot capture retained state.
	 */
	private record Snapshot(Holder<Item> item, DataComponentPatch patch) {
		/**
		 * Independently owned count-one render stack built through the real
		 * constructor, which applies the transmitted patch and runs the item's
		 * load-verification hook on that stack only; the native player-head
		 * callback may resolve a PROFILE here.
		 */
		ItemStack workingStack() {
			return new ItemStack(item, 1, patch);
		}

		/**
		 * Transmitted-faithful count-one naming stack for name composition. The
		 * prototype-only constructor has no transmitted per-stack components,
		 * and each patch entry is applied with {@link ItemStack#set} or
		 * {@link ItemStack#remove}, which delegate straight to the component map
		 * and never run {@code verifyComponentsAfterLoad} (unlike
		 * {@link ItemStack#applyComponents}). The transmitted PROFILE therefore
		 * cannot be replaced before the name is composed, even when a warmed
		 * profile cache would execute the hook inline. This stack is never
		 * retained.
		 */
		ItemStack namingStack() {
			ItemStack naming = new ItemStack(item);
			DataComponentPatch.SplitResult split = patch.split();
			for (TypedDataComponent<?> added : split.added()) {
				applyAddedComponent(naming, added);
			}
			for (DataComponentType<?> removed : split.removed()) {
				naming.remove(removed);
			}
			return naming;
		}
	}

	private static <T> void applyAddedComponent(ItemStack stack, TypedDataComponent<T> component) {
		stack.set(component.type(), component.value());
	}

	// Access-ordered so a cache hit refreshes recency and pressure retires the
	// least recently resolved variant instead of the oldest insertion.
	private static final Map<CacheKey, Snapshot> DECODED = new LinkedHashMap<>(16, 0.75f, true);
	private static long retainedBytes;
	private static HolderLookup.Provider cachedRegistry;
	private static int decodeMisses;

	private ClientItemDisplay() {}

	/**
	 * Resolves one row or annotation display from the current client level
	 * registry context. Null or unavailable registry access keeps the native
	 * fallback instead of throwing.
	 */
	public static Display resolve(String itemId, String label, String displayJson) {
		return resolve(currentRegistryAccess(), itemId, label, displayJson);
	}

	/**
	 * Resolves one display against an explicit registry context. A null context,
	 * any decode failure or a decoded registry ID that disagrees with the
	 * supplied item ID falls back to the bare supplied item stack and the
	 * registry translation key.
	 */
	public static Display resolve(HolderLookup.Provider registryAccess, String itemId, String label, String displayJson) {
		Optional<Snapshot> snapshot = transmitted(registryAccess, itemId, displayJson);
		if (snapshot.isPresent()) {
			// The retained snapshot stays private and untargetable by the
			// native load-verification callback. The name is composed first
			// from the non-verifying naming stack, then the render stack is
			// built through the real constructor: a callback that runs inline
			// on the render stack can resolve a PROFILE there, but it can no
			// longer change the retained transmission or this name.
			Component name = displayName(snapshot.get().namingStack());
			ItemStack working = snapshot.get().workingStack();
			return new Display(working, name);
		}
		return new Display(nativeStack(itemId), fallbackName(label));
	}

	/**
	 * Decodes one server-bounded display JSON into a detached,
	 * transmitted-faithful count-one stack for the codec-only chat path. The
	 * stack is built without the load-verification hook so the name composed
	 * from it always reflects the transmitted profile rather than a
	 * callback-resolved one; render paths use {@link #resolve}, whose own
	 * working stack still allows native profile resolution. Empty means the
	 * caller keeps the registry translation key fallback; raw text never leaves
	 * a failed decode.
	 */
	public static Optional<ItemStack> decode(HolderLookup.Provider registryAccess, String json) {
		return decodeSnapshot(registryAccess, json).map(Snapshot::namingStack);
	}

	/**
	 * The confirmed item display name policy, shared with the content-message
	 * family: a transmitted custom or item name is flattened to plain text and
	 * quoted once beside the trusted localized base translation; without one the
	 * stack keeps its own localized variant name, and an ordinary item keeps its
	 * registry localized base name.
	 */
	public static Component displayName(ItemStack stack) {
		Objects.requireNonNull(stack, "stack");
		Component base = Component.translatable(stack.getDescriptionId());
		Component custom = stack.get(DataComponents.CUSTOM_NAME);
		// ITEM_NAME is also server-provided component data, not a trusted registry name.
		if (custom == null) custom = stack.get(DataComponents.ITEM_NAME);
		// Keep the item implementation's normal localized variant name when no
		// untrusted name override is present (for example, a potion's base name).
		if (custom == null) return stack.getItem().getName(stack);
		Component quoted = Component.translatable(ContentChatTemplate.QUOTE_KEY, Component.literal(custom.getString()));
		return Component.translatable(ContentChatTemplate.CUSTOM_ITEM_NAME_KEY, quoted, base);
	}

	/** Connection/registry lifecycle clear; the next resolve decodes fresh values. */
	public static void reset() {
		DECODED.clear();
		retainedBytes = 0L;
		cachedRegistry = null;
		decodeMisses = 0;
	}

	/** Package-visible accounting for bound tests; not a product value. */
	static int cachedEntryCount() {
		return DECODED.size();
	}

	/** Package-visible accounting for bound tests; not a product value. */
	static long cachedBytes() {
		return retainedBytes;
	}

	/**
	 * Package-visible cache-miss accounting for the bounded-cache and ownership
	 * tests; not a product value. Returned work stacks are independently owned,
	 * so tests observe snapshot reuse through this counter instead of instance
	 * identity.
	 */
	static int decodeMisses() {
		return decodeMisses;
	}

	/**
	 * Package-visible retained-transmission inspection for the ownership tests;
	 * not a product value. The entry-set scan avoids refreshing access-order
	 * recency.
	 */
	static Optional<DataComponentPatch> cachedTransmittedPatch(HolderLookup.Provider registryAccess, String itemId, String displayJson) {
		if (cachedRegistry != registryAccess || displayJson == null) {
			return Optional.empty();
		}
		CacheKey key = new CacheKey(itemId, displayJson);
		for (Map.Entry<CacheKey, Snapshot> entry : DECODED.entrySet()) {
			if (entry.getKey().equals(key)) {
				return Optional.of(entry.getValue().patch());
			}
		}
		return Optional.empty();
	}

	private static Optional<Snapshot> transmitted(HolderLookup.Provider registryAccess, String itemId, String displayJson) {
		if (registryAccess == null || displayJson == null || displayJson.isBlank()) {
			return Optional.empty();
		}
		if (cachedRegistry != registryAccess) {
			DECODED.clear();
			retainedBytes = 0L;
			cachedRegistry = registryAccess;
		}
		CacheKey key = new CacheKey(itemId, displayJson);
		// A retained snapshot passed the identity check when it was decoded, and
		// the key includes the supplied item id, so a keyed hit is already known
		// to describe the opaque selection identity.
		Snapshot cached = DECODED.get(key);
		if (cached != null) {
			return Optional.of(cached);
		}
		decodeMisses++;
		Optional<Snapshot> decoded = decodeSnapshot(registryAccess, displayJson)
			.filter(snapshot -> matchesSuppliedItem(itemId, snapshot));
		decoded.ifPresent(snapshot -> retain(key, snapshot));
		return decoded;
	}

	/**
	 * Decodes one server-bounded display JSON into the immutable transmitted
	 * snapshot through {@link #SNAPSHOT_CODEC}; no constructed stack exists for
	 * the item load-verification hook to capture.
	 */
	private static Optional<Snapshot> decodeSnapshot(HolderLookup.Provider registryAccess, String json) {
		if (registryAccess == null || json == null || json.isBlank()) {
			return Optional.empty();
		}
		// The approved wire bound is the shared decode guard; an oversized
		// payload is never parsed on either the cache or the chat path.
		if (utf8Length(json) > InventoryS2CPacket.MAX_DISPLAY_BYTES) {
			return Optional.empty();
		}
		try {
			JsonElement element = JsonParser.parseString(json);
			return SNAPSHOT_CODEC
				.parse(registryAccess.createSerializationContext(JsonOps.INSTANCE), element)
				.result();
		} catch (RuntimeException malformed) {
			return Optional.empty();
		}
	}

	/**
	 * The decoded snapshot's registry identity must agree with the supplied
	 * opaque item ID before the display is accepted, otherwise the icon and its
	 * name could describe a different item than the selection identity.
	 */
	private static boolean matchesSuppliedItem(String itemId, Snapshot snapshot) {
		if (itemId == null || itemId.isBlank()) {
			return false;
		}
		ResourceLocation supplied = ResourceLocation.tryParse(itemId);
		return supplied != null && supplied.equals(BuiltInRegistries.ITEM.getKey(snapshot.item().value()));
	}

	private static void retain(CacheKey key, Snapshot snapshot) {
		long charge = chargeOf(key);
		if (charge > MAX_CACHE_BYTES) {
			return;
		}
		while (!DECODED.isEmpty() && (DECODED.size() >= MAX_CACHE_ENTRIES || retainedBytes + charge > MAX_CACHE_BYTES)) {
			CacheKey eldest = DECODED.keySet().iterator().next();
			DECODED.remove(eldest);
			retainedBytes -= chargeOf(eldest);
		}
		DECODED.put(key, snapshot);
		retainedBytes += charge;
	}

	private static long chargeOf(CacheKey key) {
		return 64L + utf8Length(key.displayJson()) + (key.itemId() == null ? 0L : utf8Length(key.itemId()));
	}

	private static ItemStack nativeStack(String itemId) {
		if (itemId == null || itemId.isBlank()) {
			return ItemStack.EMPTY;
		}
		ResourceLocation id = ResourceLocation.tryParse(itemId);
		if (id == null) {
			return ItemStack.EMPTY;
		}
		Item item = BuiltInRegistries.ITEM.get(id);
		if (item == null || item == Items.AIR) {
			return ItemStack.EMPTY;
		}
		return new ItemStack(item);
	}

	private static Component fallbackName(String label) {
		return label == null || label.isBlank() ? Component.empty() : Component.translatable(label);
	}

	private static HolderLookup.Provider currentRegistryAccess() {
		try {
			Minecraft game = Minecraft.getInstance();
			return game == null || game.level == null ? null : game.level.registryAccess();
		} catch (RuntimeException unavailable) {
			return null;
		}
	}

	private static int utf8Length(String value) {
		return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
	}
}
