package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;

/**
 * Pre-traversal certificate for exact OpenJDK native maps, not a portable
 * Spliterator complexity guarantee. Audited against OpenJDK HotSpot 21 and 25
 * java.base sources: HashMap's fresh EntrySpliterator halves its bucket range
 * without reading buckets; IdentityHashMap's KeySpliterator halves paired-cell
 * ranges. Following only the returned, untouched lower halves proves capacity.
 * LinkedHashMap instead has a separate head/after linked-entry iterator.
 *
 * <p>The caller admits {@link #MAX_INSPECTION_OBJECTS} fixed-size objects and
 * {@link #MAX_INSPECTION_PORT_CALLS} native calls BEFORE inspection, then charges
 * the accepted traversal work BEFORE EACH subsequent read-only native traversal.
 * No iterator, entry, array, copy, or supplied key/value method is used here.
 * Iterator/entry-wrapper allocation, callback/codec work and owned hashing,
 * sorting or encoding are separate obligations. The map must remain stable
 * through inspection and consumption; this result is not a mutation fence.
 * An accepted empty map MUST emit owned empty data without native traversal:
 * some native forEach/copy paths scan retained tables even when size is zero.
 * A traversal means one normal iterator loop or one native forEach pass,
 * including such passes inside a separately audited codec/copy. This does not
 * certify the codec/copy itself, arbitrary repeated hasNext calls, lookup,
 * removal, or stream splitting.
 *
 * <p>Matching audited java.base implementations and no rewriting of their
 * methods are assumptions. The runtime gate scopes the certificate, not the
 * whole product: an unaudited runtime needs item-boundary fallback or re-audit.
 */
public final class InventoryNativeTraversal {

	public static final int MAX_SUCCESSFUL_SPLITS = 30;
	public static final int MAX_SPLIT_CALLS = 31;
	/** One view, one root probe, thirty children, and one immutable result. */
	public static final int MAX_INSPECTION_OBJECTS = 33;
	/** size + view + spliterator + at most thirty-one trySplit calls. */
	public static final int MAX_INSPECTION_PORT_CALLS = 34;
	private static final long MAX_NATIVE_TABLE_LENGTH = 1L << 30;
	private static final boolean AUDITED_RUNTIME = auditedRuntime();

	private InventoryNativeTraversal() {}

	public enum Implementation { HASH_MAP, IDENTITY_HASH_MAP, LINKED_HASH_MAP, UNSUPPORTED }
	public enum Refusal { NONE, UNSUPPORTED_IMPLEMENTATION, UNAUDITED_RUNTIME, WORK_LIMIT, PROBE_LIMIT }

	/**
	 * For acceptance bucketWork is exact table length (IdentityHashMap counts
	 * key/value cells), or zero for linked/empty maps. On refusal it is only a
	 * proven lower bound. probeObjects counts attempted fixed-size allocations
	 * conservatively, including a possibly cached view and this result; no arrays.
	 * probePortCalls counts actual size/view/spliterator/trySplit invocations.
	 * Empty maps intentionally report zero work, not their retained capacity;
	 * that outcome requires skipping native traversal entirely.
	 */
	public record Inspection(Implementation implementation, Refusal refusal,
		long bucketWork, int entryWork, int successfulSplits, int splitCalls,
		int probeObjects, int probePortCalls) {
		public boolean accepted() { return refusal == Refusal.NONE; }
		public long traversalWork() { return bucketWork + entryWork; }
	}

	public static boolean runtimeSupported() { return AUDITED_RUNTIME; }

	/**
	 * Inspect without consuming contents. Nonnegative remainingTraversalWork pays
	 * bucket/cell inspections plus entry visits for ONE future native traversal;
	 * inspection workspace/work is admitted separately as described above.
	 * Unsupported exact classes are refused before any overridable map method.
	 */
	public static Inspection inspect(Map<?, ?> map, long remainingTraversalWork) {
		Objects.requireNonNull(map, "map");
		if (remainingTraversalWork < 0L) throw new IllegalArgumentException("negative traversal work");
		Implementation implementation = classify(map.getClass());
		if (implementation == Implementation.UNSUPPORTED) {
			return new Inspection(implementation, Refusal.UNSUPPORTED_IMPLEMENTATION, 0L, 0, 0, 0, 1, 0);
		}
		if (!AUDITED_RUNTIME) {
			return new Inspection(implementation, Refusal.UNAUDITED_RUNTIME, 0L, 0, 0, 0, 1, 0);
		}

		int entries = map.size();
		if (entries == 0) {
			return new Inspection(implementation, Refusal.NONE, 0L, 0, 0, 0, 1, 1);
		}
		// Exact LinkedHashMap's iterator follows live entries, never its bucket table.
		if (implementation == Implementation.LINKED_HASH_MAP) {
			return new Inspection(implementation, entries <= remainingTraversalWork ? Refusal.NONE : Refusal.WORK_LIMIT,
				0L, entries, 0, 0, 1, 1);
		}

		long bucketLowerBound = implementation == Implementation.IDENTITY_HASH_MAP ? 2L : 1L;
		if (entries > remainingTraversalWork || bucketLowerBound > remainingTraversalWork - entries) {
			return new Inspection(implementation, Refusal.WORK_LIMIT, bucketLowerBound, entries, 0, 0, 1, 1);
		}
		// Both native view factories and spliterator constructors have constant cost.
		// The identity key port avoids even the native entry-wrapper allocation path.
		Spliterator<?> probe = implementation == Implementation.IDENTITY_HASH_MAP
			? map.keySet().spliterator() : map.entrySet().spliterator();
		int successfulSplits = 0;
		int splitCalls = 0;
		int probeObjects = 3; // possible view + root probe + eventual result
		while (true) {
			if (bucketLowerBound > remainingTraversalWork - entries) {
				return new Inspection(implementation, Refusal.WORK_LIMIT, bucketLowerBound, entries,
					successfulSplits, splitCalls, probeObjects, 3 + splitCalls);
			}
			// At the audited native upper limit, lower == upper already proves equality.
			// Avoid an additional probe at the maximum; smaller tables end on null.
			if (bucketLowerBound == MAX_NATIVE_TABLE_LENGTH) {
				return new Inspection(implementation, Refusal.NONE, bucketLowerBound, entries,
					successfulSplits, splitCalls, probeObjects, 3 + splitCalls);
			}
			if (successfulSplits >= MAX_SUCCESSFUL_SPLITS || splitCalls >= MAX_SPLIT_CALLS) {
				return new Inspection(implementation, Refusal.PROBE_LIMIT, bucketLowerBound, entries,
					successfulSplits, splitCalls, probeObjects, 3 + splitCalls);
			}
			Spliterator<?> lower = probe.trySplit();
			splitCalls++;
			if (lower == null) {
				return new Inspection(implementation, Refusal.NONE, bucketLowerBound, entries,
					successfulSplits, splitCalls, probeObjects, 3 + splitCalls);
			}
			probe = lower; // Never advance either half; only split the fresh lower half.
			successfulSplits++;
			probeObjects++;
			bucketLowerBound <<= 1;
		}
	}

	private static Implementation classify(Class<?> type) {
		if (type == HashMap.class) return Implementation.HASH_MAP;
		if (type == IdentityHashMap.class) return Implementation.IDENTITY_HASH_MAP;
		if (type == LinkedHashMap.class) return Implementation.LINKED_HASH_MAP;
		return Implementation.UNSUPPORTED;
	}

	private static boolean auditedRuntime() {
		int feature = Runtime.version().feature();
		return (feature == 21 || feature == 25)
			&& "OpenJDK 64-Bit Server VM".equals(System.getProperty("java.vm.name"));
	}
}
