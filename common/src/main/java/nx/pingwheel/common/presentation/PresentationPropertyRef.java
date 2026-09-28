package nx.pingwheel.common.presentation;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Address of one presentable property value: an adapter field plus the literal
 * record keys leading to a nested value. A root ref addresses the top-level
 * field value; a nested ref addresses one record entry. Record keys stay
 * literal, so names such as {@code minecraft:cobblestone} or {@code #counts}
 * are never parsed as selector syntax, and a sequence index is never
 * addressable.
 */
public record PresentationPropertyRef(String adapterId, String fieldId, List<String> recordPath)
	implements Comparable<PresentationPropertyRef> {

	/** Matches the codec's wire bound for one record key. */
	public static final int MAX_KEY_BYTES = 128;

	/** A record key that can appear in an addressable wire path. */
	static boolean isAddressableKey(String key) {
		return key != null && !key.isBlank() && key.getBytes(StandardCharsets.UTF_8).length <= MAX_KEY_BYTES;
	}

	public PresentationPropertyRef {
		PresentationIds.validate(adapterId);
		PresentationIds.validate(fieldId);
		Objects.requireNonNull(recordPath, "recordPath");
		if (recordPath.size() > PresentationLimits.MAX_DEPTH)
			throw new IllegalArgumentException("property record path too deep");
		for (String key : recordPath) {
			if (!isAddressableKey(key)) throw new IllegalArgumentException("invalid property record key");
		}
		recordPath = List.copyOf(recordPath);
	}

	public static PresentationPropertyRef root(String adapterId, String fieldId) {
		return new PresentationPropertyRef(adapterId, fieldId, List.of());
	}

	public boolean isRoot() {
		return recordPath.isEmpty();
	}

	/** The value at this ref, or null when the adapter, field, or a nested key is absent. */
	public PresentationValue resolve(PresentationSection section) {
		if (section == null || !adapterId.equals(section.adapterId())) return null;
		return resolve(section.fields(), this);
	}

	/** The text at this ref, or null when it is absent or not a text value. */
	public String resolveText(PresentationSection section) {
		return resolve(section) instanceof PresentationValue.Text text ? text.value() : null;
	}

	static PresentationValue resolve(Map<String, PresentationValue> fields, PresentationPropertyRef ref) {
		PresentationValue value = fields.get(ref.fieldId());
		for (String key : ref.recordPath()) {
			if (!(value instanceof PresentationValue.RecordValue record)) return null;
			value = record.values().get(key);
			if (value == null) return null;
		}
		return value;
	}

	@Override
	public int compareTo(PresentationPropertyRef other) {
		int adapter = adapterId.compareTo(other.adapterId);
		if (adapter != 0) return adapter;
		int field = fieldId.compareTo(other.fieldId);
		if (field != 0) return field;
		int depth = Math.min(recordPath.size(), other.recordPath.size());
		for (int i = 0; i < depth; i++) {
			int key = recordPath.get(i).compareTo(other.recordPath.get(i));
			if (key != 0) return key;
		}
		return Integer.compare(recordPath.size(), other.recordPath.size());
	}

	/** Deterministic ordering helper for wire encoding and diagnostics. */
	public static final Comparator<PresentationPropertyRef> DETERMINISTIC_ORDER = PresentationPropertyRef::compareTo;
}
