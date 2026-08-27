package nx.pingwheel.common.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import nx.pingwheel.common.resource.LanguageUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionToggleNoticeLocalizationTest {
	private static final List<String> LOCALES = List.of(
		"en_us", "de_de", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");

	@Test
	void everyExistingLanguageContainsNoticeAndSettingTranslations() throws IOException {
		for (String locale : LOCALES) {
			String path = "assets/pingforit/lang/" + locale + ".json";
			try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
				assertTrue(stream != null, "missing language resource: " + path);
				String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
				assertTrue(json.contains('"' + SelectionToggleNoticeState.Kind.PASS_THROUGH_TRANSPARENT_BLOCKS.translationKey() + '"'), path);
				assertTrue(json.contains('"' + SelectionToggleNoticeState.Kind.MARK_BLACKLISTED_TARGETS.translationKey() + '"'), path);
				assertTrue(json.contains('"' + SelectionToggleNoticeState.Kind.MARK_FLUIDS.translationKey() + '"'), path);
				assertTrue(json.contains('"' + LanguageUtils.keyOf("notice", "on") + '"'), path);
				assertTrue(json.contains('"' + LanguageUtils.keyOf("notice", "off") + '"'), path);
				assertTrue(json.contains('"' + LanguageUtils.settings("configuration_notice_size").getKey() + '"'), path);
				if (locale.equals("zh_cn")) {
					assertTrue(json.contains("\"notice.pingforit.pass_through_transparent_blocks\": \"标记穿过透明方块: \""), path);
					assertTrue(json.contains("\"notice.pingforit.mark_blacklisted_targets\": \"标记黑名单目标: \""), path);
					assertTrue(json.contains("\"notice.pingforit.mark_fluids\": \"标记液体: \""), path);
					assertTrue(json.contains("\"notice.pingforit.on\": \"开\""), path);
					assertTrue(json.contains("\"notice.pingforit.off\": \"关\""), path);
				}
			}
		}
	}
}
