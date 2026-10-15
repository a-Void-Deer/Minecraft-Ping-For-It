package nx.pingwheel.common.presentation.inventory.minecraft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
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
import java.util.Set;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.config.InventorySizeScale;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary;

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
 * single-count {@link ItemStack#SINGLE_ITEM_CODEC} JSON form bounded to a
 * validated effective display byte cap; when it is too large, display-only
 * components may be omitted without changing identity. Lore and shulker item
 * contents are always redacted recursively. {@link Display#aggregateRequired()}
 * is set only when complete identity was refused, never for display omission. Only server-owned
 * strings and bounded bytes leave this class.
 *
 * <p>The catalog retains canonical identity bytes once per component digest and
 * each display value once per variant key under finite entry and retained-byte
 * guards; exceeding a guard reports the source unavailable instead of
 * truncating or emptying the catalog. A retained display never expands later:
 * lowering the effective cap reprojects the retained values, and an
 * already-known key keeps its retained projection even after a later cap
 * increase.
 */
public final class InventoryItemCodec {

	/** Internal guard for one canonical component payload; not a product value. */
	public static final int MAX_CANONICAL_BYTES = 65536;

	/**
	 * Established inventory item id bound: the same 256 UTF-8 byte limit the
	 * domain codec and the inventory wire apply. A display item id must be able
	 * to travel through both, so the bound is enforced here as well.
	 */
	private static final int MAX_ITEM_ID_BYTES = 256;

	/**
	 * Default display JSON projection cap in bytes. It is the backward-
	 * compatible 4 KiB projection used until a caller supplies a validated
	 * effective cap; it is not the absolute display bound.
	 */
	public static final int MAX_DISPLAY_BYTES = 4096;

	/**
	 * Smallest legal effective display cap. A particular suppression envelope
	 * can still be unavailable at this cap without changing exact identity.
	 */
	private static final int MIN_DISPLAY_BYTES =
		(int) InventorySizeScale.DISPLAY.bytes(InventorySizeScale.DISPLAY.minimumMultiplier());

	/**
	 * The shared lower bound for every effective display ceiling, so other
	 * ceiling owners never repeat an independent minimum.
	 */
	public static int minimumDisplayBytes() {
		return MIN_DISPLAY_BYTES;
	}

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

	private static final String DIGEST_ALGORITHM = "SHA-256";
	// Acquired and warmed ONCE at class initialization, never per resident input.
	// Only the audited bootstrap SUN implementation can enter the admitted path.
	private static final MessageDigest CANONICAL_DIGEST = canonicalDigest();
	private static final HexFormat CANONICAL_HEX = HexFormat.of();

	private InventoryItemCodec() {}

	/** Whole-root admission precedes native copy/codec/hash; no resident object is retained. */
	public static InventoryDetachedItem freeze(InventoryResidentItem input, HolderLookup.Provider registries,
		int displayBytes, InventoryItemBudget budget) {
		return freeze(input, registries, displayBytes, budget, DefaultAccess.RAW, DefaultAccess.NATIVE);
	}
	private static final class DefaultAccess {
		static final InventoryResidentAccess RAW = InventoryResidentAccess.load();
		static final InventoryItemNativeAccess NATIVE = InventoryItemNativeAccess.load();
	}

	/** Field-equivalent headless injection; never a production reflection fallback. */
	static InventoryDetachedItem freeze(InventoryResidentItem input, HolderLookup.Provider registries,
		int displayBytes, InventoryItemBudget budget, InventoryResidentAccess raw, InventoryItemNativeAccess nativeAccess) {
		Objects.requireNonNull(input, "input"); Objects.requireNonNull(registries, "registries");
		Objects.requireNonNull(budget, "budget"); Objects.requireNonNull(raw, "raw"); Objects.requireNonNull(nativeAccess, "nativeAccess");
		requireDisplayBytes(displayBytes);
		InventoryRegistryShapes.initialize(); InventoryComponentCertificate.initialize(); InventoryItemFreezer.initialize();
		budget.begin(displayBytes);
		try {
			budget.allocate(64, 0, 24, 3); // Freezer/Bounds/Certificate fields; arrays admit themselves
			return new InventoryItemFreezer(registries, displayBytes, budget, raw, nativeAccess).freeze(input);
		}
		finally { budget.end(); }
	}

	/**
	 * Detached display value; {@code label} is a registry translation key. The
	 * item id must be a non-blank inventory id within the established 256
	 * UTF-8 byte bound. A null JSON is an unavailable icon projection; metadata
	 * and exact/aggregate identity remain usable independently.
	 */
	public record Display(String itemId, String label, String displayJson, boolean componentsStripped) {
		public Display {
			Objects.requireNonNull(itemId, "itemId");
			Objects.requireNonNull(label, "label");
			if (!validItemId(itemId)) {
				throw new IllegalArgumentException("inventory display item id");
			}
		}
		/** Legacy field name; only unsafe/oversized IDENTITY requests aggregation. */
		public boolean aggregateRequired() { return componentsStripped; }
	}

	/** Safe group display from admitted metadata only; null JSON means unavailable. */
	public static Display aggregateDisplay(String itemId, String label, int displayBytes) {
		if (displayBytes < MIN_DISPLAY_BYTES || displayBytes > InventoryLimits.MAX_DISPLAY_BYTES)
			throw new AggregateMetadataException();
		if (!validItemId(itemId) || label == null || label.length() > 262)
			throw new AggregateMetadataException();
		for (int index = 0; index < label.length(); index++) {
			char c = label.charAt(index);
			if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '.')
				throw new AggregateMetadataException();
		}
		return new Display(itemId, label, InventoryDisplayWriter.aggregate(itemId), true);
	}

	/** Fixed metadata refusal: no resident data or allocation of a stack trace. */
	private static final class AggregateMetadataException extends IllegalArgumentException {
		AggregateMetadataException() { super("inventory aggregate metadata"); }
		@Override public synchronized Throwable fillInStackTrace() { return this; }
	}

	private static boolean validItemId(String id) {
		if (id == null || id.isEmpty() || id.length() > MAX_ITEM_ID_BYTES) return false;
		int colon = -1;
		for (int index = 0; index < id.length(); index++) {
			char c = id.charAt(index);
			if (c == ':') { if (colon >= 0 || index == 0 || index == id.length() - 1) return false; colon = index; }
			else if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '.' && c != '-'
				&& !(c == '/' && colon >= 0)) return false;
		}
		return colon >= 0;
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
		@Override public synchronized Throwable fillInStackTrace() { return this; }
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
		static {
			// Fixed record bootstrap/class-name metadata, not first-input work.
			var key = new InventoryScanner.Key("minecraft:air", "");
			key.hashCode(); key.equals(new InventoryScanner.Key("minecraft:air", ""));
			InventoryScanner.Key.class.getName(); String.class.getName();
		}

		private final HolderLookup.Provider registries;
		private final InventoryResidentAccess raw;
		private final InventoryItemNativeAccess nativeAccess;
		private final int maxEntries;
		private final long maxRetainedBytes;
		/** Retained display ceiling; it is only ever lowered, never raised. */
		private int displayBytes;
		private final Map<String, byte[]> canonicalByDigest = new HashMap<>(InventoryCatalogAdmission.TABLE_CAPACITY);
		private final Set<String> conflicts = new HashSet<>(InventoryCatalogAdmission.TABLE_CAPACITY);
		private final Map<InventoryScanner.Key, Display> displays = new LinkedHashMap<>(InventoryCatalogAdmission.TABLE_CAPACITY);
		private long retainedBytes;
		private InventoryReprojectionSummary reprojection = InventoryReprojectionSummary.EMPTY;

		/** Uses the finite default entry and retained-byte guards and the default 4 KiB display cap. */
		public Catalog(HolderLookup.Provider registries) {
			this(registries, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_RETAINED_BYTES, MAX_DISPLAY_BYTES);
		}

		/** Uses the finite default entry and retained-byte guards with a validated effective display cap. */
		public Catalog(HolderLookup.Provider registries, int displayBytes) {
			this(registries, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_RETAINED_BYTES, displayBytes);
		}

		/** Uses the default 4 KiB display cap; kept for existing callers until the live cap is threaded. */
		public Catalog(HolderLookup.Provider registries, int maxEntries, long maxRetainedBytes) {
			this(registries, maxEntries, maxRetainedBytes, MAX_DISPLAY_BYTES);
		}

		/**
		 * Caller-specified finite guards. {@code maxEntries} bounds both the
		 * variant keys and the distinct component digests; {@code
		 * maxRetainedBytes} bounds the retained canonical and display bytes
		 * together; {@code displayBytes} is the validated effective display
		 * projection cap. Unlimited is not a sentinel here: the guards are
		 * positive finite numbers.
		 */
		public Catalog(HolderLookup.Provider registries, int maxEntries, long maxRetainedBytes, int displayBytes) {
			this(registries, maxEntries, maxRetainedBytes, displayBytes, DefaultAccess.RAW, DefaultAccess.NATIVE);
		}

		Catalog(HolderLookup.Provider registries, int maxEntries, long maxRetainedBytes, int displayBytes,
			InventoryResidentAccess raw, InventoryItemNativeAccess nativeAccess) {
			this.registries = Objects.requireNonNull(registries, "registries");
			this.raw = Objects.requireNonNull(raw, "raw"); this.nativeAccess = Objects.requireNonNull(nativeAccess, "nativeAccess");
			if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
			if (maxRetainedBytes < 1) {
				throw new IllegalArgumentException("maxRetainedBytes must be positive");
			}
			this.maxEntries = maxEntries;
			this.maxRetainedBytes = maxRetainedBytes;
			this.displayBytes = requireDisplayBytes(displayBytes);
		}

		/** Live read-only view; entries appear as stacks are encoded. */
		public Map<InventoryScanner.Key, Display> displays() {
			return Collections.unmodifiableMap(displays);
		}

		/** Retained canonical plus display bytes; package-visible accounting. */
		long retainedBytes() {
			return retainedBytes;
		}

		/** The current retained display ceiling in bytes. */
		public int displayBytes() {
			return displayBytes;
		}

		/**
		 * Actual retained detached inputs for a strictly-lowering tightening.
		 * Pure O(1) query: no allocations, parser, UTF-8 scan or provider access.
		 * Duplicate keys retain only one display; canonical collision evidence has
		 * no display input. Source-owned frozen/other catalogues remain separate.
		 */
		public InventoryReprojectionSummary reprojectionSummary(int newDisplayBytes) {
			return requireDisplayBytes(newDisplayBytes) >= displayBytes ? InventoryReprojectionSummary.EMPTY : reprojection;
		}

		/**
		 * Lowers the retained display ceiling and reprojects every retained
		 * display at the new cap, without recomputing variant keys or canonical
		 * maps. A value at or above the current ceiling is ignored, so a later
		 * configuration increase never expands already-retained metadata.
		 */
		public void tightenDisplay(int displayBytes) {
			int ceiling = requireDisplayBytes(displayBytes);
			if (ceiling >= this.displayBytes) return;
			if (reprojection.parserCalls() == 0) { this.displayBytes = ceiling; return; }
			long refund = 0L;
			// Only actual parser owners need replacement cells. Each nonnull
			// input's public replacement allowance pays its cell/Display, while
			// METADATA_BYTES pays the two iterators, Builder and final record.
			Display[] replacements = new Display[Math.toIntExact(reprojection.parserCalls())];
			var next = new InventoryReprojectionSummary.Builder().entries(displays.size());
			int index = 0;
			for (Display previous : displays.values()) {
				if (previous.displayJson() == null) continue;
				Display tightened = reproject(previous, registries, ceiling);
				next.input(tightened.displayJson()); replacements[index++] = tightened;
				refund = checkedAdd(refund, utf8Bytes(previous.displayJson()) - utf8Bytes(tightened.displayJson()));
			}
			InventoryReprojectionSummary summary = next.build();
			long nextRetained = Math.subtractExact(retainedBytes, refund);
			// No mutation until every projection, checked sum and metadata
			// allocation succeeds. The native linked map's value swap cannot hash
			// keys or call a provider; no new map nodes are allocated.
			index = 0;
			for (var entry : displays.entrySet()) if (entry.getValue().displayJson() != null) entry.setValue(replacements[index++]);
			reprojection = summary;
			this.displayBytes = ceiling;
			retainedBytes = nextRetained;
		}

		/** Explicit grant form of the existing actual-input tightening contract. */
		public void tightenDisplay(int displayBytes, InventoryCatalogAdmission admission) {
			Objects.requireNonNull(admission, "admission");
			InventoryReprojectionSummary summary = reprojectionSummary(displayBytes);
			if (summary != InventoryReprojectionSummary.EMPTY)
				admission.admit(summary.workspaceBytes(), summary.workUnits());
			tightenDisplay(displayBytes);
		}

		/** Caller-trusted compatibility API; native routes use the admitted overload. */
		public Encoded encode(ItemStack stack) {
			return intern(freeze(new InventoryResidentItem.Stack(stack), registries, displayBytes, InventoryItemBudget.root(displayBytes), raw, nativeAccess));
		}

		/** Native callers hold both freeze and catalog allowances before entry. */
		public Encoded encode(ItemStack stack, InventoryItemBudget budget, InventoryCatalogAdmission admission) {
			Objects.requireNonNull(admission, "admission");
			return intern(freeze(new InventoryResidentItem.Stack(stack), registries, displayBytes, budget, raw, nativeAccess), admission);
		}

		/** Caller-trusted compatibility API; not a certified native admission route. */
		public Encoded intern(InventoryDetachedItem detached) {
			return internOwned(detached, null);
		}

		/** Every hash, lookup, parser, clone and summary update follows admission. */
		public Encoded intern(InventoryDetachedItem detached, InventoryCatalogAdmission admission) {
			return internOwned(detached, Objects.requireNonNull(admission, "admission"));
		}

		private Encoded internOwned(InventoryDetachedItem detached, InventoryCatalogAdmission admission) {
			Objects.requireNonNull(detached, "detached");
			byte[] canonical = detached.canonicalForCatalog();
			if (admission != null) {
				if (maxEntries > DEFAULT_MAX_ENTRIES || maxRetainedBytes > DEFAULT_MAX_RETAINED_BYTES)
					throw new IllegalArgumentException("catalog guards exceed certified envelope");
				Display input = detached.display(); String json = input.displayJson();
				// The OLD projector refuses an overlong String before scanning or
				// allocating scratch. It is unavailable display, never lost identity.
				int units = json == null || json.length() > InventoryLimits.MAX_DISPLAY_BYTES ? 0 : json.length();
				admission.admit(0, InventoryCatalogAdmission.scanWorkUpperBound(units, detached.itemId().length(), input.label().length()));
				int bytes = units == 0 ? 0 : InventoryReprojectionSummary.utf8Bytes(json);
				if (bytes > InventoryLimits.MAX_DISPLAY_BYTES) bytes = 0; // fixed OLD byte refusal, no parser scratch
				int count = Math.max(displays.size(), Math.max(canonicalByDigest.size(), conflicts.size()));
				int length = canonical == null ? 0 : canonical.length;
				admission.admit(InventoryCatalogAdmission.workspaceUpperBound(length, bytes, count),
					InventoryCatalogAdmission.operationWorkUpperBound(length, bytes, count));
			}
			String componentsKey = detached.identityMode() == InventoryIdentityMode.ALL_VARIANTS ? "" : digest(canonical);
			byte[] known = null;
			if (canonical != null) {
				if (conflicts.contains(componentsKey)) throw new IdentityUnavailableException("component digest is ambiguous");
				known = canonicalByDigest.get(componentsKey);
				if (known != null && !Arrays.equals(known, canonical)) {
					conflicts.add(componentsKey);
					throw new IdentityUnavailableException("component digest collision");
				}
			}
			InventoryScanner.Key key = new InventoryScanner.Key(detached.itemId(), componentsKey);
			Display retained = displays.get(key);
			if (retained != null) {
				// A known key keeps its retained projection: a later ceiling
				// increase never expands already-retained metadata. The digest
				// was still checked against canonical collision evidence above.
				return new Encoded(key, retained);
			}
			Display display = reproject(detached.display(), registries, displayBytes);
			retain(componentsKey, canonical, known == null, key, display);
			return new Encoded(key, display);
		}

		private void retain(String componentsKey, byte[] canonical, boolean unknownDigest, InventoryScanner.Key key, Display display) {
			boolean newDigest = canonical != null && unknownDigest;
			if (newDigest && canonicalByDigest.size() >= maxEntries) {
				throw new IdentityUnavailableException("item catalogue entry bound reached");
			}
			if (displays.size() >= maxEntries) {
				throw new IdentityUnavailableException("item catalogue entry bound reached");
			}

			long charge = newDigest ? canonical.length : 0L;
			charge = checkedAdd(charge, displayCharge(display));
			long next = checkedAdd(retainedBytes, charge);
			if (next > maxRetainedBytes) {
				throw new IdentityUnavailableException("item catalogue byte bound reached");
			}
			// Checked detached summary admission precedes canonical cloning and map
			// insertion. Duplicate keys never add a second parser invocation.
			InventoryReprojectionSummary nextSummary = reprojection.plus(
				new InventoryReprojectionSummary.Builder().entries(1).input(display.displayJson()).build());

			if (newDigest) canonicalByDigest.put(componentsKey, canonical.clone());
			displays.put(key, display);
			reprojection = nextSummary;
			retainedBytes = next;
		}
	}

	/**
	 * Bounded compatibility helper for callers requiring exact canonical bytes.
	 * The production path freezes resident data first and never copies a stack.
	 */
	static byte[] canonicalComponents(HolderLookup.Provider registries, ItemStack stack) {
		InventoryDetachedItem detached = freeze(new InventoryResidentItem.Stack(stack), registries,
			MAX_DISPLAY_BYTES, InventoryItemBudget.root(MAX_DISPLAY_BYTES));
		if (detached.identityMode() != InventoryIdentityMode.EXACT) throw new IdentityUnavailableException("item identity refused");
		return detached.canonicalForCatalog().clone();
	}

	/** Canonical recursive NBT form: sorted compound keys, preserved lists and types. */
	static byte[] canonicalTag(Tag tag) {
		return InventoryCanonicalWriter.write(Objects.requireNonNull(tag, "tag"), null);
	}

	static byte[] canonicalTag(Tag tag, InventoryItemBudget budget) {
		return InventoryCanonicalWriter.write(Objects.requireNonNull(tag, "tag"), budget);
	}

	/** Stable lowercase SHA-256 hex digest of canonical bytes. */
	static String digest(byte[] canonical) {
		Objects.requireNonNull(canonical, "canonical");
		synchronized (CANONICAL_DIGEST) {
			try { return CANONICAL_HEX.formatHex(CANONICAL_DIGEST.digest(canonical)); }
			finally { CANONICAL_DIGEST.reset(); }
		}
	}

	private static MessageDigest canonicalDigest() {
		try {
			Provider provider = Security.getProvider("SUN");
			if (provider == null || provider.getClass().getClassLoader() != null
				|| !provider.getClass().getName().equals("sun.security.provider.Sun")
				|| provider.getClass().getModule() != Object.class.getModule())
				throw new IllegalStateException("audited SUN SHA-256 is unavailable");
			Provider.Service service = provider.getService("MessageDigest", DIGEST_ALGORITHM);
			if (service == null || service.getClass() != Provider.Service.class
				|| !service.getClassName().equals("sun.security.provider.SHA2$SHA256"))
				throw new IllegalStateException("audited SUN SHA-256 service is unavailable");
			MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM, provider);
			// SHA2.W is lazy. Warm it and the exact lowercase HexFormat path here;
			// no provider acquisition or digest workspace allocation remains per call.
			HexFormat.of().formatHex(digest.digest(new byte[0])); digest.reset();
			return digest;
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
	 * Bounded display projection at the default 4 KiB cap. Identity is computed
	 * from the full component set before this step; display-only components may
	 * be omitted to fit the bound without changing identity.
	 * Identity refusal, not display omission, controls the aggregate bit.
	 */
	static Display display(ItemStack stack, HolderLookup.Provider registries) {
		return display(stack, registries, MAX_DISPLAY_BYTES);
	}

	/** Bounded display projection at a validated effective display cap. */
	static Display display(ItemStack stack, HolderLookup.Provider registries, int displayBytes) {
		return freeze(new InventoryResidentItem.Stack(stack), registries, displayBytes, InventoryItemBudget.root(displayBytes)).display();
	}

	/**
	 * Re-projects one retained display at a validated effective cap using the
	 * same bounded projection ladder as a fresh observation. Fitting input is
	 * still validated. Only owned bounded JSON is scanned;
	 * no server ItemStack or component decode is performed. Identity mode is
	 * preserved, including at the fully simplified end of the ladder.
	 */
	public static Display reproject(Display retained, HolderLookup.Provider registries, int displayBytes) {
		Objects.requireNonNull(retained, "retained");
		Objects.requireNonNull(registries, "registries");
		requireDisplayBytes(displayBytes);
		if (retained.displayJson() == null) return retained;
		String json = InventoryDisplayWriter.reproject(retained.displayJson(), retained.itemId(), displayBytes);
		// Retained projections are monotone even for malformed/legacy envelopes
		// whose mandatory suppression would otherwise grow a shorter old value.
		if (json != null && json.length() > retained.displayJson().length()) json = null;
		if (Objects.equals(json, retained.displayJson())) return retained;
		return new Display(retained.itemId(), retained.label(), json, retained.aggregateRequired());
	}


	/**
	 * The one validated effective display cap range: the display scale's
	 * smallest legal cap through the absolute display budget. Any byte cap in
	 * that range is accepted; the confirmed configuration grid stays enforced
	 * by its own owner.
	 */
	private static int requireDisplayBytes(int displayBytes) {
		if (displayBytes < MIN_DISPLAY_BYTES || displayBytes > InventoryLimits.MAX_DISPLAY_BYTES) {
			throw new IllegalArgumentException("display byte cap is out of range");
		}
		return displayBytes;
	}

	private static long displayCharge(Display display) {
		return utf8Bytes(display.itemId()) + utf8Bytes(display.label()) + utf8Bytes(display.displayJson());
	}

	private static long utf8Bytes(String value) {
		return value == null ? 0 : InventoryDisplayWriter.utf8(value);
	}

	private static long checkedAdd(long current, long additional) {
		try {
			return Math.addExact(current, additional);
		} catch (ArithmeticException overflow) {
			throw new IdentityUnavailableException("item catalogue byte accounting overflow");
		}
	}

	/** Literal lock-name flattening only; never invokes language/keybind/world resolvers. */
	static boolean lockMatchesResident(ItemStack heldKey, String lockKey, InventoryItemBudget budget) {
		return lockMatchesResident(heldKey, lockKey, budget, DefaultAccess.RAW);
	}

	static boolean lockMatchesResident(ItemStack heldKey, String lockKey, InventoryItemBudget budget, InventoryResidentAccess raw) {
		Objects.requireNonNull(heldKey); Objects.requireNonNull(lockKey); Objects.requireNonNull(budget);
		if (lockKey.isEmpty()) return true;
		InventoryComponentCertificate.initialize(); InventoryItemFreezer.initialize();
		budget.begin(budget.displayBytes());
		try {
			budget.string(lockKey); if (heldKey.isEmpty()) return false;
			var bounds = new InventoryComponentBounds(budget, raw);
			DataComponentMap map = heldKey.getComponents(); InventoryComponentCertificate.exact(map, PatchedDataComponentMap.class);
			var patched = (PatchedDataComponentMap) map; var patch = raw.patch(patched);
			int size = bounds.mapPass(patch); Object name = null; boolean replaced = false;
			if (size != 0) for (var entry : patch.entrySet()) if (entry.getKey() == DataComponents.CUSTOM_NAME) { name = entry.getValue().orElse(null); replaced = true; }
			if (!replaced) {
				var prototype = raw.prototype(patched); if (prototype == DataComponentMap.EMPTY) return false;
				if (!InventoryItemFreezer.simpleMap(prototype)) InventoryComponentCertificate.refuse();
				var defaults = raw.simple(prototype); int count = bounds.mapPass(defaults);
				if (count != 0) for (var entry : defaults.entrySet()) if (entry.getKey() == DataComponents.CUSTOM_NAME) name = entry.getValue();
			}
			if (name == null) return false;
			budget.allocate(4, 0, 0, 1);
			int[] cursor = {0}; return literalMatches((net.minecraft.network.chat.Component) name, lockKey, cursor, bounds, budget, 0) && cursor[0] == lockKey.length();
		} catch (InventoryItemBudget.RootLimitException unsupported) { return false; }
		finally { budget.end(); }
	}

	private static boolean literalMatches(net.minecraft.network.chat.Component component, String key, int[] cursor,
		InventoryComponentBounds bounds, InventoryItemBudget budget, int depth) {
		InventoryComponentCertificate.exact(component, net.minecraft.network.chat.MutableComponent.class); budget.node(depth); bounds.enter(component);
		try {
			var contents = component.getContents(); String literal;
			if (contents == net.minecraft.network.chat.contents.PlainTextContents.EMPTY) literal = "";
			else if (contents.getClass() == net.minecraft.network.chat.contents.PlainTextContents.LiteralContents.class)
				literal = ((net.minecraft.network.chat.contents.PlainTextContents.LiteralContents) contents).text();
			else return false;
			budget.string(literal); if (literal.length() > key.length() - cursor[0] || !key.regionMatches(cursor[0], literal, 0, literal.length())) return false;
			cursor[0] += literal.length(); var siblings = bounds.list(component.getSiblings()); budget.work(siblings.size());
			for (Object sibling : siblings) if (!literalMatches((net.minecraft.network.chat.Component) sibling, key, cursor, bounds, budget, depth + 1)) return false;
			return true;
		} finally { bounds.leave(); }
	}
}
