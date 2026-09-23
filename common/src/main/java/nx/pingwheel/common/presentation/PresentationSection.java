package nx.pingwheel.common.presentation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Whole-section replacement, not a delta. An empty section is a terminal initial result. */
public record PresentationSection(String adapterId, int schema, Map<String, PresentationValue> fields, boolean stale) {
	public PresentationSection {
		PresentationIds.validate(adapterId);
		if (schema < 1 || schema > 255 || fields.size() > PresentationLimits.MAX_FIELDS) {
			throw new IllegalArgumentException("section size/schema");
		}
		fields = Map.copyOf(new LinkedHashMap<>(fields));
		fields.forEach((id, value) -> {
			PresentationIds.validate(id);
			PresentationLimits.validate(value);
		});
	}

	public static PresentationSection empty(String adapterId, int schema) {
		return new PresentationSection(adapterId, schema, Map.of(), false);
	}
}
