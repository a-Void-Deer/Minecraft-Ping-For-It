package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Deterministic registry for optional/modded entity-block geometry sources.
 *
 * <p>Registrations retain declaration order. The volatile snapshot is replaced
 * atomically with an immutable list, so a render invocation gets an O(1)
 * snapshot reference and never iterates a mutating collection. Duplicate ids
 * keep the first registration and are ignored with a warning. A
 * successful registration returns a handle whose lifetime is scoped to that
 * exact registration; optional integrations must retain and close that handle
 * during teardown. Built-in BER and baked-model sources are owned by
 * {@link EntityBlockGeometryRunner}; they are never inserted here. This is an
 * internal compatibility seam for a future separately-loaded optional adapter
 * package, not a stable public plugin API; no API stability is guaranteed.</p>
 */
public final class EntityBlockGeometrySourceRegistry {
	/** The one global optional-source seam; no built-ins are registered in it. */
	public static final EntityBlockGeometrySourceRegistry INSTANCE =
		new EntityBlockGeometrySourceRegistry();

	private final Object lock = new Object();
	private final Map<String, Registration> registrations = new LinkedHashMap<>();
	private final @Nullable WarningSink warningSink;
	private volatile @Unmodifiable List<EntityBlockGeometrySource> snapshot = List.of();

	public EntityBlockGeometrySourceRegistry() {
		this(null);
	}

	/**
	 * Injectable constructor for headless tests; production callers should use
	 * the default registry warning sink.
	 */
	EntityBlockGeometrySourceRegistry(@Nullable WarningSink warningSink) {
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
	public Registration register(@Nullable EntityBlockGeometrySource source) {
		if (source == null) {
			warn(EntityBlockGeometrySourceIds.INVALID, "null-source", null);
			return Registration.rejected();
		}

		final @Nullable String id;
		try {
			id = EntityBlockGeometrySourceIds.validate(source.id());
		} catch (Exception | LinkageError | AssertionError failure) {
			warn(EntityBlockGeometrySourceIds.UNAVAILABLE, "source-id-lookup-failed", failure);
			return Registration.rejected();
		}

		if (id == null) {
			warn(EntityBlockGeometrySourceIds.INVALID, "invalid-source-id", null);
			return Registration.rejected();
		}

		synchronized (lock) {
			if (registrations.containsKey(id)) {
				warn(id, "duplicate-id-first-registration-kept", null);
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
	public @Unmodifiable List<EntityBlockGeometrySource> snapshot() {
		return snapshot;
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
		private final @Nullable EntityBlockGeometrySourceRegistry registry;
		private final @Nullable String id;
		private final @Nullable EntityBlockGeometrySource source;
		private boolean closed;

		private Registration(
			EntityBlockGeometrySourceRegistry registry,
			String id,
			EntityBlockGeometrySource source
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

		private EntityBlockGeometrySource source() {
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

	private void warn(String sourceId, String category, @Nullable Throwable failure) {
		if (warningSink == null) {
			OutlineDiagnostics.rejected("entity-block-source-register", sourceId, category, failure);
		} else if (failure == null) {
			try {
				warningSink.warn(sourceId, category);
			} catch (RuntimeException | LinkageError ignored) {
				// A diagnostic sink must not reject a later valid registration.
			}
		} else {
			OutlineDiagnostics.deliver(text -> warningSink.warn(sourceId, text), () -> category, failure);
		}
	}

	@FunctionalInterface
	interface WarningSink {
		void warn(String sourceId, String category);

		static WarningSink noop() {
			return (sourceId, reason) -> {
				// intentionally empty
			};
		}

		static WarningSink global() {
			return (sourceId, category) -> OutlineDiagnostics.rejected("entity-block-source-register", sourceId, category, null);
		}
	}
}
