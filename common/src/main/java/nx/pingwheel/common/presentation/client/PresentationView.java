package nx.pingwheel.common.presentation.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;

/** Immutable, display-authorized projection. No UI receives the retained receive store. */
public record PresentationView(Map<String, PresentationSection> sections) {
	private static final PresentationView EMPTY = new PresentationView(Map.of());

	public PresentationView {
		sections = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(sections, "sections")));
	}

	public static PresentationView empty() { return EMPTY; }

	public PresentationValue field(String adapter, String id) {
		PresentationSection section = sections.get(adapter);
		return section == null ? null : section.fields().get(id);
	}
}
