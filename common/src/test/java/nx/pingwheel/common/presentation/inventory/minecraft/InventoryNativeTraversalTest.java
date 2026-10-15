package nx.pingwheel.common.presentation.inventory.minecraft;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Capacities below are derived independently from audited OpenJDK 21/25
 * constructors/resize transitions, NOT estimateSize or this helper's output.
 * HashMap retains power-of-two bucket tables after removal; IdentityHashMap
 * allocates twice its power-of-two capacity, with paired key/value cells.
 * These tests establish the scoped native algorithm, not all JVMs' split cost.
 */
class InventoryNativeTraversalTest {

	@Test void runtimeGateIsScopedToAuditedHotSpotFamilies() {
		int feature = Runtime.version().feature();
		assertEquals((feature == 21 || feature == 25)
			&& "OpenJDK 64-Bit Server VM".equals(System.getProperty("java.vm.name")),
			InventoryNativeTraversal.runtimeSupported());
		if (!InventoryNativeTraversal.runtimeSupported()) {
			assertEquals(InventoryNativeTraversal.Refusal.UNAUDITED_RUNTIME,
				InventoryNativeTraversal.inspect(new HashMap<>(), 0L).refusal());
		}
	}

	@Test void compactHashMapHasExactConstructorCapacityAndThreshold() {
		assumeAuditedRuntime();
		// tableSizeFor(3)=4; one insertion materializes four buckets (threshold=3).
		Map<Integer, Object> map = new HashMap<>(3);
		map.put(0, new Object());
		assertAccepted(map, 4L, 1, 2);
		assertEquals(InventoryNativeTraversal.Implementation.HASH_MAP,
			InventoryNativeTraversal.inspect(map, 5L).implementation());
		var refused = InventoryNativeTraversal.inspect(map, 4L);
		assertEquals(InventoryNativeTraversal.Refusal.WORK_LIMIT, refused.refusal());
		assertEquals(4L, refused.bucketWork());
		assertEquals(2, refused.successfulSplits());
		assertEquals(2, refused.splitCalls()); // refuse before the terminal null call
		assertBounded(refused);
	}

	@Test void defaultHashMapGrowthAndNonshrinkingRemovalAreNotLogicalSizeBounds() {
		assumeAuditedRuntime();
		// Default 16 buckets / threshold 12: insertion 13 doubles to 32.
		Map<Integer, Object> map = new HashMap<>();
		for (int key = 0; key < 13; key++) map.put(key, new Object());
		assertAccepted(map, 32L, 13, 5);
		for (int key = 0; key < 12; key++) map.remove(key);
		assertAccepted(map, 32L, 1, 5);
		var refused = InventoryNativeTraversal.inspect(map, 32L);
		assertFalse(refused.accepted());
		assertEquals(32L, refused.bucketWork());
		assertEquals(1, refused.entryWork());
		assertBounded(refused);
	}

	@Test void sparseGrownHashMapRefusesAtFirstOverBudgetLowerBound() {
		assumeAuditedRuntime();
		// Sequential integers do not collide here. 0.75*8192=6144, so inserting
		// 4097 entries needs 8192 buckets; removeNode never shrinks the table.
		Map<Integer, Object> map = new HashMap<>();
		for (int key = 0; key < 4097; key++) map.put(key, new Object());
		for (int key = 0; key < 4096; key++) map.remove(key);
		assertAccepted(map, 8192L, 1, 13);
		var refused = InventoryNativeTraversal.inspect(map, 65L);
		assertEquals(InventoryNativeTraversal.Refusal.WORK_LIMIT, refused.refusal());
		assertEquals(128L, refused.bucketWork()); // 64 fits; first proof of 128 refuses
		assertEquals(7, refused.successfulSplits());
		assertEquals(7, refused.splitCalls());
		assertEquals(10, refused.probeObjects());
		assertBounded(refused);
	}

	@Test void emptyNativeMapsAvoidAnyViewProbeOrRetainedBucketTraversal() {
		assumeAuditedRuntime();
		Map<Integer, Object> grown = new HashMap<>(1024);
		grown.put(0, new Object());
		grown.remove(0); // allocated table remains, yet owned empty output needs no scan
		for (Map<?, ?> map : new Map<?, ?>[] { new HashMap<>(), grown, new IdentityHashMap<>(100), new LinkedHashMap<>() }) {
			var inspection = InventoryNativeTraversal.inspect(map, 0L);
			assertTrue(inspection.accepted());
			assertEquals(0L, inspection.traversalWork());
			assertEquals(0, inspection.splitCalls());
			assertEquals(1, inspection.probeObjects()); // only the outcome
			assertEquals(1, inspection.probePortCalls()); // only size
		}
	}

	@Test void identityHashMapMeasuresPairedCellsNotJustKeySlots() {
		assumeAuditedRuntime();
		// expectedMaxSize<=2 gives MINIMUM_CAPACITY=4, hence eight cells.
		Map<Object, Object> small = new IdentityHashMap<>(1);
		small.put(new Object(), new Object());
		assertAccepted(small, 8L, 1, 2);
		assertEquals(InventoryNativeTraversal.Implementation.IDENTITY_HASH_MAP,
			InventoryNativeTraversal.inspect(small, 9L).implementation());
		assertEquals(InventoryNativeTraversal.Refusal.WORK_LIMIT,
			InventoryNativeTraversal.inspect(small, 8L).refusal());
		// Default capacity=32, hence 64 cells. The null-key marker is native.
		Map<Object, Object> standard = new IdentityHashMap<>();
		standard.put(null, new Object());
		assertAccepted(standard, 64L, 1, 5);
	}

	@Test void identityGrowthAndRemovalRetainIndependentlyDerivedPairedWidth() {
		assumeAuditedRuntime();
		Map<Object, Object> map = new IdentityHashMap<>(2);
		Object first = new Object(), second = new Object(), third = new Object();
		map.put(first, new Object());
		map.put(second, new Object());
		// Third insertion: 3*3 > eight cells, so resize doubles cell length to 16.
		map.put(third, new Object());
		map.remove(first);
		map.remove(second);
		assertAccepted(map, 16L, 1, 3);
		var refused = InventoryNativeTraversal.inspect(map, 16L);
		assertEquals(InventoryNativeTraversal.Refusal.WORK_LIMIT, refused.refusal());
		assertEquals(16L, refused.bucketWork());
		assertEquals(3, refused.splitCalls());
		assertBounded(refused);
	}

	@Test void insufficientEntryAdmissionAvoidsEvenProbeAllocation() {
		assumeAuditedRuntime();
		Map<Integer, Object> hash = new HashMap<>();
		hash.put(0, new Object());
		var refused = InventoryNativeTraversal.inspect(hash, 0L);
		assertEquals(InventoryNativeTraversal.Refusal.WORK_LIMIT, refused.refusal());
		assertEquals(0, refused.splitCalls());
		assertEquals(1, refused.probeObjects());
		assertEquals(1, refused.probePortCalls());
	}

	@Test void linkedHashMapUsesItsSeparateLinkedEntryCertificate() {
		assumeAuditedRuntime();
		// Deliberately oversized bucket table is irrelevant to head/after iteration.
		Map<Integer, Object> map = new LinkedHashMap<>(1024, 0.75F, true);
		map.put(0, new Object());
		map.put(1, new Object());
		var accepted = InventoryNativeTraversal.inspect(map, 2L);
		assertTrue(accepted.accepted());
		assertEquals(InventoryNativeTraversal.Implementation.LINKED_HASH_MAP, accepted.implementation());
		assertEquals(0L, accepted.bucketWork());
		assertEquals(2, accepted.entryWork());
		assertEquals(0, accepted.splitCalls());
		assertEquals(1, accepted.probeObjects());
		assertFalse(InventoryNativeTraversal.inspect(map, 1L).accepted());
	}

	@Test void unsupportedSubclassesAndProxyRefuseBeforeOverriddenMethods() {
		ThrowingHashMap hash = new ThrowingHashMap();
		ThrowingIdentityHashMap identity = new ThrowingIdentityHashMap();
		ThrowingLinkedHashMap linked = new ThrowingLinkedHashMap();
		// Positive controls establish that the method traps really would fire.
		assertThrows(AssertionError.class, hash::size);
		assertThrows(AssertionError.class, hash::entrySet);
		assertThrows(AssertionError.class, hash::clone);
		assertThrows(AssertionError.class, identity::keySet);
		assertThrows(AssertionError.class, linked::size);
		AtomicInteger calls = new AtomicInteger();
		Map<?, ?> proxy = (Map<?, ?>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Map.class },
			(object, method, arguments) -> { calls.incrementAndGet(); throw new AssertionError("proxy dispatch"); });
		for (Map<?, ?> map : new Map<?, ?>[] { hash, identity, linked, proxy, new TreeMap<>(), Map.of() }) {
			var refused = InventoryNativeTraversal.inspect(map, Long.MAX_VALUE);
			assertEquals(InventoryNativeTraversal.Refusal.UNSUPPORTED_IMPLEMENTATION, refused.refusal());
			assertEquals(0, refused.probePortCalls());
			assertEquals(1, refused.probeObjects());
		}
		assertEquals(0, calls.get());
	}

	@Test void sparseExactMapInspectionNeverHashesComparesFormatsOrCopiesValues() {
		assumeAuditedRuntime();
		Poison key = new Poison(), value = new Poison();
		Map<Poison, Poison> hash = new HashMap<>(1024);
		hash.put(key, value);
		key.armed = value.armed = true;
		assertThrows(AssertionError.class, key::hashCode);
		assertThrows(AssertionError.class, () -> value.equals(new Object()));
		assertThrows(AssertionError.class, value::toString);
		assertThrows(AssertionError.class, value::clone);
		key.calls = value.calls = 0;
		var refused = InventoryNativeTraversal.inspect(hash, 33L);
		assertEquals(InventoryNativeTraversal.Refusal.WORK_LIMIT, refused.refusal());
		assertEquals(6, refused.successfulSplits()); // proof reaches 64, no terminal scan
		assertEquals(0, key.calls);
		assertEquals(0, value.calls);
		assertBounded(refused);
		// Even acceptance only inspects capacity, not entries or user methods.
		assertAccepted(hash, 1024L, 1, 10);
		assertEquals(0, key.calls);
		assertEquals(0, value.calls);
	}

	@Test void invalidInputsAreNotBudgetRefusals() {
		assertThrows(NullPointerException.class, () -> InventoryNativeTraversal.inspect(null, 0L));
		assertThrows(IllegalArgumentException.class, () -> InventoryNativeTraversal.inspect(new HashMap<>(), -1L));
	}

	private static void assertAccepted(Map<?, ?> map, long buckets, int entries, int splits) {
		var accepted = InventoryNativeTraversal.inspect(map, buckets + entries);
		assertTrue(accepted.accepted());
		assertEquals(buckets, accepted.bucketWork());
		assertEquals(entries, accepted.entryWork());
		assertEquals(buckets + entries, accepted.traversalWork());
		assertEquals(splits, accepted.successfulSplits());
		assertEquals(splits + 1, accepted.splitCalls());
		assertEquals(splits + 3, accepted.probeObjects());
		assertEquals(splits + 4, accepted.probePortCalls());
		assertBounded(accepted);
	}

	private static void assertBounded(InventoryNativeTraversal.Inspection inspection) {
		assertTrue(inspection.successfulSplits() <= 30);
		assertTrue(inspection.splitCalls() <= 31);
		assertTrue(inspection.probeObjects() <= 33);
		assertTrue(inspection.probePortCalls() <= 34);
	}

	private static void assumeAuditedRuntime() {
		assumeTrue(InventoryNativeTraversal.runtimeSupported(), "requires audited OpenJDK HotSpot 21 or 25");
	}

	private static final class ThrowingHashMap extends HashMap<Object, Object> {
		@Override public int size() { throw new AssertionError("size"); }
		@Override public Set<Map.Entry<Object, Object>> entrySet() { throw new AssertionError("entry iterator port"); }
		@Override public Set<Object> keySet() { throw new AssertionError("key iterator port"); }
		@Override public Collection<Object> values() { throw new AssertionError("values iterator port"); }
		@Override public void forEach(BiConsumer<? super Object, ? super Object> action) { throw new AssertionError("traversal"); }
		@Override public Object clone() { throw new AssertionError("copy"); }
	}

	private static final class ThrowingIdentityHashMap extends IdentityHashMap<Object, Object> {
		@Override public int size() { throw new AssertionError("size"); }
		@Override public Set<Object> keySet() { throw new AssertionError("key iterator port"); }
		@Override public Set<Map.Entry<Object, Object>> entrySet() { throw new AssertionError("entry iterator port"); }
	}

	private static final class ThrowingLinkedHashMap extends LinkedHashMap<Object, Object> {
		@Override public int size() { throw new AssertionError("size"); }
		@Override public Set<Map.Entry<Object, Object>> entrySet() { throw new AssertionError("entry iterator port"); }
	}

	private static final class Poison implements Cloneable {
		boolean armed;
		int calls;
		private void check() { if (armed) { calls++; throw new AssertionError("resident value method"); } }
		@Override public int hashCode() { check(); return 1; }
		@Override public boolean equals(Object other) { check(); return this == other; }
		@Override public String toString() { check(); return "unarmed"; }
		@Override public Object clone() { check(); return new Poison(); }
	}
}
