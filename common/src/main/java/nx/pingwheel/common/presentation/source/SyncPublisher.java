package nx.pingwheel.common.presentation.source;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Sync-publication boundary for the shared source mechanism.
 *
 * <p>A publisher re-projects one already-detached {@link CaptureResult} for one
 * isolated consumer. The consumer {@link Context} is opaque: consumer and
 * session identity only, never a Ping requirement, never a live world object.
 * The {@link AuthorizedProjection} is server-supplied and frozen; a client
 * cannot widen it. {@code Outcome.Accepted} means the publication was admitted
 * for that consumer, not that the wire or the client received it.
 *
 * <p>The two forms stay separate. {@link PublicationForm#SNAPSHOT_ONLY} may
 * reuse an existing whole-section channel but enables no heartbeat,
 * resynchronization, or fragmentation by itself.
 * {@link PublicationForm#KEYED_ABSOLUTE} carries per-key absolute values; zero,
 * absence, and tombstone meaning is owned by the domain, and a missing key
 * leaves the previous key unchanged.
 *
 * <p>Domain strategy is supplied by the domain and is not enforced here: the
 * eligible complete gate, heartbeat cadence, component fallback, recovery
 * deadlines, and the cost mapping for domain units are domain policy. This
 * common layer imposes no inventory completeness rule and must not block
 * control or status information; a budget defer is a scheduling outcome and
 * must never be confused with a source that reports itself unavailable or
 * invalid. {@code cancel} and {@code rebase} act on one context identity;
 * neither resets another consumer's baseline, and a late per-key update is not
 * discarded by another key's revision.
 */
public interface SyncPublisher {

	Outcome publish(CaptureResult result, AuthorizedProjection projection, Context context, CostLedger ledger);

	void cancel(Context context);

	void rebase(Context context, long newStateFence);

	enum PublicationForm {
		SNAPSHOT_ONLY,
		KEYED_ABSOLUTE
	}

	/**
	 * Frozen, server-supplied authorization for one publication. Keys are
	 * canonical server keys for the chosen form (presentation field ids or
	 * domain keys); the set is copied on construction and is never widened by a
	 * client.
	 */
	record AuthorizedProjection(PublicationForm form, Set<String> authorizedKeys) {

		/** Engineering guard for one authorized key set; not a product value. */
		static final int MAX_KEYS = 4096;

		public AuthorizedProjection {
			Objects.requireNonNull(form, "form");
			Objects.requireNonNull(authorizedKeys, "authorizedKeys");
			if (authorizedKeys.size() > MAX_KEYS)
				throw new IllegalArgumentException("authorized projection exceeds the key bound");
			Set<String> copy = new LinkedHashSet<>();
			for (String key : authorizedKeys) {
				copy.add(SourceKey.requireToken(key, "authorized key"));
			}
			authorizedKeys = Set.copyOf(copy);
		}
	}

	/**
	 * Opaque consumer and session identity for one isolated publication stream.
	 * It requires no Ping: preview owns its own request identity on top, while
	 * tracking binds its own keys on the caller side. {@code stateFence} and
	 * {@code baseline} are server-assigned revisions; they must be non-negative,
	 * and an older fence discards data relative to the lifetime held by the
	 * publisher, never inside this value.
	 */
	record Context(String consumerId, UUID recipient, String sessionView, long stateFence, long baseline) {

		public Context {
			consumerId = SourceKey.requireToken(consumerId, "consumerId");
			Objects.requireNonNull(recipient, "recipient");
			sessionView = SourceKey.requireToken(sessionView, "sessionView");
			if (stateFence < 0L) throw new IllegalArgumentException("state fence must be non-negative");
			if (baseline < 0L) throw new IllegalArgumentException("baseline must be non-negative");
		}
	}

	sealed interface Outcome permits Outcome.Accepted, Outcome.Deferred, Outcome.Rejected {

		/** Admitted for this consumer; no wire or client receipt is claimed. */
		record Accepted() implements Outcome {}

		/** Scheduling defer, for example an exhausted budget; the result stays valid. */
		record Deferred() implements Outcome {}

		/** Not publishable for this consumer, projection, or domain policy. */
		record Rejected() implements Outcome {}
	}
}
