package nx.pingwheel.common.integration;

import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Stub for the optional FTB Teams integration.
 *
 * <p>Platform-forced skip: FTB Teams has no Minecraft 26.3 build (its 26.x line
 * stops at 26.1.2.4), so this stays a permanent no-op adapter — final form, not a
 * pending port; see {@code docs/reviews/24-fidelity-constraint-audit.md} #17.
 * Returning empty degrades the team context to the vanilla scoreboard team, which
 * is the documented behaviour when FTB Teams is absent.</p>
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
