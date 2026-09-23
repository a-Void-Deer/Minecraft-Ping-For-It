package nx.pingwheel.common.interaction.state;

import java.util.Objects;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.interaction.CapturedPingContext;

/**
 * The single outcome emitted by {@link PingInteractionStateMachine#update}.
 *
 * <p>Exactly one action is emitted per interaction at most: either a marker
 * creation, a marker cancellation, or a target-gone error. "No action" is
 * represented by an empty {@link java.util.Optional}, never by a dedicated
 * {@code NoAction} value, so callers always know an empty result means "nothing
 * to do" rather than "an action happened with no payload".
 */
public sealed interface PingInteractionAction {

	/**
	 * Create a marker for the frozen capture using {@code pingType}.
	 */
	record CreatePing(CapturedPingContext context, PingType pingType) implements PingInteractionAction {

		public CreatePing {
			Objects.requireNonNull(context, "context");
			Objects.requireNonNull(pingType, "pingType");
		}
	}

	/**
	 * Request cancellation of the marker identified by {@code markerId}.
	 */
	record CancelMarker(MarkerId markerId) implements PingInteractionAction {

		public CancelMarker {
			Objects.requireNonNull(markerId, "markerId");
		}
	}

	/**
	 * The captured target failed validation and no marker must be created.
	 */
	record TargetGone(CapturedPingContext context, TargetGoneReason reason) implements PingInteractionAction {

		/**
		 * The language-resource key of the localized message shown to the local
		 * player when a captured target disappears, dies, changes dimension, or
		 * is replaced. The Minecraft chat boundary resolves it after the
		 * resource-supplied {@code [ping for it]} marker, so these interaction
		 * records stay free of text components.
		 */
		public static final String TARGET_GONE_MESSAGE_KEY = "pingforit.chat.target_gone";

		/**
		 * The light-red 24-bit RGB color used for the target-gone error.
		 */
		public static final int TARGET_GONE_COLOR = 0xFF5555;

		public TargetGone {
			Objects.requireNonNull(context, "context");
			Objects.requireNonNull(reason, "reason");
		}
	}
}
