package nx.pingwheel.common.presentation.inventory.client;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import nx.pingwheel.common.chat.ContentChatTemplate;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryDisplayWriter;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemDiagnostics;
import org.jetbrains.annotations.Nullable;

/**
 * Shared client-side projection of one server-bounded inventory display value.
 *
 * <p>The server sends a single-count {@link ItemStack#SINGLE_ITEM_CODEC} JSON
 * form per variant plus its registry translation key. This helper is the one
 * place that decodes that encoded value with the current client registry
 * context and derives the display name, so the inventory list rows and the
 * tracking annotations cannot drift apart. Decoding never reads a world,
 * chest, entity or server object, and a missing, malformed, oversized or
 * registry-unknown value fails softly to an empty icon plus the registry
 * translation key. A failed display never authorizes prototype reconstruction.
 *
 * <p>Decoding is identity-aware: the decoded registry ID must equal the
 * supplied opaque item ID, otherwise the value fails soft to an empty icon
 * and the supplied label instead of showing an icon that disagrees with the
 * selection identity. The codec-only {@link #decode} stays available without an expected
 * ID for the chat path.
 *
 * <p>Decoded count-one values are retained privately as an immutable
 * transmitted snapshot — the registry item holder plus the exact persistent
 * component patch — for the render paths, because the tracking annotation
 * resolves once per world-render frame. The snapshot is decoded directly with
 * the same public grammar as {@link ItemStack#SINGLE_ITEM_CODEC}
 * ({@link ItemStack#ITEM_NON_AIR_CODEC} plus {@link DataComponentPatch#CODEC}),
 * so no outer {@link ItemStack} exists while the transmitted values are captured and
 * the outer item load-verification hook cannot replace its unresolved player-head
 * PROFILE inside retained state, even when a warmed profile cache lets the
 * hook's completed future run inline. Every resolve then composes the display
 * name from a separate non-verifying naming stack built from the same
 * transmitted patch and builds an independently owned count-one render working
 * stack through the real constructor, where the native callback may resolve a
 * PROFILE as usual. The transmitted identity and the name derived from it
 * therefore stay stable across resolves and cache hits.
 * Only the shared display-supported types may survive in the effective
 * components: a prototype or item hook cannot introduce an opaque value into
 * the cache's working stacks. The guard observes component type registry IDs,
 * never unknown values, and display refusal does not alter selection identity.
 * Native component codecs can still construct nested stacks and start their
 * callbacks during decoding. Known mutable nested values are detached for each
 * consumer without repeating nested load-verification work; callbacks already
 * started by native decoding retain only their native decode targets.
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
	 * stack, or EMPTY when unavailable, plus its independently resolved localized
	 * name. Mutating or resolving the stack never changes the helper's retained
	 * snapshot, a later resolution or another
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
	private static final int MAX_ICON_DEPTH = 48;
	// Finite expanded occurrences, matching the existing item structure-guard
	// scale. One public resolve/decode/name operation shares this allowance across
	// JSON, effective items, text and every post-hook recheck; no child resets it.
	private static final int MAX_VALIDATION_VISITS = 4096;
	// Covers sequential predecode, naming and render rechecks of a legal wire
	// value without making default/hook text work unbounded by total characters.
	private static final int MAX_VALIDATION_CHARACTERS = 16 * InventoryS2CPacket.MAX_DISPLAY_BYTES;
	private static final InventoryResidentAccess RAW = InventoryResidentAccess.load();
	private static int validationVisits;
	private static boolean validationWorkExhausted;

	private record CacheKey(String itemId, String displayJson) {}

	/**
	 * The exact {@link ItemStack#SINGLE_ITEM_CODEC} grammar — non-air item
	 * holder under {@code id} plus the optional persistent component patch under
	 * {@code components} — decoded straight into the retained snapshot. No
	 * outer {@link ItemStack} is constructed while decoding, so its item
	 * load-verification hook cannot run before the transmitted values are
	 * captured. Registry-aware patch decoding, component removals, transient
	 * rejection, unknown-type failure and non-air validation all stay owned by
	 * the shared constituent codecs, including native nested-stack construction.
	 */
	private static final Codec<Snapshot> SNAPSHOT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
		ItemStack.ITEM_NON_AIR_CODEC.fieldOf("id").forGetter(Snapshot::item),
		DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(Snapshot::patch)
	).apply(instance, Snapshot::new));

	/**
	 * Immutable transmitted identity of one decoded variant: the registry item
	 * holder plus the exact component patch the server encoded, removals
	 * included. No constructed outer stack is retained, so its item
	 * load-verification callback cannot capture the retained outer PROFILE.
	 */
	private record Snapshot(Holder<Item> item, DataComponentPatch patch) {
		/**
		 * Independently owned count-one render stack built through the real
		 * constructor, which applies the transmitted patch and runs the item's
		 * load-verification hook on that stack only; the native player-head
		 * callback may resolve a PROFILE here.
		 */
		ItemStack workingStack(Validation budget) {
			budget.visit(0);
			DataComponentMap prototype = item.value().components();
			if (!supportedOverlay(prototype, RAW.patch(patch), 0, budget)) throw new UnsafeDisplay();
			DataComponentPatch owned = ClientDisplayWorkingCopy.overlay(prototype, patch, 0, budget);
			budget.cells(RAW.patch(owned).size());
			PatchedDataComponentMap components = new PatchedDataComponentMap(prototype);
			components.restorePatch(owned);
			// Same native constructor and one load hook as the public patch
			// constructor, but skip fromPatch's resident deep-equality normalization.
			ItemStack working = NativeStack.construct(item.value(), components);
			if (!supportedComponents(working.getComponents(), 0, budget)) throw new UnsafeDisplay();
			// An item hook may replace an admitted component with a known mutable
			// producer value. Install owned values back onto this same stack, so its
			// deferred PROFILE callback still targets the real render working stack.
			ClientDisplayWorkingCopy.detach(working, budget);
			return working;
		}

		/**
		 * Transmitted-faithful count-one naming stack for name composition. The
		 * prototype-only constructor runs its hook without transmitted per-stack
		 * components, and the admitted patch is installed with the native
		 * non-normalizing component-map restore (without another load hook).
		 * The transmitted PROFILE therefore
		 * cannot be replaced before the name is composed, even when a warmed
		 * profile cache would execute the hook inline. This stack is never
		 * retained.
		 */
		ItemStack namingStack(Validation budget) {
			budget.visit(0);
			ItemStack naming = new ItemStack(item);
			// Constructor hooks return to an immediate effective-map fence. Apply
			// the admitted patch without native normalization/deep equals, which
			// could otherwise compare resident translation caches or fold an owned
			// text value back into the mutable prototype.
			admitComponentMap(RAW.patch(patch), budget);
			if (!supportedComponents(naming.getComponents(), RAW.patch(patch).keySet(), 0, budget)) throw new UnsafeDisplay();
			PatchedDataComponentMap components = (PatchedDataComponentMap) naming.getComponents();
			DataComponentPatch owned = ClientDisplayWorkingCopy.overlay(components, patch, 0, budget);
			budget.cells(RAW.patch(owned).size());
			components.restorePatch(owned);
			return naming;
		}
	}

	/** Fixed native constructor descriptor; never discovers a hook on arbitrary values. */
	private static final class NativeStack {
		private static final @Nullable Constructor<ItemStack> CONSTRUCTOR = constructor();
		private static @Nullable Constructor<ItemStack> constructor() {
			try {
				Constructor<ItemStack> result = ItemStack.class.getDeclaredConstructor(net.minecraft.world.level.ItemLike.class, int.class, PatchedDataComponentMap.class);
				return result.trySetAccessible() ? result : null;
			} catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
		}
		static ItemStack construct(Item item, PatchedDataComponentMap components) {
			if (CONSTRUCTOR == null) throw new UnsafeDisplay();
			try { return CONSTRUCTOR.newInstance(item, 1, components); }
			catch (InvocationTargetException failure) {
				if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
				if (failure.getCause() instanceof Error error) throw error;
				throw new IllegalStateException("native display constructor failed", failure);
			} catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("native display constructor unavailable", unavailable); }
		}
	}

	// Access-ordered so a cache hit refreshes recency and pressure retires the
	// least recently resolved variant instead of the oldest insertion.
	private static final Map<CacheKey, Snapshot> DECODED = new LinkedHashMap<>(16, 0.75f, true);
	private static long retainedBytes;
	private static HolderLookup.@Nullable Provider cachedRegistry;
	private static int decodeMisses;

	private ClientItemDisplay() {}

	/**
	 * Resolves one row or annotation display from the current client level
	 * registry context. Null or unavailable registry access keeps an empty icon
	 * and the supplied label instead of throwing.
	 */
	public static Display resolve(String itemId, String label, @Nullable String displayJson) {
		return resolve(currentRegistryAccess(), itemId, label, displayJson);
	}

	/**
	 * Resolves one display against an explicit registry context. A null context,
	 * any decode failure or a decoded registry ID that disagrees with the
	 * supplied item ID falls back to an empty icon and the
	 * registry translation key.
	 */
	public static Display resolve(HolderLookup.@Nullable Provider registryAccess, String itemId, String label, @Nullable String displayJson) {
		Validation budget = new Validation();
		try {
			Optional<Snapshot> snapshot = transmitted(registryAccess, itemId, displayJson, budget);
			if (snapshot.isEmpty()) return unavailable(label);
			// Retain only the transmission. Both the hook-free transmitted name
			// application and the real render constructor get private outer stacks.
			ItemStack naming = snapshot.get().namingStack(budget);
			Component name = checkedName(naming, budget);
			// Lore/shulker redaction remains icon-only, not a name failure.
			if (!safeIcon(naming, 0, budget)) return new Display(ItemStack.EMPTY, name);
			ItemStack working = snapshot.get().workingStack(budget);
			if (!supportedComponents(working.getComponents(), 0, budget)) return rejectSnapshot(itemId, displayJson, label);
			boolean safe = safeIcon(working, 0, budget);
			budget.sameLanguage();
			return new Display(safe ? working : ItemStack.EMPTY, name);
		} catch (RuntimeException unavailable) {
			failure("inventory/client-display-resolve", itemId, unavailable);
			return rejectSnapshot(itemId, displayJson, label);
		}
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
	public static Optional<ItemStack> decode(HolderLookup.@Nullable Provider registryAccess, @Nullable String json) {
		Validation budget = new Validation();
		try {
			return decodeSnapshot(registryAccess, json, budget).map(snapshot -> snapshot.namingStack(budget))
				.filter(stack -> supportedComponents(stack.getComponents(), 0, budget));
		} catch (RuntimeException unavailable) {
			failure("inventory/client-display-decode", null, unavailable);
			return Optional.empty();
		}
	}

	/** Safe shared chat/name entrance, including effective-graph checks after getName. */
	public static Optional<Component> decodeName(HolderLookup.@Nullable Provider registryAccess, @Nullable String json) {
		Validation budget = new Validation();
		try {
			return decodeSnapshot(registryAccess, json, budget).map(snapshot -> snapshot.namingStack(budget))
				.map(stack -> checkedName(stack, budget));
		} catch (RuntimeException unavailable) {
			failure("inventory/client-display-name", null, unavailable);
			return Optional.empty();
		}
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
		return checkedName(stack, new Validation());
	}

	private static Component checkedName(ItemStack stack, Validation budget) {
		if (!supportedComponents(stack.getComponents(), 0, budget)) throw new UnsafeDisplay();
		budget.visit(0);
		String description = stack.getDescriptionId();
		// A description hook can mutate CUSTOM_NAME. Check immediately, before
		// even reading that name and before any owned formatting or composition.
		if (!supportedComponents(stack.getComponents(), 0, budget)) throw new UnsafeDisplay();
		budget.characters(description);
		@Nullable Component custom = stack.get(DataComponents.CUSTOM_NAME);
		// ITEM_NAME is also server-provided component data, not a trusted registry name.
		if (custom == null) custom = stack.get(DataComponents.ITEM_NAME);
		// Keep the item implementation's normal localized variant name when no
		// untrusted name override is present (for example, a potion's base name).
		Component result;
		if (custom == null) {
			budget.visit(0);
			Component returned = stack.getItem().getName(stack);
			if (!supportedComponents(stack.getComponents(), 0, budget)) throw new UnsafeDisplay();
			result = ClientDisplayText.snapshot(returned, 0, budget);
		} else {
			String plain = ClientDisplayText.own(custom, 0, budget).plain();
			Component quoted = Component.translatable(ContentChatTemplate.QUOTE_KEY, Component.literal(plain));
			result = ClientDisplayText.snapshot(Component.translatable(ContentChatTemplate.CUSTOM_ITEM_NAME_KEY,
				quoted, Component.translatable(description)), 0, budget);
		}
		budget.sameLanguage();
		return result;
	}

	/** Connection/registry lifecycle clear; the next resolve decodes fresh values. */
	public static void reset() {
		DECODED.clear();
		retainedBytes = 0L;
		cachedRegistry = null;
		decodeMisses = 0;
		validationVisits = 0;
		validationWorkExhausted = false;
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

	/** Last operation's attempted owned visits; no native callback work is implied. */
	static int validationVisits() { return validationVisits; }
	static boolean validationWorkExhausted() { return validationWorkExhausted; }

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

	private static Optional<Snapshot> transmitted(HolderLookup.@Nullable Provider registryAccess, String itemId, @Nullable String displayJson, Validation budget) {
		if (registryAccess == null || displayJson == null || displayJson.length() > InventoryS2CPacket.MAX_DISPLAY_BYTES || displayJson.isBlank()) {
			return Optional.empty();
		}
		budget.characters(displayJson);
		if (cachedRegistry != registryAccess) {
			DECODED.clear();
			retainedBytes = 0L;
			cachedRegistry = registryAccess;
		}
		CacheKey key = new CacheKey(itemId, displayJson);
		// A retained snapshot passed the identity check when it was decoded, and
		// the key includes the supplied item id, so a keyed hit is already known
		// to describe the opaque selection identity.
		@Nullable Snapshot cached = DECODED.get(key);
		if (cached != null) {
			try {
				if (supportedSnapshot(cached, 0, budget)) return Optional.of(cached);
			} catch (RuntimeException unavailable) {
				failure("inventory/client-display-cache", itemId, unavailable);
				// A failed effective-map lookup is not permission to reuse values.
			}
			DECODED.remove(key);
			retainedBytes -= chargeOf(key);
			return Optional.empty();
		}
		decodeMisses++;
		Optional<Snapshot> decoded = decodeSnapshot(registryAccess, displayJson, budget)
			.filter(snapshot -> matchesSuppliedItem(itemId, snapshot));
		decoded.ifPresent(snapshot -> retain(key, snapshot));
		return decoded;
	}

	/**
	 * Decodes one server-bounded display JSON into the immutable transmitted
	 * snapshot through {@link #SNAPSHOT_CODEC}; no constructed outer stack exists
	 * for its item load-verification hook to capture. Native component codecs
	 * may construct and verify nested stacks before eligibility is known.
	 */
	private static Optional<Snapshot> decodeSnapshot(HolderLookup.@Nullable Provider registryAccess, @Nullable String json, Validation budget) {
		if (registryAccess == null || json == null || json.length() > InventoryS2CPacket.MAX_DISPLAY_BYTES || json.isBlank()) {
			return Optional.empty();
		}
		// The approved wire bound is the shared decode guard; an oversized
		// payload is never parsed on either the cache or the chat path.
		if (utf8Length(json) > InventoryS2CPacket.MAX_DISPLAY_BYTES) {
			return Optional.empty();
		}
		try {
			admitSyntax(json, 0, budget);
			JsonElement element = JsonParser.parseString(json);
			if (!supportedAdditions(element, 0, budget)) return Optional.empty();
			replaceFlat(element, 0, budget);
			var decoded = SNAPSHOT_CODEC.parse(registryAccess.createSerializationContext(JsonOps.INSTANCE), element);
			if (decoded.error().isPresent()) InventoryItemDiagnostics.codecRejected("inventory/client-display-codec", "persistent display patch");
			return decoded.result()
				.filter(snapshot -> supportedSnapshot(snapshot, 0, budget));
		} catch (RuntimeException malformed) {
			failure("inventory/client-display-codec", null, malformed);
			return Optional.empty();
		}
	}

	private static void failure(String operation, @Nullable String itemId, Throwable failure) {
		// A normal safety/budget refusal is deliberate display suppression, as
		// are Lore/shulker stripping and ceiling reprojection. Hooks and codecs
		// throwing their own exceptions are unexpected failures.
		if (!(failure instanceof UnsafeDisplay) || failure.getCause() != null) InventoryItemDiagnostics.displayFailure(operation, itemId, failure);
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

	/** Reject unknown additions before a native patch decoder can invoke their codecs. */
	private static boolean supportedAdditions(JsonElement stack, int depth, Validation budget) {
		budget.visit(depth);
		if (!stack.isJsonObject()) return false;
		JsonElement itemId = stack.getAsJsonObject().get("id");
		if (itemId == null || !itemId.isJsonPrimitive() || !itemId.getAsJsonPrimitive().isString()) return false;
		ResourceLocation itemKey = ResourceLocation.tryParse(itemId.getAsString());
		Item item = itemKey == null ? null : BuiltInRegistries.ITEM.getOptional(itemKey).orElse(null);
		if (item == null) return false;
		JsonElement components = stack.getAsJsonObject().get("components");
		if (components == null) return supportedComponents(item.components(), depth, budget);
		if (!components.isJsonObject()) return false;
		Set<DataComponentType<?>> patched = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Map.Entry<String, JsonElement> entry : components.getAsJsonObject().entrySet()) {
			budget.visit(depth);
				// The native codec owns removal syntax and registered-type validity;
			// a removal never asks an opaque component for its value codec.
			boolean removal = entry.getKey().startsWith("!");
			ResourceLocation id = ResourceLocation.tryParse(removal ? entry.getKey().substring(1) : entry.getKey());
			DataComponentType<?> type = id == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.get(id);
			if (type != null) patched.add(type);
			if (removal) {
				continue;
			}
			if (!supportedComponent(type)) return false;
			JsonElement value = entry.getValue();
			if (type == DataComponents.CONTAINER || type == DataComponents.BUNDLE_CONTENTS || type == DataComponents.CHARGED_PROJECTILES) {
				if (!value.isJsonArray()) return false;
				for (JsonElement nested : value.getAsJsonArray()) {
					if (type == DataComponents.CONTAINER) {
						if (!nested.isJsonObject()) return false;
						nested = nested.getAsJsonObject().get("item");
					}
					if (nested == null || !supportedAdditions(nested, depth + 1, budget)) return false;
				}
			} else if (type == DataComponents.FOOD && value.isJsonObject()) {
				JsonElement nested = value.getAsJsonObject().get("using_converts_to");
				if (nested != null && !supportedAdditions(nested, depth + 1, budget)) return false;
			} else if (type == DataComponents.CUSTOM_NAME || type == DataComponents.ITEM_NAME) {
				if (!supportedFlatText(value, depth + 1, budget)) return false;
			} else if (type == DataComponents.LORE && value.isJsonArray()) {
				for (JsonElement line : value.getAsJsonArray()) if (!supportedFlatText(line, depth + 1, budget)) return false;
			} else if (type == DataComponents.WRITTEN_BOOK_CONTENT && value.isJsonObject()) {
				JsonElement pages = value.getAsJsonObject().get("pages");
				if (pages != null && pages.isJsonArray()) {
					for (JsonElement page : pages.getAsJsonArray()) {
						budget.visit(depth);
						if (page.isJsonObject()) {
							JsonElement raw = page.getAsJsonObject().get("raw"), filtered = page.getAsJsonObject().get("filtered");
							if (raw != null && !supportedFlatText(raw, depth + 1, budget)) return false;
							if (filtered != null && !supportedFlatText(filtered, depth + 1, budget)) return false;
						} else if (!supportedFlatText(page, depth + 1, budget)) return false;
					}
				}
			}
		}
		// Nested native holder codecs can hash their decoded stacks. Fence opaque
		// defaults from those calls as well as from later cache/render consumers.
		return supportedComponents(item.components(), patched, depth, budget);
	}

	private static boolean supportedFlatText(JsonElement value, int depth, Validation budget) {
		budget.visit(depth);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !budget.characters(value.getAsString())) return false;
		admitSyntax(value.getAsString(), depth, budget);
		JsonElement text = JsonParser.parseString(value.getAsString());
		if (!supportedText(text, depth, budget)) return false;
		// JSON mutation is owned. The caller replaces this FLAT string after the
		// legacy guard sanitizes its nested item/entity text before native decode.
		admitJsonWrite(text, depth, budget);
		String rewritten = text.toString();
		budget.flat.put(value, new com.google.gson.JsonPrimitive(rewritten));
		return true;
	}
	private static void admitJsonWrite(JsonElement value, int depth, Validation budget) {
		budget.visit(depth);
		if (value.isJsonObject()) {
			for (var entry : value.getAsJsonObject().entrySet()) {
				for (int i = 0; i < 6; i++) budget.characters(entry.getKey());
				admitJsonWrite(entry.getValue(), depth + 1, budget);
			}
		} else if (value.isJsonArray()) {
			for (JsonElement child : value.getAsJsonArray()) admitJsonWrite(child, depth + 1, budget);
		} else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
			// Gson escaping has at most six output units per input unit. This is
			// an upper-bound workspace reservation, before serializer allocation.
			for (int i = 0; i < 6; i++) budget.characters(value.getAsString());
		} else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
			&& value.getAsNumber().getClass() == com.google.gson.internal.LazilyParsedNumber.class) {
			// The exact parsed-number wrapper returns its stored immutable token;
			// do not assume a JSON numeric lexeme has a fixed decimal width.
			budget.characters(value.getAsNumber().toString());
		} else budget.characterUnits(64); // fixed boxed JSON/SNBT primitive token
	}
	private static void replaceFlat(JsonElement value, int depth, Validation budget) {
		budget.visit(depth);
		if (value.isJsonObject()) {
			for (var entry : value.getAsJsonObject().entrySet()) {
				JsonElement replacement = budget.flat.get(entry.getValue());
				if (replacement != null) entry.setValue(replacement); else replaceFlat(entry.getValue(), depth + 1, budget);
			}
		} else if (value.isJsonArray()) {
			for (int i = 0; i < value.getAsJsonArray().size(); i++) {
				JsonElement child = value.getAsJsonArray().get(i), replacement = budget.flat.get(child);
				if (replacement != null) value.getAsJsonArray().set(i, replacement); else replaceFlat(child, depth + 1, budget);
			}
		}
	}

	/** Follows native text's item-bearing positions, never arbitrary custom-data strings. */
	private static boolean supportedText(JsonElement value, int depth, Validation budget) {
		budget.visit(depth);
		if (value.isJsonArray()) {
			for (JsonElement child : value.getAsJsonArray()) if (!supportedText(child, depth + 1, budget)) return false;
		} else if (value.isJsonObject()) {
			JsonElement path = value.getAsJsonObject().get("nbt");
			if (path != null && path.isJsonPrimitive() && path.getAsJsonPrimitive().isString()) ClientDisplaySyntax.nbt(path.getAsString(), depth, budget);
			for (String field : new String[] {"selector", "entity"}) {
				JsonElement selector = value.getAsJsonObject().get(field);
				if (selector != null && selector.isJsonPrimitive() && selector.getAsJsonPrimitive().isString()) ClientDisplaySyntax.selector(selector.getAsString(), depth, budget);
			}
			JsonElement score = value.getAsJsonObject().get("score");
			if (score != null && score.isJsonObject()) {
				JsonElement name = score.getAsJsonObject().get("name");
				if (name != null && name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()) ClientDisplaySyntax.selector(name.getAsString(), depth, budget);
			}
			for (String field : new String[] {"extra", "with", "separator"}) {
				JsonElement child = value.getAsJsonObject().get(field);
				if (child != null && !supportedText(child, depth + 1, budget)) return false;
			}
			JsonElement hover = value.getAsJsonObject().get("hoverEvent");
			if (hover != null && hover.isJsonObject()) {
				JsonElement action = hover.getAsJsonObject().get("action"), contents = hover.getAsJsonObject().get("contents");
				if (action != null && action.isJsonPrimitive() && action.getAsJsonPrimitive().isString()) {
					// Native decoding can try the legacy alternative after a failed
					// modern payload, even when both fields are present.
					JsonElement legacy = hover.getAsJsonObject().get("value");
					if (legacy != null && !supportedLegacyHover(action.getAsString(), legacy, depth + 1, budget)) return false;
					if (legacy != null && budget.legacy.containsKey(legacy)) hover.getAsJsonObject().add("value", budget.legacy.get(legacy));
					if (contents == null) return legacy != null;
					if (action.getAsString().equals("show_item")) {
						if (contents.isJsonPrimitive() && contents.getAsJsonPrimitive().isString()) {
							ResourceLocation id = ResourceLocation.tryParse(contents.getAsString());
							Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
							if (item != null && !supportedComponents(item.components(), depth + 1, budget)) return false;
						} else if (contents.isJsonObject() && !supportedAdditions(contents, depth + 1, budget)) return false;
					} else if (action.getAsString().equals("show_text")) {
						if (!supportedText(contents, depth + 1, budget)) return false;
					} else if (action.getAsString().equals("show_entity") && contents.isJsonObject()) {
						JsonElement name = contents.getAsJsonObject().get("name");
						if (name != null && !supportedText(name, depth + 1, budget)) return false;
					}
				}
			}
		}
		return true;
	}

	private static boolean supportedLegacyHover(String action, JsonElement value, int depth, Validation budget) {
		if (!supportedText(value, depth, budget)) return false;
		if (!action.equals("show_item") && !action.equals("show_entity")) return true;
		try {
			// Legacy item/entity forms flatten their text into SNBT. This text
			// has already passed its own item-bearing-position guard above.
			replaceFlat(value, depth, budget);
			var decoded = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, value);
			if (decoded.error().isPresent()) InventoryItemDiagnostics.codecRejected("inventory/client-display-legacy-codec", "legacy display text");
			Optional<Component> text = decoded.result();
			if (text.isEmpty() || !supportedText(text.get(), depth, budget)) return false;
			String plain = ClientDisplayText.own(text.get(), depth, budget).plain();
			// This JsonElement belongs to the parsed wire tree. Replace the legacy
			// component with literal text so the subsequent native converter cannot
			// re-expand the original graph (including modern-failure fallback).
			ClientDisplaySyntax.nbt(plain, depth, budget);
			var tag = TagParser.parseTag(plain);
			if (action.equals("show_item")) {
				JsonElement item = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag);
				if (!supportedAdditions(item, depth + 1, budget)) return false;
				replaceFlat(item, depth + 1, budget);
				admitJsonWrite(item, depth + 1, budget);
				// Only owned primitive trees are converted; no item/component codec
				// runs here. Nested FLAT names must be rewritten too, otherwise the
				// later legacy item codec could reintroduce their original graph.
				String snbt = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, item).toString();
				budget.characters(snbt);
				budget.legacy.put(value, new com.google.gson.JsonPrimitive(snbt));
			} else {
				String nameJson = tag.getString("name"); admitSyntax(nameJson, depth + 1, budget);
				JsonElement name = JsonParser.parseString(nameJson);
				if (!supportedText(name, depth + 1, budget)) return false;
				admitJsonWrite(name, depth + 1, budget);
				String rewritten = name.toString(); budget.characters(rewritten);
				tag.putString("name", rewritten);
				JsonElement owned = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag);
				admitJsonWrite(owned, depth + 1, budget);
				String snbt = tag.toString(); budget.characters(snbt);
				budget.legacy.put(value, new com.google.gson.JsonPrimitive(snbt));
			}
			return true;
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException malformed) {
			return false;
		}
	}

	/** JSON uses exactly the native lenient reader, not an approximate quote lexer. */
	private static void admitSyntax(String text, int depth, Validation budget) {
		ClientDisplaySyntax.json(text, depth, budget);
	}

	private static boolean supportedComponent(DataComponentType<?> type) {
		ResourceLocation id = type == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
		return id != null && InventoryDisplayWriter.supportsComponent(id);
	}

	/** One root owns all expanded occurrences; aliases are revisited, never memoized across item hooks. */
	static final class Validation {
		private int visits, characters;
		private final int visitLimit, characterLimit;
		final Map<JsonElement, JsonElement> flat = new IdentityHashMap<>(), legacy = new IdentityHashMap<>();
		final net.minecraft.locale.Language language = net.minecraft.locale.Language.getInstance();
		Validation() { this(MAX_VALIDATION_VISITS, MAX_VALIDATION_CHARACTERS); }
		/** Smaller allowances for exact-boundary tests; production ceilings cannot be widened. */
		Validation(int visits, int characters) {
			if (visits < 0 || visits > MAX_VALIDATION_VISITS || characters < 0 || characters > MAX_VALIDATION_CHARACTERS) throw new IllegalArgumentException();
			visitLimit = visits; characterLimit = characters;
			validationVisits = 0; validationWorkExhausted = false;
		}
		void visit(int depth) {
			if (depth > MAX_ICON_DEPTH) throw new UnsafeDisplay();
			if (visits >= visitLimit) { validationWorkExhausted = true; throw new UnsafeDisplay(); }
			validationVisits = ++visits;
		}
		boolean characters(String value) {
			if (value == null || value.length() > characterLimit - characters) throw new UnsafeDisplay();
			characters += value.length();
			return true;
		}
		void characterUnits(long count) {
			if (count < 0 || count > characterLimit - characters) throw new UnsafeDisplay();
			characters += (int) count;
		}
		void cells(int count) {
			if (count < 0 || count > visitLimit - visits) { validationWorkExhausted = true; throw new UnsafeDisplay(); }
			visits += count; validationVisits = visits;
		}
		int remainingVisits() { return visitLimit - visits; }
		void sameLanguage() { if (net.minecraft.locale.Language.getInstance() != language) throw new UnsafeDisplay(); }
	}
	static final class UnsafeDisplay extends RuntimeException {
		UnsafeDisplay() { super(null, null, false, false); }
		UnsafeDisplay(Throwable cause) { super(null, cause, false, false); }
	}

	private static final Set<Class<?>> SAFE_LISTS = Set.of(ArrayList.class,
		List.of().getClass(), List.of(1).getClass(), Collections.emptyList().getClass(), Collections.singletonList(1).getClass(),
		Arrays.asList(1).getClass(), ImmutableList.of().getClass(), ImmutableList.of(1).getClass());
	private static final Class<?> SIMPLE_COMPONENTS = DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 1).build().getClass();
	private static final Class<?> EMPTY_COMPONENT_MAP = Reference2ObjectMaps.emptyMap().getClass();

	/** Exact known list backings only; pay every cell before iterator construction or lazy filtering. */
	static List<?> admittedList(List<?> list, Validation budget) {
		if (list == null) throw new UnsafeDisplay();
		if (list.getClass() == net.minecraft.core.NonNullList.class) list = RAW.nonNullList((net.minecraft.core.NonNullList<?>) list);
		if (!SAFE_LISTS.contains(list.getClass())) throw new UnsafeDisplay();
		budget.cells(list.size());
		return list;
	}

	private static boolean supportedSnapshot(Snapshot snapshot, int depth, Validation budget) {
		return supportedOverlay(snapshot.item().value().components(), RAW.patch(snapshot.patch()), depth, budget);
	}

	static boolean supportedComponents(DataComponentMap components, int depth, Validation budget) {
		if (components != null && components.getClass() == PatchedDataComponentMap.class) {
			var patched = (PatchedDataComponentMap) components;
			return supportedOverlay(RAW.prototype(patched), RAW.patch(patched), Set.of(), depth, budget);
		}
		return supportedComponents(components, Set.of(), depth, budget);
	}

	private static boolean supportedComponents(DataComponentMap components, Set<DataComponentType<?>> omitted, int depth, Validation budget) {
		budget.visit(depth);
		if (components == DataComponentMap.EMPTY) return true;
		if (components.getClass() == PatchedDataComponentMap.class) {
			var patched = (PatchedDataComponentMap) components;
			return supportedOverlay(RAW.prototype(patched), RAW.patch(patched), omitted, depth, budget);
		}
		if (components.getClass() != SIMPLE_COMPONENTS) return false;
		// Do not create PatchedDataComponentMap.keySet's combined copy before
		// allowance admission; this native raw map has separately bounded capacity.
		Map<DataComponentType<?>, Object> values = RAW.simple(components);
		admitComponentMap(values, budget);
		for (var entry : values.entrySet()) {
			if (!omitted.contains(entry.getKey()) && !supportedComponent(entry.getKey())) return false;
		}
		for (var entry : values.entrySet()) {
			budget.visit(depth);
			if (!omitted.contains(entry.getKey()) && !supportedValue(entry.getKey(), entry.getValue(), depth, budget)) return false;
		}
		return true;
	}

	private static boolean supportedOverlay(DataComponentMap prototype, Map<DataComponentType<?>, Optional<?>> patch, int depth, Validation budget) {
		return supportedOverlay(prototype, patch, Set.of(), depth, budget);
	}

	private static boolean supportedOverlay(DataComponentMap prototype, Map<DataComponentType<?>, Optional<?>> patch,
		Set<DataComponentType<?>> omitted, int depth, Validation budget) {
		budget.visit(depth);
		if (prototype != DataComponentMap.EMPTY && prototype.getClass() != SIMPLE_COMPONENTS) return false;
		admitComponentMap(patch, budget);
		budget.cells(omitted.size());
		Set<DataComponentType<?>> masked = Collections.newSetFromMap(new IdentityHashMap<>());
		masked.addAll(omitted);
		for (var entry : patch.entrySet()) {
			budget.visit(depth);
			masked.add(entry.getKey());
			if (!omitted.contains(entry.getKey()) && entry.getValue().isPresent() && !supportedComponent(entry.getKey())) return false;
		}
		if (!supportedComponents(prototype, masked, depth, budget)) return false;
		for (var entry : patch.entrySet()) {
			budget.visit(depth);
			if (!omitted.contains(entry.getKey()) && entry.getValue().isPresent()
				&& !supportedValue(entry.getKey(), entry.getValue().get(), depth, budget)) return false;
		}
		return true;
	}

	static void admitComponentMap(Map<?, ?> values, Validation budget) {
		Class<?> type = values.getClass();
		if (type != Reference2ObjectArrayMap.class && type != Reference2ObjectOpenHashMap.class && type != EMPTY_COMPONENT_MAP) throw new UnsafeDisplay();
		budget.cells(values.size());
		if (type == Reference2ObjectOpenHashMap.class) budget.cells(RAW.referenceMapCapacity(values));
	}

	private static boolean supportedValue(DataComponentType<?> type, Object value, int depth, Validation budget) {
		if (type == DataComponents.CUSTOM_NAME || type == DataComponents.ITEM_NAME) return supportedText((Component) value, depth + 1, budget);
		if (type == DataComponents.CONTAINER) return supportedItems(RAW.container((ItemContainerContents) value), depth, budget);
		if (type == DataComponents.BUNDLE_CONTENTS) return supportedItems(RAW.bundle((BundleContents) value), depth, budget);
		if (type == DataComponents.CHARGED_PROJECTILES) return supportedItems(RAW.projectiles((ChargedProjectiles) value), depth, budget);
		if (type == DataComponents.FOOD) {
			var nested = ((FoodProperties) value).usingConvertsTo();
			return nested.isEmpty() || supportedComponents(nested.get().getComponents(), depth + 1, budget);
		}
		if (type == DataComponents.LORE) {
			ItemLore lore = (ItemLore) value;
			for (Object line : admittedList(lore.lines(), budget)) if (!supportedText((Component) line, depth + 1, budget)) return false;
			// Validate the exact native lazy view's backing identity and function
			// before relying on the already checked lines. Never invoke its mapper.
			if (LoreView.matches(lore)) return true;
			for (Object line : admittedList(lore.styledLines(), budget)) if (!supportedText((Component) line, depth + 1, budget)) return false;
		}
		if (type == DataComponents.WRITTEN_BOOK_CONTENT) {
			WrittenBookContent book = (WrittenBookContent) value;
			if (!filterableString(book.title(), budget) || !budget.characters(book.author())) return false;
			for (Object page : admittedList(book.pages(), budget)) {
				budget.visit(depth);
				if (!(page instanceof Filterable<?> filter)) return false;
				if (!(filter.raw() instanceof Component text) || !supportedText(text, depth + 1, budget)) return false;
				if (filter.filtered().isPresent() && (!(filter.filtered().get() instanceof Component filtered) || !supportedText(filtered, depth + 1, budget))) return false;
			}
		}
		if (type == DataComponents.WRITABLE_BOOK_CONTENT) {
			for (Object page : admittedList(((WritableBookContent) value).pages(), budget)) {
				budget.visit(depth);
				if (!(page instanceof Filterable<?> filter) || !filterableString(filter, budget)) return false;
			}
		}
		return true;
	}

	private static boolean filterableString(Filterable<?> value, Validation budget) {
		budget.visit(0);
		return value != null && value.raw() instanceof String raw && budget.characters(raw)
			&& (value.filtered().isEmpty() || value.filtered().get() instanceof String filtered && budget.characters(filtered));
	}

	private static boolean supportedItems(List<ItemStack> items, int depth, Validation budget) {
		for (Object item : admittedList(items, budget)) {
			budget.visit(depth);
			if (!(item instanceof ItemStack stack) || !supportedComponents(stack.getComponents(), depth + 1, budget)) return false;
		}
		return true;
	}

	/** Native text fields only. Unknown implementations and arguments fail before formatting callbacks. */
	private static boolean supportedText(Component value, int depth, Validation budget) {
		if (!supportedTextGraph(value, depth, budget)) return false;
		ClientDisplayText.certify(value, depth, budget);
		return true;
	}

	private static boolean supportedTextGraph(Component value, int depth, Validation budget) {
		budget.visit(depth);
		if (value == null || value.getClass() != MutableComponent.class) return false;
		ComponentContents contents = value.getContents();
		Class<?> type = contents.getClass();
		if (contents == PlainTextContents.EMPTY) { /* no children */ }
		else if (type == PlainTextContents.LiteralContents.class) budget.characters(((PlainTextContents.LiteralContents) contents).text());
		else if (type == TranslatableContents.class) {
			TranslatableContents trans = (TranslatableContents) contents;
			budget.characters(trans.getKey());
			if (trans.getFallback() != null) budget.characters(trans.getFallback());
			Object[] args = trans.getArgs(); budget.cells(args.length);
			for (Object arg : args) {
				budget.visit(depth);
				if (arg instanceof Component child) { if (!supportedTextGraph(child, depth + 1, budget)) return false; }
				else if (arg instanceof String text) budget.characters(text);
				else if (arg != null && arg.getClass() != Boolean.class && arg.getClass() != Integer.class && arg.getClass() != Long.class
					&& arg.getClass() != Float.class && arg.getClass() != Double.class && arg.getClass() != Byte.class && arg.getClass() != Short.class) return false;
			}
		} else if (type == SelectorContents.class) {
			SelectorContents selector = (SelectorContents) contents; ClientDisplaySyntax.selector(selector.getPattern(), depth, budget);
			if (selector.getSeparator().isPresent() && !supportedTextGraph(selector.getSeparator().get(), depth + 1, budget)) return false;
		} else if (type == NbtContents.class) {
			NbtContents nbt = (NbtContents) contents; budget.characters(nbt.getNbtPath());
			ClientDisplaySyntax.nbt(nbt.getNbtPath(), depth, budget);
			if (nbt.getSeparator().isPresent() && !supportedTextGraph(nbt.getSeparator().get(), depth + 1, budget)) return false;
			Class<?> source = nbt.getDataSource().getClass();
			if (source == BlockDataSource.class) budget.characters(((BlockDataSource) nbt.getDataSource()).posPattern());
			else if (source == EntityDataSource.class) ClientDisplaySyntax.selector(((EntityDataSource) nbt.getDataSource()).selectorPattern(), depth, budget);
			else if (source != StorageDataSource.class) return false;
		} else if (type == ScoreContents.class) {
			ScoreContents score = (ScoreContents) contents; ClientDisplaySyntax.selector(score.getName(), depth, budget); budget.characters(score.getObjective());
		} else if (type == KeybindContents.class) {
			budget.characters(((KeybindContents) contents).getName());
			// Keybind visit() reaches a mutable external resolver. Its output is
			// neither a known native child nor needed for an isolated item label.
			return false;
		} else return false;
		return supportedTextChildren(value, depth, budget);
	}

	private static boolean supportedTextChildren(Component value, int depth, Validation budget) {
		for (Object sibling : admittedList(value.getSiblings(), budget)) {
			if (!(sibling instanceof Component child) || !supportedTextGraph(child, depth + 1, budget)) return false;
		}
		Style style = value.getStyle();
		if (style.getClass() != Style.class) return false;
		if (style.getInsertion() != null) budget.characters(style.getInsertion());
		if (style.getClickEvent() != null) {
			if (style.getClickEvent().getClass() != net.minecraft.network.chat.ClickEvent.class) return false;
			budget.characters(style.getClickEvent().getValue());
		}
		HoverEvent hover = style.getHoverEvent();
		if (hover == null) return true;
		budget.visit(depth);
		if (hover.getClass() != HoverEvent.class) return false;
		if (hover.getAction() == HoverEvent.Action.SHOW_TEXT) return supportedTextGraph(hover.getValue(HoverEvent.Action.SHOW_TEXT), depth + 1, budget);
		if (hover.getAction() == HoverEvent.Action.SHOW_ENTITY) {
			var entity = hover.getValue(HoverEvent.Action.SHOW_ENTITY);
			return entity != null && entity.getClass() == HoverEvent.EntityTooltipInfo.class
				&& (entity.name.isEmpty() || supportedTextGraph(entity.name.get(), depth + 1, budget));
		}
		if (hover.getAction() != HoverEvent.Action.SHOW_ITEM) return false;
		return HoverStack.supported(hover.getValue(HoverEvent.Action.SHOW_ITEM), depth + 1, budget);
	}

	/** Exact native hover descriptors, not field discovery on arbitrary component values. */
	static final class HoverStack {
		private static final Field ITEM = nativeField(HoverEvent.ItemStackInfo.class, Holder.class);
		private static final Field PATCH = nativeField(HoverEvent.ItemStackInfo.class, DataComponentPatch.class);
		private static final Field STACK = nativeField(HoverEvent.ItemStackInfo.class, ItemStack.class);
		private static final java.lang.reflect.Method VERIFY = verifier();
		private static java.lang.reflect.Method verifier() {
			java.lang.reflect.Method result = null;
			for (var method : Item.class.getDeclaredMethods()) {
				if (!java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
					&& Arrays.equals(method.getParameterTypes(), new Class<?>[] {ItemStack.class})) {
					if (result != null) return null;
					result = method;
				}
			}
			return result;
		}
		static final ClassValue<Boolean> NO_LOAD_HOOK = new ClassValue<>() {
			@Override protected Boolean computeValue(Class<?> type) {
				try { return VERIFY != null && type.getMethod(VERIFY.getName(), ItemStack.class).getDeclaringClass() == Item.class; }
				catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
			}
		};
		static boolean supported(HoverEvent.ItemStackInfo info, int depth, Validation budget) {
			budget.visit(depth);
			if (info == null || info.getClass() != HoverEvent.ItemStackInfo.class || ITEM == null || PATCH == null || STACK == null) return false;
			try {
				Holder<?> holder = (Holder<?>) ITEM.get(info);
				DataComponentPatch patch = (DataComponentPatch) PATCH.get(info);
				if (!(holder.value() instanceof Item item) || !supportedOverlay(item.components(), RAW.patch(patch), depth, budget)) return false;
				ItemStack cached = (ItemStack) STACK.get(info);
				// Never call getItemStack(): its lazy construction runs item hooks
				// and starts extra PROFILE work. Already materialized state is checked too.
				// An unmaterialized hover is eligible only when lazy construction
				// inherits Item's empty verifier; otherwise a later getter could
				// inject opaque state after this read-only validation has finished.
				return cached == null ? NO_LOAD_HOOK.get(item.getClass()) : supportedComponents(cached.getComponents(), depth, budget);
			} catch (IllegalAccessException unavailable) { throw new UnsafeDisplay(unavailable); }
		}
		static HoverEvent.ItemStackInfo owned(HoverEvent.ItemStackInfo info, int depth, Validation budget) {
			budget.visit(depth);
			if (!supported(info, depth, budget)) throw new UnsafeDisplay();
			try {
				ItemStack cached = (ItemStack) STACK.get(info);
				ItemStack working;
				if (cached == null) {
					@SuppressWarnings("unchecked") Holder<Item> holder = (Holder<Item>) ITEM.get(info);
					// supported() certified that deferred construction inherits the
					// empty verifier. Native construction cannot start a profile hook.
					working = ClientDisplayItemCopy.copy(holder.value(), count(info), (DataComponentPatch) PATCH.get(info), depth, budget);
				} else {
					// Existing display ownership is an independent outer stack, not
					// arbitrary-value deep freeze. Only the checked supported graph
					// reaches this native copy, and its hook result is checked again.
					working = ClientDisplayItemCopy.copy(cached, depth, budget);
				}
				if (!supportedComponents(working.getComponents(), depth, budget)) throw new UnsafeDisplay();
				HoverEvent.ItemStackInfo owned = new HoverEvent.ItemStackInfo(working);
				// Keep the checked private working stack; a later hover getter must
				// not run another item hook against a newly constructed stack.
				STACK.set(owned, working);
				return owned;
			} catch (IllegalAccessException unavailable) { throw new UnsafeDisplay(unavailable); }
		}
		private static final Field COUNT = nativeField(HoverEvent.ItemStackInfo.class, int.class);
		private static int count(HoverEvent.ItemStackInfo info) throws IllegalAccessException {
			if (COUNT == null) throw new UnsafeDisplay();
			return COUNT.getInt(info);
		}
	}

	static final class LoreView {
		private static final ItemLore SAMPLE = new ItemLore(List.of());
		private static final Class<?> TYPE = SAMPLE.styledLines().getClass();
		private static final Field LIST = nativeField(TYPE, List.class);
		private static final Field FUNCTION = nativeField(TYPE, com.google.common.base.Function.class);
		static boolean matches(ItemLore lore) {
			if (lore.styledLines().getClass() != TYPE || LIST == null || FUNCTION == null) return false;
			try { return LIST.get(lore.styledLines()) == lore.lines() && FUNCTION.get(lore.styledLines()) == FUNCTION.get(SAMPLE.styledLines()); }
			catch (IllegalAccessException unavailable) { throw new UnsafeDisplay(unavailable); }
		}
	}

	/** Fixed, class-initialization-only descriptor lookup; unavailable access rejects display. */
	static Field nativeField(Class<?> owner, Class<?> type) {
		try {
			Field result = null;
			for (Field field : owner.getDeclaredFields()) {
				if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
					if (result != null || !field.trySetAccessible()) return null;
					result = field;
				}
			}
			return result;
		} catch (RuntimeException unavailable) { return null; }
	}

	/** Redaction policy stays icon-only; all visits still share the root safety allowance. */
	private static boolean safeIcon(ItemStack stack, int depth, Validation budget) {
		budget.visit(depth);
		if (stack.isEmpty()) return true;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null && !lore.lines().isEmpty()) return false;
		ItemContainerContents container = stack.get(DataComponents.CONTAINER);
		if (container != null) {
			boolean shulker = stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
			for (Object nested : admittedList(RAW.container(container), budget)) {
				if (!(nested instanceof ItemStack child) || (!child.isEmpty() && (shulker || !safeIcon(child, depth + 1, budget)))) return false;
			}
		}
		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) for (Object nested : admittedList(RAW.bundle(bundle), budget)) if (!(nested instanceof ItemStack child) || !safeIcon(child, depth + 1, budget)) return false;
		ChargedProjectiles projectiles = stack.get(DataComponents.CHARGED_PROJECTILES);
		if (projectiles != null) for (Object nested : admittedList(RAW.projectiles(projectiles), budget)) if (!(nested instanceof ItemStack child) || !safeIcon(child, depth + 1, budget)) return false;
		FoodProperties food = stack.get(DataComponents.FOOD);
		return food == null || food.usingConvertsTo().isEmpty() || safeIcon(food.usingConvertsTo().get(), depth + 1, budget);
	}

	private static Component fallbackName(String label) {
		return label == null || label.isBlank() ? Component.empty() : Component.translatable(label);
	}

	private static Display unavailable(String label) {
		return new Display(ItemStack.EMPTY, fallbackName(label));
	}

	private static Display rejectSnapshot(String itemId, @Nullable String displayJson, String label) {
		if (displayJson == null) return unavailable(label);
		CacheKey key = new CacheKey(itemId, displayJson);
		if (DECODED.remove(key) != null) retainedBytes -= chargeOf(key);
		return unavailable(label);
	}

	private static HolderLookup.@Nullable Provider currentRegistryAccess() {
		try {
			Minecraft game = Minecraft.getInstance();
			return game == null || game.level == null ? null : game.level.registryAccess();
		} catch (RuntimeException unavailable) {
			failure("inventory/client-display-registry", null, unavailable);
			return null;
		}
	}

	private static int utf8Length(String value) {
		return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
	}
}
