package nx.pingwheel.common.presentation;

import java.util.Objects;

/** Manifest entry; source and field IDs use namespace:path and never depend on display text. */
public record PresentationField(String id, Kind kind, boolean enabledByDefault, int permissionLevel,
	String label) {
	public enum Kind { TEXT, NUMBER, FLAG, SEQUENCE, RECORD }

	public PresentationField {
		PresentationIds.validate(id);
		Objects.requireNonNull(kind);
		if (permissionLevel < 0 || permissionLevel > 4) throw new IllegalArgumentException("permission level");
		if (label == null || label.length() > 128) throw new IllegalArgumentException("label");
	}

	public boolean accepts(PresentationValue value) {
		return switch (kind) {
			case TEXT -> value instanceof PresentationValue.Text;
			case NUMBER -> value instanceof PresentationValue.NumberValue;
			case FLAG -> value instanceof PresentationValue.Flag;
			case SEQUENCE -> value instanceof PresentationValue.Sequence;
			case RECORD -> value instanceof PresentationValue.RecordValue;
		};
	}
}
