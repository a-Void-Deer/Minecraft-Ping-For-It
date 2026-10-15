package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DynamicOps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import net.minecraft.core.NonNullList;
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

/**
 * Read-only resident-shape admission primitives, not permission to invoke a
 * component codec. Exact backing implementation, expanded occurrences, active
 * cycles, primitive-array bytes, strings and native bucket work are independent
 * limits. Each accepted native pass must be charged again before traversal.
 */
final class InventoryComponentBounds {
	private static final Class<?> LIST12 = List.of(new Object()).getClass();
	private static final Class<?> LISTN = List.of(new Object(), new Object(), new Object()).getClass();
	private static final Class<?> EMPTY_LIST = Collections.emptyList().getClass();
	private static final Class<?> SINGLETON_LIST = Collections.singletonList(new Object()).getClass();
	private static final Class<?> ARRAYS_LIST = Arrays.asList(new Object()).getClass();
	private static final Class<?> GUAVA_SINGLETON = ImmutableList.of(new Object()).getClass();
	private static final Class<?> GUAVA_REGULAR = ImmutableList.of(new Object(), new Object()).getClass();
	private static final Class<?> EMPTY_REFERENCE_MAP = Reference2ObjectMaps.emptyMap().getClass();
	private static final Class<?> MAP1 = Map.of("a", "b").getClass();
	private static final Class<?> MAPN = Map.of().getClass();
	private static final Class<?> EMPTY_MAP = Collections.emptyMap().getClass();
	private static final Class<?> SINGLETON_MAP = Collections.singletonMap("a", "b").getClass();
	private static final Class<?> GUAVA_MAP = com.google.common.collect.ImmutableMap.of().getClass();
	private static final Class<?> GUAVA_MAP1 = com.google.common.collect.ImmutableMap.of("a", "b").getClass();

	private final InventoryItemBudget budget;
	private final InventoryResidentAccess raw;
	private final Object[] active;
	private int activeDepth;
	private long nativeWork;
	private long residentMapWork;
	private long residentListCells;

	InventoryComponentBounds(InventoryItemBudget budget, InventoryResidentAccess raw) {
		this.budget = budget;
		this.raw = raw;
		budget.allocate(0, 0, InventoryItemBudget.MAX_DEPTH + 1L, 1);
		active = new Object[InventoryItemBudget.MAX_DEPTH + 1];
	}
	static void initialize() {
		// Fixed runtime/class identity work is process initialization, not a
		// first resident map's admission or permission to call unknown methods.
		InventoryNativeTraversal.runtimeSupported();
	}

	/** Reads at most bounded logical cells; no size/iterator method on an unknown implementation. */
	List<?> list(List<?> value) {
		budget.work(32); // closed exact-class checks, raw unwrap and size control
		if (value == null) refuse("null native list");
		Class<?> type = value.getClass();
		if (type == NonNullList.class) {
			budget.work(1);
			value = raw.nonNullList((NonNullList<?>) value);
			if (value == null || value.getClass() == NonNullList.class) refuse("unsupported NonNullList backing");
			type = value.getClass();
		}
		if (type != ArrayList.class && type != LIST12 && type != LISTN && type != EMPTY_LIST
			&& type != SINGLETON_LIST && type != ARRAYS_LIST && type != GUAVA_SINGLETON && type != GUAVA_REGULAR) {
			refuse("unsupported native list implementation");
		}
		budget.work(1);
		int size = value.size();
		if (size > InventoryItemBudget.MAX_COLLECTION) refuse("native list cardinality");
		residentListCells = InventoryItemBudget.add(residentListCells, size);
		return value;
	}

	/**
	 * Inspects the raw map, then pays ONE accepted entry pass before returning.
	 * Empty maps authorize owned empty output only: do not call native forEach.
	 */
	int mapPass(Map<?, ?> value) {
		budget.work(48); // closed exact-class dispatch before any native method
		if (value == null) refuse("null native map");
		Class<?> type = value.getClass();
		if (type == EMPTY_REFERENCE_MAP) { budget.work(1); return 0; }
		if (type == MAP1 || type == MAPN || type == EMPTY_MAP || type == SINGLETON_MAP || type == GUAVA_MAP || type == GUAVA_MAP1) {
			budget.work(1);
			int size = value.size(); cardinality(size);
			// Immutable MapN keeps at most four cells per live entry; native
			// entry iterator scans that fixed-density table, unlike HashMap.
			mapTraversal(5L * size);
			budget.allocate(32, 0, 2L * size + 5, size + 4L);
			return size;
		}
		if (type == Reference2ObjectArrayMap.class) {
			budget.work(1);
			int size = value.size();
			cardinality(size);
			mapTraversal(size);
			// ArrayMap entrySet + iterator + one reusable fast entry, or one
			// ordinary entry wrapper per element. Pay the latter conservative path.
			budget.allocate(16, 0, 2L * size + 4, size + 3L);
			return size;
		}
		if (type == Reference2ObjectOpenHashMap.class) {
			budget.work(2);
			int size = value.size();
			cardinality(size);
			if (size == 0) return 0;
			int capacity = raw.referenceMapCapacity(value);
			if (capacity < 1 || (capacity & (capacity - 1)) != 0) refuse("native reference-map capacity");
			mapTraversal(InventoryItemBudget.add(capacity, size));
			// Ordinary MapEntry has its own int index in addition to the
			// iterator state; even reused/cached entries are charged afresh.
			budget.allocate(32 + 4L * size, 0, 2L * size + 5, size + 4L);
			return size;
		}
		if (type != HashMap.class && type != IdentityHashMap.class && type != LinkedHashMap.class)
			refuse("unsupported native map implementation");
		// A0 vector: at most 33 objects, 65 reference fields, 524 scalar
		// bytes; fixed helper/port operations are charged BEFORE inspect.
		budget.allocate(524, 0, 65, InventoryNativeTraversal.MAX_INSPECTION_OBJECTS);
		// Fixed setup/result <=128 events; each split's caller control and
		// exact JDK splitter body together <=48 (at most31 attempts).
		budget.work(128 + 48L * InventoryNativeTraversal.MAX_SPLIT_CALLS);
		long remaining = Math.min(InventoryItemBudget.MAX_NATIVE_TRAVERSAL - nativeWork, budget.remainingTraversalWork() / 16);
		var inspected = InventoryNativeTraversal.inspect(value, remaining);
		if (!inspected.accepted()) refuse("native map traversal refusal");
		cardinality(inspected.entryWork());
		mapTraversal(inspected.traversalWork());
		if (inspected.entryWork() != 0) {
			// Native entry view/iterator plus per-entry wrappers on IdentityHashMap.
			// IdentityHashMap.EntryIterator.Entry carries int index + iterator
			// reference. Hash/linked maps reuse resident nodes (overcharged here).
			budget.allocate(16 + 4L * inspected.entryWork(), 0, 2L * inspected.entryWork() + 5, inspected.entryWork() + 3L);
		}
		return inspected.entryWork();
	}

	/** Whole NBT preflight, with NO copy, encoding, hashing or SNBT formatting. */
	void nbt(Tag value) { nbt(value, 0); }

	/**
	 * One resident traversal materializes the finite conversion graph. List wrapper
	 * unwrapping belongs to the source NbtOps.getStream boundary, before conversion
	 * to either target ops. The resulting graph contains only owned collections,
	 * immutable scalar tags and private primitive-array copies.
	 */
	NbtValue customData(Tag value) { return nbtValue(value, 0, true); }
	NbtValue snapshotNbt(Tag value) { return nbtValue(value, 0, false); }

	private NbtValue nbtValue(Tag value, int depth, boolean convert) {
		budget.work(64); // exact tag dispatch, getters and owned descriptor control
		if (value == null) refuse("null native tag");
		budget.node(depth);
		Class<?> type = value.getClass();
		if (type == ByteTag.class || type == ShortTag.class || type == IntTag.class || type == LongTag.class
			|| type == FloatTag.class || type == DoubleTag.class || type == StringTag.class) {
			if (type == StringTag.class) budget.string(value.getAsString());
			budget.allocate(0, 0, 3, 1);
			return new NbtValue(value, null, null);
		}
		if (type == ByteArrayTag.class || type == IntArrayTag.class || type == LongArrayTag.class) {
			long bytes = type == ByteArrayTag.class ? ((ByteArrayTag) value).getAsByteArray().length
				: type == IntArrayTag.class ? 4L * ((IntArrayTag) value).getAsIntArray().length
				: 8L * ((LongArrayTag) value).getAsLongArray().length;
			budget.array(bytes); budget.allocate(bytes, 0, 4, 3);
			Tag owned = type == ByteArrayTag.class ? new ByteArrayTag(((ByteArrayTag) value).getAsByteArray().clone())
				: type == IntArrayTag.class ? new IntArrayTag(((IntArrayTag) value).getAsIntArray().clone())
				: new LongArrayTag(((LongArrayTag) value).getAsLongArray().clone());
			return new NbtValue(owned, null, null);
		}
		if (type != CompoundTag.class && type != ListTag.class) refuse("unsupported native tag implementation");
		enter(value);
		try {
			if (type == CompoundTag.class) {
				Map<String, Tag> tags = raw.compound((CompoundTag) value);
				int size = mapPass(tags);
				budget.allocate(8, 0, 3L * size + 4, size + 3L);
				var fields = new ArrayList<NbtField>(size);
				if (size != 0) for (var entry : tags.entrySet()) {
					budget.string(entry.getKey());
					fields.add(new NbtField(entry.getKey(), nbtValue(entry.getValue(), depth + 1, convert)));
				}
				return new NbtValue(null, fields, null);
			}
			var tag = (ListTag) value;
			List<?> tags = list(raw.list(tag)); int size = tags.size();
			budget.work(size); budget.allocate(8, 0, size + 4L, 3);
			var children = new ArrayList<NbtValue>(size);
			for (int index = 0; index < size; index++) {
				Object child = tags.get(index);
				if (!(child instanceof Tag)) refuse("invalid native list tag value");
				NbtValue admitted = nbtValue((Tag) child, depth + 1, convert);
				// customData has now established an exact native implementation.
				if (((Tag) child).getId() != tag.getElementType()) refuse("invalid native list tag value");
				if (convert && tag.getElementType() == Tag.TAG_COMPOUND && admitted.fields.size() == 1
					&& admitted.fields.getFirst().key.isEmpty()) admitted = admitted.fields.getFirst().value;
				children.add(admitted);
			}
			return new NbtValue(null, null, children);
		} finally { leave(); }
	}

	record NbtField(String key, NbtValue value) {}

	/** No resident map or list is visited by either encode direction. */
	record NbtValue(Tag scalar, List<NbtField> fields, List<NbtValue> children) {
		boolean emptyCompound() { return fields != null && fields.isEmpty(); }
		<T> T encode(DynamicOps<T> ops) {
			if (fields != null) return fields.isEmpty() ? ops.emptyMap() : ops.createMap(fields.stream()
				.map(field -> Pair.of(ops.createString(field.key), field.value.encode(ops))));
			if (children != null) return ops.createList(children.stream().map(child -> child.encode(ops)));
			// Exact primitive kinds retain widths. Native conversion owns target array
			// storage; no JSON roundtrip and no raw-copy shortcut for NBT contexts.
			return net.minecraft.nbt.NbtOps.INSTANCE.convertTo(ops, scalar);
		}
	}

	private void nbt(Tag value, int depth) {
		budget.work(64);
		if (value == null) refuse("null native tag");
		budget.node(depth);
		Class<?> type = value.getClass();
		if (type == ByteTag.class || type == ShortTag.class || type == IntTag.class || type == LongTag.class
			|| type == FloatTag.class || type == DoubleTag.class) return;
		if (type == StringTag.class) { budget.string(value.getAsString()); return; }
		if (type == ByteArrayTag.class) { budget.array(((ByteArrayTag) value).getAsByteArray().length); return; }
		if (type == IntArrayTag.class) {
			budget.array(InventoryItemBudget.multiply(4, ((IntArrayTag) value).getAsIntArray().length)); return;
		}
		if (type == LongArrayTag.class) {
			budget.array(InventoryItemBudget.multiply(8, ((LongArrayTag) value).getAsLongArray().length)); return;
		}
		if (type != CompoundTag.class && type != ListTag.class) refuse("unsupported native tag implementation");
		enter(value);
		try {
			if (type == CompoundTag.class) {
				Map<String, Tag> tags = raw.compound((CompoundTag) value);
				int size = mapPass(tags);
				if (size == 0) return;
				for (var entry : tags.entrySet()) {
					budget.string(entry.getKey());
					nbt(entry.getValue(), depth + 1);
				}
			} else {
				List<?> tags = list(raw.list((ListTag) value));
				budget.work(tags.size());
				for (int index = 0; index < tags.size(); index++) {
					Object child = tags.get(index);
					if (!(child instanceof Tag)) refuse("invalid native list tag value");
					nbt((Tag) child, depth + 1);
				}
			}
		} finally { leave(); }
	}

	void enter(Object value) {
		// Eight local events per path cell; fixed16 includes matching leave.
		budget.work(8L * activeDepth + 16);
		if (activeDepth == active.length) refuse("native active depth");
		for (int index = 0; index < activeDepth; index++) if (active[index] == value) refuse("native value cycle");
		active[activeDepth++] = value;
	}

	void leave() { active[--activeDepth] = null; }
	void nativeTraversal(long units) { traversal(units); }
	long residentMapWork() { return residentMapWork; }
	long residentListCells() { return residentListCells; }
	private void mapTraversal(long units) {
		traversal(units); residentMapWork = InventoryItemBudget.add(residentMapWork, units);
	}

	private void traversal(long units) {
		if (units > InventoryItemBudget.MAX_NATIVE_TRAVERSAL - nativeWork) refuse("native traversal bound");
		// Bucket/cell test+increment <=8; entry hasNext/next/dispatch <=16.
		// Multiplicity remains visits, not bytes and not the work multiplier.
		budget.work(InventoryItemBudget.multiply(16, units));
		nativeWork += units;
	}

	private static void cardinality(int size) {
		if (size < 0 || size > InventoryItemBudget.MAX_COLLECTION) refuse("native map cardinality");
	}

	private static void refuse(String reason) { throw new InventoryItemBudget.RootLimitException(reason); }
}
