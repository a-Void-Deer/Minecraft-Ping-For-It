package nx.pingwheel.common.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class PingTypeLocalizationCatalogTest {

	@Test
	void builtInDisplayKeysAndTargetFallbacksHaveEnglishEntries() throws IOException {
		String enUs;
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
			"assets/pingforit/lang/en_us.json")) {
			assertNotNull(stream);
			enUs = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}

		for (PingType pingType : PingTypeCatalog.builtIn().entries()) {
			assertFalse(pingType.displayKey().isBlank());
			assertNonBlankTranslation(enUs, pingType.displayKey());
		}

		assertNonBlankTranslation(enUs, "pingforit.target.here");
		assertNonBlankTranslation(enUs, "pingforit.target.unknown");
	}

	private static void assertNonBlankTranslation(String enUs, String key) {
		Pattern pattern = Pattern.compile(
			"\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
		Matcher matcher = pattern.matcher(enUs);

		assertTrue(matcher.find(), () -> "missing en_us translation: " + key);
		assertFalse(matcher.group(1).isBlank(), () -> "blank en_us translation: " + key);
	}
}
