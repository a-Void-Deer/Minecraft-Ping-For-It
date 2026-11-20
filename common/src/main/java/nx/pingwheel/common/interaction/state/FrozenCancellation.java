package nx.pingwheel.common.interaction.state;

import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.NotNullByDefault;

/**
 * The press-edge selection, including a miss. Only confirmation may emit its action.
 *
 * @param selected the locked marker and local ownership context, or empty on a miss
 * @param candidateCount the number of press-edge candidates supplied to the picker, before cone filtering
 */
@NotNullByDefault
public record FrozenCancellation(Optional<PingInteractionAction.CancelMarker> selected, int candidateCount) {

	/** Empty selection with no candidates. */
	public static final FrozenCancellation NONE = new FrozenCancellation(Optional.empty(), 0);

	/**
	 * Creates a selection result for one edge.
	 *
	 * @param selected the chosen marker, if any
	 * @param candidateCount the number of candidates checked by the picker
	 */
	public FrozenCancellation {
		Objects.requireNonNull(selected, "selected");
	}
}
