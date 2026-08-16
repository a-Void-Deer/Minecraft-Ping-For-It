package nx.pingwheel.common.integration;

import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.UUID;

public class VoiceChatWrapper {
	private VoiceChatWrapper() {}

	private static final IntegrationLinkGuard LINK_GUARD = new IntegrationLinkGuard("voicechat");

	public static Optional<UUID> getGroupId(Player player) {
		if (!ModContext.HasVoiceChat || LINK_GUARD.disabled() || VoiceChatIntegration.serverApi == null) return Optional.empty();

		try {
			var voiceChatConnection = VoiceChatIntegration.serverApi.getConnectionOf(player.getUUID());
			if (voiceChatConnection == null) return Optional.empty();

			var voiceChatGroup = voiceChatConnection.getGroup();
			if (voiceChatGroup == null) return Optional.empty();

			return Optional.of(voiceChatGroup.getId());
		} catch (LinkageError error) {
			LINK_GUARD.disable(error);
			return Optional.empty();
		}
	}

	public static Optional<UUID> getSelfGroupId() {
		if (!ModContext.HasVoiceChat || LINK_GUARD.disabled() || VoiceChatIntegration.clientApi == null) return Optional.empty();

		try {
			var voiceChatGroup = VoiceChatIntegration.clientApi.getGroup();
			if (voiceChatGroup == null) return Optional.empty();

			return Optional.of(voiceChatGroup.getId());
		} catch (LinkageError error) {
			LINK_GUARD.disable(error);
			return Optional.empty();
		}
	}
}
