package nx.pingwheel.common.presentation.inventory;

import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Additional bounded inventory context attached to one diagnostic report. The
 * report terminal owns the stable summary; this object supplies only the extra
 * stage, lane and count detail. It deliberately has no payload, exception or
 * arbitrary map field. Counts use {@link #NONE} when the operation did not have
 * a meaningful value.
 */
@NotNullByDefault
public record InventoryDiagnosticDetail(
	@Nullable String stage,
	long request,
	long commit,
	@Nullable String lane,
	long requested,
	long used,
	long limit
) {
	public static final long NONE = -1L;
	private static final int MAX_CODE_CHARACTERS = 48;

	public InventoryDiagnosticDetail {
		stage = bounded(stage);
		lane = bounded(lane);
	}

	/**
	 * DEEP adds only the short stage/lane handling; DEBUG adds the numeric
	 * association. NORMAL never asks for this object's detail.
	 */
	public String format(LoggingProfile profile) {
		if (profile == LoggingProfile.NORMAL) return "";
		StringBuilder text = new StringBuilder();
		append(text, "stage", stage);
		if (profile == LoggingProfile.DEBUG) {
			appendNumber(text, "request", request);
			appendNumber(text, "commit", commit);
		}
		append(text, "lane", lane);
		if (profile == LoggingProfile.DEBUG) {
			appendNumber(text, "requested", requested);
			appendNumber(text, "used", used);
			appendNumber(text, "limit", limit);
		}
		return text.toString();
	}

	private static void append(StringBuilder text, String name, @Nullable String value) {
		if (value == null || value.isBlank()) return;
		if (!text.isEmpty()) text.append(' ');
		text.append(name).append('=').append(value);
	}

	private static void appendNumber(StringBuilder text, String name, long value) {
		if (value < 0) return;
		if (!text.isEmpty()) text.append(' ');
		text.append(name).append('=').append(value);
	}

	private static @Nullable String bounded(@Nullable String value) {
		if (value == null || value.length() <= MAX_CODE_CHARACTERS) return value;
		return value.substring(0, MAX_CODE_CHARACTERS - 11) + "[truncated]";
	}
}
