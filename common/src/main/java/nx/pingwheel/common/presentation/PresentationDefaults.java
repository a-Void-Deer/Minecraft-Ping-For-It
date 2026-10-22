package nx.pingwheel.common.presentation;

import java.util.Optional;

/**
 * Code-defined default property references per existing target type. An unknown
 * target type resolves to empty rather than a guessed reference, so a caller
 * that must display something fails closed instead of inventing an address.
 */
public final class PresentationDefaults {
	private PresentationDefaults() {}

	/** Default display ref: dropped item id, entity health, or the target name. */
	public static Optional<PresentationPropertyRef> forTargetType(String targetTypeId) {
		if (targetTypeId == null) return Optional.empty();

		return switch (targetTypeId) {
			case "dropped_item" -> Optional.of(PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.ITEM_ID));
			case "entity" -> Optional.of(PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.HEALTH));
			case "entity_block", "block", "location" ->
				Optional.of(PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.NAME));
			default -> Optional.empty();
		};
	}

	/** Dropped-item display uses the item id with the stack count as context. */
	public static Optional<PresentationPropertyRef> displayCountContextRef(String targetTypeId) {
		return "dropped_item".equals(targetTypeId)
			? Optional.of(PresentationPropertyRef.root(PresentationBasic.ID, PresentationBasic.ITEM_COUNT))
			: Optional.empty();
	}
}
