package nx.pingwheel.common.math;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.Global;

/**
 * Internal common registry for loader-owned entity narrowphase adapters.
 *
 * <p>Ownership is intentionally based only on exact entity registry ids. One
 * immutable, priority-sorted snapshot is taken by each ray; the first matching
 * source owns a candidate even when that source later reports a recoverable
 * miss or failure. This prevents a lower-priority adapter or legacy AABB from
 * reviving a claimed entity.</p>
 */
public final class EntityLocalGeometryRegistry {

	public static final EntityLocalGeometryRegistry INSTANCE = new EntityLocalGeometryRegistry();

	private static final Comparator<Entry> ENTRY_ORDER = Comparator
		.comparingInt(Entry::priority)
		.thenComparing(entry -> entry.sourceId().toString());

	private final Object lock = new Object();
	private final List<Registration> registrations = new ArrayList<>();
	private final Set<String> warnedDiagnosticKeys = ConcurrentHashMap.newKeySet();
	private volatile List<Entry> snapshot = List.of();

	/**
	 * Registers a fixed-id owner for exact entity type registry ids.
	 *
	 * <p>Lower priority wins; equal priorities use the stable source id. The
	 * first registration for a source id wins. A duplicate returns an inactive,
	 * idempotently closeable handle and never removes the existing owner.</p>
	 */
	public Registration register(
		ResourceLocation sourceId,
		int priority,
		Collection<ResourceLocation> entityTypeIds,
		EntityLocalGeometryResolver resolver
	) {
		Objects.requireNonNull(sourceId, "sourceId");
		Objects.requireNonNull(entityTypeIds, "entityTypeIds");
		Objects.requireNonNull(resolver, "resolver");

		Set<ResourceLocation> exactTypeIds = copyExactTypeIds(entityTypeIds);

		if (exactTypeIds.isEmpty()) {
			throw new IllegalArgumentException("entityTypeIds must not be empty");
		}

		synchronized (lock) {
			for (Registration existing : registrations) {
				if (existing.sourceId.equals(sourceId)) {
					warnOnce("duplicate:" + sourceId, "entity local geometry source registration ignored; id="
						+ sourceId + "; category=duplicate-id-first-registration-kept", null);
					return Registration.rejected(this, sourceId);
				}
			}

			Registration registration = new Registration(this, sourceId, priority, exactTypeIds, resolver, true);
			registrations.add(registration);
			publishSnapshot();
			return registration;
		}
	}

	private static Set<ResourceLocation> copyExactTypeIds(Collection<ResourceLocation> entityTypeIds) {
		var copied = new LinkedHashSet<ResourceLocation>();

		for (ResourceLocation entityTypeId : entityTypeIds) {
			copied.add(Objects.requireNonNull(entityTypeId, "entityTypeIds contains null"));
		}

		return Set.copyOf(copied);
	}

	private void close(Registration registration) {
		synchronized (lock) {
			if (registration.closed) {
				return;
			}

			registration.closed = true;

			if (registration.accepted && registrations.remove(registration)) {
				publishSnapshot();
			}
		}
	}

	private void publishSnapshot() {
		var entries = new ArrayList<Entry>(registrations.size());

		for (Registration registration : registrations) {
			if (!registration.closed) {
				entries.add(new Entry(
					registration.sourceId,
					registration.priority,
					registration.entityTypeIds,
					registration.resolver));
			}
		}

		entries.sort(ENTRY_ORDER);
		snapshot = List.copyOf(entries);
	}

	/** Package-private so the ray takes exactly one snapshot before candidate iteration. */
	Snapshot snapshot() {
		return new Snapshot(this, snapshot);
	}

	private void warnCallbackFailure(ResourceLocation sourceId, Throwable failure) {
		warnOnce(
			"callback:" + sourceId + ':' + failure.getClass().getName(),
			"entity local geometry source failed; id=" + sourceId + "; category=callback",
			failure);
	}

	private void warnOnce(String key, String message, Throwable failure) {
		if (warnedDiagnosticKeys.add(key)) {
			Global.LOGGER.warn(message, failure);
		}
	}

	static final class Snapshot {

		private final EntityLocalGeometryRegistry registry;
		private final List<Entry> entries;

		private Snapshot(EntityLocalGeometryRegistry registry, List<Entry> entries) {
			this.registry = registry;
			this.entries = entries;
		}

		Claim firstOwner(Entity candidate) {
			Objects.requireNonNull(candidate, "candidate");
			ResourceLocation entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType());

			if (entityTypeId == null) {
				return null;
			}

			for (Entry entry : entries) {
				if (entry.entityTypeIds().contains(entityTypeId)) {
					return new Claim(registry, entry);
				}
			}

			return null;
		}
	}

	static final class Claim {

		private final EntityLocalGeometryRegistry registry;
		private final Entry entry;

		private Claim(EntityLocalGeometryRegistry registry, Entry entry) {
			this.registry = registry;
			this.entry = entry;
		}

		String sourceId() {
			return entry.sourceId().toString();
		}

		EntityLocalGeometryResult trace(Entity candidate, EntityLocalRaycastRequest request) {
			try {
				EntityLocalGeometryResult result = entry.resolver().trace(candidate, request);
				return result == null ? EntityLocalGeometryResult.failed() : result;
			} catch (RuntimeException | LinkageError | AssertionError recoverableFailure) {
				registry.warnCallbackFailure(entry.sourceId(), recoverableFailure);
				return EntityLocalGeometryResult.failed();
			}
		}
	}

	private record Entry(
		ResourceLocation sourceId,
		int priority,
		Set<ResourceLocation> entityTypeIds,
		EntityLocalGeometryResolver resolver
	) {}

	/** Idempotent lifecycle handle for one exact source-id registration. */
	public static final class Registration implements AutoCloseable {

		private final EntityLocalGeometryRegistry registry;
		private final ResourceLocation sourceId;
		private final int priority;
		private final Set<ResourceLocation> entityTypeIds;
		private final EntityLocalGeometryResolver resolver;
		private final boolean accepted;
		private boolean closed;

		private Registration(
			EntityLocalGeometryRegistry registry,
			ResourceLocation sourceId,
			int priority,
			Set<ResourceLocation> entityTypeIds,
			EntityLocalGeometryResolver resolver,
			boolean accepted
		) {
			this.registry = registry;
			this.sourceId = sourceId;
			this.priority = priority;
			this.entityTypeIds = entityTypeIds;
			this.resolver = resolver;
			this.accepted = accepted;
		}

		private static Registration rejected(EntityLocalGeometryRegistry registry, ResourceLocation sourceId) {
			return new Registration(registry, sourceId, 0, Set.of(), null, false);
		}

		/** Whether this handle successfully registered a live source. */
		public boolean isActive() {
			return accepted && !closed;
		}

		@Override
		public void close() {
			registry.close(this);
		}
	}
}
