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

	@Test
	void lockedModelCannotExpand() {
		var model = new ServerSettingsModel(false);
		assertEquals(-1L, model.beginExpansion());
		assertFalse(model.expanded());
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

		var plan = model.updatePlan().orElseThrow();
		assertEquals(ServerConfigUpdate.DEFAULT_CHANNEL_MODE | ServerConfigUpdate.MS_TO_REGENERATE, plan.changedFields());
		assertEquals(ChannelMode.DISABLED, plan.defaultChannelMode());
		assertEquals(2500, plan.msToRegenerate());
		assertEquals(5, plan.rateLimit());
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
	void permissionRevocationCollapsesAndDiscardsDraft() {
		var model = new ServerSettingsModel(true);
		long requestId = model.beginExpansion();
		model.applySnapshot(requestId, EDITABLE);
		model.setRateLimitText("99");
		model.setClientPermission(false);

		assertFalse(model.expanded());
		assertFalse(model.dirty());
		assertFalse(model.canEdit());
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
		assertFalse(model.applySnapshot(requestId, EDITABLE));
	}

	@Test
	void authoritativeDenialLocksWhileLocalPermissionRemainsTrue() {
		var model = new ServerSettingsModel(true);
		long deniedRequestId = model.beginExpansion();
		assertTrue(model.applySnapshot(deniedRequestId, new ServerConfigSnapshot(
			false,
			ChannelMode.AUTO,
			true,
			1000,
			5)));
		assertFalse(model.expanded());
		assertNull(model.authoritative());
		assertTrue(model.accessDenied());
		assertFalse(model.loading());
		assertEquals(-1L, model.pendingRequestId());
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
