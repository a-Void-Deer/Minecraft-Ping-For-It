package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model-level boundaries for the authoritative inventory budget settings. The
 * expectations come from the confirmed budget contract (finite caps, explicit
 * unlimited mode, the two quantized multiplier grids and the derived byte
 * scopes), not from copying incidental defaults.
 */
class InventorySettingsTest {

	@Test
	void explicitUnlimitedUsesTheCallerGuardAndKeepsTheFiniteValue() {
		IntLimit limit = IntLimit.finite(2048);
		limit.setUnlimited(true);

		assertEquals(4242, limit.effective(4242));
		assertEquals(2048, limit.getValue());

		limit.setUnlimited(false);
		assertEquals(2048, limit.effective(4242));
	}

	@Test
	void finiteCapsClampToPositiveAndKeepTheIntegerMaximumFinite() {
		IntLimit limit = IntLimit.finite(16);
		limit.setValue(0);
		limit.validate();
		assertEquals(InventoryLimits.MIN_FINITE_LIMIT, limit.getValue());

		limit.setValue(Integer.MIN_VALUE);
		limit.validate();
		assertEquals(InventoryLimits.MIN_FINITE_LIMIT, limit.getValue());

		limit.setValue(Integer.MAX_VALUE);
		limit.validate();
		assertEquals(Integer.MAX_VALUE, limit.getValue());
		assertFalse(limit.isUnlimited());
	}

	@Test
	void unlimitedPhysicalModeIsBoundedAndKeepsTheConfiguredValue() {
		InventorySettings settings = InventorySettings.serverDefaults();
		settings.getPhysicalSlotsPerTick().setValue(2048);
		settings.getPhysicalSlotsPerTick().setUnlimited(true);
		settings.setPendingMemoryMiB(2);
		settings.validate();

		assertTrue(settings.getPhysicalSlotsPerTick().isUnlimited());
		assertEquals(2048, settings.getPhysicalSlotsPerTick().getValue());
		int effective = settings.effectivePhysicalSlotsPerTick();
		assertTrue(effective > 0 && effective < Integer.MAX_VALUE, "unlimited work must stay a finite guard");
		assertEquals(2, settings.getPendingMemoryMiB());
	}

	@Test
	void finiteRangesClampAtTheirConfirmedBoundaries() {
		InventorySettings settings = InventorySettings.serverDefaults();
		settings.setPendingMemoryMiB(InventoryLimits.MAX_PENDING_MEMORY_MIB + 1);
		settings.getPreview().setPeriodTicks(0);
		settings.getTracking().setPeriodTicks(0);
		settings.getTracking().setGracePeriods(InventoryLimits.MAX_GRACE_PERIODS + 1);
		settings.getTracking().setResyncMinPeriods(0);
		settings.validate();

		assertEquals(InventoryLimits.MAX_PENDING_MEMORY_MIB, settings.getPendingMemoryMiB());
		assertEquals(InventoryLimits.MIN_PERIOD_TICKS, settings.getPreview().getPeriodTicks());
		assertEquals(InventoryLimits.MIN_PERIOD_TICKS, settings.getTracking().getPeriodTicks());
		assertEquals(InventoryLimits.MAX_GRACE_PERIODS, settings.getTracking().getGracePeriods());
		assertEquals(InventoryLimits.MIN_RESYNC_PERIODS, settings.getTracking().getResyncMinPeriods());
	}

	@Test
	void zeroHeartbeatDisablesOnlyThePeriodicCadence() {
		InventorySettings settings = InventorySettings.serverDefaults();
		settings.getTracking().setHeartbeatPeriods(0);
		settings.validate();

		assertEquals(0, settings.getTracking().getHeartbeatPeriods());
		assertFalse(settings.getTracking().isHeartbeatEnabled());

		settings.getTracking().setHeartbeatPeriods(-5);
		settings.validate();
		assertEquals(0, settings.getTracking().getHeartbeatPeriods());

		settings.getTracking().setHeartbeatPeriods(InventoryLimits.MAX_HEARTBEAT_PERIODS + 1);
		settings.validate();
		assertEquals(InventoryLimits.MAX_HEARTBEAT_PERIODS, settings.getTracking().getHeartbeatPeriods());
		assertTrue(settings.getTracking().isHeartbeatEnabled());
	}

	@Test
	void multipliersNormalizeOntoTheConfirmedGrids() {
		ByteMultiplier client = ByteMultiplier.finite(new BigDecimal("0.24"));
		client.validate(ByteMultiplierGrid.CLIENT);
		assertEquals(0, new BigDecimal("0.25").compareTo(client.getValue()));

		client.setValue(new BigDecimal("4.2"));
		client.validate(ByteMultiplierGrid.CLIENT);
		assertEquals(0, new BigDecimal("4").compareTo(client.getValue()));

		client.setValue(new BigDecimal("7.9"));
		client.validate(ByteMultiplierGrid.CLIENT);
		assertEquals(0, new BigDecimal("8").compareTo(client.getValue()));

		client.setValue(new BigDecimal("100"));
		client.validate(ByteMultiplierGrid.CLIENT);
		assertEquals(0, new BigDecimal("16").compareTo(client.getValue()));

		ByteMultiplier global = ByteMultiplier.finite(new BigDecimal("0.3"));
		global.validate(ByteMultiplierGrid.GLOBAL);
		assertEquals(0, new BigDecimal("0.25").compareTo(global.getValue()));

		global.setValue(new BigDecimal("4.2"));
		global.validate(ByteMultiplierGrid.GLOBAL);
		assertEquals(0, new BigDecimal("4.25").compareTo(global.getValue()));

		global.setValue(new BigDecimal("16.2"));
		global.validate(ByteMultiplierGrid.GLOBAL);
		assertEquals(0, new BigDecimal("16").compareTo(global.getValue()));

		global.setValue(new BigDecimal("0.01"));
		global.validate(ByteMultiplierGrid.GLOBAL);
		assertEquals(0, new BigDecimal("0.125").compareTo(global.getValue()));
	}

	@Test
	void byteBudgetsFollowTheConfirmedBasesAndDerivedScopes() {
		InventorySettings settings = InventorySettings.serverDefaults();
		settings.getPreview().setClientByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.25")));
		settings.getPreview().setGlobalByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.125")));
		settings.getTracking().setStreamByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.25")));
		settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.25")));
		settings.getTracking().setGlobalByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.125")));
		settings.validate();

		assertEquals(1024, settings.previewClientPeriodBytes());
		assertEquals(4096, settings.previewGlobalPeriodBytes());
		assertEquals(4 * settings.previewGlobalPeriodBytes(), settings.previewQueueBytes());
		assertEquals(512, settings.trackingStreamPeriodBytesPerClientTarget());
		assertEquals(4096, settings.trackingSnapshotFragmentBytes());
		assertEquals(settings.trackingSnapshotFragmentBytes(), settings.trackingSnapshotClientPeriodBytes());
		assertEquals(2048, settings.trackingGlobalPeriodBytes());
		assertEquals(8 * settings.trackingGlobalPeriodBytes(), settings.trackingRollingQueueBytes());
	}

	@Test
	void independentByteMultipliersControlOnlyTheirOwnScope() {
		InventorySettings settings = InventorySettings.serverDefaults();
		settings.getPreview().setClientByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.25")));
		settings.getPreview().setGlobalByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.5")));
		settings.getTracking().setStreamByteMultiplier(ByteMultiplier.finite(new BigDecimal("1")));
		settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(new BigDecimal("2")));
		settings.getTracking().setGlobalByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.125")));
		settings.validate();

		long previewGlobal = settings.previewGlobalPeriodBytes();
		long stream = settings.trackingStreamPeriodBytesPerClientTarget();
		long snapshot = settings.trackingSnapshotFragmentBytes();
		long trackingGlobal = settings.trackingGlobalPeriodBytes();

		settings.getPreview().getClientByteMultiplier().setValue(new BigDecimal("1"));
		settings.validate();
		assertEquals(4096, settings.previewClientPeriodBytes());
		assertEquals(previewGlobal, settings.previewGlobalPeriodBytes());
		assertEquals(4 * previewGlobal, settings.previewQueueBytes());
		assertEquals(stream, settings.trackingStreamPeriodBytesPerClientTarget());
		assertEquals(snapshot, settings.trackingSnapshotFragmentBytes());
		assertEquals(trackingGlobal, settings.trackingGlobalPeriodBytes());

		settings.getTracking().getStreamByteMultiplier().setValue(new BigDecimal("0.25"));
		settings.validate();
		assertEquals(512, settings.trackingStreamPeriodBytesPerClientTarget());
		assertEquals(snapshot, settings.trackingSnapshotFragmentBytes());
		assertEquals(trackingGlobal, settings.trackingGlobalPeriodBytes());

		settings.getPreview().getGlobalByteMultiplier().setValue(new BigDecimal("2"));
		settings.validate();
		assertEquals(65536, settings.previewGlobalPeriodBytes());
		assertEquals(trackingGlobal, settings.trackingGlobalPeriodBytes());
		assertEquals(512, settings.trackingStreamPeriodBytesPerClientTarget());
		assertEquals(snapshot, settings.trackingSnapshotFragmentBytes());
	}

	@Test
	void snapshotFragmentAndClientPeriodScopesStayEqualAcrossChanges() {
		InventorySettings settings = InventorySettings.serverDefaults();
		settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(new BigDecimal("4")));
		settings.validate();

		assertEquals(65536, settings.trackingSnapshotFragmentBytes());
		assertEquals(settings.trackingSnapshotFragmentBytes(), settings.trackingSnapshotClientPeriodBytes());

		settings.getTracking().getSnapshotByteMultiplier().setValue(new BigDecimal("0.5"));
		settings.validate();

		assertEquals(8192, settings.trackingSnapshotFragmentBytes());
		assertEquals(settings.trackingSnapshotFragmentBytes(), settings.trackingSnapshotClientPeriodBytes());
	}

	@Test
	void unlimitedByteMultiplierUsesAFiniteGuardAndKeepsTheFiniteValue() {
		InventorySettings settings = InventorySettings.serverDefaults();
		ByteMultiplier previewClient = ByteMultiplier.finite(new BigDecimal("2"));
		previewClient.setUnlimited(true);
		settings.getPreview().setClientByteMultiplier(previewClient);
		settings.getTracking().setStreamByteMultiplier(ByteMultiplier.finite(new BigDecimal("1")));
		settings.validate();

		assertTrue(settings.getPreview().getClientByteMultiplier().isUnlimited());
		assertEquals(0, new BigDecimal("2").compareTo(settings.getPreview().getClientByteMultiplier().getValue()));
		long budget = settings.previewClientPeriodBytes();
		assertTrue(budget > 0 && budget < Long.MAX_VALUE, "unlimited byte budget must stay finite");
		assertNotEquals(8192, budget);
		assertEquals(2048, settings.trackingStreamPeriodBytesPerClientTarget());
	}

	@Test
	void multiplierStepHelpersCrossPiecewiseBoundaries() {
		assertEquals(0, new BigDecimal("4").compareTo(InventorySettings.nextClientByteMultiplier(new BigDecimal("3.75"))));
		assertEquals(0, new BigDecimal("3.75").compareTo(InventorySettings.previousClientByteMultiplier(new BigDecimal("4"))));
		assertEquals(0, new BigDecimal("8").compareTo(InventorySettings.nextClientByteMultiplier(new BigDecimal("7.5"))));
		assertEquals(0, new BigDecimal("7.5").compareTo(InventorySettings.previousClientByteMultiplier(new BigDecimal("8"))));
		assertEquals(0, new BigDecimal("16").compareTo(InventorySettings.nextClientByteMultiplier(new BigDecimal("16"))));
		assertEquals(0, new BigDecimal("0.25").compareTo(InventorySettings.previousClientByteMultiplier(new BigDecimal("0.25"))));

		assertEquals(0, new BigDecimal("4").compareTo(InventorySettings.nextGlobalByteMultiplier(new BigDecimal("3.875"))));
		assertEquals(0, new BigDecimal("3.875").compareTo(InventorySettings.previousGlobalByteMultiplier(new BigDecimal("4"))));
		assertEquals(0, new BigDecimal("0.125").compareTo(InventorySettings.previousGlobalByteMultiplier(new BigDecimal("0.125"))));
		assertEquals(0, new BigDecimal("32").compareTo(InventorySettings.nextGlobalByteMultiplier(new BigDecimal("32"))));
	}

	@Test
	void multiplierStepHelpersAdvanceAndRetreatStrictly() {
		BigDecimal client = new BigDecimal("0.25");
		BigDecimal nextClient = InventorySettings.nextClientByteMultiplier(client);
		assertTrue(nextClient.compareTo(client) > 0);
		assertEquals(0, client.compareTo(InventorySettings.previousClientByteMultiplier(nextClient)));

		BigDecimal global = new BigDecimal("3.875");
		BigDecimal nextGlobal = InventorySettings.nextGlobalByteMultiplier(global);
		assertTrue(nextGlobal.compareTo(global) > 0);
		assertEquals(0, global.compareTo(InventorySettings.previousGlobalByteMultiplier(nextGlobal)));
	}

	@Test
	void pendingMemoryStepsFollowTheConfirmedPiecewiseGrid() {
		assertEquals(1, InventorySettings.pendingMemoryStepMiB(31));
		assertEquals(2, InventorySettings.pendingMemoryStepMiB(32));
		assertEquals(2, InventorySettings.pendingMemoryStepMiB(127));
		assertEquals(4, InventorySettings.pendingMemoryStepMiB(128));
		assertEquals(4, InventorySettings.pendingMemoryStepMiB(512));
	}

	@Test
	void independentDefaultsDoNotAliasNestedState() {
		InventorySettings first = InventorySettings.serverDefaults();
		InventorySettings second = InventorySettings.serverDefaults();

		first.getPreview().getMaxSlotsPerClient().setValue(5);

		assertNotEquals(5, second.getPreview().getMaxSlotsPerClient().getValue());
	}
}
