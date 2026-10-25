package nx.pingwheel.common.presentation.inventory;

import java.util.Arrays;
import java.util.Objects;

import nx.pingwheel.common.presentation.inventory.minecraft.InventoryDisplayWriter;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * One append-only, server-local sweep journal. The caller reserves growth before
 * append. Only ordinals and authoritative longs are stored per slot; catalogues
 * own the shared Key/Display strings and canonical evidence, not this journal.
 * No resident root, detached root, canonical buffer or encoded page is retained.
 */
@NotNullByDefault
public final class InventorySlotJournal implements AutoCloseable {
	public static final int CHUNK_SLOTS = 32;
	private static final int MAX_DEFINITIONS = InventoryItemCodec.DEFAULT_MAX_ENTRIES;
	private static final int INDEX_CAPACITY = 512;
	private static final long METADATA_BYTES = 160, DEFINITION_BYTES = 72;
	private static final long CHUNK_BYTES = 72 + arrayBytes(CHUNK_SLOTS, 8) + arrayBytes(CHUNK_SLOTS, 2);
	private final int firstSlot;
	private short @Nullable [] index = new short[INDEX_CAPACITY]; // zero = no definition; otherwise ordinal + 1
	private @Nullable Chunk @Nullable [] chunks = new Chunk[4];
	private @Nullable Definition @Nullable [] definitions = new Definition[8];
	private int length, chunkCount, definitionCount;
	private boolean closed;

	@NotNullByDefault
	private static final class Chunk {
		final long[] amounts = new long[CHUNK_SLOTS];
		final short[] ordinals = new short[CHUNK_SLOTS]; // -1 is an observed empty slot
	}

	/** One shared first projection per exact/aggregate key, refreshed only from its owning catalogue. */
	@NotNullByDefault
	public static final class Definition {
		private final InventoryScanner.Key key;
		private InventoryItemCodec.Display display;
		private Definition(InventoryItemCodec.Encoded encoded) { key = encoded.key(); display = encoded.display(); }
		public InventoryScanner.Key key() { return key; }
		public InventoryItemCodec.Display display() { return display; }
	}

	@NotNullByDefault
	public static final class CapacityException extends IllegalStateException {
		private CapacityException() { super("inventory journal capacity reached"); }
		@Override public synchronized Throwable fillInStackTrace() { return this; }
	}

	public InventorySlotJournal() { this(0); }
	public InventorySlotJournal(int firstSlot) {
		if (firstSlot < 0 || firstSlot > InventorySourceAccess.MAX_SLOTS) throw new IllegalArgumentException("journal first slot");
		this.firstSlot = firstSlot;
	}

	/** Includes initial arrays at their allocated capacity; no shared catalogue payload is charged here. */
	public long modeledRetainedBytes() {
		if (closed) return 0;
		return METADATA_BYTES + arrayBytes(INDEX_CAPACITY, 2) + arrayBytes(chunks.length, 8) + arrayBytes(definitions.length, 8)
			+ chunkCount * CHUNK_BYTES + definitionCount * DEFINITION_BYTES;
	}
	public static long initialRetainedBytes() {
		return METADATA_BYTES + arrayBytes(INDEX_CAPACITY, 2) + arrayBytes(4, 8) + arrayBytes(8, 8);
	}

	/** Pure pre-read bound for one possibly new definition and slot, including spare allocated capacity. */
	public long appendRetainedGrowthUpperBound() { return appendGrowth(false); }
	/** Cumulative allocation also pays old/new directory overlap; temporary arrays are not retained twice. */
	public long appendAllocationUpperBound() { return appendGrowth(true); }
	/** Pure safe batch bound including old/new directory overlap and possibly all-new definitions. */
	public long batchAllocationUpperBound(int slots) {
		requireOpen();
		if (slots < 0 || slots > InventorySourceAccess.MAX_STEP || slots > InventorySourceAccess.MAX_SLOTS - endSlot()) throw new IllegalArgumentException("journal batch bound");
		int addedChunks = (length + slots + CHUNK_SLOTS - 1) / CHUNK_SLOTS - chunkCount;
		int nextDefinitions = Math.min(MAX_DEFINITIONS, definitionCount + slots);
		long bytes = addedChunks * CHUNK_BYTES + (nextDefinitions - definitionCount) * DEFINITION_BYTES;
		for (int capacity = chunks.length; capacity < chunkCount + addedChunks; capacity *= 2) bytes += arrayBytes(capacity * 2, 8);
		for (int capacity = definitions.length; capacity < nextDefinitions; capacity *= 2) bytes += arrayBytes(capacity * 2, 8);
		return bytes;
	}
	private long appendGrowth(boolean allocation) {
		requireOpen();
		if (endSlot() == InventorySourceAccess.MAX_SLOTS) return 0;
		long bytes = definitionCount < MAX_DEFINITIONS ? DEFINITION_BYTES : 0;
		if (definitionCount == definitions.length && definitionCount < MAX_DEFINITIONS)
			bytes += arrayBytes(definitions.length * 2, 8) - (allocation ? 0 : arrayBytes(definitions.length, 8));
		if (length % CHUNK_SLOTS == 0) {
			bytes += CHUNK_BYTES;
			if (chunkCount == chunks.length) bytes += arrayBytes(chunks.length * 2, 8) - (allocation ? 0 : arrayBytes(chunks.length, 8));
		}
		return bytes;
	}

	public int size() { requireOpen(); return length; }
	public int endSlot() { requireOpen(); return firstSlot + length; }
	public int allocatedSlotCapacity() { requireOpen(); return chunkCount * CHUNK_SLOTS; }
	public int definitionCount() { requireOpen(); return definitionCount; }
	public Definition definition(int ordinal) { requireOpen(); return Objects.requireNonNull(definitions[Objects.checkIndex(ordinal, definitionCount)]); }

	/** An empty slot requires a null definition and zero amount; a zero-count nonempty observation stays distinct. */
	public void append(int absoluteSlot, @Nullable InventoryItemCodec.Encoded encoded, long amount) {
		requireOpen();
		if (absoluteSlot != endSlot() || absoluteSlot >= InventorySourceAccess.MAX_SLOTS || amount < 0 || (encoded == null && amount != 0))
			throw new IllegalArgumentException("journal append bounds");
		int bucket = encoded == null ? -1 : bucket(encoded.key());
		int ordinal = bucket < 0 ? -1 : index[bucket] - 1;
		boolean newDefinition = encoded != null && ordinal < 0;
		if (newDefinition && definitionCount == MAX_DEFINITIONS) throw new CapacityException();
		if (newDefinition && (encoded.display().aggregateRequired() != encoded.key().componentsKey().isEmpty()
			|| !encoded.display().itemId().equals(encoded.key().itemId()))) throw new IllegalArgumentException("journal identity mode");
		if (length % CHUNK_SLOTS == 0) {
			if (chunkCount == chunks.length) chunks = Arrays.copyOf(chunks, chunks.length * 2);
			chunks[chunkCount++] = new Chunk();
		}
		if (newDefinition) {
			if (definitionCount == definitions.length) definitions = Arrays.copyOf(definitions, definitions.length * 2);
			ordinal = definitionCount;
			definitions[definitionCount++] = new Definition(Objects.requireNonNull(encoded));
			index[bucket] = (short) (ordinal + 1);
		}
		Chunk chunk = Objects.requireNonNull(chunks[length / CHUNK_SLOTS]);
		chunk.amounts[length % CHUNK_SLOTS] = amount;
		chunk.ordinals[length % CHUNK_SLOTS] = (short) ordinal;
		length++; // committed prefix advances only after both fields are stored
	}

	private int bucket(InventoryScanner.Key key) {
		int hash = key.hashCode(); int bucket = (hash ^ (hash >>> 16)) & (INDEX_CAPACITY - 1);
		while (index[bucket] != 0 && !definition(index[bucket] - 1).key.equals(key)) bucket = (bucket + 1) & (INDEX_CAPACITY - 1);
		return bucket;
	}
	public int definitionOrdinal(int absoluteSlot) {
		int offset = offset(absoluteSlot);
		return Objects.requireNonNull(chunks[offset / CHUNK_SLOTS]).ordinals[offset % CHUNK_SLOTS];
	}
	public long amount(int absoluteSlot) {
		int offset = offset(absoluteSlot);
		return Objects.requireNonNull(chunks[offset / CHUNK_SLOTS]).amounts[offset % CHUNK_SLOTS];
	}
	public @Nullable InventoryDomainCodec.Item read(int absoluteSlot) {
		int ordinal = definitionOrdinal(absoluteSlot);
		if (ordinal < 0) return null;
		Definition value = definition(ordinal); InventoryItemCodec.Display display = value.display;
		return new InventoryDomainCodec.Item(value.key, amount(absoluteSlot), display.label(), display.displayJson(), display.aggregateRequired());
	}

	/**
	 * Run after the catalogue's admitted tightening, before its old projection
	 * charge is released. No codec, JSON parse or source read occurs. A null or
	 * terminal first projection cannot be replaced with a fuller later value.
	 */
	public void refreshDisplays(InventoryItemCodec.Catalog catalog) {
		requireOpen(); Objects.requireNonNull(catalog, "catalog");
		for (int ordinal = 0; ordinal < definitionCount; ordinal++) {
			Definition definition = definition(ordinal);
			InventoryItemCodec.Display next = Objects.requireNonNull(catalog.retainedDisplay(definition.key), "journal catalogue definition missing");
			InventoryItemCodec.Display old = definition.display;
			if (old.displayJson() == null || InventoryDisplayWriter.isDefaultIcon(old.itemId(), old.displayJson())) continue;
			if (next.displayJson() != null && next.displayJson().length() > old.displayJson().length())
				throw new IllegalArgumentException("journal projection expansion");
			definition.display = next;
		}
	}

	private int offset(int absoluteSlot) { requireOpen(); return Objects.checkIndex(absoluteSlot - firstSlot, length); }
	private static long arrayBytes(int capacity, int width) { return 40L + (long) capacity * width; }
	private void requireOpen() { if (closed) throw new IllegalStateException("closed inventory journal"); }
	@Override public void close() {
		if (closed) return;
		index = null; chunks = null; definitions = null; closed = true;
	}
}
