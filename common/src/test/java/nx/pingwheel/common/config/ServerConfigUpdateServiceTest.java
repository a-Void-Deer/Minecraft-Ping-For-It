package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
		assertEquals(7, result.snapshot().syncDuration());
	}

	@Test
	void levelThreePermissionCanApplySyncDurationWithoutChangingOtherFields() {
		var update = new ServerConfigUpdate(
			ServerConfigUpdate.SYNC_DURATION,
			ChannelMode.DISABLED,
			false,
			9999,
			99,
			23);

		var result = ServerConfigUpdateService.apply(true, CURRENT, update);
		assertTrue(result.applied());
		assertEquals(23, result.snapshot().syncDuration());
		assertEquals(ChannelMode.AUTO, result.snapshot().defaultChannelMode());
		assertTrue(result.snapshot().playerTrackingEnabled());
		assertEquals(1000, result.snapshot().msToRegenerate());
		assertEquals(5, result.snapshot().rateLimit());
	}

	@Test
	void noticeEditUsesExistingAuthorityAndDenialCopyPreservesExplicitFalse() {
		var update = new ServerConfigUpdate(ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED,
			ChannelMode.AUTO, true, 1000, 5, 7, CURRENT.inventory(), false);
		var denied = ServerConfigUpdateService.apply(false, CURRENT, update);
		assertFalse(denied.applied());
		assertTrue(denied.snapshot().budgetDelayNotificationsEnabled());
		var applied = ServerConfigUpdateService.apply(true, CURRENT, update);
		assertTrue(applied.applied());
		assertFalse(applied.snapshot().budgetDelayNotificationsEnabled());
		var deniedEnable = ServerConfigUpdateService.apply(false, applied.snapshot(),
			new ServerConfigUpdate(ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED,
				ChannelMode.AUTO, true, 1000, 5, 7, CURRENT.inventory(), true));
		assertFalse(deniedEnable.applied());
		assertFalse(deniedEnable.snapshot().budgetDelayNotificationsEnabled());
		assertFalse(deniedEnable.snapshot().canEdit());
	}

	@Test
	void mixedNoticeAndSizePairConflictRejectsAllAssignmentsAtomically() {
		var currentInventory = CURRENT.inventory()
			.with(InventoryConfigValues.Field.DISPLAY_BYTE_MULTIPLIER,
				new InventoryConfigValues.Value(false, new BigDecimal("2")));
		var current = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 7, currentInventory, true);
		var replacement = currentInventory.with(InventoryConfigValues.Field.FRAME_BYTE_MULTIPLIER,
			new InventoryConfigValues.Value(false, new BigDecimal("0.25")));
		var update = new ServerConfigUpdate(ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED
			| ServerConfigUpdate.RATE_LIMIT | ServerConfigUpdate.INVENTORY_FRAME_BYTE_MULTIPLIER,
			ChannelMode.AUTO, true, 1000, 99, 7, replacement, false);

		var result = ServerConfigUpdateService.apply(true, current, update);
		assertFalse(result.applied());
		assertEquals(current, result.snapshot());
		var validPair = replacement.with(InventoryConfigValues.Field.DISPLAY_BYTE_MULTIPLIER,
			new InventoryConfigValues.Value(false, new BigDecimal("1")));
		var valid = new ServerConfigUpdate(update.changedFields() | ServerConfigUpdate.INVENTORY_DISPLAY_BYTE_MULTIPLIER,
			ChannelMode.AUTO, true, 1000, 99, 7, validPair, false);
		var applied = ServerConfigUpdateService.apply(true, current, valid);
		assertTrue(applied.applied());
		assertFalse(applied.snapshot().budgetDelayNotificationsEnabled());
		assertEquals(99, applied.snapshot().rateLimit());
		assertEquals(validPair, applied.snapshot().inventory());
	}
}
