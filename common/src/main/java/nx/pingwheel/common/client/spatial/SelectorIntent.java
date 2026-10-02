package nx.pingwheel.common.client.spatial;

import java.util.Objects;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.presentation.PresentationPropertyIntent;

/** One single-use proposal. Runtime phase/token/timeout and server admission remain external. */
public sealed interface SelectorIntent<R> permits SelectorIntent.None, SelectorIntent.CreateTarget,
	SelectorIntent.CreateProperty, SelectorIntent.SelectInventory, SelectorIntent.CancelOwnMarker,
	SelectorIntent.ToggleNextCapture {

	record None<R>() implements SelectorIntent<R> {}

	record CreateTarget<R>(SpatialSelectorSession.CapturedTarget candidate, PingType pingType)
		implements SelectorIntent<R> {
		public CreateTarget { Objects.requireNonNull(candidate); Objects.requireNonNull(pingType); }
	}

	record CreateProperty<R>(SpatialSelectorSession.CapturedTarget candidate, PingType mainType,
		PresentationPropertyIntent property) implements SelectorIntent<R> {
		public CreateProperty {
			Objects.requireNonNull(candidate); Objects.requireNonNull(mainType); Objects.requireNonNull(property);
		}
	}

	/** The opaque reference is the only item identity. Display counts never enter a proposal. */
	record SelectInventory<R>(SpatialSelectorSession.CapturedTarget candidate,
		SpatialSelectorSession.ContentFence fence, R reference, PingType mainType, PingType itemType)
		implements SelectorIntent<R> {
		public SelectInventory {
			Objects.requireNonNull(candidate); Objects.requireNonNull(fence); Objects.requireNonNull(reference);
			Objects.requireNonNull(mainType); Objects.requireNonNull(itemType);
		}
	}

	record CancelOwnMarker<R>() implements SelectorIntent<R> {}

	enum CaptureToggle { FLUIDS, ENTITY_BLACKLIST, TRANSPARENT_BLOCKS }

	/** A local preference proposal only; it must not change this session's frozen capture. */
	record ToggleNextCapture<R>(CaptureToggle toggle) implements SelectorIntent<R> {
		public ToggleNextCapture { Objects.requireNonNull(toggle); }
	}
}
