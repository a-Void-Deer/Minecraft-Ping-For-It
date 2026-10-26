package nx.pingwheel.common.presentation.inventory.minecraft;

import org.jetbrains.annotations.Nullable;

/**
 * Scalar E1–E5 event accumulator. One instance per freezer, no vector/row objects
 * per event. Children, DFU results and DynamicOps outputs are separate events.
 * All totals are cumulative allocations, including discarded growth/reset arrays.
 */
final class InventoryEncodingCost {
	private static final int MAX_KEYS = 258;
	private static final int MAX_BUCKETS = 1024;
	private final InventoryItemBudget budget;
	private long bytes, chars, refs, objects, work;
	private boolean json;
	private @Nullable String @Nullable [] mapKeys;
	private int @Nullable [] hash, next, heads, counts;
	private boolean @Nullable [] tree;
	private int keyCount;
	private int longestKey;

	InventoryEncodingCost(InventoryItemBudget budget) {
		this.budget = budget;
	}
	void reset(boolean json) { bytes = chars = refs = objects = work = 0; this.json = json; }
	boolean json() { return json; }
	boolean output(boolean value) { boolean previous = json; json = value; return previous; }
	void v(long b, long u, long r, long o) {
		budget.work(16); // checked scalar event accumulation, before arithmetic
		bytes = add(bytes, b); chars = add(chars, u); refs = add(refs, r); objects = add(objects, o);
	}
	void w(long units) { budget.work(4); work = add(work, units); }
	void planningList(int size) { budget.work(8L * size + 8); }
	void planningIterator(int size) {
		budget.allocate(32, 0, 7, 3); budget.work(16L * size + 16);
	}
	void admit() { budget.allocate(bytes, chars, refs, objects); budget.work(work); }
	long storage() { return InventoryItemBudget.allocationCost(bytes, chars, refs, objects); }
	long operations() { return add(work, add(add(bytes, chars), add(refs, objects))); }
	static long add(long a, long b) { return InventoryItemBudget.add(a, b); }
	static long mul(long a, long b) { return InventoryItemBudget.multiply(a, b); }

	// E2: encoder nodes plus the corrected getter-driven curry execution.
	// Inline records call graph twice, map only once. Repeated getter allocations
	// are family-specific (Style), never a second child encode/default test.
	void graph(int f) {
		if (f < 1 || f > 16) throw new InventoryItemBudget.RootLimitException("record arity");
		int a = f <= 4 ? 1 : f <= 8 ? 2 : f == 9 ? 3 : 4;
		int m = a - 1;
		v(4L * (a + m + 1), 0, 2L * f + 10L * a + 12L * m + 7, 3L * a + 2L * m + 2);
		w(8 + 12L * f + 18L * a + 14L * m + 2L * f); // graph + cached getters
		if (f == 9) { v(0, 0, 6, 2); w(8L * 4 + 2L * 32); }
		else if (f >= 10) {
			int first = f / 2;
			// f10/11:1+1+1+3+6=12R; f12/13:1+1+1+4+7=14R;
			// f14/15:1+1+1+4+8=15R; f16:1+1+1+5+9=17R.
			int captures = first + first / 2 + 5;
			v(0, 0, captures, 5); w(8L * first + 5L * 32);
		}
	}
	void repeatedOptionals(int present) { v(0, 0, present, present); w(4L * present); }
	void box4() { v(4, 0, 0, 1); w(6); }
	void box8() { v(8, 0, 0, 1); w(6); }
	void optional(boolean present) { if (present) v(0, 0, 1, 1); w(4); }
	void defaults(int count, int present) { v(0, 0, present, present); w(12L * count); }
	void optionalFields(int count) { w(4L * count); }
	void emptyListEquality() { v(32, 0, 4, 2); w(28); }
	void either() { v(0, 0, 7, 3); w(12); }
	void dispatch() { v(0, 0, 6, 2); w(10); }
	void checked() { v(0, 0, 7, 3); w(28); }
	/** One bounded STRING validator, including its empty-partial failure. */
	void flatResult() {
		// RegistryOps.withParent2R; FLAT continuation2R; checker Error3R and
		// supplier(three ints12B, String1R); Encoder.flatComap continuation3R;
		// primitive Success2R; output string1R; lifecycle replacement<=3R.
		// Failure skips primitive/output, but all sites are conservatively paid.
		v(12, 0, 17, 8); w(192);
	}
	/** MapEncoder.flatComap, NOT Encoder.flatComap; no second map builder. */
	void mapValidation() {
		// checker Success OR Error+supplier<=4R/2O; withErrorsFrom<=7R/4O;
		// map continuation3R/1O, map Success2R/1O, result Optional1R/1O.
		v(0, 0, 17, 9); w(256);
	}
	void keyDispatch() {
		// getCodec11R/8O (incl. two uncached noncapturing function headers),
		// withErrorsFrom7R/4O, result Optional1R/1O, second type/flatMap6R/4O.
		v(0, 0, 25, 17); w(384);
	}
	void javaPrimitive(Object value) {
		// Call only after the exact argument guard. No Number virtual callback,
		// JavaOps.getNumberValue, map/list conversion or prefix merge is selected.
		Class<?> type = value == null ? null : value.getClass();
		if (type != String.class && type != Boolean.class && type != Byte.class && type != Short.class
			&& type != Integer.class && type != Long.class && type != Float.class && type != Double.class)
			throw new InventoryItemBudget.RootLimitException("uncertified primitive text argument");
		if (value instanceof Float f && !Float.isFinite(f) || value instanceof Double d && !Double.isFinite(d))
			throw new InventoryItemBudget.RootLimitException("nonfinite primitive text argument");
		checked(); // filterAllowedArguments Success + Encoder.flatComap
		// Full Dynamic.convert/static convert + JavaOps.convertTo source337
		// tokens (including untaken type cases), selected PASSTHROUGH/constructors
		// and exact unbox/getters<=175; no converter helper remains unspecified.
		v(0, 0, 4, 2); w(512); // converter input Dynamic and converted Dynamic
		primitive(); // PASSTHROUGH's empty-prefix Success, not PrimitiveCodec
		if (type == String.class) string((String) value);
		else if (type == Boolean.class) bool();
		else number(type == Long.class || type == Double.class ? 8 : 4);
	}
	void range() { checked(); v(8, 0, 0, 2); w(12); }
	void lifecycle() { v(0, 0, 2, 1); w(6); }
	void result() { v(0, 0, 1, 1); w(4); }
	void xmap() { w(4); }
	void primitive() { v(0, 0, 2, 1); w(8); }
	void string(String value) { outputString(); w(value.length()); }
	void outputString() { v(0, 0, 1, 1); w(8); }
	void stringLeaf(String value) { primitive(); string(value); }
	void number(int width) {
		// JsonOps createNumeric uses a closed Java wrapper and JsonPrimitive;
		// NbtOps creates/caches the matching NumericTag (charging even cache hits).
		v(width, 0, json ? 1 : 0, json ? 2 : 1); w(12);
	}
	void numericLeaf(int width) { primitive(); number(width); }
	void bool() { if (json) v(0, 0, 1, 1); w(8); }
	void booleanLeaf() { primitive(); bool(); }
	void enumLeaf(String name) { v(0, 0, 10, 6); w(44); stringLeaf(name); }
	void resource(net.minecraft.resources.ResourceLocation id) {
		int n = id.getNamespace().length() + id.getPath().length() + 1;
		v(16 + 3L * n, 0, 1, 3); w(12 + 3L * n); primitive(); outputString(); lifecycle();
	}
	void fileHolder(net.minecraft.resources.ResourceLocation id) { v(0, 0, 7, 4); w(48); resource(id); }
	void builtinHolder(net.minecraft.resources.ResourceLocation id, long lookupWork) {
		checked(); v(0, 0, 4, 3); w(add(48, lookupWork)); resource(id);
	}
	void key() { outputString(); }
	void keys(int count) { for (int index = 0; index < count; index++) key(); }
	void record(int declared, int emitted) { graph(declared); keys(emitted); map(emitted); }

	// E3 universal fixed DISTINCT-key maps. All covered native sets have <=9
	// keys or the E3 text-union collision proof; key lengths <=21 (neoforge:cures
	// is14). No Guava duplicate/hash-flood fallback or arbitrary map is admitted.
	void map(int n) {
		if (n < 0 || n > 15) throw new InventoryItemBudget.RootLimitException("fixed map arity");
		v(10, 0, 20 + 16L * n, 8 + 11L * n); w(1024 + 768L * n);
		// apply2stable success OR empty-partial failure:24R/12O, replacing
		// E3's12R/9O. Includes failed-fast-test Optionals, curry functions,
		// ap partial-map closures and unevaluated combined-message suppliers.
		// Child checker results, append closure/entry and build/reset are separate.
		v(0, 0, 12L * n, 3L * n);
		guava(n, true);
		if (n == 1) v(0, 0, 8, 1);
		if (n >= 2) {
			int t = guavaTable(n);
			// C<=n-1 nonterminal collision entries; exact bucket proof precludes
			// overflow, but does not assume a perfect hash for optional subsets.
			v(4, 0, 6L + t + 3L * (n - 1), 2L + n - 1);
			w(256 + 256L * n + 64 * pairs(n) + keyWork(n, 21));
		}
		if (json) {
			if (n == 0) v(73, 0, 42, 14);
			else if (n == 1) v(75, 0, 53, 18);
			else v(73 + 5L * n, 0, 43 + 9L * n, 15 + 2L * n);
			w(512 + 128L * n + 256L * n + 512L * n * (n + 1) / 2 + keyWork(n, 21));
		} else {
			v(24, 0, 8, 4);
			if (n == 0) v(0, 0, 3, 1);
			else if (n == 1) v(1, 0, 4, 3);
			else v(12, 0, 5, 3);
			long tables = n == 0 ? 0 : n <= 12 ? 16 : 48;
			long r = n == 0 ? 0 : n <= 12 ? 1 : 2;
			v(4L * n, 0, tables + 3L * n, r + n);
			w(512 + 256L * n + 128 * pairs(n) + 256 * r + keyWork(n, 21)
				+ (n > 12 ? 128L * (16 + 13) : 0));
		}
	}
	private static long keyWork(int n, long length) {
		return add(mul(8 * length + 64, n), mul(8 * (length + 1) + 64, pairs(n)));
	}
	static long pairs(long n) { return n * (n - 1) / 2; }
	private static int guavaTable(int n) {
		int e = Math.max(n, 2), t = Integer.highestOneBit(e);
		return e * 5L > 6L * t ? t * 2 : t;
	}
	private void guava(int n, boolean map) {
		int c = 4;
		for (int i = 0; i < n; i++) if (i == c) { int old = c; c += (c >> 1) + 1; v(0, 0, c, 1); w(128L + old); }
		if (n >= 2 && n < c) { v(0, 0, n, 1); w(n); }
		if (!map && n != 0) v(0, 0, 1, 1);
	}
	// E4 ListCodec: source iterator, universal builder and native output pass.
	// kind0 compound/non-numeric homogeneous, kind1 int, kind2 byte, kind3 long.
	void list(int n, int kind) {
		v(16, 0, 2, 2); w(12L + 8L * n); // admitted source list/view iterator
		v(10, 0, 18 + 12L * n, 8 + 10L * n); w(1024 + 768L * n);
		v(0, 0, 12L * n, 3L * n); // bounded empty-partial error propagation
		guava(n, false);
		if (json) { v(8, 0, 5, 4); arrayGrowth(n, 0); w(256 + 128L * n); return; }
		v(0, 0, 6, 5);
		if (n == 1) v(1, 0, 1, 1); else if (n >= 2) v(12, 0, 1, 1);
		collector(n, kind, false);
		w(256 + 64L * n);
	}
	private void arrayGrowth(int n, int width) {
		int c = 0;
		for (int i = 0; i < n; i++) if (i == c) {
			int old = c; c = c == 0 ? 10 : c + (c >> 1);
			if (width == 0) v(0, 0, c, 1); else v((long) width * c, 0, 0, 1);
			w(128L + (long) Math.max(1, width) * old);
		}
	}
	void collector(int n, int kind, boolean heterogeneous) {
		if (n == 0) { v(13, 0, 2, 2); return; }
		if (kind != 0 && !heterogeneous) {
			int q = kind == 1 ? 4 : kind == 2 ? 1 : 8;
			v(16 + (long) q * n, 0, 4, 5); arrayGrowth(n, q); w(128 + 64L * n + (long) q * n);
		} else {
			v(13, 0, 3, 3); arrayGrowth(n, 0);
			if (heterogeneous) {
				// At most one discarded numeric/homogeneous collector and replay.
				// All n inputs may need wrappers; no EndTag elements are admitted.
				v(25 + 20L * n + 8L * n, 0, 4 + 24L * n, 4 + 5L * n);
				arrayGrowth(n, 8); w(128 + 384L * n + 1024L * n);
			}
		}
		w(256L * n);
	}
	// Owned map/list envelopes bypass Guava. Producer stages, Pair wrappers,
	// ArrayList growth, mapped-stream sinks/iterator state and native output are
	// explicit; native arbitrary-key HashMap collision/tree replacements below.
	void ownedMap(int n, long keyUnits, int maxKey) {
		ownedMapShell(n);
		if (json) {
			v(14 + 5L * n, 0, 16 + 7L * n, 4 + n);
			w(256L * n + 512L * n * (n + 1) / 2 + keyWork(n, maxKey));
		} else if (n <= 8) {
			v(16 + 4L * n, 0, 6 + (n == 0 ? 0 : 16) + 3L * n, 3 + (n == 0 ? 0 : 1) + n);
			w(512 + 256L * n + (n == 0 ? 0 : 256) + 128 * pairs(n) + keyWork(n, maxKey));
		} else {
			// Larger arbitrary maps MUST provide their actual owned key schedule.
			throw new InventoryItemBudget.RootLimitException("missing owned key schedule");
		}
		w(8 * keyUnits);
	}
	private void ownedMapShell(int n) {
		v(64, 0, 25 + 2L * n, 9 + n); arrayGrowth(n, 0); w(192 + 128L * n);
	}
	void beginKeys() { keyCount = 0; longestKey = 0; }
	int keyCount() { return keyCount; }
	void mapKey(String key) {
		if (keyCount == MAX_KEYS) throw new InventoryItemBudget.RootLimitException("owned map cardinality");
		if (mapKeys == null) {
			// Planner scratch is a direct debit, NOT a native output event.
			budget.allocate(0, 0, MAX_KEYS, 1);
			mapKeys = new String[MAX_KEYS];
		}
		budget.work(key.length() + 4L); mapKeys[keyCount++] = key; longestKey = Math.max(longestKey, key.length());
	}
	void ownedKeyMap() {
		int n = keyCount; long units = 0; int max = 0;
		budget.work(12L * n + 8);
		for (int i = 0; i < n; i++) { String key = mapKeys[i]; units = add(units, key.length()); max = Math.max(max, key.length()); }
		if (json) {
			ownedMapShell(n); v(14 + 5L * n, 0, 16 + 7L * n, 4 + n);
			w(256L * n + 512L * n * (n + 1) / 2 + keyWork(n, max) + 8 * units);
		} else {
			ownedMapShell(n); v(16, 0, 6, 3); w(512);
			if (n != 0 && hash == null) {
				// JSON needs keys only; an empty map needs neither group. Once
				// used, retain the original fixed simulator arrays for this root.
				budget.allocate(4L * (2 * MAX_KEYS + 2 * MAX_BUCKETS) + MAX_BUCKETS, 0, 0, 5);
				hash = new int[MAX_KEYS]; next = new int[MAX_KEYS];
				heads = new int[MAX_BUCKETS]; counts = new int[MAX_BUCKETS]; tree = new boolean[MAX_BUCKETS];
			}
			int capacity = 0;
			for (int i = 0; i < n; i++) {
				budget.work(mapKeys[i].length() + 8L);
				int h = mapKeys[i].hashCode(); hash[i] = h ^ (h >>> 16);
				if (capacity == 0) capacity = resizeMap(0, 16, i);
				int bucket = hash[i] & (capacity - 1), old = counts[bucket];
				w(256 + 8L * mapKeys[i].length() + 128L * old + 8L * old * (max + 1));
				if (tree[bucket]) {
					v(5, 0, 9, 1); w(mul(1024L + max + 1L, mul(old + 1L, old + 1L)));
				} else v(4, 0, 3, 1);
				next[i] = heads[bucket]; heads[bucket] = i; counts[bucket]++;
				if (!tree[bucket] && old >= 8) {
					if (capacity < 64) capacity = resizeMap(capacity, capacity * 2, i + 1);
					else {
						int size = old + 1; v(5L * size, 0, 9L * size, size); tree[bucket] = true;
						w(mul(1024L + max + 1L, mul(size + 1L, size + 1L)));
					}
				}
				if (i + 1 > 3 * capacity / 4) capacity = resizeMap(capacity, capacity * 2, i + 1);
			}
		}
		budget.work(4L * n + 4);
		for (int i = 0; i < n; i++) mapKeys[i] = null;
	}
	private int resizeMap(int old, int capacity, int size) {
		if (capacity > MAX_BUCKETS) throw new InventoryItemBudget.RootLimitException("owned map capacity");
		budget.work(8L * capacity + 12L * size);
		// Preserve old tree membership on each node while rebuilding bucket lists.
		for (int i = 0; i < size; i++) if (old != 0 && tree[hash[i] & (old - 1)]) next[i] = -2; else next[i] = -1;
		java.util.Arrays.fill(heads, 0, capacity, -1); java.util.Arrays.fill(counts, 0, capacity, 0); java.util.Arrays.fill(tree, 0, capacity, false);
		for (int i = 0; i < size; i++) { int b = hash[i] & (capacity - 1); if (next[i] == -2) tree[b] = true; next[i] = heads[b]; heads[b] = i; counts[b]++; }
		for (int b = 0; b < capacity; b++) if (tree[b]) {
			if (counts[b] <= 6) { int count = counts[b]; v(4L * count, 0, 3L * count, count); tree[b] = false; }
			else w((1024L + longestKey + 1) * (counts[b] + 1) * (counts[b] + 1));
		}
		v(0, 0, capacity, 1); w(256 + 128L * (old + size)); return capacity;
	}
	void ownedList(int n, int kind, boolean heterogeneous) {
		v(80, 0, 64, 18); w(192 + 128L * n); // source spliterator/head/map + mapped iterator setup
		if (json) { v(8, 0, 3, 3); arrayGrowth(n, 0); w(256 + 128L * n); }
		else collector(n, kind, heterogeneous);
	}
	void intStream(int n) {
		v(4L * n + 35, 0, 7, 3); w(24 + 4L * n); primitive();
		primitiveArrayOutput(n, 4);
	}
	/** NbtOps.convertTo array inputs already own their source primitive array. */
	void primitiveArray(int n, int width) {
		if (width == 1) {
			// HeapByteBuffer incl. Buffer/ByteBuffer fields; JsonOps additionally
			// creates IntStream.range before the primitive mapToObj terminal.
			v(48, 0, 4, 1); w(128);
			if (json) { v(43, 0, 7, 2); w(64); }
		} else { v(35, 0, 7, 2); w(24); }
		primitiveArrayOutput(n, width);
	}
	private void primitiveArrayOutput(int n, int width) {
		if (json) {
			// Primitive spliterator/head->mapToObj->JsonArray collector. Fresh
			// closed boxes/JsonPrimitives and append capacity, no ListBuilder.
			// mapToObj StatelessOp15B/8R, method reference1R, consumer1R,
			// Head + primitive spliterator already above; JSON terminal sink and
			// source adapter are covered by the extra29B/8R/4O here.
			// The byte mapper also captures its input ByteBuffer. Long boxes are
			// eight bytes; Byte boxes are cached but conservatively charged4.
			v(64 + (long) Math.max(4, width) * n, 0, 32L + n + (width == 1 ? 1 : 0), 11 + 2L * n);
			arrayGrowth(n, 0); w(384 + 256L * n);
		} else if (width == 1) {
			// Native createByteList duplicates the buffer, copies remaining bytes,
			// then creates the ByteArrayTag. No primitive stream/node executes.
			v(48L + n, 0, 5, 3); w(256 + 64L * n);
		} else {
			// IntPipeline.toArray has a sized primitive node builder as well as
			// the final int[n]/IntArrayTag. No boxed Integer stream is involved.
			// FixedNodeBuilder4B/1R + fresh int[n] + IntArrayTag1R.
			// Include the zero-field array-generator lambda conservatively.
			v((long) width * n + 4, 0, 2, 4); w(1024 + 128L * n);
		}
	}
	void decimal() { v(92, 0, 2, 4); w(13523); }
}
