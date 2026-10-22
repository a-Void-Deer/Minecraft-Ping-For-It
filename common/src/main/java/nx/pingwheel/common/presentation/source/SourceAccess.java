package nx.pingwheel.common.presentation.source;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import nx.pingwheel.common.presentation.PresentationAdapter;

/**
 * Server-side source-access boundary for the shared source mechanism.
 *
 * <p>{@link #resolve} turns a dispatch-only target hint into an explicit
 * outcome: an {@link ResolveResult.Available} descriptor, a scheduling
 * {@code DEFERRED}, or an ordinary {@code UNAVAILABLE}/{@code UNSUPPORTED}
 * value. A budget defer is never an invalid source and an ordinary
 * unavailability is never an exception. {@link #open} admits a bounded handle,
 * and {@link Handle#step} produces either one {@link CaptureResult} or another
 * scheduling defer.
 *
 * <p>The identity is {@link SourceKey}: provider identity plus compatible read
 * scope, never a Ping ID. The target passed to {@code resolve} is a dispatch
 * payload only and must not be stored or treated as physical identity. A
 * handle is server-thread confined, holds any provider or world object
 * privately, exposes only detached demand, and is idempotently released by
 * {@link Handle#close()}. Budget admission is checked against the supplied
 * {@link CostLedger} before any read; a denied admission performs no world
 * read.
 */
public interface SourceAccess {

	ResolveResult resolve(PresentationAdapter.DetachedTarget dispatchTarget, ReadScope readScope, CostLedger ledger);

	OpenResult open(Descriptor descriptor, ReadScope readScope, CostLedger ledger);

	/**
	 * Independent provider capabilities. The mode and the two observations are
	 * separate declarations: a one-shot source may promise an atomic/stable
	 * version without a cursor, and a resumable source may promise a stable
	 * cursor without a stable version.
	 */
	record Capabilities(boolean oneShot, boolean stableCursor, boolean stableVersion) {}

	/** Code-registered descriptor: physical identity, schema and capabilities. */
	record Descriptor(SourceKey key, int schema, Capabilities capabilities) {
		public Descriptor {
			Objects.requireNonNull(key, "key");
			if (schema < 0) throw new IllegalArgumentException("schema must be non-negative");
			Objects.requireNonNull(capabilities, "capabilities");
		}
	}

	/**
	 * Immutable server-derived read scope: the compatible view key plus the
	 * frozen union of already-authorized demand. Demand entries are
	 * server-canonical demand tokens (field identities and prefixed domain
	 * filters), never item keys chosen by a client; the set is copied on
	 * construction and never widened by a client. A descriptor is only readable
	 * through a scope whose view key matches its {@link SourceKey#readScope()}.
	 */
	record ReadScope(String viewKey, Set<String> demand) {

		/** Engineering guard for one authorized demand set; not a product value. */
		static final int MAX_DEMAND_ENTRIES = 4096;

		public ReadScope {
			viewKey = SourceKey.requireToken(viewKey, "viewKey");
			Objects.requireNonNull(demand, "demand");
			if (demand.size() > MAX_DEMAND_ENTRIES)
				throw new IllegalArgumentException("demand set exceeds the read-scope bound");
			Set<String> copy = new LinkedHashSet<>();
			for (String field : demand) {
				copy.add(SourceKey.requireToken(field, "demand entry"));
			}
			demand = Set.copyOf(copy);
		}

		/** Whether this authorized scope observes the descriptor's compatible view. */
		public boolean compatibleWith(Descriptor descriptor) {
			return descriptor.key().readScope().equals(viewKey);
		}
	}

	sealed interface ResolveResult permits ResolveResult.Available, ResolveResult.Unresolved {

		record Available(Descriptor descriptor) implements ResolveResult {
			public Available {
				Objects.requireNonNull(descriptor, "descriptor");
			}
		}

		/** Stateless non-available outcomes; ordinary unavailability is a value. */
		enum Unresolved implements ResolveResult {
			DEFERRED,
			UNAVAILABLE,
			UNSUPPORTED
		}
	}

	sealed interface OpenResult permits OpenResult.Started, OpenResult.Unstarted {

		record Started(Handle handle) implements OpenResult {
			public Started {
				Objects.requireNonNull(handle, "handle");
			}
		}

		enum Unstarted implements OpenResult {
			DEFERRED,
			UNAVAILABLE
		}
	}

	sealed interface StepOutcome permits StepOutcome.Captured, StepOutcome.Deferred {

		record Captured(CaptureResult result) implements StepOutcome {
			public Captured {
				Objects.requireNonNull(result, "result");
			}
		}

		/** Budget or scheduling defer; never a fake invalid observation. */
		record Deferred() implements StepOutcome {}
	}

	/**
	 * Server-thread-confined bounded observation handle. The demand is frozen at
	 * open time; {@code step} revalidates the source and returns either one
	 * detached result or a scheduling defer. {@link #close()} releases the
	 * handle, is idempotent, and must not touch a world object afterwards.
	 */
	interface Handle extends AutoCloseable {

		Descriptor descriptor();

		Set<String> demand();

		StepOutcome step(CostLedger ledger);

		@Override
		void close();
	}
}
