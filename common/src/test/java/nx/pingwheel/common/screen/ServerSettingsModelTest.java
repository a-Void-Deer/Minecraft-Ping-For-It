package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerSettingsModelTest {
	private static final ServerConfigSnapshot EDITABLE = new ServerConfigSnapshot(
		true,
		ChannelMode.AUTO,
		true,
		1000,
		5);
	private static final ServerConfigSnapshot READ_ONLY = EDITABLE.withCanEdit(false);

	@Test
	void readOnlySnapshotIsAcceptedAndRenderedForViewerBelowPermission() {
		var model = new ServerSettingsModel(false);
		long requestId = model.beginExpansion();
		assertTrue(requestId > 0L);
		assertFalse(model.canView());

		assertTrue(model.applySnapshot(requestId, READ_ONLY));

		assertTrue(model.canView());
		assertFalse(model.canEdit());
		assertFalse(model.accessDenied());
		assertFalse(model.loading());
		assertEquals(ChannelMode.AUTO, model.defaultChannelMode());
		assertTrue(model.playerTrackingEnabled());
		assertEquals("1000", model.msToRegenerateText());
		assertEquals("5", model.rateLimitText());
		assertEquals(Integer.toString(READ_ONLY.syncDuration()), model.syncDurationText());
		assertFalse(model.dirty());
		assertTrue(model.updatePlan().isEmpty());
		assertEquals(-1L, model.beginSessionIfNeeded());
	}

	@Test
	void readOnlyViewerCannotChangeTheDraftOrBuildAnUpdate() {
		var model = new ServerSettingsModel(false);
		long requestId = model.beginExpansion();
		assertTrue(model.applySnapshot(requestId, READ_ONLY));

		model.cycleDefaultChannelMode();
		model.togglePlayerTracking();
		model.setMsToRegenerateText("2500");
		model.setRateLimitText("99");
		model.setSyncDurationText("23");

		assertEquals(ChannelMode.AUTO, model.defaultChannelMode());
		assertTrue(model.playerTrackingEnabled());
		assertEquals("1000", model.msToRegenerateText());
		assertEquals("5", model.rateLimitText());
		assertEquals(Integer.toString(READ_ONLY.syncDuration()), model.syncDurationText());
		assertFalse(model.dirty());
		assertEquals(0, model.invalidFieldMask());
		assertTrue(model.updatePlan().isEmpty());
	}

	@Test
	void unsafeOrUncorrelatedSnapshotIsNeverViewable() {
		var model = new ServerSettingsModel(false);
		long requestId = model.beginExpansion();

		assertFalse(model.applySnapshot(requestId, new ServerConfigSnapshot(
			false,
			ChannelMode.AUTO,
			true,
			1000,
			5,
			-1)));
		assertFalse(model.applySnapshot(requestId + 1L, READ_ONLY));
		assertFalse(model.canView());
	}

	@Test
	void expansionLoadsSnapshotAndTracksDirtyUpdatePlan() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		assertTrue(requestId > 0L);
		assertEquals(requestId, model.pendingRequestId());
		assertTrue(model.loading());

		assertTrue(model.applySnapshot(requestId, EDITABLE));
		assertTrue(model.canEdit());
		model.cycleDefaultChannelMode();
		model.setMsToRegenerateText("2500");
		model.setSyncDurationText("23");

		var plan = model.updatePlan().orElseThrow();
		assertEquals(ServerConfigUpdate.DEFAULT_CHANNEL_MODE | ServerConfigUpdate.MS_TO_REGENERATE
			| ServerConfigUpdate.SYNC_DURATION, plan.changedFields());
		assertEquals(ChannelMode.DISABLED, plan.defaultChannelMode());
		assertEquals(2500, plan.msToRegenerate());
		assertEquals(5, plan.rateLimit());
		assertEquals(23, plan.syncDuration());
	}

	@Test
	void enteringTheSharedSessionDoesNotReplaceLoadedOrInflightState() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginSessionIfNeeded();
		assertTrue(requestId > 0L);
		assertEquals(-1L, model.beginSessionIfNeeded());

		assertTrue(model.applySnapshot(requestId, EDITABLE));
		model.setRateLimitText("99");
		assertEquals(-1L, model.beginSessionIfNeeded());
		assertEquals("99", model.rateLimitText());
		assertTrue(model.dirty());
	}

	@Test
	void invalidNumericDraftDoesNotProduceAnUpdate() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		model.applySnapshot(requestId, EDITABLE);
		model.setRateLimitText("");

		assertTrue(model.hasInvalidDraft());
		assertTrue(model.updatePlan().isEmpty());
	}

	@Test
	void invalidFieldMaskIdentifiesEachInvalidNumericFieldIndependently() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		model.applySnapshot(requestId, EDITABLE);

		model.setMsToRegenerateText("");
		assertEquals(ServerConfigUpdate.MS_TO_REGENERATE, model.invalidFieldMask());
		assertTrue(model.hasInvalidDraft());
		model.setMsToRegenerateText(Integer.toString(EDITABLE.msToRegenerate()));
		assertEquals(0, model.invalidFieldMask());

		model.setRateLimitText("not-a-number");
		assertEquals(ServerConfigUpdate.RATE_LIMIT, model.invalidFieldMask());
		model.setRateLimitText(Integer.toString(EDITABLE.rateLimit()));
		assertEquals(0, model.invalidFieldMask());

		model.setSyncDurationText("-1");
		assertEquals(ServerConfigUpdate.SYNC_DURATION, model.invalidFieldMask());
		assertTrue(model.hasInvalidDraft());
		model.setSyncDurationText(Integer.toString(EDITABLE.syncDuration()));
		assertEquals(0, model.invalidFieldMask());
		assertFalse(model.hasInvalidDraft());
	}

	@Test
	void invalidFieldMaskReportsOnlyInvalidEditedFields() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		model.applySnapshot(requestId, EDITABLE);

		model.setMsToRegenerateText(Integer.toString(EDITABLE.msToRegenerate() + 1));
		assertTrue(model.dirty());
		assertEquals(0, model.invalidFieldMask());

		model.setSyncDurationText("");
		assertEquals(ServerConfigUpdate.SYNC_DURATION, model.invalidFieldMask());
		assertTrue(model.updatePlan().isEmpty());

		model.setMsToRegenerateText("");
		assertEquals(
			ServerConfigUpdate.MS_TO_REGENERATE | ServerConfigUpdate.SYNC_DURATION,
			model.invalidFieldMask());
	}

	@Test
	void invalidFieldMaskClearsOnPermissionRevocationAndDisconnect() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		model.applySnapshot(requestId, EDITABLE);
		model.setRateLimitText("");
		assertEquals(ServerConfigUpdate.RATE_LIMIT, model.invalidFieldMask());

		model.setClientPermission(false);
		assertEquals(0, model.invalidFieldMask());
		assertFalse(model.hasInvalidDraft());

		model.setClientPermission(true);
		assertTrue(model.canEdit());
		model.setMsToRegenerateText("");
		assertEquals(ServerConfigUpdate.MS_TO_REGENERATE, model.invalidFieldMask());

		model.resetForDisconnect();
		assertEquals(0, model.invalidFieldMask());
		assertFalse(model.hasInvalidDraft());
	}

	@Test
	void markCleanPreservesTheDraftTextAndClearsTheInvalidMask() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		model.applySnapshot(requestId, EDITABLE);
		model.setMsToRegenerateText("2500");
		model.setSyncDurationText("");

		model.markClean();

		assertEquals(0, model.invalidFieldMask());
		assertFalse(model.hasInvalidDraft());
		assertEquals("2500", model.msToRegenerateText());
		assertEquals("", model.syncDurationText());
		assertTrue(model.updatePlan().isEmpty());
	}

	@Test
	void permissionRevocationRetainsTheReadOnlyViewAndDropsEdits() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		model.applySnapshot(requestId, EDITABLE);
		model.setRateLimitText("99");
		model.setClientPermission(false);

		assertFalse(model.expanded());
		assertFalse(model.dirty());
		assertFalse(model.canEdit());
		assertTrue(model.canView());
		assertEquals("5", model.rateLimitText());
		assertTrue(model.updatePlan().isEmpty());
	}

	@Test
	void permissionReturnRestoresTheEditableSessionForARetainedEditableSnapshot() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		model.applySnapshot(requestId, EDITABLE);
		model.setClientPermission(false);
		model.setClientPermission(true);

		assertTrue(model.canEdit());
		assertTrue(model.updatePlan().isEmpty());
		model.setRateLimitText("99");
		assertEquals(ServerConfigUpdate.RATE_LIMIT, model.updatePlan().orElseThrow().changedFields());
	}

	@Test
	void promotionToEditorDropsTheReadOnlyViewAndRequestsAFreshSnapshot() {
		var model = new ServerSettingsModel(false);
		long requestId = model.beginExpansion();
		assertTrue(model.applySnapshot(requestId, READ_ONLY));

		model.setClientPermission(true);

		assertFalse(model.canView());
		assertFalse(model.accessDenied());
		long freshRequestId = model.beginSessionIfNeeded();
		assertTrue(freshRequestId > 0L);
		assertTrue(model.applySnapshot(freshRequestId, EDITABLE));
		assertTrue(model.canEdit());
	}

	@Test
	void staleSnapshotAfterLoadingIsIgnoredUntilAFreshExpansion() {
		var model = new ServerSettingsModel(true);
		long firstRequestId = model.beginExpansion();
		model.collapseAndDiscard();

		assertFalse(model.applySnapshot(firstRequestId, EDITABLE));
		assertFalse(model.expanded());

		long secondRequestId = model.beginExpansion();
		assertNotEquals(firstRequestId, secondRequestId);
		assertFalse(model.applySnapshot(firstRequestId, EDITABLE));
		assertTrue(model.applySnapshot(secondRequestId, EDITABLE));
		assertTrue(model.expanded());
		assertFalse(model.loading());
	}

	@Test
	void staleSnapshotAfterPermissionRevocationCannotReopenTheSection() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		model.setClientPermission(false);

		assertFalse(model.applySnapshot(requestId, EDITABLE));
		assertFalse(model.expanded());
		assertFalse(model.loading());
	}

	@Test
	void dirtyBitsClearWhenEnumBooleanAndNumericValuesReturnToTheSnapshot() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		model.applySnapshot(requestId, EDITABLE);

		model.cycleDefaultChannelMode();
		assertTrue((model.dirtyFields() & ServerConfigUpdate.DEFAULT_CHANNEL_MODE) != 0);
		for (int i = 1; i < ChannelMode.values().length; i++) {
			model.cycleDefaultChannelMode();
		}
		assertEquals(0, model.dirtyFields());

		model.togglePlayerTracking();
		assertTrue((model.dirtyFields() & ServerConfigUpdate.PLAYER_TRACKING_ENABLED) != 0);
		model.togglePlayerTracking();
		assertEquals(0, model.dirtyFields());

		model.setRateLimitText("99");
		assertTrue((model.dirtyFields() & ServerConfigUpdate.RATE_LIMIT) != 0);
		model.setRateLimitText("5");
		assertEquals(0, model.dirtyFields());

		model.setSyncDurationText("23");
		assertTrue((model.dirtyFields() & ServerConfigUpdate.SYNC_DURATION) != 0);
		model.setSyncDurationText("7");
		assertEquals(0, model.dirtyFields());
	}

	@Test
	void invalidNumericEditRemainsDirtyUntilTheAuthoritativeValueIsRestored() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		model.applySnapshot(requestId, EDITABLE);
		model.setMsToRegenerateText("");

		assertTrue(model.dirty());
		assertTrue(model.hasInvalidDraft());

		model.setMsToRegenerateText("1000");
		assertFalse(model.dirty());
		assertFalse(model.hasInvalidDraft());
	}

	@Test
	void disconnectResetClearsConnectionScopedStateAndRejectsTheOldResponse() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		assertTrue(model.applySnapshot(requestId, EDITABLE));
		model.setRateLimitText("99");

		model.resetForDisconnect();

		assertFalse(model.clientPermission());
		assertFalse(model.expanded());
		assertFalse(model.loading());
		assertFalse(model.dirty());
		assertEquals(-1L, model.pendingRequestId());
		assertNull(model.authoritative());
		assertEquals("", model.msToRegenerateText());
		assertEquals("", model.rateLimitText());
		assertEquals("", model.syncDurationText());
		assertFalse(model.applySnapshot(requestId, EDITABLE));
	}

	@Test
	void authoritativeDenialKeepsTheSnapshotViewableButNotEditable() {
		var model = new ServerSettingsModel(true);
		long deniedRequestId = model.beginExpansion();
		assertTrue(model.applySnapshot(deniedRequestId, READ_ONLY));

		assertTrue(model.expanded());
		assertTrue(model.canView());
		assertFalse(model.canEdit());
		assertTrue(model.accessDenied());
		assertFalse(model.loading());
		assertEquals(-1L, model.pendingRequestId());
		assertEquals("1000", model.msToRegenerateText());
		assertEquals(-1L, model.beginExpansion());
		model.setClientPermission(true);
		assertTrue(model.accessDenied());
		assertEquals(-1L, model.beginExpansion());
	}

	@Test
	void localPermissionFalseDoesNotClearDenialUntilItReturnsTrue() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		assertTrue(model.applySnapshot(requestId, new ServerConfigSnapshot(
			false,
			ChannelMode.AUTO,
			true,
			1000,
			5)));

		model.setClientPermission(false);
		assertTrue(model.accessDenied());
		assertFalse(model.expanded());
		model.setClientPermission(false);
		assertTrue(model.accessDenied());
		assertEquals(-1L, model.beginExpansion());
	}

	@Test
	void falseThenTruePermissionTransitionClearsDenialAndAllowsFreshExpansion() {
		var model = new ServerSettingsModel(true);
		long deniedRequestId = model.beginExpansion();
		assertTrue(model.applySnapshot(deniedRequestId, new ServerConfigSnapshot(
			false,
			ChannelMode.AUTO,
			true,
			1000,
			5)));

		model.setClientPermission(false);
		model.setClientPermission(true);
		long freshRequestId = model.beginExpansion();

		assertNotEquals(deniedRequestId, freshRequestId);
		assertTrue(model.applySnapshot(freshRequestId, EDITABLE));
		assertTrue(model.canEdit());
		assertFalse(model.accessDenied());
	}

	@Test
	void requestIdRolloverSkipsZeroAndReturnsToPositiveOne() {
		ServerSettingsModel.setRequestIdSequenceForTesting(Long.MAX_VALUE);
		try {
			var model = new ServerSettingsModel(true);
			assertEquals(1L, model.beginExpansion());
		} finally {
			ServerSettingsModel.setRequestIdSequenceForTesting(Long.MAX_VALUE);
		}
	}
}
