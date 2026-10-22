package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigUpdateTest {
	private static final ServerConfigSnapshot CURRENT = new ServerConfigSnapshot(
		true,
		ChannelMode.AUTO,
		true,
		1000,
		5,
		23);

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
		assertEquals(CURRENT.syncDuration(), merged.syncDuration());
	}

	@Test
	void syncDurationIsADirtyServerSettingAndIsClampedToTheSharedBounds() {
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
			ServerConfigBounds.MAX_PING_DURATION + 1);
		assertEquals(ServerConfigBounds.MAX_PING_DURATION, high.applyTo(CURRENT).syncDuration());

		var inRange = new ServerConfigUpdate(
			ServerConfigUpdate.SYNC_DURATION,
			ChannelMode.AUTO,
			true,
			1000,
			5,
			42);
		assertEquals(42, inRange.applyTo(CURRENT).syncDuration());
	}

	@Test
	void invalidMasksAndValuesAreRejected() {
		assertFalse(new ServerConfigUpdate(0, ChannelMode.AUTO, true, 0, 0).isValid());
		assertFalse(new ServerConfigUpdate(1 << 27, ChannelMode.AUTO, true, 0, 0).isValid());
		assertFalse(new ServerConfigUpdate(1, null, true, 0, 0).isValid());
		assertFalse(new ServerConfigUpdate(1, ChannelMode.AUTO, true, -1, 0).isValid());
	}

	@Test
	void noticeMaskIsAdditiveAndUnselectedFalseSurvivesOtherEdits() {
		assertEquals(1 << 26, ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED);
		assertEquals(((1 << 26) - 1) & ~31, ServerConfigUpdate.ALL_INVENTORY_FIELDS);
		assertEquals((1 << 27) - 1, ServerConfigUpdate.ALL_FIELDS);
		var current = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23,
			InventoryConfigValues.defaults(), false);
		var update = new ServerConfigUpdate(ServerConfigUpdate.RATE_LIMIT, ChannelMode.GLOBAL,
			false, 99, 17, 9, InventoryConfigValues.defaults(), true);

		var merged = update.appliedTo(current).orElseThrow();
		assertFalse(merged.budgetDelayNotificationsEnabled());
		assertEquals(17, merged.rateLimit());
		assertEquals(current.inventory(), merged.inventory());
		assertEquals(current.defaultChannelMode(), merged.defaultChannelMode());
		assertFalse(current.withCanEdit(false).budgetDelayNotificationsEnabled());
	}

	@Test
	void selectedFalseAndTrueApplyWithoutChangingAnyOtherLeaf() {
		var disable = ServerConfigUpdate.validated(ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED,
			ChannelMode.DISABLED, false, 99, 88, 44, InventoryConfigValues.defaults(), false).orElseThrow();
		var disabled = disable.appliedTo(CURRENT).orElseThrow();
		assertEquals(new ServerConfigSnapshot(CURRENT.canEdit(), CURRENT.defaultChannelMode(),
			CURRENT.playerTrackingEnabled(), CURRENT.msToRegenerate(), CURRENT.rateLimit(), CURRENT.syncDuration(),
			CURRENT.inventory(), false), disabled);
		var enable = new ServerConfigUpdate(ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED,
			ChannelMode.DISABLED, false, 99, 88, 44, InventoryConfigValues.defaults(), true);
		assertEquals(CURRENT, enable.appliedTo(disabled).orElseThrow());
	}

	@Test
	void everyCompatibilityConstructorAndValidatedOverloadDefaultsNoticesOn() {
		var inventory = InventoryConfigValues.defaults();
		for (var snapshot : List.of(
			new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1, 2),
			new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1, 2, 7),
			new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1, 2, 7, inventory))) {
			assertTrue(snapshot.budgetDelayNotificationsEnabled());
		}
		for (var update : List.of(
			new ServerConfigUpdate(1, ChannelMode.AUTO, true, 1, 2),
			new ServerConfigUpdate(1, ChannelMode.AUTO, true, 1, 2, 7),
			new ServerConfigUpdate(1, ChannelMode.AUTO, true, 1, 2, 7, inventory),
			ServerConfigUpdate.validated(1, ChannelMode.AUTO, true, 1, 2).orElseThrow(),
			ServerConfigUpdate.validated(1, ChannelMode.AUTO, true, 1, 2, 7).orElseThrow(),
			ServerConfigUpdate.validated(1, ChannelMode.AUTO, true, 1, 2, 7, inventory).orElseThrow())) {
			assertTrue(update.budgetDelayNotificationsEnabled());
		}
		var config = new ServerConfig();
		assertTrue(ServerConfigSnapshot.from(config, true).budgetDelayNotificationsEnabled());
		config.setBudgetDelayNotificationsEnabled(false);
		assertFalse(ServerConfigSnapshot.from(config, false).budgetDelayNotificationsEnabled());
	}
}
