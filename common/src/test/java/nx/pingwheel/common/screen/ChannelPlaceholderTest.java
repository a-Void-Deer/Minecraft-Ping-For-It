package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.ChannelMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@NotNullByDefault
class ChannelPlaceholderTest {
	@Test
	void globalModeDescribesTheEmptyChannelAsGlobalForEveryPlayer() {
		assertEquals(ChannelPlaceholder.Kind.GLOBAL,
			ChannelPlaceholder.resolve(ChannelMode.GLOBAL, true, false));
		assertEquals(ChannelPlaceholder.Kind.GLOBAL,
			ChannelPlaceholder.resolve(ChannelMode.GLOBAL, true, true));
	}

	@Test
	void autoModeOnlyClaimsTeamSynchronizationWhenThePlayerHasATeam() {
		assertEquals(ChannelPlaceholder.Kind.TEAM_SYNCED,
			ChannelPlaceholder.resolve(ChannelMode.AUTO, true, true));
		assertEquals(ChannelPlaceholder.Kind.NONE,
			ChannelPlaceholder.resolve(ChannelMode.AUTO, true, false),
			"an empty AUTO channel without a team is not the global audience");
	}

	@Test
	void teamOnlyAndDisabledDoNotClaimAnotherAudience() {
		assertEquals(ChannelPlaceholder.Kind.NONE,
			ChannelPlaceholder.resolve(ChannelMode.TEAM_ONLY, true, true));
		assertEquals(ChannelPlaceholder.Kind.NONE,
			ChannelPlaceholder.resolve(ChannelMode.TEAM_ONLY, true, false));
		assertEquals(ChannelPlaceholder.Kind.NONE,
			ChannelPlaceholder.resolve(ChannelMode.DISABLED, true, true));
		assertEquals(ChannelPlaceholder.Kind.NONE,
			ChannelPlaceholder.resolve(ChannelMode.DISABLED, true, false));
	}

	@Test
	void noReceivedAuthorityOrNoCurrentConnectionProducesNoHint() {
		assertEquals(ChannelPlaceholder.Kind.NONE, ChannelPlaceholder.resolve(null, true, true));
		assertEquals(ChannelPlaceholder.Kind.NONE, ChannelPlaceholder.resolve(null, true, false));
		assertEquals(ChannelPlaceholder.Kind.NONE,
			ChannelPlaceholder.resolve(ChannelMode.GLOBAL, false, true));
		assertEquals(ChannelPlaceholder.Kind.NONE,
			ChannelPlaceholder.resolve(ChannelMode.AUTO, false, true));
	}
}
