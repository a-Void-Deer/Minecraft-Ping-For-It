package nx.pingwheel.common.name;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import nx.pingwheel.common.domain.MarkerId;

/**
 * Client-side, main-thread-confined bookkeeping for authoritative target
 * names, keyed by {@link MarkerId}.
 *
 * <p>{@link #onCreated} is an idempotent upsert: re-applying the same
 * {@code (id, name)} pair changes nothing, and a different name for the same
 * id deterministically replaces the stored name with the latest payload (the
 * server is authoritative, so the newest received name for an id wins).
 *
 * <p>Thread safety: this store is <strong>main-thread-confined</strong>. Every
 * method must be called from the client main thread (the same thread the S2C
 * packet handlers run on). No synchronization is provided and concurrent
 * access is unsupported. The store never logs and carries no
 * {@code net.minecraft} references.
 *
 * <h2>Determinism</h2>
 *
 * <p>Names are held in a {@link LinkedHashMap} and
 * {@link #snapshot()} returns an unmodifiable map ordered by ascending
 * {@link MarkerId}, so repeated snapshots of the same state are identical.
 */
public final class ClientTargetNameStore {

	private final Map<MarkerId, TargetNameJson> names = new LinkedHashMap<>();

	/**
	 * Records the authoritative name for {@code id}.
	 *
	 * <p>Idempotent replace: an identical {@code (id, name)} pair changes
	 * nothing; a different name for the same id replaces the stored name.
	 */
	public void onCreated(MarkerId id, TargetNameJson name) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(name, "name");

		names.put(id, name);
	}

	/**
	 * Removes the name for {@code id}, if known. Removing an unknown id is a
	 * safe no-op.
	 */
	public void onRemoved(MarkerId id) {
		Objects.requireNonNull(id, "id");

		names.remove(id);
	}

	/**
	 * The stored name for {@code id}, if present.
	 */
	public Optional<TargetNameJson> find(MarkerId id) {
		Objects.requireNonNull(id, "id");

		return Optional.ofNullable(names.get(id));
	}

	/**
	 * Drops every stored name.
	 */
	public void clear() {
		names.clear();
	}

	/**
	 * All stored {@code (id, name)} pairs as an immutable snapshot, ordered by
	 * ascending {@link MarkerId}. Later mutations never affect the returned
	 * map.
	 */
	public Map<MarkerId, TargetNameJson> snapshot() {
		Map<MarkerId, TargetNameJson> copy = new LinkedHashMap<>();

		names.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> copy.put(entry.getKey(), entry.getValue()));

		return Collections.unmodifiableMap(copy);
	}
}
