package nx.pingwheel.common.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigurationNoticeSizeTest {
	@Test
	void setterAndValidationClampThePersistedValue() {
		ClientConfig config = new ClientConfig();
		config.setConfigurationNoticeSize(ClientConfigBounds.MIN_CONFIGURATION_NOTICE_SIZE - 1);
		assertEquals(ClientConfigBounds.MIN_CONFIGURATION_NOTICE_SIZE, config.getConfigurationNoticeSize());

		config.setConfigurationNoticeSize(ClientConfigBounds.MAX_CONFIGURATION_NOTICE_SIZE + 1);
		assertEquals(ClientConfigBounds.MAX_CONFIGURATION_NOTICE_SIZE, config.getConfigurationNoticeSize());

		ClientConfig lowFromJson = new Gson().fromJson(
			"{\"configurationNoticeSize\":" + Integer.MIN_VALUE + "}", ClientConfig.class);
		lowFromJson.validate((key, supplied, effective) -> {});
		assertEquals(ClientConfigBounds.MIN_CONFIGURATION_NOTICE_SIZE, lowFromJson.getConfigurationNoticeSize());

		ClientConfig highFromJson = new Gson().fromJson(
			"{\"configurationNoticeSize\":" + Integer.MAX_VALUE + "}", ClientConfig.class);
		highFromJson.validate((key, supplied, effective) -> {});
		assertEquals(ClientConfigBounds.MAX_CONFIGURATION_NOTICE_SIZE, highFromJson.getConfigurationNoticeSize());
	}
}
