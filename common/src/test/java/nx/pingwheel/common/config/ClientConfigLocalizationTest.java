package nx.pingwheel.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigLocalizationTest {

	@Test
	void englishLocaleContainsInteractionSettingsAndUnits() throws IOException {
		String enUs;
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
			"assets/pingforit/lang/en_us.json")) {
			assertNotNull(stream);
			enUs = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertContainsKey(enUs, "settings.pingforit.wheel_hold_millis");
		assertContainsKey(enUs, "settings.pingforit.wheel_timeout_millis");
		assertContainsKey(enUs, "settings.pingforit.cancel_half_cone_angle_degrees");
		assertContainsKey(enUs, "unit.pingforit.milliseconds");
		assertContainsKey(enUs, "unit.pingforit.degrees");
	}

	private static void assertContainsKey(String contents, String key) {
		assertTrue(
			Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:").matcher(contents).find(),
			() -> "missing en_us translation: " + key);
	}
}
