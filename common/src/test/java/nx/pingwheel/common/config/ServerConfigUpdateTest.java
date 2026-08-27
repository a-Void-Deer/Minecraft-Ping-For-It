package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigUpdateTest {
	private static final ServerConfigSnapshot CURRENT = new ServerConfigSnapshot(
		true,
		ChannelMode.AUTO,
		true,
		1000,
		5);

	@Test
	void onlyDirtyFieldsAreMergedIntoTheAuthoritativeSnapshot() {
		var update = new ServerConfigUpdate(
			ServerConfigUpdate.DEFAULT_CHANNEL_MODE | ServerConfigUpdate.RATE_LIMIT,
			ChannelMode.GLOBAL,
			false,
			9999,
			12);

		var merged = update.applyTo(CURRENT);
		assertEquals(ChannelMode.GLOBAL, merged.defaultChannelMode());
		assertTrue(merged.playerTrackingEnabled());
		assertEquals(1000, merged.msToRegenerate());
		assertEquals(12, merged.rateLimit());
		assertEquals(7, merged.syncDuration());
	}

	@Test
	void syncDurationIsADirtyServerSettingAndIsClampedToTheExistingBounds() {
		var update = new ServerConfigUpdate(
			ServerConfigUpdate.SYNC_DURATION,
			ChannelMode.AUTO,
			true,
			1000,
			5,
			0);

		var merged = update.applyTo(CURRENT);
		assertEquals(ServerConfigBounds.MIN_PING_DURATION, merged.syncDuration());

		var high = new ServerConfigUpdate(
			ServerConfigUpdate.SYNC_DURATION,
			ChannelMode.AUTO,
			true,
			1000,
			5,
			61);
		assertEquals(ServerConfigBounds.MAX_PING_DURATION, high.applyTo(CURRENT).syncDuration());
	}

	@Test
	void invalidMasksAndValuesAreRejected() {
		assertFalse(new ServerConfigUpdate(0, ChannelMode.AUTO, true, 0, 0).isValid());
		assertFalse(new ServerConfigUpdate(1 << 8, ChannelMode.AUTO, true, 0, 0).isValid());
		assertFalse(new ServerConfigUpdate(1, null, true, 0, 0).isValid());
		assertFalse(new ServerConfigUpdate(1, ChannelMode.AUTO, true, -1, 0).isValid());
	}
}
