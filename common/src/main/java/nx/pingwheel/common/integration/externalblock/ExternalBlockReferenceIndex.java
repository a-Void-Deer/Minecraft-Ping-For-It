package nx.pingwheel.common.integration.externalblock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Pure lifecycle bookkeeping for provider-owned tracking points.
 *
 * <p>A lease is prepared before marker insertion and committed only after the
 * marker store accepts the marker.  Rolling back a committed lease is the same
 * operation as releasing a marker: the provider cleanup callback runs exactly
 * once when the shared reference count reaches zero.  Locator migration keeps
 * the stable id and all existing leases intact.
 */
public final class ExternalBlockReferenceIndex {

	private final Map<LocatorKey, Entry> byLocator = new LinkedHashMap<>();
	private final Map<String, Entry> byStableId = new LinkedHashMap<>();

	/**
	 * Prepares a lease. Existing live entries are reused; a new entry is
	 * inserted with zero references until {@link #commit(Lease)} succeeds.
	 */
	public synchronized Lease prepare(LocatorKey locator, Supplier<String> stableIdFactory) {
		Objects.requireNonNull(locator, "locator");
		Objects.requireNonNull(stableIdFactory, "stableIdFactory");

		Entry existing = byLocator.get(locator);
		if (existing != null) {
			return new Lease(existing.stableId, locator, existing, false);
		}

		String stableId = Objects.requireNonNull(stableIdFactory.get(), "stableIdFactory.get");
		if (stableId.isBlank() || byStableId.containsKey(stableId)) {
			throw new IllegalArgumentException("stable id must be unique and non-blank");
		}

		Entry created = new Entry(stableId, locator);
		byLocator.put(locator, created);
		byStableId.put(stableId, created);
		return new Lease(stableId, locator, created, true);
	}

	/** Commits one prepared lease and increments its shared reference count. */
	public synchronized boolean commit(Lease lease) {
		Objects.requireNonNull(lease, "lease");

		if (lease.finished || lease.committed || byStableId.get(lease.stableId) != lease.entry) {
			return false;
		}

		lease.committed = true;
		lease.entry.references++;
		return true;
	}

	/**
	 * Rolls back one lease. A committed lease decrements its marker reference;
	 * an uncommitted newly-created lease removes the provisional entry.
	 */
	public synchronized boolean rollback(Lease lease, Consumer<String> removePoint) {
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(removePoint, "removePoint");

		if (lease.finished) {
			return false;
		}

		lease.finished = true;
		if (lease.committed) {
			return releaseEntry(lease.entry, removePoint);
		}

		if (lease.newlyCreated && lease.entry.references == 0) {
			removeEntry(lease.entry);
			removePoint.accept(lease.stableId);
		}

		return true;
	}

	/** Releases one committed marker reference by stable id. */
	public synchronized boolean release(String stableId, Consumer<String> removePoint) {
		Objects.requireNonNull(stableId, "stableId");
		Objects.requireNonNull(removePoint, "removePoint");

		Entry entry = byStableId.get(stableId);
		if (entry == null || entry.references <= 0) {
			return false;
		}

		return releaseEntry(entry, removePoint);
	}

	/**
	 * Moves a stable entry to a new locator while retaining its references. A
	 * collision with a different stable entry is rejected without mutation.
	 */
	public synchronized boolean migrate(String stableId, LocatorKey newLocator) {
		Objects.requireNonNull(stableId, "stableId");
		Objects.requireNonNull(newLocator, "newLocator");

		Entry entry = byStableId.get(stableId);
		if (entry == null) {
			return false;
		}

		Entry collision = byLocator.get(newLocator);
		if (collision != null && collision != entry) {
			return false;
		}

		if (!entry.locator.equals(newLocator)) {
			byLocator.remove(entry.locator, entry);
			byLocator.put(newLocator, entry);
			entry.locator = newLocator;
		}

		return true;
	}

	/** Drops an entry whose provider point is already absent, without a callback. */
	public synchronized boolean forget(String stableId) {
		Objects.requireNonNull(stableId, "stableId");
		Entry entry = byStableId.get(stableId);
		if (entry == null || entry.references != 0) {
			return false;
		}

		removeEntry(entry);
		return true;
	}

	public synchronized Optional<String> stableFor(LocatorKey locator) {
		Objects.requireNonNull(locator, "locator");
		return Optional.ofNullable(byLocator.get(locator)).map(entry -> entry.stableId);
	}

	public synchronized int references(String stableId) {
		Objects.requireNonNull(stableId, "stableId");
		Entry entry = byStableId.get(stableId);
		return entry == null ? 0 : entry.references;
	}

	public synchronized int size() {
		return byStableId.size();
	}

	/** Removes every entry, invoking the provider callback once per stable id. */
	public synchronized void close(Consumer<String> removePoint) {
		Objects.requireNonNull(removePoint, "removePoint");

		for (Entry entry : byStableId.values().stream().toList()) {
			removeEntry(entry);
			try {
				removePoint.accept(entry.stableId);
			} catch (RuntimeException ignored) {
				// One provider cleanup failure must not prevent later points from
				// being retired during server teardown.
			}
		}
	}

	public record LocatorKey(
		String providerId,
		String providerLocator,
		String expectedBlockRegistryId,
		boolean hasBlockEntity
	) {
		public LocatorKey {
			requireNonBlank(providerId, "providerId");
			Objects.requireNonNull(providerLocator, "providerLocator");
			requireNonBlank(expectedBlockRegistryId, "expectedBlockRegistryId");
		}
	}

	/** A one-shot transaction token returned by {@link #prepare}. */
	public static final class Lease {
		private final String stableId;
		private final LocatorKey locator;
		private final Entry entry;
		private final boolean newlyCreated;
		private boolean committed;
		private boolean finished;

		private Lease(String stableId, LocatorKey locator, Entry entry, boolean newlyCreated) {
			this.stableId = stableId;
			this.locator = locator;
			this.entry = entry;
			this.newlyCreated = newlyCreated;
		}

		public String stableId() {
			return stableId;
		}

		public LocatorKey locator() {
			return locator;
		}

		public boolean newlyCreated() {
			return newlyCreated;
		}
	}

	private static final class Entry {
		private final String stableId;
		private LocatorKey locator;
		private int references;

		private Entry(String stableId, LocatorKey locator) {
			this.stableId = stableId;
			this.locator = locator;
		}
	}

	private boolean releaseEntry(Entry entry, Consumer<String> removePoint) {
		entry.references--;
		if (entry.references != 0) {
			return true;
		}

		removeEntry(entry);
		removePoint.accept(entry.stableId);
		return true;
	}

	private void removeEntry(Entry entry) {
		byLocator.remove(entry.locator, entry);
		byStableId.remove(entry.stableId, entry);
	}

	private static void requireNonBlank(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}
