package nx.pingwheel.common.integration;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.UUID;

public class FTBTeamsWrapper {
	private FTBTeamsWrapper() {}

	public static Optional<UUID> getTeamId(Player player) {
		if (!ModContext.HasFTBTeams || !FTBTeamsAPI.api().isManagerLoaded()) return Optional.empty();

		final var teamManager = FTBTeamsAPI.api().getManager();
		final var ftbTeam = teamManager.getTeamForPlayerID(player.getUUID()).orElse(null);
		if (ftbTeam == null || ftbTeam.isPlayerTeam()) return Optional.empty();

		return Optional.of(ftbTeam.getId());
	}

	public static Optional<UUID> getSelfTeamId() {
		if (!ModContext.HasFTBTeams || !FTBTeamsAPI.api().isClientManagerLoaded()) return Optional.empty();

		final var teamManager = FTBTeamsAPI.api().getClientManager();
		final var ftbTeam = teamManager.selfTeam();
		if (ftbTeam == null || ftbTeam.isPlayerTeam()) return Optional.empty();

		return Optional.of(ftbTeam.getId());
	}
}
