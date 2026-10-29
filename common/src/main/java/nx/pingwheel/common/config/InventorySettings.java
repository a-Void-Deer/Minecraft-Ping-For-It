package nx.pingwheel.common.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * Server-authoritative inventory budget policy for the preview and tracking
 * domains. It carries the shared physical scan and pending-memory bounds plus
 * separate preview and tracking accounting. Every cap is either a positive
 * finite value or an explicit unlimited mode; the five send-byte multipliers
 * are independent, exact grid values, and all derived byte amounts are
 * computed from code-owned bases. The display and frame size multipliers are
 * finite-only exact {@link InventorySizeScale} values whose pair keeps the
 * frame budget above the display budget plus its fixed metadata reserve.
 *
 * <p>There is no tracking-duration setting here: the tracking deadline is the
 * Ping's own marker lifetime. Periods, resynchronization cooldown and heartbeat
 * have no unlimited mode. Pending memory retains a finite ledger in either mode.
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
@NotNullByDefault
public final class InventorySettings {

	/** Shared physical scan allowance, finite or explicit unlimited. */
	private @Nullable IntLimit physicalSlotsPerTick = IntLimit.finite(ConfigDefaults.Inventory.PHYSICAL_SLOTS_PER_TICK);
	/** Single server-wide memory cap, with a finite internal guard in unlimited mode, MiB. */
	private @Nullable IntLimit pendingMemoryMiB = IntLimit.finite(ConfigDefaults.Inventory.PENDING_MEMORY_MIB);
	/** Finite display budget multiplier on the display size scale; no unlimited mode. */
	private @Nullable BigDecimal displayByteMultiplier = ConfigDefaults.Inventory.DISPLAY_BYTE_MULTIPLIER;
	/** Finite frame budget multiplier on the frame size scale; no unlimited mode. */
	private @Nullable BigDecimal frameByteMultiplier = ConfigDefaults.Inventory.FRAME_BYTE_MULTIPLIER;
	private @Nullable Preview preview = new Preview();
	private @Nullable Tracking tracking = new Tracking();

	public static InventorySettings serverDefaults() {
		return new InventorySettings();
	}

	/**
	 * Clamps every finite value, normalizes each multiplier onto its grid and
	 * allocates missing nested objects. Validation itself does not persist;
	 * ConfigHandler owns legacy shape normalization and guarded writeback.
	 */
	public void validate() {
		physicalSlotsPerTick = validatedLimit(physicalSlotsPerTick, ConfigDefaults.Inventory.PHYSICAL_SLOTS_PER_TICK,
			InventoryConfigValues.Field.PHYSICAL_SLOTS_PER_TICK);
		pendingMemoryMiB = validatedLimit(pendingMemoryMiB, ConfigDefaults.Inventory.PENDING_MEMORY_MIB,
			InventoryConfigValues.Field.PENDING_MEMORY_MIB);
		var sizePair = InventorySizeScale.reconcile(displayByteMultiplier, frameByteMultiplier);
		displayByteMultiplier = sizePair.displayMultiplier();
		frameByteMultiplier = sizePair.frameMultiplier();
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
		return pendingMemoryMiB.effective(InventoryLimits.INTERNAL_PENDING_MEMORY_MIB_GUARD) * 1024L * 1024L;
	}

	public @Nullable IntLimit getPendingMemoryMiB() {
		return pendingMemoryMiB;
	}

	public void setPendingMemoryMiB(@Nullable IntLimit pendingMemoryMiB) {
		this.pendingMemoryMiB = pendingMemoryMiB;
	}

	/** Source-compatible finite assignment; it deliberately exits unlimited mode. */
	public void setPendingMemoryMiB(int pendingMemoryMiB) {
		this.pendingMemoryMiB = IntLimit.finite(pendingMemoryMiB);
	}

	/** Effective display byte budget: the exact display size scale applied to its base. */
	public long effectiveDisplayBytes() {
		return InventorySizeScale.DISPLAY.bytes(
			InventorySizeScale.DISPLAY.normalize(displayByteMultiplier));
	}

	/** Effective frame byte budget: the exact frame size scale applied to its base. */
	public long effectiveFrameBytes() {
		return InventorySizeScale.FRAME.bytes(
			InventorySizeScale.FRAME.normalize(frameByteMultiplier));
	}

	/** Preview per-client period bytes: 4 KiB scaled by the preview client multiplier. */
	public long previewClientPeriodBytes() {
		return preview.clientByteMultiplier.appliedTo(
			InventoryLimits.PREVIEW_CLIENT_BASE_BYTES,
			InventoryLimits.INTERNAL_BYTE_BUDGET_GUARD,
			ByteMultiplierGrid.CLIENT, ConfigDefaults.Inventory.Preview.CLIENT_BYTE_MULTIPLIER);
	}

	/** Preview global period bytes: 32 KiB scaled by the preview global multiplier. */
	public long previewGlobalPeriodBytes() {
		return preview.globalByteMultiplier.appliedTo(
			InventoryLimits.PREVIEW_GLOBAL_BASE_BYTES,
			InventoryLimits.INTERNAL_BYTE_BUDGET_GUARD,
			ByteMultiplierGrid.GLOBAL, ConfigDefaults.Inventory.Preview.GLOBAL_BYTE_MULTIPLIER);
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
			ByteMultiplierGrid.CLIENT, ConfigDefaults.Inventory.Tracking.STREAM_BYTE_MULTIPLIER);
	}

	/** Tracking snapshot per-fragment bytes: 16 KiB scaled by the snapshot multiplier. */
	public long trackingSnapshotFragmentBytes() {
		return tracking.snapshotByteMultiplier.appliedTo(
			InventoryLimits.TRACKING_SNAPSHOT_BASE_BYTES,
			InventoryLimits.INTERNAL_BYTE_BUDGET_GUARD,
			ByteMultiplierGrid.CLIENT, ConfigDefaults.Inventory.Tracking.SNAPSHOT_BYTE_MULTIPLIER);
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
			ByteMultiplierGrid.GLOBAL, ConfigDefaults.Inventory.Tracking.GLOBAL_BYTE_MULTIPLIER);
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
		if (current < 64) return 4;
		if (current < 256) return 8;
		return 16;
	}

	static IntLimit validatedLimit(@Nullable IntLimit limit, int defaultValue, InventoryConfigValues.Field field) {
		if (limit == null) limit = IntLimit.finite(defaultValue);
		limit.restoreMissingValue(defaultValue);
		limit.setValue(Math.clamp(limit.getValue(), field.minimum(), field.maximum()));
		return limit;
	}

	static ByteMultiplier validatedMultiplier(@Nullable ByteMultiplier multiplier, ByteMultiplierGrid grid,
		BigDecimal defaultValue) {
		if (multiplier == null) multiplier = ByteMultiplier.finite(defaultValue);
		multiplier.validate(grid, defaultValue);
		return multiplier;
	}

	@Getter
	@Setter
	@ToString
	@EqualsAndHashCode
	public static final class Preview {

		private int periodTicks = ConfigDefaults.Inventory.Preview.PERIOD_TICKS;
		private @Nullable IntLimit maxVariantsPerClientPeriod =
			IntLimit.finite(ConfigDefaults.Inventory.Preview.MAX_VARIANTS_PER_CLIENT_PERIOD);
		private @Nullable IntLimit maxSlotsPerClient =
			IntLimit.finite(ConfigDefaults.Inventory.Preview.MAX_SLOTS_PER_CLIENT);
		private @Nullable IntLimit maxSlotsServer =
			IntLimit.finite(ConfigDefaults.Inventory.Preview.MAX_SLOTS_SERVER);
		private @Nullable IntLimit maxTargetsPerClient =
			IntLimit.finite(ConfigDefaults.Inventory.Preview.MAX_TARGETS_PER_CLIENT);
		/** Preview per-client period bytes on the client grid; independent of every other multiplier. */
		private @Nullable ByteMultiplier clientByteMultiplier =
			ByteMultiplier.finite(ConfigDefaults.Inventory.Preview.CLIENT_BYTE_MULTIPLIER);
		/** Preview global period bytes on the global grid; independent of every other multiplier. */
		private @Nullable ByteMultiplier globalByteMultiplier =
			ByteMultiplier.finite(ConfigDefaults.Inventory.Preview.GLOBAL_BYTE_MULTIPLIER);

		void validate() {
			periodTicks = Math.clamp(periodTicks, InventoryLimits.MIN_PERIOD_TICKS, InventoryLimits.MAX_PERIOD_TICKS);
			maxVariantsPerClientPeriod = validatedLimit(maxVariantsPerClientPeriod, ConfigDefaults.Inventory.Preview.MAX_VARIANTS_PER_CLIENT_PERIOD,
				InventoryConfigValues.Field.PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD);
			maxSlotsPerClient = validatedLimit(maxSlotsPerClient, ConfigDefaults.Inventory.Preview.MAX_SLOTS_PER_CLIENT,
				InventoryConfigValues.Field.PREVIEW_MAX_SLOTS_PER_CLIENT);
			maxSlotsServer = validatedLimit(maxSlotsServer, ConfigDefaults.Inventory.Preview.MAX_SLOTS_SERVER,
				InventoryConfigValues.Field.PREVIEW_MAX_SLOTS_SERVER);
			maxTargetsPerClient = validatedLimit(maxTargetsPerClient, ConfigDefaults.Inventory.Preview.MAX_TARGETS_PER_CLIENT,
				InventoryConfigValues.Field.PREVIEW_MAX_TARGETS_PER_CLIENT);
			clientByteMultiplier = validatedMultiplier(clientByteMultiplier, ByteMultiplierGrid.CLIENT,
				ConfigDefaults.Inventory.Preview.CLIENT_BYTE_MULTIPLIER);
			globalByteMultiplier = validatedMultiplier(globalByteMultiplier, ByteMultiplierGrid.GLOBAL,
				ConfigDefaults.Inventory.Preview.GLOBAL_BYTE_MULTIPLIER);
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

		private int periodTicks = ConfigDefaults.Inventory.Tracking.PERIOD_TICKS;
		/** Additional tracking-view partition; never substitutes for current face exposure checks. */
		private boolean distinguishFaces = ConfigDefaults.Inventory.Tracking.DISTINGUISH_FACES;
		/** Additional tracking-view partition; never substitutes for current per-player permission checks. */
		private boolean distinguishPlayers = ConfigDefaults.Inventory.Tracking.DISTINGUISH_PLAYERS;
		private @Nullable IntLimit maxVariantsPerTarget =
			IntLimit.finite(ConfigDefaults.Inventory.Tracking.MAX_VARIANTS_PER_TARGET);
		private @Nullable IntLimit maxSlotsPerTarget =
			IntLimit.finite(ConfigDefaults.Inventory.Tracking.MAX_SLOTS_PER_TARGET);
		private @Nullable IntLimit maxSlotsServer =
			IntLimit.finite(ConfigDefaults.Inventory.Tracking.MAX_SLOTS_SERVER);
		/** Tracking stream bytes per client+target period on the client grid. */
		private @Nullable ByteMultiplier streamByteMultiplier =
			ByteMultiplier.finite(ConfigDefaults.Inventory.Tracking.STREAM_BYTE_MULTIPLIER);
		/** Tracking snapshot bytes per fragment and per client period on the client grid. */
		private @Nullable ByteMultiplier snapshotByteMultiplier =
			ByteMultiplier.finite(ConfigDefaults.Inventory.Tracking.SNAPSHOT_BYTE_MULTIPLIER);
		/** Tracking global period bytes on the global grid. */
		private @Nullable ByteMultiplier globalByteMultiplier =
			ByteMultiplier.finite(ConfigDefaults.Inventory.Tracking.GLOBAL_BYTE_MULTIPLIER);
		/** Resynchronization cooldown in periods; zero skips the check, no unlimited mode. */
		private int resyncMinPeriods = ConfigDefaults.Inventory.Tracking.RESYNC_MIN_PERIODS;
		/** Periodic heartbeat cadence; zero is the explicit disabled value, not unlimited. */
		private int heartbeatPeriods = ConfigDefaults.Inventory.Tracking.HEARTBEAT_PERIODS;
		/** Rolling excess-byte smoothing window; independent of the resync/unknown-stream window. */
		private int gracePeriods = ConfigDefaults.Inventory.Tracking.GRACE_PERIODS;

		void validate() {
			periodTicks = Math.clamp(periodTicks, InventoryLimits.MIN_PERIOD_TICKS, InventoryLimits.MAX_PERIOD_TICKS);
			maxVariantsPerTarget = validatedLimit(maxVariantsPerTarget, ConfigDefaults.Inventory.Tracking.MAX_VARIANTS_PER_TARGET,
				InventoryConfigValues.Field.TRACKING_MAX_VARIANTS_PER_TARGET);
			maxSlotsPerTarget = validatedLimit(maxSlotsPerTarget, ConfigDefaults.Inventory.Tracking.MAX_SLOTS_PER_TARGET,
				InventoryConfigValues.Field.TRACKING_MAX_SLOTS_PER_TARGET);
			maxSlotsServer = validatedLimit(maxSlotsServer, ConfigDefaults.Inventory.Tracking.MAX_SLOTS_SERVER,
				InventoryConfigValues.Field.TRACKING_MAX_SLOTS_SERVER);
			streamByteMultiplier = validatedMultiplier(streamByteMultiplier, ByteMultiplierGrid.CLIENT,
				ConfigDefaults.Inventory.Tracking.STREAM_BYTE_MULTIPLIER);
			snapshotByteMultiplier = validatedMultiplier(snapshotByteMultiplier, ByteMultiplierGrid.CLIENT,
				ConfigDefaults.Inventory.Tracking.SNAPSHOT_BYTE_MULTIPLIER);
			globalByteMultiplier = validatedMultiplier(globalByteMultiplier, ByteMultiplierGrid.GLOBAL,
				ConfigDefaults.Inventory.Tracking.GLOBAL_BYTE_MULTIPLIER);
			resyncMinPeriods = Math.clamp(resyncMinPeriods,
				InventoryLimits.MIN_RESYNC_PERIODS, InventoryLimits.MAX_RESYNC_PERIODS);
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
			return maxSlotsServer.effective(InventoryLimits.INTERNAL_TRACKING_SERVER_SLOT_GUARD);
		}

		/** False only for the explicit zero heartbeat value; other repair paths stay active. */
		public boolean isHeartbeatEnabled() {
			return heartbeatPeriods > 0;
		}
	}
}
