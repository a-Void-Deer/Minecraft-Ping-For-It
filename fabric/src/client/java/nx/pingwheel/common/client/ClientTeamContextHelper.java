package nx.pingwheel.common.client;

import nx.pingwheel.common.integration.FTBTeamsWrapper;
import nx.pingwheel.common.integration.TeamContext;
import nx.pingwheel.common.integration.VoiceChatWrapper;

import static nx.pingwheel.common.CommonClient.Game;

/**
 * Client-side half of {@code TeamContextHandler}.
 *
 * <p>Resolving the "self" team context needs the local player, which only exists
 * on the client. Extracted here so that {@code TeamContextHandler} itself can
 * live in the common (main) source set and stay usable by the server.</p>
 */
public final class ClientTeamContextHelper {

	private ClientTeamContextHelper() {}

	public static TeamContext getSelfContext() {
		final var voiceChatId = VoiceChatWrapper.getSelfGroupId();
		if (voiceChatId.isPresent()) return TeamContext.VOICE_CHAT;

		final var ftbTeamId = FTBTeamsWrapper.getSelfTeamId();
		if (ftbTeamId.isPresent()) return TeamContext.FTB_TEAMS;

		if (Game.player != null && Game.player.getTeam() != null) return TeamContext.VANILLA_TEAM;

		return TeamContext.NONE;
	}
}
