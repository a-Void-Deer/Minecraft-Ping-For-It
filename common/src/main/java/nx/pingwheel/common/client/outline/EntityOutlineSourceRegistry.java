package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Deterministic registry for optional/modded entity-outline sources.
 *
 * <p>Registrations retain declaration order. The volatile snapshot is replaced
 * atomically with an immutable list, so a render invocation gets an O(1)
 * snapshot reference and never iterates a mutating collection. Duplicate ids
 * keep the first registration and are ignored with a warning. A successful
 * registration returns a handle whose lifetime is scoped to that exact
 * registration; optional integrations must retain and close that handle during
 * teardown. This is an internal compatibility seam for the loader-neutral
 * entity-outline infrastructure, not a stable public plugin API; no API
 * stability is guaranteed.</p>
 *
 * <p>{@link #handlesAny(Entity)} is the cheap "does anything claim this
 * entity" probe used by the render redirects. Each source's {@code handles()}
 * failure fails soft with a full diagnostic and counts as not-handling;
 * fatal JVM {@link Error}s propagate unchanged.</p>
 */
public final class EntityOutlineSourceRegistry {
	/** The one global optional-source seam; no built-ins are registered in it. */
	public static final EntityOutlineSourceRegistry INSTANCE =
		new EntityOutlineSourceRegistry();

	private final Object lock = new Object();
	private final Map<String, Registration> registrations = new LinkedHashMap<>();
	private final @Nullable WarningSink warningSink;
	private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();
	private volatile @Unmodifiable List<EntityOutlineSource> snapshot = List.of();

	public EntityOutlineSourceRegistry() {
		this(null);
	}

	/**
	 * Injectable constructor for headless tests; production callers should use
	 * the default registry warning sink.
	 */
	EntityOutlineSourceRegistry(@Nullable WarningSink warningSink) {
		this.warningSink = warningSink;
	}

	/**
	 * Registers a source after validating its stable namespaced id.
	 *
	 * <p>The returned handle is non-null even when registration is rejected, so
	 * optional adapters can use one lifecycle shape without special cleanup
	 * branches. A rejected handle is a no-op. Duplicate ids keep the first
	 * source, and closing a rejected duplicate handle can never remove that
	 * winner.</p>
	 */
	public Registration register(@Nullable EntityOutlineSource source) {
		if (source == null) {
			rejected("null-source", "entity outline source registration ignored; id="
				+ EntityBlockGeometrySourceIds.INVALID + "; category=null-source", null);
			return Registration.rejected();
		}

		final @Nullable String id;
		try {
			id = EntityBlockGeometrySourceIds.validate(source.id());
		} catch (Exception | LinkageError | AssertionError failure) {
			rejected("source-id-lookup-failed", "entity outline source registration ignored; id="
				+ EntityBlockGeometrySourceIds.UNAVAILABLE + "; category=source-id-lookup-failed", failure);
			return Registration.rejected();
		}

		if (id == null) {
			rejected("invalid-source-id", "entity outline source registration ignored; id="
				+ EntityBlockGeometrySourceIds.INVALID + "; category=invalid-source-id", null);
			return Registration.rejected();
		}

		synchronized (lock) {
			if (registrations.containsKey(id)) {
				rejected("duplicate-id:" + id, "entity outline source registration ignored; id="
					+ id + "; category=duplicate-id-first-registration-kept", null);
				return Registration.rejected();
			}

			Registration registration = new Registration(this, id, source);
			registrations.put(id, registration);
			publishSnapshot();
			return registration;
		}
	}

	/**
	 * Returns the immutable registration-order snapshot. Retrieval is a single
	 * volatile read; callers must not retain a context from an attempt.
	 */
	public @Unmodifiable List<EntityOutlineSource> snapshot() {
		return snapshot;
	}

	/**
	 * Whether at least one registered source claims {@code entity}. Iterates
	 * the registration-order snapshot; a source whose {@code handles()} throws
	 * {@code Exception}, {@code LinkageError}, or {@code AssertionError} is
	 * diagnosed once per source and treated as not-handling. Fatal JVM
	 * {@link Error}s propagate.
	 */
	public boolean handlesAny(Entity entity) {
		if (entity == null) {
			return false;
		}

		for (EntityOutlineSource source : snapshot) {
			final boolean handles;
			try {
				handles = source.handles(entity);
			} catch (Exception | LinkageError | AssertionError failure) {
				String sourceId = safeSourceId(source);
				warnOnce(
					"handles:" + sourceId,
					() -> "entity outline source handles() failed; id=" + sourceId
						+ "; category=handles; entity=" + describeEntity(entity),
					failure);
				continue;
			}

			if (handles) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Rate-limited warning: the message supplier is only evaluated for a
	 * category that has not been warned before, so a broken source cannot
	 * spam one log line per entity per frame while still being retried on
	 * every call.
	 */
	void warnOnce(String key, java.util.function.Supplier<String> message, @Nullable Throwable failure) {
		if (warningSink == null && !OutlineDiagnostics.enabled()) {
			return;
		}
		if (!warnedKeys.add(key)) {
			return;
		}
		if (warningSink == null) {
			OutlineDiagnostics.approved("entity-outline-source", key, message, failure);
		} else {
			OutlineDiagnostics.deliver(text -> warningSink.warn(text, null), message, failure);
		}
	}

	private void rejected(String category, String message, @Nullable Throwable failure) {
		if (warningSink == null) {
			if (failure == null) {
				Diagnostics.event(Level.WARN,
					"entity-outline-register", category, "registration-rejected", () -> message);
			} else {
				Diagnostics.warn("entity-outline-register", category, () -> message, failure);
			}
		} else {
			OutlineDiagnostics.deliver(text -> warningSink.warn(text, null), () -> message, failure);
		}
	}

	private static String describeEntity(Entity entity) {
		try {
			return "class=" + entity.getClass().getName()
				+ "; id=" + entity.getId()
				+ "; uuid=" + entity.getUUID();
		} catch (Exception | LinkageError | AssertionError failure) {
			return "class=" + entity.getClass().getName() + "; <descriptor-unavailable>";
		}
	}

	private void close(Registration registration) {
		synchronized (lock) {
			if (registration.closed) {
				return;
			}

			registration.closed = true;
			if (registrations.get(registration.id) == registration) {
				registrations.remove(registration.id);
				publishSnapshot();
			}
		}
	}

	private void publishSnapshot() {
		snapshot = List.copyOf(new ArrayList<>(
			registrations.values().stream().map(Registration::source).toList()));
	}

	/**
	 * Lifecycle handle for one exact source registration. This is part of the
	 * internal compatibility seam only, has no public API stability guarantee,
	 * and is intended for a future optional adapter package to retain until
	 * teardown.
	 */
	public static final class Registration implements AutoCloseable {
		private final @Nullable EntityOutlineSourceRegistry registry;
		private final @Nullable String id;
		private final @Nullable EntityOutlineSource source;
		private boolean closed;

		private Registration(
			EntityOutlineSourceRegistry registry,
			String id,
			EntityOutlineSource source
		) {
			this.registry = registry;
			this.id = id;
			this.source = source;
		}

		private Registration() {
			this.registry = null;
			this.id = null;
			this.source = null;
			this.closed = true;
		}

		private static Registration rejected() {
			return new Registration();
		}

		private EntityOutlineSource source() {
			return Objects.requireNonNull(source);
		}

		/** Whether this handle owns an accepted registration rather than a no-op rejection. */
		public boolean accepted() {
			return registry != null;
		}

		@Override
		public void close() {
			if (registry != null) {
				registry.close(this);
			}
		}
	}

	private String safeSourceId(EntityOutlineSource source) {
		try {
			@Nullable String id = EntityBlockGeometrySourceIds.validate(source.id());
			if (id == null) {
				warnOnce("invalid-id:" + source.getClass().getName(),
					() -> "entity outline source has invalid id; class=" + source.getClass().getName(), null);
			}
			return id == null ? EntityBlockGeometrySourceIds.INVALID : id;
		} catch (Exception | LinkageError | AssertionError failure) {
			warnOnce("source-id:" + source.getClass().getName(),
				() -> "entity outline source id failed; class=" + source.getClass().getName(), failure);
			return EntityBlockGeometrySourceIds.UNAVAILABLE;
		}
	}

	@FunctionalInterface
	interface WarningSink {
		void warn(String message, @Nullable Throwable failure);

		static WarningSink noop() {
			return (message, failure) -> {
				// intentionally empty
			};
		}

		static WarningSink global() {
			return (message, failure) -> {
				OutlineDiagnostics.approved("entity-outline-source", "source", () -> message, failure);
			};
		}
	}
}
