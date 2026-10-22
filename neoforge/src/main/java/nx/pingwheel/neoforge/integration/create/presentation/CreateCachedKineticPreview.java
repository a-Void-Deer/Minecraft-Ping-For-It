package nx.pingwheel.neoforge.integration.create.presentation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.preview.PreviewFieldAccess;
import nx.pingwheel.common.presentation.preview.PreviewObservation;

/**
 * Create-free local projection of the cached kinetic network totals. Production
 * adapts a live kinetic block entity to {@link Source}; tests implement the same
 * seam without loading Create classes. A missing receipt, network or
 * cached-field accessor, or a raw value failing its finite, non-negative gate,
 * yields no local value for that field rather than an asserted zero.
 */
public final class CreateCachedKineticPreview {
	/** Receipt, network presence and the signature-gated raw cached totals. */
	public interface Source {
		boolean receivedKinetics();
		boolean hasNetwork();
		boolean hasCachedTotals();
		float cachedStress();
		float cachedCapacity();
	}

	private CreateCachedKineticPreview() {}

	/**
	 * Projects only the demanded stress, capacity and available-capacity fields.
	 * Each raw accessor is read at most once; the derived field reuses the same
	 * pair and promotes both raw floats to double before subtraction.
	 */
	public static Map<String, PreviewFieldAccess.Outcome> observe(
		Source source, Set<String> demand, long tick) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(demand, "demand");
		boolean wantsStress = demand.contains(CreatePresentationAdapter.STRESS)
			|| demand.contains(CreatePresentationAdapter.AVAILABLE_CAPACITY);
		boolean wantsCapacity = demand.contains(CreatePresentationAdapter.CAPACITY)
			|| demand.contains(CreatePresentationAdapter.AVAILABLE_CAPACITY);
		Map<String, PreviewFieldAccess.Outcome> result = new LinkedHashMap<>();
		for (String field : Set.of(CreatePresentationAdapter.STRESS,
			CreatePresentationAdapter.CAPACITY, CreatePresentationAdapter.AVAILABLE_CAPACITY)) {
			if (demand.contains(field)) result.put(field, PreviewFieldAccess.Missing.UNAVAILABLE);
		}
		if ((!wantsStress && !wantsCapacity) || !source.receivedKinetics()) return Map.copyOf(result);
		boolean network;
		try {
			network = source.hasNetwork();
		} catch (RuntimeException | LinkageError unavailable) {
			return Map.copyOf(result);
		}
		if (!network || !hasCachedTotals(source)) return Map.copyOf(result);
		Float stress = wantsStress ? raw(source::cachedStress) : null;
		Float capacity = wantsCapacity ? raw(source::cachedCapacity) : null;
		if (demand.contains(CreatePresentationAdapter.STRESS) && stress != null) {
			put(result, CreatePresentationAdapter.STRESS,
				new PresentationValue.NumberValue(stress.doubleValue()), tick);
		}
		if (demand.contains(CreatePresentationAdapter.CAPACITY) && capacity != null) {
			put(result, CreatePresentationAdapter.CAPACITY,
				new PresentationValue.NumberValue(capacity.doubleValue()), tick);
		}
		if (demand.contains(CreatePresentationAdapter.AVAILABLE_CAPACITY)
			&& stress != null && capacity != null) {
			// Both raw floats are promoted to double before the subtraction, so a
			// large capacity keeps the exact difference instead of a float rounding.
			put(result, CreatePresentationAdapter.AVAILABLE_CAPACITY,
				new PresentationValue.NumberValue(capacity.doubleValue() - stress.doubleValue()), tick);
		}
		return Map.copyOf(result);
	}

	private static boolean hasCachedTotals(Source source) {
		try {
			return source.hasCachedTotals();
		} catch (RuntimeException | LinkageError unavailable) {
			return false;
		}
	}

	private static Float raw(RawGetter getter) {
		try {
			float value = getter.get();
			return Float.isFinite(value) && value >= 0 ? value : null;
		} catch (RuntimeException | LinkageError unavailable) {
			return null;
		}
	}

	private static void put(Map<String, PreviewFieldAccess.Outcome> result, String field,
		PresentationValue value, long tick) {
		result.put(field, new PreviewFieldAccess.Observed(
			new PreviewObservation(value, PreviewObservation.Origin.CLIENT_SYNCED, tick, false)));
	}

	@FunctionalInterface
	private interface RawGetter {
		float get();
	}
}
