package nx.pingwheel.common.presentation.preview;

import java.util.ArrayList;
import java.util.List;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationValue;

/** Bounded root/record addresses for the native ContentPort; never sequence indexes. */
public final class PreviewPropertyEntries {
	private PreviewPropertyEntries() {}
	public record Entry(PresentationPropertyRef ref, PreviewObservation observed) {}
	public static List<Entry> of(ClientPresentationPreview.Projection projection) {
		List<Entry> result = new ArrayList<>();
		projection.fields().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
			if (entry.getValue() instanceof PreviewFieldAccess.Observed observed)
				add(result, entry.getKey(), observed.observation());
		});
		return List.copyOf(result);
	}
	private static void add(List<Entry> result, PresentationPropertyRef ref, PreviewObservation observed) {
		if (result.size() >= PresentationPreviewLimits.MAX_PROPERTY_ENTRIES) return;
		// Health is displayed only with its maximum by the owning facade/formatter, not invented here.
		result.add(new Entry(ref, observed));
		if (observed.value() instanceof PresentationValue.RecordValue record) {
			for (String key : record.values().keySet().stream().sorted().toList()) {
				List<String> path = new ArrayList<>(ref.recordPath()); path.add(key);
				PresentationPropertyRef nested;
				try { nested = new PresentationPropertyRef(ref.adapterId(), ref.fieldId(), path); }
				catch (IllegalArgumentException unaddressable) { continue; }
				add(result, nested, new PreviewObservation(record.values().get(key), observed.origin(), observed.observedAtTick(), observed.stale()));
			}
		}
	}
}
