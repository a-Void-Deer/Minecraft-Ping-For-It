package nx.pingwheel.common.config;

/**
 * A validation failure with a bounded, non-payload diagnostic. The detail is
 * intentionally limited to configuration structure so recovery diagnostics do
 * not expose the raw config text or an arbitrary parser/I/O message.
 */
public final class ConfigValidationException extends IllegalArgumentException {
	public enum Reason {
		NULL_LIST("list is null"),
		BLANK_ENTRY("entry is blank or null"),
		INVALID_GRAMMAR("entry has invalid block matcher grammar");

		private final String description;

		Reason(String description) {
			this.description = description;
		}
	}

	private final String fieldName;
	private final int entryIndex;
	private final Reason reason;

	public ConfigValidationException(String fieldName, int entryIndex, Reason reason) {
		super("invalid client config value");
		this.fieldName = fieldName;
		this.entryIndex = entryIndex;
		this.reason = reason;
	}

	public String safeSummary() {
		StringBuilder summary = new StringBuilder("validation: ")
			.append(safeFieldName(fieldName));

		if (entryIndex >= 0) {
			summary.append(" entry ").append(entryIndex);
		}

		return summary.append(" ").append(reason.description).toString();
	}

	private static String safeFieldName(String value) {
		if (value == null || value.isBlank()) {
			return "config";
		}

		StringBuilder sanitized = new StringBuilder();
		for (int index = 0; index < value.length() && sanitized.length() < 80; index++) {
			char character = value.charAt(index);
			if ((character >= 'a' && character <= 'z')
				|| (character >= 'A' && character <= 'Z')
				|| (character >= '0' && character <= '9')
				|| character == '_' || character == '-' || character == '.') {
				sanitized.append(character);
			} else {
				sanitized.append('_');
			}
		}

		return sanitized.isEmpty() ? "config" : sanitized.toString();
	}
}
