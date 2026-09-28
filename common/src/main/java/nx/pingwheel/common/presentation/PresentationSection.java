package nx.pingwheel.common.presentation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whole-section replacement, not a delta. An empty section is a terminal
 * initial result. Annotations attach one known ping type to an addressed
 * property value; nested record paths stay literal and a value inside a
 * sequence is never addressable.
 */
public record PresentationSection(String adapterId, int schema, Map<String, PresentationValue> fields, boolean stale,
	Map<PresentationPropertyRef, String> annotations) {
	public PresentationSection {
		PresentationIds.validate(adapterId);
		if (schema < 1 || schema > 255 || fields.size() > PresentationLimits.MAX_FIELDS) {
			throw new IllegalArgumentException("section size/schema");
		}
		Map<String, PresentationValue> immutableFields = Map.copyOf(new LinkedHashMap<>(fields));
		for (var entry : immutableFields.entrySet()) {
			PresentationIds.validate(entry.getKey());
			PresentationLimits.validate(entry.getValue());
		}
		fields = immutableFields;
		Map<PresentationPropertyRef, String> immutableAnnotations = Map.copyOf(new LinkedHashMap<>(annotations));
		for (var entry : immutableAnnotations.entrySet()) {
			PresentationPropertyRef ref = entry.getKey();
			if (!ref.adapterId().equals(adapterId)) throw new IllegalArgumentException("annotation adapter mismatch");
			if (PresentationPropertyRef.resolve(immutableFields, ref) == null) throw new IllegalArgumentException("annotation path absent");
			if (!PresentationPropertyPingTypes.isKnownPingTypeId(entry.getValue()))
				throw new IllegalArgumentException("unknown annotation ping type");
		}
		annotations = immutableAnnotations;
	}

	/** Legacy shape without property annotations. */
	public PresentationSection(String adapterId, int schema, Map<String, PresentationValue> fields, boolean stale) {
		this(adapterId, schema, fields, stale, Map.of());
	}

	public static PresentationSection empty(String adapterId, int schema) {
		return new PresentationSection(adapterId, schema, Map.of(), false);
	}
}
