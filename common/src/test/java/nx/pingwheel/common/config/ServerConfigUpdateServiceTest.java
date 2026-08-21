package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigUpdateServiceTest {
	private static final ServerConfigSnapshot CURRENT = new ServerConfigSnapshot(
		true,
		ChannelMode.AUTO,
		true,
		1000,
		5);

	@Test
	void levelTwoPermissionIsRejectedWithoutMutation() {
		var update = new ServerConfigUpdate(
			ServerConfigUpdate.RATE_LIMIT,
			ChannelMode.AUTO,
			true,
			1000,
			99);

		var result = ServerConfigUpdateService.apply(false, CURRENT, update);
		assertFalse(result.applied());
		assertFalse(result.snapshot().canEdit());
		assertEquals(5, result.snapshot().rateLimit());
	}

	@Test
	void levelThreePermissionAppliesOnlyDirtyFields() {
		var update = new ServerConfigUpdate(
			ServerConfigUpdate.RATE_LIMIT,
			ChannelMode.AUTO,
			false,
			9999,
			99);

		var result = ServerConfigUpdateService.apply(true, CURRENT, update);
		assertTrue(result.applied());
		assertTrue(result.snapshot().canEdit());
		assertEquals(ChannelMode.AUTO, result.snapshot().defaultChannelMode());
		assertTrue(result.snapshot().playerTrackingEnabled());
		assertEquals(1000, result.snapshot().msToRegenerate());
		assertEquals(99, result.snapshot().rateLimit());
	}
}
