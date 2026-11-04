package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class ServerSettingsLoggingProfileTest {
	private static ServerConfigSnapshot snapshot(boolean canEdit, LoggingProfile profile) {
		return new ServerConfigSnapshot(
			canEdit,
			ChannelMode.AUTO,
			true,
			1000,
			5,
			7,
			InventoryConfigValues.defaults(),
			true,
			profile);
	}

	@Test
	void editableDraftCarriesTheAuthoritativeProfileAndTracksOnlyItsOwnDirtyBit() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		assertTrue(model.applySnapshot(requestId, snapshot(true, LoggingProfile.DEEP)));
		assertEquals(LoggingProfile.DEEP, model.loggingProfile());
		assertFalse(model.dirty());

		model.setLoggingProfile(LoggingProfile.DEBUG);

		assertEquals(LoggingProfile.DEBUG, model.loggingProfile());
		assertEquals(ServerConfigUpdate.LOGGING_PROFILE, model.dirtyFields());
		assertEquals(0, model.invalidFieldMask());
		var plan = model.updatePlan().orElseThrow();
		assertEquals(ServerConfigUpdate.LOGGING_PROFILE, plan.changedFields());
		assertEquals(LoggingProfile.DEBUG, plan.loggingProfile());

		model.setLoggingProfile(LoggingProfile.DEEP);
		assertFalse(model.dirty());
		assertTrue(model.updatePlan().isEmpty());
	}

	@Test
	void readOnlyViewerSeesADebugAuthoritativeValueButCannotEditOrPlanIt() {
		var model = new ServerSettingsModel(false);
		long requestId = model.beginExpansion();
		assertTrue(model.applySnapshot(requestId, snapshot(false, LoggingProfile.DEBUG)));

		assertTrue(model.canView());
		assertFalse(model.canEdit());
		assertEquals(LoggingProfile.DEBUG, model.loggingProfile());

		model.setLoggingProfile(LoggingProfile.NORMAL);

		assertEquals(LoggingProfile.DEBUG, model.loggingProfile());
		assertFalse(model.dirty());
		assertTrue(model.updatePlan().isEmpty());
	}

	@Test
	void otherEditsPreserveTheDraftProfileAndDisconnectRestoresTheModelDefault() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		assertTrue(model.applySnapshot(requestId, snapshot(true, LoggingProfile.NORMAL)));
		model.setLoggingProfile(LoggingProfile.DEEP);
		model.setRateLimitText("9");

		var plan = model.updatePlan().orElseThrow();
		assertEquals(ServerConfigUpdate.RATE_LIMIT | ServerConfigUpdate.LOGGING_PROFILE, plan.changedFields());
		assertEquals(LoggingProfile.DEEP, plan.loggingProfile());
		assertEquals(9, plan.rateLimit());

		model.resetForDisconnect();

		assertEquals(LoggingProfile.NORMAL, model.loggingProfile());
		assertFalse(model.dirty());
		assertTrue(model.updatePlan().isEmpty());
	}

	@Test
	void aSnapshotRefreshReplacesTheProfileDraftAndClearsItsDirtyBit() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		assertTrue(model.applySnapshot(requestId, snapshot(true, LoggingProfile.NORMAL)));
		model.setLoggingProfile(LoggingProfile.DEBUG);
		assertTrue(model.dirty());

		model.collapseAndDiscard();
		long refresh = model.beginExpansion();
		assertTrue(model.applySnapshot(refresh, snapshot(true, LoggingProfile.DEEP)));

		assertEquals(LoggingProfile.DEEP, model.loggingProfile());
		assertFalse(model.dirty());
	}
}
