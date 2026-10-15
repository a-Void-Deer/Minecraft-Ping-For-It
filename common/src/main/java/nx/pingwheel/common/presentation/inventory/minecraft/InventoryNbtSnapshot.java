package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;

/**
 * Caller-trusted extension for complete per-member detached NBT payloads, not
 * automatic production extraction or certification of arbitrary Counter/CODEC
 * behavior. The caller owns source safety and frozen semantic dependencies.
 */
public final class InventoryNbtSnapshot implements InventorySourceAccess.InventorySnapshot {

	private static final long SLOT_ALLOWANCE_BYTES = 65536L, MEMBER_ALLOWANCE_BYTES = 65536L;
	private static final long FIXED_ALLOWANCE_BYTES = 2097152L;
	private static final long TREE_COPY_FACTOR = 4L;
	// Charged for the whole lifetime, before a decode occurs. Covers the bounded
	// item catalog, its Java strings/maps, canonical/display byte workspace,
	// and the first-read index workspace. It is not a wire-byte estimate.
	private static final long DECODE_RESERVE_BYTES = 1048576L;
	private static final int MAX_TAG_DEPTH = 64, MAX_TAG_NODES = 262144;

	private InventorySnapshotLayout layout;
	private BlockPos originalTarget;
	private HolderLookup.Provider registries;
	private SegmentState[] segments;
	private int[] segmentBySlot, visibleBySlot;
	private InventoryItemCodec.Catalog catalog;
	private final int slots;
	private final long accountedBytes;
	private int displayBytes;
	private boolean closed;

	/** Explicit finite engineering cap; large NBT is rejected rather than truncated. */
	public static long memoryUpperBound(int visibleSlots, int memberCount) {
		if (visibleSlots < 0 || visibleSlots > InventorySourceAccess.MAX_SLOTS
			|| memberCount < 0 || memberCount > InventorySnapshotLayout.MAX_MEMBERS) throw new IllegalArgumentException("snapshot structure bound");
		return add(FIXED_ALLOWANCE_BYTES, add(multiply(visibleSlots, SLOT_ALLOWANCE_BYTES), multiply(memberCount, MEMBER_ALLOWANCE_BYTES)));
	}

	/** Uses the backward-compatible default 4 KiB display projection. */
	public static InventoryNbtSnapshot create(InventorySnapshotLayout layout, BlockPos originalTarget,
		List<Tag> payloads, HolderLookup.Provider registries, long memoryLimitBytes) {
		return create(layout, originalTarget, payloads, registries, memoryLimitBytes, InventoryItemCodec.MAX_DISPLAY_BYTES);
	}

	/**
	 * Payload order is member order, then each member's segment order. The
	 * factory accepts every segment or none and performs no ItemStack decoding.
	 * All trees are measured with checked long arithmetic before any deep copy.
	 * {@code displayBytes} is the validated effective display ceiling the lazy
	 * decode catalogue will adopt; it is only ever lowered afterwards.
	 */
	public static InventoryNbtSnapshot create(InventorySnapshotLayout layout, BlockPos originalTarget,
		List<Tag> payloads, HolderLookup.Provider registries, long memoryLimitBytes, int displayBytes) {
		Objects.requireNonNull(layout, "layout");
		Objects.requireNonNull(originalTarget, "originalTarget");
		Objects.requireNonNull(payloads, "payloads");
		Objects.requireNonNull(registries, "registries");
		if (memoryLimitBytes < 0) throw new IllegalArgumentException("negative snapshot memory limit");
		if (displayBytes < InventoryItemCodec.minimumDisplayBytes() || displayBytes > InventoryLimits.MAX_DISPLAY_BYTES)
			throw new IllegalArgumentException("display byte cap is out of range");
		if (layout.members().stream().noneMatch(member -> member.position().equals(originalTarget))) throw new IllegalArgumentException("original target is not a snapshot member");

		List<InventorySnapshotLayout.Segment> topology = new ArrayList<>();
		int slots = 0;
		for (var member : layout.members()) {
			for (var segment : member.segments()) {
				topology.add(segment);
				slots = Math.addExact(slots, segment.visibleSlots().size());
			}
		}
		if (payloads.size() != topology.size()) throw new IllegalArgumentException("snapshot requires every member segment payload");
		long limit = Math.min(memoryLimitBytes, memoryUpperBound(slots, layout.members().size()));
		long fixed = add(envelopeBytes(layout), add(DECODE_RESERVE_BYTES, add(multiply(slots, 64L), multiply(topology.size(), 256L))));
		if (fixed > limit) throw new LimitException("snapshot structure and decode workspace exceed memory limit");
		TreeGuard guard = new TreeGuard((limit - fixed) / TREE_COPY_FACTOR);
		InventorySnapshotSchemas.Counter[] counters = new InventorySnapshotSchemas.Counter[topology.size()];
		for (int i = 0; i < payloads.size(); i++) {
			counters[i] = InventorySnapshotSchemas.counter(topology.get(i).schemaId());
			Tag payload = Objects.requireNonNull(payloads.get(i), "payload");
			// Caller-trusted extension, not automatic production extraction. Guard
			// exact backing/capacity/cycles before TreeGuard or native copy traverses.
			preflightNative(payload, displayBytes);
			guard.measure(payload, 0);
		}
		// One persistent tree plus isolated read/export, decoded component and
		// component-encoding tree workspace. Lazy indexes and catalog storage are
		// covered above, so later allocation cannot outgrow a settled ticket.
		long accounted = add(fixed, multiply(guard.bytes, TREE_COPY_FACTOR));
		SegmentState[] segments = new SegmentState[topology.size()];
		for (int i = 0; i < segments.length; i++) segments[i] = new SegmentState(topology.get(i), payloads.get(i).copy(), counters[i]);
		return new InventoryNbtSnapshot(layout, originalTarget.immutable(), registries, segments, slots, accounted, displayBytes);
	}

	private InventoryNbtSnapshot(InventorySnapshotLayout layout, BlockPos originalTarget, HolderLookup.Provider registries,
		SegmentState[] segments, int slots, long accountedBytes, int displayBytes) {
		this.layout = layout;
		this.originalTarget = originalTarget;
		this.registries = registries;
		this.segments = segments;
		this.slots = slots;
		this.accountedBytes = accountedBytes;
		this.displayBytes = displayBytes;
		segmentBySlot = new int[slots];
		visibleBySlot = new int[slots];
		int cursor = 0;
		for (int i = 0; i < segments.length; i++) {
			for (int visible = 0; visible < segments[i].topology.visibleSlots().size(); visible++) {
				segmentBySlot[cursor] = i;
				visibleBySlot[cursor++] = visible;
			}
		}
	}

	public InventorySnapshotLayout layout() { requireOpen(); return layout; }
	public BlockPos originalTarget() { requireOpen(); return originalTarget; }
	/** Caller-owned defensive copy; no mutable internal tag escapes. */
	public Tag payload(int segment) { requireOpen(); return isolated(segments[segment].payload); }
	@Override public int slots() { requireOpen(); return slots; }
	@Override public long retainedBytes() { return closed ? 0L : accountedBytes; }
	@Override public InventorySourceAccess.SnapshotEvidence evidence() { return InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED; }
	@Override public int displayBytes() { requireOpen(); return displayBytes; }
	@Override public InventoryReprojectionSummary reprojectionSummary(int displayBytes) {
		requireOpen(); InventoryMinecraftSources.requireDisplayBytes(displayBytes);
		return displayBytes >= this.displayBytes || catalog == null ? InventoryReprojectionSummary.EMPTY : catalog.reprojectionSummary(displayBytes);
	}

	/**
	 * Monotone ceiling: a later configuration increase never expands retained
	 * metadata. An existing lazy catalogue is reprojected at the tighter ceiling;
	 * a catalogue created later simply adopts it.
	 */
	@Override public void tightenDisplay(int displayBytes) {
		requireOpen();
		if (displayBytes < InventoryItemCodec.minimumDisplayBytes() || displayBytes > InventoryLimits.MAX_DISPLAY_BYTES)
			throw new IllegalArgumentException("display byte cap is out of range");
		if (displayBytes >= this.displayBytes) return;
		if (catalog != null) catalog.tightenDisplay(displayBytes);
		this.displayBytes = displayBytes;
	}

	@Override public InventoryDomainCodec.Item read(int index) {
		return readOwned(index, null); // Caller-trusted direct extension entrypoint.
	}
	@Override public InventoryDomainCodec.Item read(int index, InventoryCatalogAdmission admission) {
		return readOwned(index, Objects.requireNonNull(admission));
	}
	private InventoryDomainCodec.Item readOwned(int index, InventoryCatalogAdmission admission) {
		requireOpen();
		Objects.checkIndex(index, slots);
		SegmentState segment = segments[segmentBySlot[index]];
		int visible = visibleBySlot[index];
		IPlatformInventoryService.Entry entry;
		if (InventorySnapshotSchemas.ITEMS.equals(segment.topology.schemaId())) {
			if (segment.items == null) segment.items = InventorySnapshotSchemas.index(segment.payload, segment.topology.localSlots(), segment.topology.visibleSlots());
			entry = segment.items.read(visible, registries);
		} else {
			entry = segment.counter.read(isolated(segment.payload), segment.topology.visibleSlots().get(visible), segment.topology.localSlots(), registries);
		}
		if (entry == null) throw new InventorySnapshotSchemas.ReadFailureException("snapshot counter returned no observation");
		if (entry.isEmpty()) return null;
		if (catalog == null) catalog = new InventoryItemCodec.Catalog(registries, displayBytes);
		// Counter/decoder internals stay caller-trusted. Owned returned-stack
		// freezing and Catalog processing use the consuming Runtime's live grant.
		var encoded = admission == null ? catalog.encode(entry.exemplar())
			: catalog.encode(entry.exemplar(), InventoryItemBudget.root(displayBytes), admission);
		var display = encoded.display();
		return new InventoryDomainCodec.Item(encoded.key(), entry.amount(), display.label(), display.displayJson(), display.componentsStripped());
	}

	@Override public void close() {
		if (!closed) {
			closed = true;
			layout = null;
			originalTarget = null;
			registries = null;
			segments = null;
			segmentBySlot = null;
			visibleBySlot = null;
			catalog = null;
		}
	}

	private void requireOpen() { if (closed) throw new IllegalStateException("inventory snapshot is closed"); }
	private Tag isolated(Tag payload) { preflightNative(payload, displayBytes); return payload.copy(); }

	private static void preflightNative(Tag payload, int displayBytes) {
		var budget = InventoryItemBudget.root(displayBytes); budget.begin(displayBytes);
		try {
			var bounds = new InventoryComponentBounds(budget, InventoryResidentAccess.load());
			// This is only a native shape/capacity/cycle gate for the trusted
			// extension's copy. It is not A1's missing native copy certificate or
			// a new claim about arbitrary subsequent counter/decoder work.
			bounds.nbt(payload);
		} catch (InventoryItemBudget.RootLimitException failure) { throw new LimitException("native snapshot payload refused"); }
		finally { budget.end(); }
	}

	public static final class LimitException extends IllegalStateException {
		public LimitException(String reason) { super(reason); }
	}

	private static final class SegmentState {
		final InventorySnapshotLayout.Segment topology;
		final Tag payload;
		final InventorySnapshotSchemas.Counter counter;
		InventorySnapshotSchemas.ItemsIndex items;
		SegmentState(InventorySnapshotLayout.Segment topology, Tag payload, InventorySnapshotSchemas.Counter counter) {
			this.topology = topology; this.payload = payload; this.counter = counter;
		}
	}

	private static long envelopeBytes(InventorySnapshotLayout layout) {
		long bytes = add(1024L, add(textBytes(layout.layoutId()), textBytes(layout.layoutData())));
		for (var member : layout.members()) {
			bytes = add(bytes, add(384L, add(textBytes(member.blockId()), add(textBytes(member.blockEntityId()), textBytes(member.role())))));
			for (var segment : member.segments()) {
				bytes = add(bytes, add(256L, add(textBytes(segment.fieldPath()), add(textBytes(segment.schemaId()), multiply(segment.visibleSlots().size(), 32L)))));
			}
		}
		return bytes;
	}

	private static long textBytes(String value) { return add(64L, multiply(value.length(), 2L)); }
	private static long add(long first, long second) {
		try { return Math.addExact(first, second); }
		catch (ArithmeticException overflow) { throw new LimitException("snapshot memory accounting overflow"); }
	}
	private static long multiply(long first, long second) {
		try { return Math.multiplyExact(first, second); }
		catch (ArithmeticException overflow) { throw new LimitException("snapshot memory accounting overflow"); }
	}

	/** Conservative Java-tree cost, never Tag.sizeInBytes()'s overflowing int. */
	private static final class TreeGuard {
		final long limit;
		long bytes;
		int nodes;
		TreeGuard(long limit) { this.limit = limit; }

		void charge(long additional) {
			bytes = add(bytes, additional);
			if (bytes > limit) throw new LimitException("snapshot NBT tree exceeds memory limit");
		}

		void measure(Tag tag, int depth) {
			if (depth > MAX_TAG_DEPTH) throw new LimitException("snapshot NBT depth bound");
			if (++nodes > MAX_TAG_NODES) throw new LimitException("snapshot NBT node bound");
			// Only Minecraft's concrete tag implementations have known copy and
			// allocation behavior. An arbitrary Tag implementation is not evidence.
			Class<?> expected = switch (tag) {
				case EndTag value -> EndTag.class;
				case ByteTag value -> ByteTag.class;
				case ShortTag value -> ShortTag.class;
				case IntTag value -> IntTag.class;
				case LongTag value -> LongTag.class;
				case FloatTag value -> FloatTag.class;
				case DoubleTag value -> DoubleTag.class;
				case ByteArrayTag value -> ByteArrayTag.class;
				case StringTag value -> StringTag.class;
				case ListTag value -> ListTag.class;
				case CompoundTag value -> CompoundTag.class;
				case IntArrayTag value -> IntArrayTag.class;
				case LongArrayTag value -> LongArrayTag.class;
				default -> throw new LimitException("unsupported snapshot NBT implementation");
			};
			if (tag.getClass() != expected) throw new LimitException("unsupported snapshot NBT implementation");
			charge(256L);
			switch (tag) {
				case StringTag value -> charge(textBytes(value.getAsString()));
				case ByteArrayTag value -> charge(add(64L, value.size()));
				case IntArrayTag value -> charge(add(64L, multiply(value.size(), Integer.BYTES)));
				case LongArrayTag value -> charge(add(64L, multiply(value.size(), Long.BYTES)));
				case ListTag value -> {
					if (value.size() > MAX_TAG_NODES - nodes) throw new LimitException("snapshot NBT node bound");
					charge(add(128L, multiply(value.size(), 16L)));
					for (Tag child : value) measure(child, depth + 1);
				}
				case CompoundTag value -> {
					if (value.getAllKeys().size() > MAX_TAG_NODES - nodes) throw new LimitException("snapshot NBT node bound");
					charge(add(128L, multiply(value.getAllKeys().size(), 128L)));
					for (String key : value.getAllKeys()) {
						charge(textBytes(key));
						measure(Objects.requireNonNull(value.get(key), "NBT child"), depth + 1);
					}
				}
				default -> { /* Fixed-size scalar, already conservatively charged. */ }
			}
		}
	}
}
