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

	enum Admission { PRESS_RAY, PRECISE_PRESENTED }

	/** Navigation, disabled entries, the deadzone and outside releases are all no-action. */
	record None<P>() implements SelectorReleaseProposal<P> {}

	/**
	 * A create must resolve through the caller's immutable release table. Ordinary
	 * admission requires the press ray; Precise additionally requires the machine's
	 * exact paint-admitted context and revision, not merely this admission flag. The
	 * claimed target is compared with that entry; it never supplies a new context.
	 * pingType is the whole-marker type; a property's or item's separate annotation
	 * stays in intent and is never used as the whole-marker type here.
	 */
	record Create<P>(String candidateId, ResolvedTarget target, PingType pingType, P intent,
		Admission admission, long presentationRevision)
		implements SelectorReleaseProposal<P> {
		public Create(String candidateId, ResolvedTarget target, PingType pingType, P intent) {
			this(candidateId, target, pingType, intent, Admission.PRESS_RAY, 0);
		}
		public Create {
			Objects.requireNonNull(candidateId, "candidateId");
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(pingType, "pingType");
			Objects.requireNonNull(intent, "intent");
			Objects.requireNonNull(admission, "admission");
			if (candidateId.isBlank()) throw new IllegalArgumentException("blank candidate id");
			if (admission == Admission.PRECISE_PRESENTED && presentationRevision <= 0)
				throw new IllegalArgumentException("precise create requires a paint revision");
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
