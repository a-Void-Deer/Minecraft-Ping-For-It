package nx.pingwheel.common.presentation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit adapter registration on each endpoint; no reflective object/field dumping. */
public final class PresentationRegistry {
	private final Map<String, PresentationAdapter> adapters = new LinkedHashMap<>();

	public synchronized void register(PresentationAdapter adapter) {
		PresentationIds.validate(adapter.adapterId());
		if (adapter.schema() < 1 || adapter.schema() > 255 || adapter.minUpdateIntervalTicks() < 1
			|| adapter.fields().size() > PresentationCodec.MAX_FIELDS) throw new IllegalArgumentException("adapter metadata");
		var ids = new java.util.HashSet<String>();
		for (var field : adapter.fields()) if (!ids.add(field.id())) throw new IllegalArgumentException("duplicate field");
		if (adapters.putIfAbsent(adapter.adapterId(), adapter) != null) throw new IllegalArgumentException("duplicate adapter");
	}

	public synchronized PresentationAdapter get(String id) { return adapters.get(id); }
	public synchronized List<PresentationAdapter> all() { return List.copyOf(adapters.values()); }
}
