package nx.pingwheel.common.presentation;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One selected property reference and its optional server-authorized ping type.
 * Selection itself never grants authority; the server remains free to reject or
 * replace the assignment.
 */
public record PresentationPropertySelection(PresentationPropertyRef ref, @Nullable String pingTypeId) {

	public PresentationPropertySelection {
		Objects.requireNonNull(ref, "ref");
		if (pingTypeId != null && !PresentationPropertyPingTypes.isKnownPingTypeId(pingTypeId))
			throw new IllegalArgumentException("unknown property ping type: " + pingTypeId);
	}

	public static PresentationPropertySelection of(PresentationPropertyRef ref) {
		return new PresentationPropertySelection(ref, null);
	}

	public static PresentationPropertySelection of(PresentationPropertyRef ref, String pingTypeId) {
		return new PresentationPropertySelection(ref, pingTypeId);
	}
}
