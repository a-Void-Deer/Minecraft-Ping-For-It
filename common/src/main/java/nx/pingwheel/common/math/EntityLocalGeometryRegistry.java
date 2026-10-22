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
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

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
	private volatile @Unmodifiable List<Entry> snapshot = List.of();

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

		@Unmodifiable Set<ResourceLocation> exactTypeIds = copyExactTypeIds(entityTypeIds);

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

	private static @Unmodifiable Set<ResourceLocation> copyExactTypeIds(Collection<ResourceLocation> entityTypeIds) {
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
					Objects.requireNonNull(registration.resolver)));
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

	private void warnOnce(String key, String message, @Nullable Throwable failure) {
		boolean enabled;
		try {
			enabled = Diagnostics.logger().isEnabled(Level.WARN);
		} catch (RuntimeException | LinkageError ignored) {
			enabled = false;
		}
		if (!enabled) {
			return;
		}
		if (warnedDiagnosticKeys.add(key)) {
			if (failure == null) {
				Diagnostics.domainApprovedEvent(Level.WARN, "entity-local-geometry", key, "contract-or-registration", () -> message);
			} else {
				Diagnostics.domainApprovedFailure(Level.WARN, "entity-local-geometry", key, () -> message, failure);
			}
		}
	}

	static final class Snapshot {

		private final EntityLocalGeometryRegistry registry;
		private final @Unmodifiable List<Entry> entries;

		private Snapshot(EntityLocalGeometryRegistry registry, @Unmodifiable List<Entry> entries) {
			this.registry = registry;
			this.entries = entries;
		}

		@Nullable Claim firstOwner(Entity candidate) {
			Objects.requireNonNull(candidate, "candidate");
			@Nullable ResourceLocation entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType());

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
				@Nullable EntityLocalGeometryResult result = entry.resolver().trace(candidate, request);
				if (result == null) {
					registry.warnOnce("null-result:" + entry.sourceId(),
						"entity local geometry source returned null; id=" + entry.sourceId(), null);
					return EntityLocalGeometryResult.failed();
				}
				return result;
			} catch (RuntimeException | LinkageError | AssertionError recoverableFailure) {
				registry.warnCallbackFailure(entry.sourceId(), recoverableFailure);
				return EntityLocalGeometryResult.failed();
			}
		}
	}

	private record Entry(
		ResourceLocation sourceId,
		int priority,
		@Unmodifiable Set<ResourceLocation> entityTypeIds,
		EntityLocalGeometryResolver resolver
	) {}

	/** Idempotent lifecycle handle for one exact source-id registration. */
	public static final class Registration implements AutoCloseable {

		private final EntityLocalGeometryRegistry registry;
		private final ResourceLocation sourceId;
		private final int priority;
		private final @Unmodifiable Set<ResourceLocation> entityTypeIds;
		private final @Nullable EntityLocalGeometryResolver resolver;
		private final boolean accepted;
		private boolean closed;

		private Registration(
			EntityLocalGeometryRegistry registry,
			ResourceLocation sourceId,
			int priority,
			@Unmodifiable Set<ResourceLocation> entityTypeIds,
			@Nullable EntityLocalGeometryResolver resolver,
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
