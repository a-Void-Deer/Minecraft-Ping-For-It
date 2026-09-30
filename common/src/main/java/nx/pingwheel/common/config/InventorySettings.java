package nx.pingwheel.common.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Server-authoritative inventory budget policy for the preview and tracking
 * domains. It carries the shared physical scan and pending-memory bounds plus
 * separate preview and tracking accounting. Every cap is either a positive
 * finite value or an explicit unlimited mode; the five send-byte multipliers
 * are independent, exact grid values, and all derived byte amounts are
 * computed from code-owned bases.
 *
 * <p>There is no tracking-duration setting here: the tracking deadline is the
 * Ping's own marker lifetime. Periods, resynchronization cooldown, heartbeat
 * and pending memory have no unlimited mode.
 *
 * <p>JSON shape:
 * <pre>
 * "inventory": {
 *   "physicalSlotsPerTick": {"unlimited": false, "value": 1024},
 *   "pendingMemoryMiB": 16,
 *   "preview": {
 *     "periodTicks": 5,
 *     "maxVariantsPerClientPeriod": {"unlimited": false, "value": 32},
 *     "maxSlotsPerClient": {"unlimited": false, "value": 128},
 *     "maxSlotsServer": {"unlimited": false, "value": 1024},
 *     "maxTargetsPerClient": {"unlimited": false, "value": 2},
 *     "clientByteMultiplier": {"unlimited": false, "value": 1},
 *     "globalByteMultiplier": {"unlimited": false, "value": 1}
 *   },
 *   "tracking": {
 *     "periodTicks": 3,
 *     "maxVariantsPerTarget": {"unlimited": false, "value": 8},
 *     "maxSlotsPerTarget": {"unlimited": false, "value": 512},
 *     "maxSlotsServer": {"unlimited": false, "value": 4096},
 *     "streamByteMultiplier": {"unlimited": false, "value": 1},
 *     "snapshotByteMultiplier": {"unlimited": false, "value": 1},
 *     "globalByteMultiplier": {"unlimited": false, "value": 1},
 *     "resyncMinPeriods": 5,
 *     "heartbeatPeriods": 16,
 *     "gracePeriods": 4
 *   }
 * }
 * </pre>
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
public final class InventorySettings {

	/** Shared physical scan allowance, finite or explicit unlimited. */
	private IntLimit physicalSlotsPerTick = IntLimit.finite(InventoryLimits.DEFAULT_PHYSICAL_SLOTS_PER_TICK);
	/** Single finite server-wide pending-memory bound for preview and tracking together, MiB. */
	private int pendingMemoryMiB = InventoryLimits.DEFAULT_PENDING_MEMORY_MIB;
	private Preview preview = new Preview();
	private Tracking tracking = new Tracking();

	public static InventorySettings serverDefaults() {
		return new InventorySettings();
	}

	/**
	 * Clamps every finite value, normalizes each multiplier onto its grid and
	 * allocates missing nested objects. Values stay in memory until a caller
	 * saves; no migration step or version change is needed for an additive
	 * object with defaults.
	 */
	public void validate() {
		physicalSlotsPerTick = validatedLimit(physicalSlotsPerTick, InventoryLimits.DEFAULT_PHYSICAL_SLOTS_PER_TICK);
		pendingMemoryMiB = Math.clamp(
			pendingMemoryMiB,
			InventoryLimits.MIN_PENDING_MEMORY_MIB,
			InventoryLimits.MAX_PENDING_MEMORY_MIB);
		if (preview == null) preview = new Preview();
		preview.validate();
		if (tracking == null) tracking = new Tracking();
		tracking.validate();
	}

	/** The effective physical slots per tick, keeping a finite guard in unlimited mode. */
	public int effectivePhysicalSlotsPerTick() {
		return physicalSlotsPerTick.effective(InventoryLimits.INTERNAL_PHYSICAL_SLOT_GUARD);
	}

	public long pendingMemoryBytes() {
		return pendingMemoryMiB * 1024L * 1024L;
	}

	/** Preview per-client period bytes: 4 KiB scaled by the preview client multiplier. */
	public long previewClientPeriodBytes() {
		return preview.clientByteMultiplier.appliedTo(
			InventoryLimits.PREVIEW_CLIENT_BASE_BYTES,
			InventoryLimits.INTERNAL_BYTE_BUDGET_GUARD,
			ByteMultiplierGrid.CLIENT);
	}

	/** Preview global period bytes: 32 KiB scaled by the preview global multiplier. */
	public long previewGlobalPeriodBytes() {
		return preview.globalByteMultiplier.appliedTo(
			InventoryLimits.PREVIEW_GLOBAL_BASE_BYTES,
			InventoryLimits.INTERNAL_BYTE_BUDGET_GUARD,
			ByteMultiplierGrid.GLOBAL);
	}

	/** Derived preview send-queue capacity: four global server period byte amounts. */
	public long previewQueueBytes() {
		return InventoryLimits.PREVIEW_QUEUE_GLOBAL_PERIODS * previewGlobalPeriodBytes();
	}

	/** Tracking stream bytes per client+target period: 2 KiB scaled by the stream multiplier. */
	public long trackingStreamPeriodBytesPerClientTarget() {
		return tracking.streamByteMultiplier.appliedTo(
			InventoryLimits.TRACKING_STREAM_BASE_BYTES,
			InventoryLimits.INTERNAL_BYTE_BUDGET_GUARD,
			ByteMultiplierGrid.CLIENT);
	}

	/** Tracking snapshot per-fragment bytes: 16 KiB scaled by the snapshot multiplier. */
	public long trackingSnapshotFragmentBytes() {
		return tracking.snapshotByteMultiplier.appliedTo(
			InventoryLimits.TRACKING_SNAPSHOT_BASE_BYTES,
			InventoryLimits.INTERNAL_BYTE_BUDGET_GUARD,
			ByteMultiplierGrid.CLIENT);
	}

	/** Tracking snapshot per-client period bytes across all targets; the same snapshot multiplier. */
	public long trackingSnapshotClientPeriodBytes() {
		return trackingSnapshotFragmentBytes();
	}

	/** Tracking global period bytes: 16 KiB scaled by the tracking global multiplier. */
	public long trackingGlobalPeriodBytes() {
		return tracking.globalByteMultiplier.appliedTo(
			InventoryLimits.TRACKING_GLOBAL_BASE_BYTES,
			InventoryLimits.INTERNAL_BYTE_BUDGET_GUARD,
			ByteMultiplierGrid.GLOBAL);
	}

	/** Derived tracking rolling-queue capacity: four average bounds of two global period amounts. */
	public long trackingRollingQueueBytes() {
		return InventoryLimits.TRACKING_ROLLING_QUEUE_GLOBAL_PERIODS * trackingGlobalPeriodBytes();
	}

	/** UI helper: next legal client-grid multiplier value, saturating at the maximum. */
	public static BigDecimal nextClientByteMultiplier(BigDecimal current) {
		return ByteMultiplierGrid.CLIENT.next(current);
	}

	/** UI helper: previous legal client-grid multiplier value, saturating at the minimum. */
	public static BigDecimal previousClientByteMultiplier(BigDecimal current) {
		return ByteMultiplierGrid.CLIENT.previous(current);
	}

	/** UI helper: next legal global-grid multiplier value, saturating at the maximum. */
	public static BigDecimal nextGlobalByteMultiplier(BigDecimal current) {
		return ByteMultiplierGrid.GLOBAL.next(current);
	}

	/** UI helper: previous legal global-grid multiplier value, saturating at the minimum. */
	public static BigDecimal previousGlobalByteMultiplier(BigDecimal current) {
		return ByteMultiplierGrid.GLOBAL.previous(current);
	}

	/** UI helper: the confirmed pending-memory step in MiB at the given value. */
	public static int pendingMemoryStepMiB(int current) {
		if (current < 32) return 1;
		if (current < 128) return 2;
		return 4;
	}

	static IntLimit validatedLimit(IntLimit limit, int defaultValue) {
		if (limit == null) return IntLimit.finite(defaultValue);
		limit.validate();
		return limit;
	}

	static ByteMultiplier validatedMultiplier(ByteMultiplier multiplier, ByteMultiplierGrid grid) {
		if (multiplier == null) return ByteMultiplier.one();
		multiplier.validate(grid);
		return multiplier;
	}

	@Getter
	@Setter
	@ToString
	@EqualsAndHashCode
	public static final class Preview {

		private int periodTicks = 5;
		private IntLimit maxVariantsPerClientPeriod = IntLimit.finite(32);
		private IntLimit maxSlotsPerClient = IntLimit.finite(128);
		private IntLimit maxSlotsServer = IntLimit.finite(1024);
		private IntLimit maxTargetsPerClient = IntLimit.finite(2);
		/** Preview per-client period bytes on the client grid; independent of every other multiplier. */
		private ByteMultiplier clientByteMultiplier = ByteMultiplier.one();
		/** Preview global period bytes on the global grid; independent of every other multiplier. */
		private ByteMultiplier globalByteMultiplier = ByteMultiplier.one();

		void validate() {
			periodTicks = Math.clamp(periodTicks, InventoryLimits.MIN_PERIOD_TICKS, InventoryLimits.MAX_PERIOD_TICKS);
			maxVariantsPerClientPeriod = validatedLimit(maxVariantsPerClientPeriod, 32);
			maxSlotsPerClient = validatedLimit(maxSlotsPerClient, 128);
			maxSlotsServer = validatedLimit(maxSlotsServer, 1024);
			maxTargetsPerClient = validatedLimit(maxTargetsPerClient, 2);
			clientByteMultiplier = validatedMultiplier(clientByteMultiplier, ByteMultiplierGrid.CLIENT);
			globalByteMultiplier = validatedMultiplier(globalByteMultiplier, ByteMultiplierGrid.GLOBAL);
		}

		public int effectiveMaxVariantsPerClientPeriod() {
			return maxVariantsPerClientPeriod.effective(InventoryLimits.INTERNAL_COUNT_LIMIT_GUARD);
		}

		public int effectiveMaxSlotsPerClient() {
			return maxSlotsPerClient.effective(InventoryLimits.INTERNAL_COUNT_LIMIT_GUARD);
		}

		public int effectiveMaxSlotsServer() {
			return maxSlotsServer.effective(InventoryLimits.INTERNAL_COUNT_LIMIT_GUARD);
		}

		public int effectiveMaxTargetsPerClient() {
			return maxTargetsPerClient.effective(InventoryLimits.INTERNAL_COUNT_LIMIT_GUARD);
		}
	}

	@Getter
	@Setter
	@ToString
	@EqualsAndHashCode
	public static final class Tracking {

		private int periodTicks = 3;
		private IntLimit maxVariantsPerTarget = IntLimit.finite(8);
		private IntLimit maxSlotsPerTarget = IntLimit.finite(512);
		private IntLimit maxSlotsServer = IntLimit.finite(4096);
		/** Tracking stream bytes per client+target period on the client grid. */
		private ByteMultiplier streamByteMultiplier = ByteMultiplier.one();
		/** Tracking snapshot bytes per fragment and per client period on the client grid. */
		private ByteMultiplier snapshotByteMultiplier = ByteMultiplier.one();
		/** Tracking global period bytes on the global grid. */
		private ByteMultiplier globalByteMultiplier = ByteMultiplier.one();
		/** Positive resynchronization cooldown in periods; no unlimited mode. */
		private int resyncMinPeriods = 5;
		/** Periodic heartbeat cadence; zero is the explicit disabled value, not unlimited. */
		private int heartbeatPeriods = 16;
		/** Bounded grace periods for an unknown-baseline stream. */
		private int gracePeriods = 4;

		void validate() {
			periodTicks = Math.clamp(periodTicks, InventoryLimits.MIN_PERIOD_TICKS, InventoryLimits.MAX_PERIOD_TICKS);
			maxVariantsPerTarget = validatedLimit(maxVariantsPerTarget, 8);
			maxSlotsPerTarget = validatedLimit(maxSlotsPerTarget, 512);
			maxSlotsServer = validatedLimit(maxSlotsServer, 4096);
			streamByteMultiplier = validatedMultiplier(streamByteMultiplier, ByteMultiplierGrid.CLIENT);
			snapshotByteMultiplier = validatedMultiplier(snapshotByteMultiplier, ByteMultiplierGrid.CLIENT);
			globalByteMultiplier = validatedMultiplier(globalByteMultiplier, ByteMultiplierGrid.GLOBAL);
			if (resyncMinPeriods < InventoryLimits.MIN_RESYNC_PERIODS) {
				resyncMinPeriods = InventoryLimits.MIN_RESYNC_PERIODS;
			}
			heartbeatPeriods = Math.clamp(
				heartbeatPeriods,
				InventoryLimits.MIN_HEARTBEAT_PERIODS,
				InventoryLimits.MAX_HEARTBEAT_PERIODS);
			gracePeriods = Math.clamp(
				gracePeriods,
				InventoryLimits.MIN_GRACE_PERIODS,
				InventoryLimits.MAX_GRACE_PERIODS);
		}

		public int effectiveMaxVariantsPerTarget() {
			return maxVariantsPerTarget.effective(InventoryLimits.INTERNAL_COUNT_LIMIT_GUARD);
		}

		public int effectiveMaxSlotsPerTarget() {
			return maxSlotsPerTarget.effective(InventoryLimits.INTERNAL_COUNT_LIMIT_GUARD);
		}

		public int effectiveMaxSlotsServer() {
			return maxSlotsServer.effective(InventoryLimits.INTERNAL_COUNT_LIMIT_GUARD);
		}

		/** False only for the explicit zero heartbeat value; other repair paths stay active. */
		public boolean isHeartbeatEnabled() {
			return heartbeatPeriods > 0;
		}
	}
}
