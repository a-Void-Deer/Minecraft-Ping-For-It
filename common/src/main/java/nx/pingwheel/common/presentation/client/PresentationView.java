package nx.pingwheel.common.presentation.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationValue;

/** Immutable server-authorized projection; no provider receives the retained store. */
public record PresentationView(String targetTypeId, PresentationPropertyRef defaultRef,
	Map<String, PresentationSection> sections, Map<String, Map<String, String>> fieldLabels) {
	private static final PresentationView EMPTY = new PresentationView(null, null, Map.of(), Map.of());

	public PresentationView {
		sections = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(sections, "sections")));
		Map<String, Map<String, String>> detached = new LinkedHashMap<>();
		Objects.requireNonNull(fieldLabels, "fieldLabels").forEach((adapter, fields) ->
			detached.put(adapter, Map.copyOf(fields)));
		fieldLabels = Map.copyOf(detached);
	}
	public PresentationView(String targetTypeId, PresentationPropertyRef defaultRef,
		Map<String, PresentationSection> sections) {
		this(targetTypeId, defaultRef, sections, Map.of());
	}
	public PresentationView(Map<String, PresentationSection> sections) {
		this(null, null, sections, Map.of());
	}

	public static PresentationView empty() { return EMPTY; }

	public PresentationValue field(String adapter, String id) {
		PresentationSection section = sections.get(adapter);
		return section == null ? null : section.fields().get(id);
	}
	public PresentationValue property(PresentationPropertyRef ref) {
		if (ref == null) return null;
		return ref.resolve(sections.get(ref.adapterId()));
	}
}
