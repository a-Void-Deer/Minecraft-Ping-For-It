package nx.pingwheel.common.client.marker;

import nx.pingwheel.common.client.duration.ClientMarkerDisplayDuration;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
public final class ClientMarkerFixtures {

	private ClientMarkerFixtures() {}

	public static ClientMarkerStore localStore(long graceTicks, long displayTicks) {
		var duration = ClientMarkerDisplayDuration.customTicks(displayTicks);
		return new ClientMarkerStore(graceTicks, new ObservedServerClock(), ignored -> duration);
	}

	/** A local-tick fixture whose visuals have the same span as each snapshot. */
	public static ClientMarkerStore localSnapshotSpanStore(long graceTicks) {
		return new ClientMarkerStore(graceTicks, new ObservedServerClock(),
			snapshot -> ClientMarkerDisplayDuration.customTicks(snapshot.expiresAtTick() - snapshot.arrivalTick()));
	}
}
