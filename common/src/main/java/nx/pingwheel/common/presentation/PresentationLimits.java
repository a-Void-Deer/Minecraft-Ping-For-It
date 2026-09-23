package nx.pingwheel.common.presentation;

import java.nio.charset.StandardCharsets;

/** Pure presentation value bounds; validation does not load Minecraft networking classes. */
public final class PresentationLimits {
	public static final int MAX_FIELDS = 48;
	public static final int MAX_ENTRIES = 32;
	public static final int MAX_DEPTH = 4;
	public static final int MAX_TEXT_BYTES = 1024;
	public static final int MAX_FIELD_BYTES = 4096;
	public static final int MAX_SECTION_BYTES = 32768;
	private PresentationLimits() {}

	public static void validate(PresentationValue value) { validate(value, 0); }

	private static void validate(PresentationValue value, int depth) {
		if (depth > MAX_DEPTH || value == null) throw new IllegalArgumentException("presentation depth/value");
		switch (value) {
			case PresentationValue.Text text -> {
				if (text.value().getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES)
					throw new IllegalArgumentException("presentation text too long");
			}
			case PresentationValue.NumberValue ignored -> {}
			case PresentationValue.Flag ignored -> {}
			case PresentationValue.Sequence list -> {
				if (list.values().size() > MAX_ENTRIES) throw new IllegalArgumentException("presentation list too long");
				list.values().forEach(v -> validate(v, depth + 1));
			}
			case PresentationValue.RecordValue record -> {
				if (record.values().size() > MAX_ENTRIES) throw new IllegalArgumentException("presentation map too large");
				record.values().forEach((key, v) -> {
					if (key == null || key.getBytes(StandardCharsets.UTF_8).length > 128)
						throw new IllegalArgumentException("presentation map key");
					validate(v, depth + 1);
				});
			}
		}
	}
}
