package nx.pingwheel.common.presentation.inventory.minecraft;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import nx.pingwheel.common.presentation.inventory.InventoryScanner;

/**
 * Detached item-variant identity and bounded display projection for the vanilla
 * container source.
 *
 * <p>Variant identity is the item registry id plus the canonical persistent
 * component bytes, keyed by their SHA-256 digest. The canonical form is
 * produced here rather than by {@link CompoundTag#toString()}: compound keys
 * are sorted recursively, list order and every NBT tag type are preserved, and
 * the output is bounded. A digest alone is not identity evidence, so the
 * catalog keeps the canonical bytes per digest and rejects a different payload
 * that reuses a digest instead of merging two variants.
 *
 * <p>The display projection is deliberately separate from identity. It is a
 * single-count {@link ItemStack#SINGLE_ITEM_CODEC} JSON form bounded to
 * {@link #MAX_DISPLAY_BYTES}; when it is too large, display-only components may
 * be omitted without changing identity, and
 * {@link Display#componentsStripped()} is set only when every component of the
 * observed stack was dropped because of the size bound. Only server-owned
 * strings and bounded bytes leave this class.
 *
 * <p>The catalog retains canonical identity bytes once per component digest and
 * each display value once per variant key under finite entry and retained-byte
 * guards; exceeding a guard reports the source unavailable instead of
 * truncating or emptying the catalog.
 */
public final class InventoryItemCodec {

	/** Internal guard for one canonical component payload; not a product value. */
	public static final int MAX_CANONICAL_BYTES = 65536;

	/** Internal guard for one display JSON string; not a product value. */
	public static final int MAX_DISPLAY_BYTES = 4096;

	/**
	 * Default catalogue variant cap. Finite and aligned with the 256-entry
	 * preview entry quota so a source catalogue never admits more variants than
	 * a preview can consume.
	 */
	public static final int DEFAULT_MAX_ENTRIES = 256;

	/**
	 * Default retained catalogue byte cap. Finite and aligned with the 256 KiB
	 * preview byte bound; it covers canonical identity bytes and display bytes
	 * together.
	 */
	public static final long DEFAULT_MAX_RETAINED_BYTES = 262144L;

	/** Internal recursion guard; not a product value. */
	private static final int MAX_TAG_DEPTH = 64;

	private static final String DIGEST_ALGORITHM = "SHA-256";

	private InventoryItemCodec() {}

	/** Detached display value; {@code label} is a registry translation key. */
	public record Display(String itemId, String label, String displayJson, boolean componentsStripped) {
		public Display {
			Objects.requireNonNull(itemId, "itemId");
			Objects.requireNonNull(label, "label");
			Objects.requireNonNull(displayJson, "displayJson");
		}
	}

	/** One observed variant: stable key plus its detached display value. */
	public record Encoded(InventoryScanner.Key key, Display display) {
		public Encoded {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(display, "display");
		}
	}

	/**
	 * Full variant identity could not be established, or a digest collision was
	 * observed. Callers must report the source unavailable instead of guessing
	 * an identity or merging variants.
	 */
	public static final class IdentityUnavailableException extends IllegalStateException {
		public IdentityUnavailableException(String message) {
			super(message);
		}
	}

	/**
	 * Per-source variant catalog with finite entry and retained-byte guards.
	 *
	 * <p>Entries are added as stacks are encoded; the returned display view
	 * stays live so a caller can observe catalog updates while a scan advances.
	 * Canonical identity bytes are retained once per component digest and each
	 * display value is retained and charged once per variant key. Both guards
	 * are checked, with checked addition, before any payload is cloned or
	 * stored; exceeding either guard raises {@link IdentityUnavailableException}
	 * so the scanner reports the source unavailable rather than a truncated or
	 * empty catalog.
	 */
	public static final class Catalog {

		private final HolderLookup.Provider registries;
		private final int maxEntries;
		private final long maxRetainedBytes;
		private final Map<String, byte[]> canonicalByDigest = new HashMap<>();
		private final Set<String> conflicts = new HashSet<>();
		private final Map<InventoryScanner.Key, Display> displays = new LinkedHashMap<>();
		private long retainedBytes;

		/** Uses the finite default entry and retained-byte guards. */
		public Catalog(HolderLookup.Provider registries) {
			this(registries, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_RETAINED_BYTES);
		}

		/**
		 * Caller-specified finite guards. {@code maxEntries} bounds both the
		 * variant keys and the distinct component digests; {@code
		 * maxRetainedBytes} bounds the retained canonical and display bytes
		 * together. Unlimited is not a sentinel here: both guards are positive
		 * finite numbers.
		 */
		public Catalog(HolderLookup.Provider registries, int maxEntries, long maxRetainedBytes) {
			this.registries = Objects.requireNonNull(registries, "registries");
			if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
			if (maxRetainedBytes < 1) {
				throw new IllegalArgumentException("maxRetainedBytes must be positive");
			}
			this.maxEntries = maxEntries;
			this.maxRetainedBytes = maxRetainedBytes;
		}

		/** Live read-only view; entries appear as stacks are encoded. */
		public Map<InventoryScanner.Key, Display> displays() {
			return Collections.unmodifiableMap(displays);
		}

		/** Retained canonical plus display bytes; package-visible accounting. */
		long retainedBytes() {
			return retainedBytes;
		}

		public Encoded encode(ItemStack stack) {
			Objects.requireNonNull(stack, "stack");
			ItemStack detached = stack.copy();
			if (detached.isEmpty()) {
				throw new IllegalArgumentException("an empty stack carries no item identity");
			}

			String itemId = BuiltInRegistries.ITEM.getKey(detached.getItem()).toString();
			byte[] canonical = canonicalComponents(registries, detached);
			String componentsKey = digest(canonical);
			InventoryScanner.Key key = new InventoryScanner.Key(itemId, componentsKey);
			Display display = display(detached, registries);
			retain(componentsKey, canonical, key, display);
			return new Encoded(key, display);
		}

		private void retain(String componentsKey, byte[] canonical, InventoryScanner.Key key, Display display) {
			boolean newDigest = !canonicalByDigest.containsKey(componentsKey);
			boolean newKey = !displays.containsKey(key);
			if (newDigest && canonicalByDigest.size() >= maxEntries) {
				throw new IdentityUnavailableException("item catalogue entry bound reached");
			}
			if (newKey && displays.size() >= maxEntries) {
				throw new IdentityUnavailableException("item catalogue entry bound reached");
			}

			long charge = newDigest ? canonical.length : 0L;
			if (newKey) charge = checkedAdd(charge, displayCharge(display));
			long next = checkedAdd(retainedBytes, charge);
			if (next > maxRetainedBytes) {
				throw new IdentityUnavailableException("item catalogue byte bound reached");
			}

			registerCanonical(canonicalByDigest, conflicts, componentsKey, canonical);
			if (newKey) displays.put(key, display);
			retainedBytes = next;
		}
	}

	/**
	 * Encodes the persistent component map of a detached stack copy into
	 * canonical bytes. Transient or otherwise unencodable components make the
	 * identity unavailable instead of producing a fake variant.
	 */
	static byte[] canonicalComponents(HolderLookup.Provider registries, ItemStack stack) {
		Objects.requireNonNull(registries, "registries");
		Objects.requireNonNull(stack, "stack");
		DataResult<Tag> encoded = DataComponentMap.CODEC.encodeStart(
			registries.createSerializationContext(NbtOps.INSTANCE), stack.getComponents());
		Tag tag = encoded.result().orElseThrow(() ->
			new IdentityUnavailableException("item components do not encode to persistent data"));
		return canonicalTag(tag);
	}

	/** Canonical recursive NBT form: sorted compound keys, preserved lists and types. */
	static byte[] canonicalTag(Tag tag) {
		Objects.requireNonNull(tag, "tag");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			writeCanonical(tag, out, 0);
		} catch (IOException failure) {
			throw new IdentityUnavailableException("canonical component encoding failed");
		}
		return bytes.toByteArray();
	}

	private static void writeCanonical(Tag tag, DataOutputStream out, int depth) throws IOException {
		if (depth > MAX_TAG_DEPTH) {
			throw new IdentityUnavailableException("component nesting exceeds the canonical bound");
		}

		requireRoom(out, 1);
		out.writeByte(tag.getId());
		switch (tag) {
			case ByteTag value -> out.writeByte(value.getAsByte());
			case ShortTag value -> out.writeShort(value.getAsShort());
			case IntTag value -> out.writeInt(value.getAsInt());
			case LongTag value -> out.writeLong(value.getAsLong());
			case FloatTag value -> out.writeFloat(value.getAsFloat());
			case DoubleTag value -> out.writeDouble(value.getAsDouble());
			case ByteArrayTag value -> writeByteArray(out, value.getAsByteArray());
			case StringTag value -> writeString(out, value.getAsString());
			case IntArrayTag value -> writeIntArray(out, value.getAsIntArray());
			case LongArrayTag value -> writeLongArray(out, value.getAsLongArray());
			case ListTag value -> {
				requireRoom(out, 5);
				out.writeByte(value.getElementType());
				out.writeInt(value.size());
				for (int index = 0; index < value.size(); index++) {
					writeCanonical(value.get(index), out, depth + 1);
				}
			}
			case CompoundTag value -> {
				List<String> keys = new ArrayList<>(value.getAllKeys());
				Collections.sort(keys);
				requireRoom(out, 4);
				out.writeInt(keys.size());
				for (String key : keys) {
					writeString(out, key);
					writeCanonical(value.get(key), out, depth + 1);
				}
			}
			default -> throw new IdentityUnavailableException(
				"unsupported component tag type " + tag.getId());
		}
		requireRoom(out, 0);
	}

	private static void writeByteArray(DataOutputStream out, byte[] data) throws IOException {
		requireRoom(out, 4L + data.length);
		out.writeInt(data.length);
		out.write(data);
	}

	private static void writeIntArray(DataOutputStream out, int[] data) throws IOException {
		requireRoom(out, 4L + (long) data.length * Integer.BYTES);
		out.writeInt(data.length);
		for (int value : data) {
			out.writeInt(value);
		}
	}

	private static void writeLongArray(DataOutputStream out, long[] data) throws IOException {
		requireRoom(out, 4L + (long) data.length * Long.BYTES);
		out.writeInt(data.length);
		for (long value : data) {
			out.writeLong(value);
		}
	}

	private static void writeString(DataOutputStream out, String value) throws IOException {
		byte[] data = value.getBytes(StandardCharsets.UTF_8);
		requireRoom(out, 4L + data.length);
		out.writeInt(data.length);
		out.write(data);
	}

	private static void requireRoom(DataOutputStream out, long additional) {
		if (out.size() + additional > MAX_CANONICAL_BYTES) {
			throw new IdentityUnavailableException("component identity exceeds the canonical bound");
		}
	}

	/** Stable lowercase SHA-256 hex digest of canonical bytes. */
	static String digest(byte[] canonical) {
		Objects.requireNonNull(canonical, "canonical");
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance(DIGEST_ALGORITHM).digest(canonical));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
	}

	/**
	 * Digest collision policy as a pure helper: the first canonical payload wins,
	 * an identical payload is accepted, and a different payload under the same
	 * digest marks the digest ambiguous and fails instead of merging. The
	 * catalog checks its finite entry and byte guards before calling this, so a
	 * rejected or over-budget encode retains nothing.
	 */
	static void registerCanonical(Map<String, byte[]> canonicalByDigest, Set<String> conflicts,
		String digest, byte[] canonical) {
		Objects.requireNonNull(canonicalByDigest, "canonicalByDigest");
		Objects.requireNonNull(conflicts, "conflicts");
		Objects.requireNonNull(digest, "digest");
		Objects.requireNonNull(canonical, "canonical");
		if (conflicts.contains(digest)) {
			throw new IdentityUnavailableException("component digest is ambiguous");
		}

		byte[] known = canonicalByDigest.get(digest);
		if (known == null) {
			canonicalByDigest.put(digest, canonical.clone());
			return;
		}
		if (!Arrays.equals(known, canonical)) {
			conflicts.add(digest);
			throw new IdentityUnavailableException("component digest collision");
		}
	}

	/**
	 * Bounded display projection. Identity is computed from the full component
	 * set before this step; display-only components may be omitted to fit the
	 * bound without changing identity. {@link Display#componentsStripped()} is
	 * set only when the observed stack carried components, the size bound was
	 * the observed cause, and every component was dropped; a naturally
	 * componentless stack, a partial display omission, and an encoding failure
	 * without size evidence all keep it false.
	 */
	static Display display(ItemStack stack, HolderLookup.Provider registries) {
		Objects.requireNonNull(registries, "registries");
		return display(stack, itemStack -> encodeDisplay(itemStack, registries));
	}

	/** Encoder seam deciding the bounded projection and the fallback metadata. */
	static Display display(ItemStack stack, Function<ItemStack, Optional<String>> encode) {
		Objects.requireNonNull(stack, "stack");
		Objects.requireNonNull(encode, "encode");
		String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		String label = stack.getDescriptionId();
		ItemStack single = stack.copyWithCount(1);
		boolean hadComponents = !single.getComponentsPatch().isEmpty();

		Optional<String> full = encode.apply(single);
		if (full.isPresent() && !exceedsDisplayBound(full.get())) {
			return new Display(itemId, label, full.get(), false);
		}
		boolean sizeEvidence = full.isPresent() && exceedsDisplayBound(full.get());

		ItemStack reduced = single.copy();
		reduced.remove(DataComponents.CUSTOM_NAME);
		reduced.remove(DataComponents.ITEM_NAME);
		reduced.remove(DataComponents.LORE);
		Optional<String> reducedJson = encode.apply(reduced);
		if (reducedJson.isPresent() && !exceedsDisplayBound(reducedJson.get())) {
			boolean allComponentsStripped = hadComponents && reduced.getComponentsPatch().isEmpty();
			return new Display(itemId, label, reducedJson.get(), sizeEvidence && allComponentsStripped);
		}
		sizeEvidence |= reducedJson.isPresent() && exceedsDisplayBound(reducedJson.get());

		String json = encode.apply(new ItemStack(stack.getItem()))
			.filter(value -> !exceedsDisplayBound(value))
			.orElseGet(() -> "{\"id\":\"" + itemId + "\"}");
		return new Display(itemId, label, json, sizeEvidence && hadComponents);
	}

	private static Optional<String> encodeDisplay(ItemStack stack, HolderLookup.Provider registries) {
		DataResult<JsonElement> encoded = ItemStack.SINGLE_ITEM_CODEC.encodeStart(
			registries.createSerializationContext(JsonOps.INSTANCE), stack);
		return encoded.result().map(JsonElement::toString);
	}

	private static boolean exceedsDisplayBound(String json) {
		return utf8Bytes(json) > MAX_DISPLAY_BYTES;
	}

	private static long displayCharge(Display display) {
		return utf8Bytes(display.itemId()) + utf8Bytes(display.label()) + utf8Bytes(display.displayJson());
	}

	private static long utf8Bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	private static long checkedAdd(long current, long additional) {
		try {
			return Math.addExact(current, additional);
		} catch (ArithmeticException overflow) {
			throw new IdentityUnavailableException("item catalogue byte accounting overflow");
		}
	}
}
