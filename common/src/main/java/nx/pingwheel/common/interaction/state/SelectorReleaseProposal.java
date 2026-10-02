package nx.pingwheel.common.interaction.state;

import java.util.Objects;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.ResolvedTarget;

/**
 * Admission envelope for a caller-owned typed selector intent. The payload is
 * opaque to the interaction layer: property and inventory data stay with their
 * owners, and neither rendering nor networking enters this boundary.
 *
 * @param <P> the original detached intent, returned only when admitted
 */
public sealed interface SelectorReleaseProposal<P> permits SelectorReleaseProposal.None,
	SelectorReleaseProposal.Create, SelectorReleaseProposal.Cancel, SelectorReleaseProposal.Local {

	/** Navigation, disabled entries, the deadzone and outside releases are all no-action. */
	record None<P>() implements SelectorReleaseProposal<P> {}

	/**
	 * A create must resolve through the caller's frozen candidate table. The
	 * claimed target is compared with that entry; it never supplies a new context.
	 * pingType is the whole-marker type; a property's or item's separate annotation
	 * stays in intent and is never used as the whole-marker type here.
	 */
	record Create<P>(String candidateId, ResolvedTarget target, PingType pingType, P intent)
		implements SelectorReleaseProposal<P> {
		public Create {
			Objects.requireNonNull(candidateId, "candidateId");
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(pingType, "pingType");
			Objects.requireNonNull(intent, "intent");
			if (candidateId.isBlank()) throw new IllegalArgumentException("blank candidate id");
		}
	}

	/** Explicit marker cancellation, never inferred from a no-action release. */
	record Cancel<P>(P intent) implements SelectorReleaseProposal<P> {
		public Cancel { Objects.requireNonNull(intent, "intent"); }
	}

	/** Local-only terminal intent, such as a next-capture preference toggle. */
	record Local<P>(P intent) implements SelectorReleaseProposal<P> {
		public Local { Objects.requireNonNull(intent, "intent"); }
	}
}
