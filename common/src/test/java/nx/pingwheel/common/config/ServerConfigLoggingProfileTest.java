package nx.pingwheel.common.config;

import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Logging profile is one independent remote leaf with strict full-value validation. */
@NotNullByDefault
class ServerConfigLoggingProfileTest {
	@ParameterizedTest
	@EnumSource(LoggingProfile.class)
	void selectedProfileChangesOnlyItsLeafAndDeniedEditPreservesAuthority(LoggingProfile profile) {
		var current = current();
		var update = ServerConfigUpdate.validated(ServerConfigUpdate.LOGGING_PROFILE,
			ChannelMode.DISABLED, false, 99, 88, 7, InventoryConfigValues.defaults(), true, profile).orElseThrow();
		var applied = ServerConfigUpdateService.apply(true, current, update);
		assertTrue(applied.applied());
		assertEquals(new ServerConfigSnapshot(true, current.defaultChannelMode(), current.playerTrackingEnabled(),
			current.msToRegenerate(), current.rateLimit(), current.syncDuration(), current.inventory(), false, profile),
			applied.snapshot());
		var denied = ServerConfigUpdateService.apply(false, current, update);
		assertFalse(denied.applied());
		assertEquals(current.withCanEdit(false), denied.snapshot());
	}

	@Test
	void unselectedProfileSurvivesOtherEditsAndNullIsInvalidEvenWhenUnselected() {
		var current = current();
		var rateOnly = new ServerConfigUpdate(ServerConfigUpdate.RATE_LIMIT,
			ChannelMode.AUTO, true, 1, 19, 7, InventoryConfigValues.defaults(), true, LoggingProfile.NORMAL);
		var merged = rateOnly.appliedTo(current).orElseThrow();
		assertEquals(19, merged.rateLimit());
		assertEquals(LoggingProfile.DEBUG, merged.loggingProfile());
		assertEquals(current.inventory(), merged.inventory());
		for (int mask : new int[] {ServerConfigUpdate.RATE_LIMIT, ServerConfigUpdate.LOGGING_PROFILE}) {
			var invalid = new ServerConfigUpdate(mask, ChannelMode.AUTO, true, 1, 19, 7, current.inventory(), true, null);
			assertFalse(invalid.isValid());
			assertEquals(current, ServerConfigUpdateService.apply(true, current, invalid).snapshot());
			assertTrue(ServerConfigUpdate.validated(mask, ChannelMode.AUTO, true, 1, 19, 7,
				current.inventory(), true, null).isEmpty());
		}
		var unsafe = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1, 2, 7,
			current.inventory(), true, null);
		assertFalse(unsafe.isSafe());
		assertFalse(ServerConfigUpdateService.apply(true, unsafe, rateOnly).applied());
	}

	@Test
	void conflictingInventoryPairRejectsTheSelectedProfileAtomically() {
		var current = current();
		var replacement = current.inventory().with(InventoryConfigValues.Field.DISPLAY_BYTE_MULTIPLIER,
			new InventoryConfigValues.Value(false, new BigDecimal("32")));
		var update = new ServerConfigUpdate(ServerConfigUpdate.LOGGING_PROFILE
			| ServerConfigUpdate.INVENTORY_DISPLAY_BYTE_MULTIPLIER, ChannelMode.AUTO, true,
			1, 2, 7, replacement, true, LoggingProfile.DEEP);
		assertTrue(update.isValid());
		var rejected = ServerConfigUpdateService.apply(true, current, update);
		assertFalse(rejected.applied());
		assertEquals(current, rejected.snapshot());
	}

	@Test
	void sourceConversionsAndPermissionCopiesCarryTheProfileAndOldConstructorsUseNormal() {
		var config = new ServerConfig();
		config.setLoggingProfile(LoggingProfile.DEEP);
		var snapshot = ServerConfigSnapshot.from(config, true);
		assertEquals(LoggingProfile.DEEP, snapshot.loggingProfile());
		assertEquals(LoggingProfile.DEEP, snapshot.withCanEdit(false).loggingProfile());
		var inventory = InventoryConfigValues.defaults();
		for (var previous : List.of(
			new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1, 2),
			new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1, 2, 7),
			new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1, 2, 7, inventory),
			new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1, 2, 7, inventory, false))) {
			assertEquals(LoggingProfile.NORMAL, previous.loggingProfile());
		}
		for (var previous : List.of(
			new ServerConfigUpdate(1, ChannelMode.AUTO, true, 1, 2),
			new ServerConfigUpdate(1, ChannelMode.AUTO, true, 1, 2, 7),
			new ServerConfigUpdate(1, ChannelMode.AUTO, true, 1, 2, 7, inventory),
			new ServerConfigUpdate(1, ChannelMode.AUTO, true, 1, 2, 7, inventory, false),
			ServerConfigUpdate.validated(1, ChannelMode.AUTO, true, 1, 2).orElseThrow(),
			ServerConfigUpdate.validated(1, ChannelMode.AUTO, true, 1, 2, 7).orElseThrow(),
			ServerConfigUpdate.validated(1, ChannelMode.AUTO, true, 1, 2, 7, inventory).orElseThrow(),
			ServerConfigUpdate.validated(1, ChannelMode.AUTO, true, 1, 2, 7, inventory, false).orElseThrow())) {
			assertEquals(LoggingProfile.NORMAL, previous.loggingProfile());
		}
		assertEquals(1 << 29, ServerConfigUpdate.LOGGING_PROFILE);
		assertEquals(30, Integer.bitCount(ServerConfigUpdate.ALL_FIELDS));
		assertEquals(0, ServerConfigUpdate.ALL_INVENTORY_FIELDS & ServerConfigUpdate.LOGGING_PROFILE);
	}

	private static ServerConfigSnapshot current() {
		return new ServerConfigSnapshot(true, ChannelMode.TEAM_ONLY, true, 1000, 5, 23,
			InventoryConfigValues.defaults(), false, LoggingProfile.DEBUG);
	}
}
