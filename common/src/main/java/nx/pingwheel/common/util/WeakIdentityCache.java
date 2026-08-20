package nx.pingwheel.common.util;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A synchronized weak-key cache whose keys are compared by object identity.
 *
 * <p>Stale keys are removed from a reference queue as they are encountered;
 * cache hits do not scan the complete entry set. This is useful for live
 * client resources whose instances must not be kept alive by a cache and
 * whose implementations may define value-based {@code equals} semantics.</p>
 */
public final class WeakIdentityCache<K, V> {
	private final ReferenceQueue<K> referenceQueue = new ReferenceQueue<>();
	private final Map<IdentityKey, V> entries = new HashMap<>();

	/** Returns the value for the exact key instance, or {@code null} on a miss. */
	public synchronized V get(K key) {
		Objects.requireNonNull(key, "key");
		expungeStaleEntries();
		return entries.get(new LookupKey(key));
	}

	/** Stores a value for the exact key instance. */
	public synchronized void put(K key, V value) {
		Objects.requireNonNull(key, "key");
		expungeStaleEntries();
		entries.put(new WeakKey<>(key, referenceQueue), value);
	}

	/** Clears all entries and any queued weak-key notifications. */
	public synchronized void clear() {
		entries.clear();
		while (referenceQueue.poll() != null) {
			// Drain notifications for discarded entries so a later cache use does
			// not retain an unbounded queue of references from the old generation.
		}
	}

	/** Number of currently indexed entries, primarily for focused tests. */
	public synchronized int size() {
		expungeStaleEntries();
		return entries.size();
	}

	/**
	 * Deterministic test seam: clears and queues the entry for {@code key}
	 * without relying on a garbage-collection cycle.
	 */
	synchronized void clearAndEnqueueForTest(K key) {
		Objects.requireNonNull(key, "key");
		for (IdentityKey entry : entries.keySet()) {
			if (entry instanceof WeakKey<?> weakKey && weakKey.get() == key) {
				weakKey.clear();
				weakKey.enqueue();
				return;
			}
		}
		throw new IllegalArgumentException("key is not cached");
	}

	private void expungeStaleEntries() {
		WeakKey<?> stale;
		while ((stale = (WeakKey<?>) referenceQueue.poll()) != null) {
			entries.remove(stale);
		}
	}

	private interface IdentityKey {
		Object referent();
	}

	private static final class LookupKey implements IdentityKey {
		private final Object referent;
		private final int hash;

		private LookupKey(Object referent) {
			this.referent = referent;
			hash = System.identityHashCode(referent);
		}

		@Override
		public Object referent() {
			return referent;
		}

		@Override
		public int hashCode() {
			return hash;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof IdentityKey key && referent == key.referent();
		}
	}

	static final class WeakKey<K> extends WeakReference<K> implements IdentityKey {
		private final int hash;

		WeakKey(K referent, ReferenceQueue<K> queue) {
			super(referent, queue);
			hash = System.identityHashCode(referent);
		}

		@Override
		public Object referent() {
			return get();
		}

		@Override
		public int hashCode() {
			return hash;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof IdentityKey key)) {
				return false;
			}
			Object thisReferent = referent();
			Object otherReferent = key.referent();
			return thisReferent != null && thisReferent == otherReferent;
		}
	}
}
