package nx.pingwheel.common.integration;

import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Stub for the optional FTB Teams integration.
 *
 * <p>TODO(M9): restore the real adapter. Returning empty degrades the team
 * context to the vanilla scoreboard team, which is the documented behaviour when
 * FTB Teams is absent.</p>
 */
public class FTBTeamsWrapper {
	private FTBTeamsWrapper() {}

	public static Optional<UUID> getTeamId(Player player) {
		return Optional.empty();
	}

	public static Optional<UUID> getSelfTeamId() {
		return Optional.empty();
	}
}
