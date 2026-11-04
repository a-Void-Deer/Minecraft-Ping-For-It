package nx.pingwheel.common.diagnostics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

@NotNullByDefault
final class DiagnosticText {
	private DiagnosticText() {}

	static String controlled(String text) {
		if (text.isEmpty() || text.length() > 96 || !text.matches("[A-Za-z0-9_.:/-]+")) {
			throw new IllegalArgumentException("Expected a stable diagnostic code");
		}
		return text;
	}

	static String bounded(String text, int maximum) {
		return text.length() <= maximum ? text : text.substring(0, maximum - 11) + "[truncated]";
	}

	static String scalar(@Nullable String text, int maximum) {
		if (text == null) {
			return "[null]";
		}
		StringBuilder result = new StringBuilder();
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			String token = switch (character) {
				case '\\' -> "\\\\";
				case '\n' -> "\\n";
				case '\r' -> "\\r";
				case '\t' -> "\\t";
				default -> Character.isISOControl(character)
					? "\\u" + "0".repeat(4 - Integer.toHexString(character).length()) + Integer.toHexString(character)
					: Character.toString(character);
			};
			if (result.length() + token.length() > maximum) {
				result.setLength(Math.min(result.length(), maximum - 11));
				return result.append("[truncated]").toString();
			}
			result.append(token);
		}
		return result.toString();
	}
}
