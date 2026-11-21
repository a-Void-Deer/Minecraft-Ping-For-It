package nx.pingwheel.common.client;

import java.util.Objects;

import org.jetbrains.annotations.NotNullByDefault;

import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.state.FrozenCancellation;

/**
 * Aiming evidence carried from one physical down edge to any baseline it starts.
 *
 * @param ray the ray sampled at that edge
 * @param cancellation the cancellation selection locked from the same ray and edge
 */
@NotNullByDefault
public record FrozenPingPress(CapturedRay ray, FrozenCancellation cancellation) {

	/**
	 * Creates the immutable edge snapshot.
	 *
	 * @param ray the press ray
	 * @param cancellation the press cancellation selection
	 */
	public FrozenPingPress {
		Objects.requireNonNull(ray, "ray");
		Objects.requireNonNull(cancellation, "cancellation");
	}
}
