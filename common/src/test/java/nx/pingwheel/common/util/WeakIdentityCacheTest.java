package nx.pingwheel.common.util;

import java.lang.ref.ReferenceQueue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeakIdentityCacheTest {
	@Test
	void cacheHitsByIdentityAndClearAllowsAFreshValue() {
		WeakIdentityCache<EqualKey, Object> cache = new WeakIdentityCache<>();
		EqualKey first = new EqualKey("same-value");
		EqualKey equalButDistinct = new EqualKey("same-value");
		Object firstValue = new Object();
		Object secondValue = new Object();

		cache.put(first, firstValue);
		assertSame(firstValue, cache.get(first));
		assertNull(cache.get(equalButDistinct));
		assertEquals(1, cache.size());

		cache.clear();
		assertEquals(0, cache.size());
		cache.put(first, secondValue);
		assertSame(secondValue, cache.get(first));
	}

	@Test
	void clearedWeakKeysOnlyEqualThemselvesAndQueuedRemovalIsDeterministic() {
		ReferenceQueue<EqualKey> queue = new ReferenceQueue<>();
		EqualKey firstReferent = new EqualKey("first");
		EqualKey secondReferent = new EqualKey("second");
		WeakIdentityCache.WeakKey<EqualKey> first =
			new WeakIdentityCache.WeakKey<>(firstReferent, queue);
		WeakIdentityCache.WeakKey<EqualKey> second =
			new WeakIdentityCache.WeakKey<>(secondReferent, queue);
		first.clear();
		second.clear();

		assertTrue(first.equals(first));
		assertFalse(first.equals(second));

		WeakIdentityCache<EqualKey, Object> cache = new WeakIdentityCache<>();
		Object firstValue = new Object();
		Object secondValue = new Object();
		cache.put(firstReferent, firstValue);
		cache.put(secondReferent, secondValue);
		cache.clearAndEnqueueForTest(firstReferent);

		assertEquals(1, cache.size());
		assertSame(secondValue, cache.get(secondReferent));
	}

	private record EqualKey(String value) {
		@Override
		public boolean equals(Object other) {
			return other instanceof EqualKey key && value.equals(key.value);
		}

		@Override
		public int hashCode() {
			return value.hashCode();
		}
	}
}
