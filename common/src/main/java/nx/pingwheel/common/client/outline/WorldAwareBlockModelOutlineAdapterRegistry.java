package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic internal registry for world-aware baked-model outline
 * adapters.
 *
 * <p>The registry publishes one immutable registration-order snapshot. A
 * duplicate id keeps the first registration, and every registration returns
 * an idempotent handle whose close only removes that exact registration. The
 * seam exists for loader compatibility and is not a public plugin API.</p>
 */
public final class WorldAwareBlockModelOutlineAdapterRegistry {
	/** The one common compatibility seam; no adapters are built in here. */
	public static final WorldAwareBlockModelOutlineAdapterRegistry INSTANCE =
		new WorldAwareBlockModelOutlineAdapterRegistry();

	private final Object lock = new Object();
	private final Map<String, Registration> registrations = new LinkedHashMap<>();
	private volatile List<WorldAwareBlockModelOutlineAdapter> snapshot = List.of();

	public WorldAwareBlockModelOutlineAdapterRegistry() {
	}

	/**
	 * Registers an adapter after validating its stable namespaced id. Invalid
	 * and duplicate registrations return a harmless rejected handle.
	 */
	public Registration register(WorldAwareBlockModelOutlineAdapter adapter) {
		if (adapter == null) {
			return Registration.rejected();
		}

		final String id;
		try {
			id = EntityBlockGeometrySourceIds.validate(adapter.id());
		} catch (Exception | LinkageError | AssertionError failure) {
			return Registration.rejected();
		}

		if (id == null) {
			return Registration.rejected();
		}

		synchronized (lock) {
			if (registrations.containsKey(id)) {
				return Registration.rejected();
			}

			Registration registration = new Registration(this, id, adapter);
			registrations.put(id, registration);
			publishSnapshot();
			return registration;
		}
	}

	/** Returns the immutable registration-order snapshot. */
	public List<WorldAwareBlockModelOutlineAdapter> snapshot() {
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
			registrations.values().stream().map(Registration::adapter).toList()));
	}

	/** Lifecycle handle for one exact internal adapter registration. */
	public static final class Registration implements AutoCloseable {
		private final WorldAwareBlockModelOutlineAdapterRegistry registry;
		private final String id;
		private final WorldAwareBlockModelOutlineAdapter adapter;
		private boolean closed;

		private Registration(
			WorldAwareBlockModelOutlineAdapterRegistry registry,
			String id,
			WorldAwareBlockModelOutlineAdapter adapter
		) {
			this.registry = registry;
			this.id = id;
			this.adapter = adapter;
		}

		private Registration() {
			this.registry = null;
			this.id = null;
			this.adapter = null;
			this.closed = true;
		}

		private static Registration rejected() {
			return new Registration();
		}

		private WorldAwareBlockModelOutlineAdapter adapter() {
			return adapter;
		}

		/**
		 * Whether this registration was accepted when created. This remains true
		 * after {@link #close()}; it is not an active-registration query.
		 */
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
}
