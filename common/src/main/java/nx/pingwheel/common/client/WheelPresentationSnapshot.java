package nx.pingwheel.common.client;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;

/**
 * Read-only presentation data for the currently visible wheel.
 *
 * <p>The snapshot deliberately exists only for {@link
 * PingInteractionPhase#WHEEL_OPEN}.  It carries the already-frozen capture,
 * not a fresh raycast or any network/display-name payload, so GUI rendering
 * cannot retarget the interaction or make presentation data authoritative.
 */
public record WheelPresentationSnapshot(
	CapturedPingContext context,
	List<PingType> pingTypes
) {
	public WheelPresentationSnapshot {
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(pingTypes, "pingTypes");
		pingTypes = List.copyOf(pingTypes);

		if (pingTypes.isEmpty()) {
			throw new IllegalArgumentException("pingTypes must not be empty");
		}
	}

	/**
	 * Creates a snapshot only when the supplied runtime state is visibly open
	 * and has a completed capture.  This pure seam keeps the visibility rule
	 * testable without constructing a Minecraft client.
	 */
	public static Optional<WheelPresentationSnapshot> visible(
		PingInteractionPhase phase,
		Optional<CapturedPingContext> context,
		List<PingType> pingTypes
	) {
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(pingTypes, "pingTypes");

		if (phase != PingInteractionPhase.WHEEL_OPEN || context.isEmpty() || pingTypes.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(new WheelPresentationSnapshot(context.get(), pingTypes));
	}
}
