package nx.pingwheel.common.presentation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Function;
import net.minecraft.network.chat.Component;

/**
 * Create-free SU value formatting shared by the selector content bridge and the
 * HUD property formatter. Only values already present in the same authorized
 * received projection are consulted; no client world state is sampled here.
 */
public final class PresentationKineticFormat {
	public static final String STRESS_FIELD = "create:kinetic.stress";
	public static final String CAPACITY_FIELD = "create:kinetic.capacity";
	public static final String AVAILABLE_CAPACITY_FIELD = "create:kinetic.available_capacity";
	private static final String PREFIX = "presentation.pingforit.format.";

	private PresentationKineticFormat() {}

	/**
	 * Formats one root kinetic stress, capacity, or available-capacity value as a
	 * semantic SU component, or returns null when the ref/value is not one of
	 * those fields. The authorized lookup resolves capacity from the same
	 * projection; a missing, non-numeric, or non-positive capacity omits the
	 * percentage instead of inventing zero, and a value over capacity stays above
	 * 100 percent.
	 */
	public static Component value(PresentationPropertyRef ref, PresentationValue value,
		Function<PresentationPropertyRef, PresentationValue> authorizedCapacity) {
		if (ref == null || !ref.isRoot() || !(value instanceof PresentationValue.NumberValue number)) return null;
		String field = ref.fieldId();
		boolean stress = STRESS_FIELD.equals(field);
		if (!stress && !CAPACITY_FIELD.equals(field) && !AVAILABLE_CAPACITY_FIELD.equals(field)) return null;
		double amount = number.value();
		if (!Double.isFinite(amount)) return null;
		if (stress) {
			PresentationValue capacity = authorizedCapacity == null ? null
				: authorizedCapacity.apply(PresentationPropertyRef.root(ref.adapterId(), CAPACITY_FIELD));
			if (capacity instanceof PresentationValue.NumberValue capacityNumber) {
				double total = capacityNumber.value();
				if (Double.isFinite(total) && total > 0.0)
					return Component.translatable(PREFIX + "su_percent", decimal(amount), percent(amount, total));
			}
		}
		return Component.translatable(PREFIX + "su", decimal(amount));
	}

	/** Two-decimal HALF_UP, trailing zeros stripped, plain notation. */
	static String decimal(double value) {
		return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
	}

	static String percent(double amount, double capacity) {
		return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(100))
			.divide(BigDecimal.valueOf(capacity), 2, RoundingMode.HALF_UP)
			.stripTrailingZeros().toPlainString();
	}
}
