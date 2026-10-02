package nx.pingwheel.common.presentation.preview;

import java.util.Objects;
import nx.pingwheel.common.presentation.PresentationLimits;
import nx.pingwheel.common.presentation.PresentationValue;

/** Provisional evidence, never an authoritative marker value or a freshness promise. */
public record PreviewObservation(PresentationValue value, Origin origin, long observedAtTick, boolean stale) {
	public enum Origin { CLIENT_SYNCED, SERVER_PREVIEW, RETAINED_MARKER }
	public PreviewObservation {
		Objects.requireNonNull(origin);
		PresentationLimits.validate(value);
		if (observedAtTick < 0) throw new IllegalArgumentException("negative observation tick");
	}
}
