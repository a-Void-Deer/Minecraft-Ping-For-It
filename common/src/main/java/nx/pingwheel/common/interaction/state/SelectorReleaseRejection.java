package nx.pingwheel.common.interaction.state;

import org.jetbrains.annotations.NotNullByDefault;

/**
 * Finite, caller-owned reason codes for one terminal selector release that
 * produced no admitted action. These are diagnostic labels only: they do not
 * participate in admission, ordering, dispatch or feedback.
 */
@NotNullByDefault
public enum SelectorReleaseRejection {
	WRONG_TOKEN("wrong-token"),
	MISSING_CANDIDATE("missing-candidate"),
	PAINT_REVISION_MISMATCH("paint-revision-mismatch"),
	RAY_TYPE_LOCATOR_MISMATCH("ray-type-locator-mismatch"),
	PRESENTATION_NOT_READY("presentation-not-ready"),
	PROPERTY_DENIED("property-denied"),
	INVENTORY_VIEW_MISMATCH("inventory-view-mismatch"),
	COURTESY_THROTTLED("courtesy-throttled");

	private final String code;

	SelectorReleaseRejection(String code) {
		this.code = code;
	}

	/** Stable lowercase token suitable for a fixed diagnostic template. */
	public String code() {
		return code;
	}
}
