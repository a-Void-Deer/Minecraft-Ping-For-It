package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import nx.pingwheel.common.config.InventorySettings;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Exact finite stops followed by an optional unlimited stop; only the small index is interpolated. */
@NotNullByDefault
final class InventoryPerformanceScale {
	private final Field field;
	private final @Unmodifiable List<BigDecimal> finiteValues;

	private InventoryPerformanceScale(Field field, List<BigDecimal> finiteValues) {
		this.field = field;
		this.finiteValues = List.copyOf(finiteValues);
	}

	static InventoryPerformanceScale forField(Field field) {
		if (field.isSizeScale()) throw new IllegalArgumentException("size sliders have their own paired scale");
		var stops = new ArrayList<BigDecimal>();
		if (field.isMultiplier()) {
			for (BigDecimal value = field.grid().minimum();; value = field.grid().next(value)) {
				stops.add(value);
				if (value.compareTo(field.grid().maximum()) == 0) break;
			}
		} else {
			for (int value = field.minimum();; value = Math.min(field.maximum(), value + step(field, value))) {
				stops.add(BigDecimal.valueOf(value));
				if (value == field.maximum()) break;
			}
		}
		return new InventoryPerformanceScale(field, stops);
	}

	private static int step(Field field, int value) {
		return switch (field) {
			case PHYSICAL_SLOTS_PER_TICK, PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD,
				PREVIEW_MAX_SLOTS_PER_CLIENT, PREVIEW_MAX_SLOTS_SERVER, TRACKING_MAX_SLOTS_PER_TARGET ->
				value < 64 ? 2 : value < 256 ? 4 : 8;
			case TRACKING_MAX_SLOTS_SERVER -> value < 512 ? 32 : value < 2048 ? 128 : 512;
			case PENDING_MEMORY_MIB -> InventorySettings.pendingMemoryStepMiB(value);
			default -> 1;
		};
	}

	/** Legal off-grid integers are additional stops, never replacements for an existing grid stop. */
	InventoryPerformanceScale including(Value value) {
		if (!field.isSafe(value) || finiteValues.contains(value.value())) return this;
		var ordered = new TreeSet<BigDecimal>(finiteValues);
		ordered.add(value.value());
		return new InventoryPerformanceScale(field, new ArrayList<>(ordered));
	}

	boolean belongsTo(Field field) {
		return this.field == field;
	}

	@Unmodifiable List<BigDecimal> finiteValues() {
		return finiteValues;
	}

	int size() {
		return finiteValues.size() + (field.supportsUnlimited() ? 1 : 0);
	}

	boolean isUnlimited(int position) {
		checkPosition(position);
		return position == finiteValues.size();
	}

	BigDecimal finiteValueAt(int position) {
		checkPosition(position);
		if (isUnlimited(position)) throw new IllegalArgumentException("unlimited has no replacement finite value");
		return finiteValues.get(position);
	}

	int positionOf(Value value) {
		if (value.unlimited() && field.supportsUnlimited()) return finiteValues.size();
		int position = finiteValues.indexOf(value.value());
		if (position < 0) throw new IllegalArgumentException("value has no stop: " + value);
		return position;
	}

	private void checkPosition(int position) {
		if (position < 0 || position >= size()) throw new IllegalArgumentException("invalid slider position: " + position);
	}
}
