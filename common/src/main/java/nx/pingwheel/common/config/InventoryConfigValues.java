package nx.pingwheel.common.config;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Detached inventory administration values; never shares a mutable persisted object. */
public record InventoryConfigValues(Map<Field, Value> fields) {

	/** A scalar or an atomic cap/multiplier, including its retained finite value. */
	public record Value(boolean unlimited, BigDecimal value) {
		public Value {
			if (value != null) value = ByteMultiplierGrid.canonical(value);
		}

		public static Value scalar(int value) {
			return new Value(false, BigDecimal.valueOf(value));
		}

		public static Value limit(boolean unlimited, int value) {
			return new Value(unlimited, BigDecimal.valueOf(value));
		}
	}

	public enum Kind {
		INTEGER, LIMIT, CLIENT_MULTIPLIER, GLOBAL_MULTIPLIER
	}

	/** Stable leaf bits; the original five administration bits remain unchanged. */
	public enum Field {
		PHYSICAL_SLOTS_PER_TICK(5, Kind.LIMIT, InventoryLimits.MIN_FINITE_LIMIT, Integer.MAX_VALUE),
		PENDING_MEMORY_MIB(6, Kind.INTEGER, InventoryLimits.MIN_PENDING_MEMORY_MIB, InventoryLimits.MAX_PENDING_MEMORY_MIB),
		PREVIEW_PERIOD_TICKS(7, Kind.INTEGER, InventoryLimits.MIN_PERIOD_TICKS, InventoryLimits.MAX_PERIOD_TICKS),
		PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD(8, Kind.LIMIT, InventoryLimits.MIN_FINITE_LIMIT, Integer.MAX_VALUE),
		PREVIEW_MAX_SLOTS_PER_CLIENT(9, Kind.LIMIT, InventoryLimits.MIN_FINITE_LIMIT, Integer.MAX_VALUE),
		PREVIEW_MAX_SLOTS_SERVER(10, Kind.LIMIT, InventoryLimits.MIN_FINITE_LIMIT, Integer.MAX_VALUE),
		PREVIEW_MAX_TARGETS_PER_CLIENT(11, Kind.LIMIT, InventoryLimits.MIN_FINITE_LIMIT, Integer.MAX_VALUE),
		PREVIEW_CLIENT_BYTE_MULTIPLIER(12, Kind.CLIENT_MULTIPLIER, 0, 0),
		PREVIEW_GLOBAL_BYTE_MULTIPLIER(13, Kind.GLOBAL_MULTIPLIER, 0, 0),
		TRACKING_PERIOD_TICKS(14, Kind.INTEGER, InventoryLimits.MIN_PERIOD_TICKS, InventoryLimits.MAX_PERIOD_TICKS),
		TRACKING_MAX_VARIANTS_PER_TARGET(15, Kind.LIMIT, InventoryLimits.MIN_FINITE_LIMIT, Integer.MAX_VALUE),
		TRACKING_MAX_SLOTS_PER_TARGET(16, Kind.LIMIT, InventoryLimits.MIN_FINITE_LIMIT, Integer.MAX_VALUE),
		TRACKING_MAX_SLOTS_SERVER(17, Kind.LIMIT, InventoryLimits.MIN_FINITE_LIMIT, Integer.MAX_VALUE),
		TRACKING_STREAM_BYTE_MULTIPLIER(18, Kind.CLIENT_MULTIPLIER, 0, 0),
		TRACKING_SNAPSHOT_BYTE_MULTIPLIER(19, Kind.CLIENT_MULTIPLIER, 0, 0),
		TRACKING_GLOBAL_BYTE_MULTIPLIER(20, Kind.GLOBAL_MULTIPLIER, 0, 0),
		TRACKING_RESYNC_MIN_PERIODS(21, Kind.INTEGER, InventoryLimits.MIN_RESYNC_PERIODS, Integer.MAX_VALUE),
		TRACKING_HEARTBEAT_PERIODS(22, Kind.INTEGER, InventoryLimits.MIN_HEARTBEAT_PERIODS, InventoryLimits.MAX_HEARTBEAT_PERIODS),
		/** Rolling excess-byte smoothing, not the unknown-baseline buffer window. */
		TRACKING_GRACE_PERIODS(23, Kind.INTEGER, InventoryLimits.MIN_GRACE_PERIODS, InventoryLimits.MAX_GRACE_PERIODS);

		private final int mask;
		private final Kind kind;
		private final int minimum;
		private final int maximum;

		Field(int bit, Kind kind, int minimum, int maximum) {
			this.mask = 1 << bit;
			this.kind = kind;
			this.minimum = minimum;
			this.maximum = maximum;
		}

		public int mask() { return mask; }
		public Kind kind() { return kind; }
		public int minimum() { return minimum; }
		public int maximum() { return maximum; }
		public boolean supportsUnlimited() { return kind != Kind.INTEGER; }
		public boolean isMultiplier() { return kind == Kind.CLIENT_MULTIPLIER || kind == Kind.GLOBAL_MULTIPLIER; }
		public ByteMultiplierGrid grid() {
			if (!isMultiplier()) throw new IllegalStateException("not a multiplier: " + this);
			return kind == Kind.CLIENT_MULTIPLIER ? ByteMultiplierGrid.CLIENT : ByteMultiplierGrid.GLOBAL;
		}

		/** Network values must already be safe; persisted-file defaulting/clamping is separate. */
		public boolean isSafe(Value supplied) {
			if (supplied == null || supplied.value() == null || supplied.unlimited() && !supportsUnlimited()) return false;
			BigDecimal value = supplied.value();
			if (isMultiplier()) {
				return value.compareTo(grid().minimum()) >= 0
					&& value.compareTo(grid().maximum()) <= 0
					&& value.compareTo(grid().normalize(value)) == 0;
			}
			try {
				int integer = value.intValueExact();
				return integer >= minimum && integer <= maximum;
			} catch (ArithmeticException invalid) {
				return false;
			}
		}

		/** Draft parsing keeps malformed or out-of-range text available for correction. */
		public Optional<Value> parse(String text, boolean unlimited) {
			if (text == null || text.isEmpty()) return Optional.empty();
			try {
				BigDecimal value;
				if (isMultiplier()) {
					// Bound text before decimal parsing; exponents and non-finite spellings are not UI numbers.
					if (text.length() > 32 || !text.matches("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) return Optional.empty();
					value = new BigDecimal(text);
				} else {
					value = BigDecimal.valueOf(Integer.parseInt(text));
				}
				Value result = new Value(unlimited, value);
				return isSafe(result) ? Optional.of(result) : Optional.empty();
			} catch (NumberFormatException invalid) {
				return Optional.empty();
			}
		}
	}

	public InventoryConfigValues {
		var copy = new EnumMap<Field, Value>(Field.class);
		if (fields != null) copy.putAll(fields);
		fields = Collections.unmodifiableMap(copy);
	}

	public static InventoryConfigValues defaults() {
		return from(InventorySettings.serverDefaults());
	}

	/** Copies exactly the configured fields without mutating or normalizing the source. */
	public static InventoryConfigValues from(InventorySettings settings) {
		if (settings == null || settings.getPreview() == null || settings.getTracking() == null) return null;
		var values = new EnumMap<Field, Value>(Field.class);
		var preview = settings.getPreview();
		var tracking = settings.getTracking();
		values.put(Field.PHYSICAL_SLOTS_PER_TICK, copy(settings.getPhysicalSlotsPerTick()));
		values.put(Field.PENDING_MEMORY_MIB, Value.scalar(settings.getPendingMemoryMiB()));
		values.put(Field.PREVIEW_PERIOD_TICKS, Value.scalar(preview.getPeriodTicks()));
		values.put(Field.PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD, copy(preview.getMaxVariantsPerClientPeriod()));
		values.put(Field.PREVIEW_MAX_SLOTS_PER_CLIENT, copy(preview.getMaxSlotsPerClient()));
		values.put(Field.PREVIEW_MAX_SLOTS_SERVER, copy(preview.getMaxSlotsServer()));
		values.put(Field.PREVIEW_MAX_TARGETS_PER_CLIENT, copy(preview.getMaxTargetsPerClient()));
		values.put(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER, copy(preview.getClientByteMultiplier()));
		values.put(Field.PREVIEW_GLOBAL_BYTE_MULTIPLIER, copy(preview.getGlobalByteMultiplier()));
		values.put(Field.TRACKING_PERIOD_TICKS, Value.scalar(tracking.getPeriodTicks()));
		values.put(Field.TRACKING_MAX_VARIANTS_PER_TARGET, copy(tracking.getMaxVariantsPerTarget()));
		values.put(Field.TRACKING_MAX_SLOTS_PER_TARGET, copy(tracking.getMaxSlotsPerTarget()));
		values.put(Field.TRACKING_MAX_SLOTS_SERVER, copy(tracking.getMaxSlotsServer()));
		values.put(Field.TRACKING_STREAM_BYTE_MULTIPLIER, copy(tracking.getStreamByteMultiplier()));
		values.put(Field.TRACKING_SNAPSHOT_BYTE_MULTIPLIER, copy(tracking.getSnapshotByteMultiplier()));
		values.put(Field.TRACKING_GLOBAL_BYTE_MULTIPLIER, copy(tracking.getGlobalByteMultiplier()));
		values.put(Field.TRACKING_RESYNC_MIN_PERIODS, Value.scalar(tracking.getResyncMinPeriods()));
		values.put(Field.TRACKING_HEARTBEAT_PERIODS, Value.scalar(tracking.getHeartbeatPeriods()));
		values.put(Field.TRACKING_GRACE_PERIODS, Value.scalar(tracking.getGracePeriods()));
		return new InventoryConfigValues(values);
	}

	public Value value(Field field) { return fields.get(field); }

	public boolean isSafe() {
		if (fields.size() != Field.values().length) return false;
		for (Field field : Field.values()) if (!field.isSafe(value(field))) return false;
		return true;
	}

	public InventoryConfigValues with(Field field, Value value) {
		var copy = new EnumMap<Field, Value>(Field.class);
		copy.putAll(fields);
		copy.put(field, value);
		return new InventoryConfigValues(copy);
	}

	/** Every unselected leaf is retained from this current authoritative value. */
	public InventoryConfigValues merge(int changedFields, InventoryConfigValues replacement) {
		var merged = new EnumMap<Field, Value>(Field.class);
		merged.putAll(fields);
		for (Field field : Field.values()) {
			if ((changedFields & field.mask()) != 0) merged.put(field, replacement.value(field));
		}
		return new InventoryConfigValues(merged);
	}

	/** Returns fresh nested persisted objects for production assignment after a successful merge. */
	public InventorySettings toSettings() {
		if (!isSafe()) throw new IllegalStateException("unsafe inventory configuration");
		var settings = InventorySettings.serverDefaults();
		var preview = settings.getPreview();
		var tracking = settings.getTracking();
		settings.setPhysicalSlotsPerTick(limit(Field.PHYSICAL_SLOTS_PER_TICK));
		settings.setPendingMemoryMiB(integer(Field.PENDING_MEMORY_MIB));
		preview.setPeriodTicks(integer(Field.PREVIEW_PERIOD_TICKS));
		preview.setMaxVariantsPerClientPeriod(limit(Field.PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD));
		preview.setMaxSlotsPerClient(limit(Field.PREVIEW_MAX_SLOTS_PER_CLIENT));
		preview.setMaxSlotsServer(limit(Field.PREVIEW_MAX_SLOTS_SERVER));
		preview.setMaxTargetsPerClient(limit(Field.PREVIEW_MAX_TARGETS_PER_CLIENT));
		preview.setClientByteMultiplier(multiplier(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER));
		preview.setGlobalByteMultiplier(multiplier(Field.PREVIEW_GLOBAL_BYTE_MULTIPLIER));
		tracking.setPeriodTicks(integer(Field.TRACKING_PERIOD_TICKS));
		tracking.setMaxVariantsPerTarget(limit(Field.TRACKING_MAX_VARIANTS_PER_TARGET));
		tracking.setMaxSlotsPerTarget(limit(Field.TRACKING_MAX_SLOTS_PER_TARGET));
		tracking.setMaxSlotsServer(limit(Field.TRACKING_MAX_SLOTS_SERVER));
		tracking.setStreamByteMultiplier(multiplier(Field.TRACKING_STREAM_BYTE_MULTIPLIER));
		tracking.setSnapshotByteMultiplier(multiplier(Field.TRACKING_SNAPSHOT_BYTE_MULTIPLIER));
		tracking.setGlobalByteMultiplier(multiplier(Field.TRACKING_GLOBAL_BYTE_MULTIPLIER));
		tracking.setResyncMinPeriods(integer(Field.TRACKING_RESYNC_MIN_PERIODS));
		tracking.setHeartbeatPeriods(integer(Field.TRACKING_HEARTBEAT_PERIODS));
		tracking.setGracePeriods(integer(Field.TRACKING_GRACE_PERIODS));
		return settings;
	}

	public static int allFieldMask() {
		int mask = 0;
		for (Field field : Field.values()) mask |= field.mask();
		return mask;
	}

	private int integer(Field field) { return value(field).value().intValueExact(); }
	private IntLimit limit(Field field) {
		return value(field).unlimited() ? IntLimit.unlimited(integer(field)) : IntLimit.finite(integer(field));
	}
	private ByteMultiplier multiplier(Field field) {
		var multiplier = ByteMultiplier.finite(value(field).value());
		multiplier.setUnlimited(value(field).unlimited());
		return multiplier;
	}
	private static Value copy(IntLimit limit) {
		return limit == null ? null : Value.limit(limit.isUnlimited(), limit.getValue());
	}
	private static Value copy(ByteMultiplier multiplier) {
		return multiplier == null ? null : new Value(multiplier.isUnlimited(), multiplier.getValue());
	}
}
