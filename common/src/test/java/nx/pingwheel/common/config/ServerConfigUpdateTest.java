package nx.pingwheel.common.config;

import nx.pingwheel.common.diagnostics.LoggingProfile;
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
		assertFalse(new ServerConfigUpdate(1L << 32, ChannelMode.AUTO, true, 0, 0).isValid());
		assertFalse(new ServerConfigUpdate(1, null, true, 0, 0).isValid());
		assertFalse(new ServerConfigUpdate(1, ChannelMode.AUTO, true, -1, 0).isValid());
	}

	@Test
	void noticeMaskIsAdditiveAndUnselectedFalseSurvivesOtherEdits() {
		assertEquals(1 << 26, ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED);
		assertEquals((((1 << 26) - 1) & ~31) | (1 << 27) | (1 << 28), ServerConfigUpdate.ALL_INVENTORY_FIELDS);
		assertEquals(4294967295L, ServerConfigUpdate.ALL_FIELDS);
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
			assertFalse(snapshot.previewMonitorEnabled());
			assertFalse(snapshot.trackingMonitorEnabled());
		}
		for (var update : List.of(
			new ServerConfigUpdate(1, ChannelMode.AUTO, true, 1, 2),
			new ServerConfigUpdate(1, ChannelMode.AUTO, true, 1, 2, 7),
			new ServerConfigUpdate(1, ChannelMode.AUTO, true, 1, 2, 7, inventory),
			ServerConfigUpdate.validated(1, ChannelMode.AUTO, true, 1, 2).orElseThrow(),
			ServerConfigUpdate.validated(1, ChannelMode.AUTO, true, 1, 2, 7).orElseThrow(),
			ServerConfigUpdate.validated(1, ChannelMode.AUTO, true, 1, 2, 7, inventory).orElseThrow())) {
			assertTrue(update.budgetDelayNotificationsEnabled());
			assertFalse(update.previewMonitorEnabled());
			assertFalse(update.trackingMonitorEnabled());
		}
		var config = new ServerConfig();
		assertTrue(ServerConfigSnapshot.from(config, true).budgetDelayNotificationsEnabled());
		config.setBudgetDelayNotificationsEnabled(false);
		assertFalse(ServerConfigSnapshot.from(config, false).budgetDelayNotificationsEnabled());
	}

	@Test
	void monitorMasksMergeAllFourChoicesWithoutReplacingUnselectedAuthority() {
		assertEquals(1073741824L, ServerConfigUpdate.PREVIEW_MONITOR_ENABLED);
		assertEquals(2147483648L, ServerConfigUpdate.TRACKING_MONITOR_ENABLED);
		assertEquals(0L, ServerConfigUpdate.ALL_INVENTORY_FIELDS
			& (ServerConfigUpdate.PREVIEW_MONITOR_ENABLED | ServerConfigUpdate.TRACKING_MONITOR_ENABLED));
		var current = new ServerConfigSnapshot(true, ChannelMode.TEAM_ONLY, true, 1000, 19, 23,
			InventoryConfigValues.defaults(), false, LoggingProfile.DEBUG, true, true);
		for (boolean preview : new boolean[] {false, true}) {
			for (boolean tracking : new boolean[] {false, true}) {
				var update = new ServerConfigUpdate(ServerConfigUpdate.PREVIEW_MONITOR_ENABLED
					| ServerConfigUpdate.TRACKING_MONITOR_ENABLED, ChannelMode.DISABLED, false, 1, 2, 7,
					InventoryConfigValues.defaults(), true, LoggingProfile.NORMAL, preview, tracking);
				var merged = update.appliedTo(current).orElseThrow();
				assertEquals(preview, merged.previewMonitorEnabled());
				assertEquals(tracking, merged.trackingMonitorEnabled());
				assertEquals(LoggingProfile.DEBUG, merged.loggingProfile());
				assertEquals(current.rateLimit(), merged.rateLimit());
				assertEquals(current.inventory(), merged.inventory());
				assertFalse(ServerConfigUpdateService.apply(false, current, update).applied());
			}
		}
		var rateOnly = new ServerConfigUpdate(ServerConfigUpdate.RATE_LIMIT, ChannelMode.AUTO,
			false, 0, 29, 7, InventoryConfigValues.defaults(), true, LoggingProfile.NORMAL, false, false);
		var preserved = rateOnly.appliedTo(current).orElseThrow();
		assertTrue(preserved.previewMonitorEnabled());
		assertTrue(preserved.trackingMonitorEnabled());
		var previewOnly = new ServerConfigUpdate(ServerConfigUpdate.PREVIEW_MONITOR_ENABLED,
			ChannelMode.AUTO, false, 0, 0, 7, InventoryConfigValues.defaults(), true, LoggingProfile.NORMAL, false, false);
		assertFalse(previewOnly.appliedTo(current).orElseThrow().previewMonitorEnabled());
		assertTrue(previewOnly.appliedTo(current).orElseThrow().trackingMonitorEnabled());
		var trackingOnly = new ServerConfigUpdate(ServerConfigUpdate.TRACKING_MONITOR_ENABLED,
			ChannelMode.AUTO, false, 0, 0, 7, InventoryConfigValues.defaults(), true, LoggingProfile.NORMAL, false, false);
		assertTrue(trackingOnly.appliedTo(current).orElseThrow().previewMonitorEnabled());
		assertFalse(trackingOnly.appliedTo(current).orElseThrow().trackingMonitorEnabled());
	}

	@Test
	void monitorNormalizationUsesTheFinalMaskedProfileAndNeverRestoresAfterDowngrade() {
		var enable = ServerConfigUpdate.validated(ServerConfigUpdate.LOGGING_PROFILE
			| ServerConfigUpdate.PREVIEW_MONITOR_ENABLED | ServerConfigUpdate.TRACKING_MONITOR_ENABLED,
			ChannelMode.AUTO, true, 1, 2, 7, InventoryConfigValues.defaults(), true, LoggingProfile.DEBUG, true, true).orElseThrow();
		var enabled = enable.appliedTo(CURRENT).orElseThrow();
		assertTrue(enabled.previewMonitorEnabled());
		assertTrue(enabled.trackingMonitorEnabled());
		for (LoggingProfile profile : List.of(LoggingProfile.NORMAL, LoggingProfile.DEEP)) {
			var downgrade = new ServerConfigUpdate(ServerConfigUpdate.LOGGING_PROFILE, ChannelMode.AUTO,
				true, 1, 2, 7, InventoryConfigValues.defaults(), true, profile, true, true);
			var disabled = downgrade.appliedTo(enabled).orElseThrow();
			assertFalse(disabled.previewMonitorEnabled());
			assertFalse(disabled.trackingMonitorEnabled());
			var reenter = new ServerConfigUpdate(ServerConfigUpdate.LOGGING_PROFILE, ChannelMode.AUTO,
				true, 1, 2, 7, InventoryConfigValues.defaults(), true, LoggingProfile.DEBUG, true, true);
			var notRestored = reenter.appliedTo(disabled).orElseThrow();
			assertFalse(notRestored.previewMonitorEnabled());
			assertFalse(notRestored.trackingMonitorEnabled());
		}
		var normalEnable = new ServerConfigUpdate(ServerConfigUpdate.PREVIEW_MONITOR_ENABLED,
			ChannelMode.AUTO, true, 1, 2, 7, InventoryConfigValues.defaults(), true, LoggingProfile.DEBUG, true, true);
		assertFalse(normalEnable.appliedTo(CURRENT).orElseThrow().previewMonitorEnabled());
		var unsafe = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1, 2, 7,
			InventoryConfigValues.defaults(), true, LoggingProfile.NORMAL, true, false);
		assertFalse(unsafe.isSafe());
		assertFalse(enable.appliedTo(unsafe).isPresent());
	}

	@Test
	void inventoryPairFailureRejectsMonitorSelectionAtomicallyAndConversionsRetainFlags() {
		var config = new ServerConfig();
		assertFalse(config.isPreviewMonitorEnabled());
		assertFalse(config.isTrackingMonitorEnabled());
		config.setLoggingProfile(LoggingProfile.DEBUG);
		config.setPreviewMonitorEnabled(true);
		config.setTrackingMonitorEnabled(true);
		var snapshot = ServerConfigSnapshot.from(config, true);
		assertTrue(snapshot.withCanEdit(false).previewMonitorEnabled());
		assertTrue(snapshot.withCanEdit(false).trackingMonitorEnabled());
		var conflict = snapshot.inventory().with(InventoryConfigValues.Field.DISPLAY_BYTE_MULTIPLIER,
			new InventoryConfigValues.Value(false, new java.math.BigDecimal("32")));
		var update = new ServerConfigUpdate(ServerConfigUpdate.TRACKING_MONITOR_ENABLED
			| ServerConfigUpdate.INVENTORY_DISPLAY_BYTE_MULTIPLIER, ChannelMode.AUTO, true, 1, 2, 7,
			conflict, true, LoggingProfile.DEBUG, false, false);
		assertTrue(update.isValid());
		assertEquals(snapshot, ServerConfigUpdateService.apply(true, snapshot, update).snapshot());
		assertFalse(new ServerConfigUpdate(1L << 32, ChannelMode.AUTO, true, 1, 2, 7).isValid());
		assertFalse(new ServerConfigUpdate(-1L, ChannelMode.AUTO, true, 1, 2, 7).isValid());
	}
}
