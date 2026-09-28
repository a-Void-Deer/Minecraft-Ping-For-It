package nx.pingwheel.common.presentation;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Client-uploaded observation for one property reference. The observed value is
 * a detached, bounded {@link PresentationValue}, so numbers, flags, records and
 * nested record structure survive the wire instead of being flattened to text.
 * It stays informational because the server recaptures the authoritative value
 * and never treats this field as authority. A null ping type keeps the current
 * server-side assignment; a present type must name a code-defined ping type.
 */
public record PresentationPropertyIntent(PresentationPropertyRef ref, PresentationValue observedValue,
	@Nullable String pingTypeId) {

	public PresentationPropertyIntent {
		Objects.requireNonNull(ref, "ref");
		Objects.requireNonNull(observedValue, "observedValue");
		PresentationLimits.validate(observedValue);
		if (pingTypeId != null && !PresentationPropertyPingTypes.isKnownPingTypeId(pingTypeId))
			throw new IllegalArgumentException("unknown property ping type: " + pingTypeId);
	}

	/** An observation that carries no property ping type override. */
	public static PresentationPropertyIntent observed(PresentationPropertyRef ref, PresentationValue observedValue) {
		return new PresentationPropertyIntent(ref, observedValue, null);
	}

	public static PresentationPropertyIntent of(PresentationPropertyRef ref, PresentationValue observedValue, String pingTypeId) {
		return new PresentationPropertyIntent(ref, observedValue, pingTypeId);
	}
}