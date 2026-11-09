package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Monitor edits are draft-only, independently selected, and fenced by the settings lifecycle. */
@NotNullByDefault
class ServerSettingsMonitorTest {
	@Test
	void eachMonitorHasAnIndependentLongDirtyBitAndToggleBackIsClean() {
		var model = loaded(true, LoggingProfile.DEBUG, false, false);
		model.setPreviewMonitorEnabled(true);
		assertEquals(1073741824L, model.dirtyFields());
		assertTrue(model.updatePlan().orElseThrow().previewMonitorEnabled());
		assertFalse(model.authoritative().previewMonitorEnabled());
		model.setPreviewMonitorEnabled(false);
		assertFalse(model.dirty());
		model.setTrackingMonitorEnabled(true);
		assertEquals(2147483648L, model.dirtyFields());
		assertTrue(model.updatePlan().orElseThrow().trackingMonitorEnabled());
		model.setPreviewMonitorEnabled(true);
		model.setRateLimitText("19");
		assertEquals(3221225480L, model.dirtyFields());
		assertEquals(0, model.invalidFieldMask());
		var plan = model.updatePlan().orElseThrow();
		assertTrue(plan.previewMonitorEnabled());
		assertTrue(plan.trackingMonitorEnabled());
		assertEquals(19, plan.rateLimit());
		model.setTrackingMonitorEnabled(false);
		model.setPreviewMonitorEnabled(false);
		model.setRateLimitText("5");
		assertFalse(model.dirty());
		assertTrue(model.updatePlan().isEmpty());
	}

	@Test
	void leavingDebugClearsOnlyDraftMonitorsAndReentryDoesNotRestoreThem() {
		LoggingProfile runtime = Diagnostics.profile(DiagnosticSide.SERVER);
		var model = loaded(true, LoggingProfile.DEBUG, true, true);
		model.setLoggingProfile(LoggingProfile.DEEP);
		assertFalse(model.previewMonitorEnabled());
		assertFalse(model.trackingMonitorEnabled());
		assertTrue(model.authoritative().previewMonitorEnabled());
		assertTrue(model.authoritative().trackingMonitorEnabled());
		assertEquals(runtime, Diagnostics.profile(DiagnosticSide.SERVER));
		assertEquals(ServerConfigUpdate.LOGGING_PROFILE | ServerConfigUpdate.PREVIEW_MONITOR_ENABLED
			| ServerConfigUpdate.TRACKING_MONITOR_ENABLED, model.dirtyFields());
		model.setPreviewMonitorEnabled(true);
		model.setTrackingMonitorEnabled(true);
		assertFalse(model.previewMonitorEnabled());
		assertFalse(model.trackingMonitorEnabled());
		model.setLoggingProfile(LoggingProfile.DEBUG);
		assertFalse(model.previewMonitorEnabled());
		assertFalse(model.trackingMonitorEnabled());
		assertEquals(ServerConfigUpdate.PREVIEW_MONITOR_ENABLED | ServerConfigUpdate.TRACKING_MONITOR_ENABLED,
			model.updatePlan().orElseThrow().changedFields());
		model.setPreviewMonitorEnabled(true);
		model.setTrackingMonitorEnabled(true);
		assertFalse(model.dirty());
	}

	@Test
	void enablingDebugAndAMonitorInOnePlanUsesTheFinalProfile() {
		var model = loaded(true, LoggingProfile.NORMAL, false, false);
		model.setPreviewMonitorEnabled(true);
		assertFalse(model.dirty());
		model.setLoggingProfile(LoggingProfile.DEBUG);
		model.setTrackingMonitorEnabled(true);
		var plan = model.updatePlan().orElseThrow();
		assertEquals(ServerConfigUpdate.LOGGING_PROFILE | ServerConfigUpdate.TRACKING_MONITOR_ENABLED,
			plan.changedFields());
		assertTrue(plan.appliedTo(model.authoritative()).orElseThrow().trackingMonitorEnabled());
		assertFalse(model.authoritative().trackingMonitorEnabled());
	}

	@Test
	void readOnlyRevocationStaleResponsesAndDisconnectFenceMonitorDrafts() {
		var readOnly = loaded(false, LoggingProfile.DEBUG, true, false);
		readOnly.setPreviewMonitorEnabled(false);
		readOnly.setTrackingMonitorEnabled(true);
		readOnly.setLoggingProfile(LoggingProfile.NORMAL);
		assertTrue(readOnly.previewMonitorEnabled());
		assertFalse(readOnly.trackingMonitorEnabled());
		assertEquals(LoggingProfile.DEBUG, readOnly.loggingProfile());
		assertFalse(readOnly.dirty());
		assertTrue(readOnly.updatePlan().isEmpty());

		var model = loaded(true, LoggingProfile.DEBUG, true, false);
		model.setTrackingMonitorEnabled(true);
		model.setClientPermission(false);
		assertTrue(model.canView());
		assertFalse(model.canEdit());
		assertTrue(model.previewMonitorEnabled());
		assertFalse(model.trackingMonitorEnabled());
		model.setPreviewMonitorEnabled(false);
		assertTrue(model.previewMonitorEnabled());
		assertFalse(model.dirty());
		model.setClientPermission(true);
		model.setTrackingMonitorEnabled(true);
		assertTrue(model.dirty());
		assertFalse(model.applySnapshot(1L, snapshot(true, LoggingProfile.NORMAL, false, false)));
		assertTrue(model.trackingMonitorEnabled());
		model.resetForDisconnect();
		assertFalse(model.previewMonitorEnabled());
		assertFalse(model.trackingMonitorEnabled());
		assertFalse(model.dirty());
		assertTrue(model.updatePlan().isEmpty());
	}

	@Test
	void monitorDirtyBitsDoNotNarrowNumericInvalidMaskOrPermitPartialPlans() {
		var model = loaded(true, LoggingProfile.DEBUG, false, false);
		model.setTrackingMonitorEnabled(true);
		model.setRateLimitText("invalid");
		assertEquals(2147483656L, model.dirtyFields());
		assertEquals(ServerConfigUpdate.RATE_LIMIT, model.invalidFieldMask());
		assertTrue(model.updatePlan().isEmpty());
		model.setRateLimitText("5");
		assertEquals(2147483648L, model.updatePlan().orElseThrow().changedFields());
		model.markClean();
		assertTrue(model.trackingMonitorEnabled());
		assertFalse(model.dirty());
		assertTrue(model.updatePlan().isEmpty());
	}

	private static ServerSettingsModel loaded(boolean editable, LoggingProfile profile, boolean preview, boolean tracking) {
		var model = new ServerSettingsModel(editable);
		long request = model.beginExpansion();
		assertTrue(model.applySnapshot(request, snapshot(editable, profile, preview, tracking)));
		return model;
	}

	private static ServerConfigSnapshot snapshot(boolean editable, LoggingProfile profile, boolean preview, boolean tracking) {
		return new ServerConfigSnapshot(editable, ChannelMode.AUTO, true, 1000, 5, 23,
			InventoryConfigValues.defaults(), true, profile, preview, tracking);
	}
}
