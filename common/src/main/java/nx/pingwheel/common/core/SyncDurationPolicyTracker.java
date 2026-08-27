package nx.pingwheel.common.core;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks connection-scoped duration-policy delivery without depending on a
 * loader or a live Minecraft server.
 */
final class SyncDurationPolicyTracker {

	private final Set<UUID> initialSyncSent = new HashSet<>();
	private Integer lastBroadcastDuration;

	/**
	 * Claims the initial policy delivery for one player connection. A repeated
	 * channel update for the same connection must not resend this policy.
	 */
	boolean claimInitialSync(UUID playerId) {
		return initialSyncSent.add(Objects.requireNonNull(playerId, "playerId"));
	}

	/** Returns whether the effective broadcast value differs from the last one. */
	boolean needsBroadcast(int duration) {
		return lastBroadcastDuration == null || lastBroadcastDuration != duration;
	}

	/** Records a policy that was delivered to the active server's audience. */
	void recordBroadcast(int duration) {
		lastBroadcastDuration = duration;
	}

	/** Forgets one connection after its player leaves the server. */
	void forget(UUID playerId) {
		initialSyncSent.remove(Objects.requireNonNull(playerId, "playerId"));
	}

	/** Resets all state when the server instance is reset or replaced. */
	void reset() {
		initialSyncSent.clear();
		lastBroadcastDuration = null;
	}
}
