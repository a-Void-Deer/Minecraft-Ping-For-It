package nx.pingwheel.common.presentation;

import java.util.Objects;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * The per-marker content authorization delivered with the atomic initial.
 *
 * <p>{@code trackingKind} is the marker's immutable dedicated inventory domain,
 * not an authorization bit. It stays present when a receipt is suppressed so a
 * client never has to infer the lane from an empty status. Ordinary and
 * Property markers keep it null even when their grant happens to include an
 * inventory field.
 */
@NotNullByDefault
public record MarkerContentGrant(PresentationGrant authorization,
	@Nullable InventoryResourceKind trackingKind) {

	public MarkerContentGrant {
		Objects.requireNonNull(authorization, "authorization");
	}

	public static MarkerContentGrant denied(@Nullable InventoryResourceKind trackingKind) {
		return new MarkerContentGrant(PresentationGrant.denied(), trackingKind);
	}
}
