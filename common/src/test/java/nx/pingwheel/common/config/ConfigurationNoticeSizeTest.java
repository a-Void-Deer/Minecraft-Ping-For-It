package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigurationNoticeSizeTest {
	@Test
	void defaultsAndSliderMetadataAreStable() {
		ClientConfig config = new ClientConfig();

		assertEquals(100, config.getConfigurationNoticeSize());
		assertEquals(100, ClientConfigBounds.DEFAULT_CONFIGURATION_NOTICE_SIZE);
		assertEquals(0, ClientConfigBounds.MIN_CONFIGURATION_NOTICE_SIZE);
		assertEquals(500, ClientConfigBounds.MAX_CONFIGURATION_NOTICE_SIZE);
		assertEquals(10, ClientConfigBounds.CONFIGURATION_NOTICE_SIZE_STEP);
	}

	@Test
	void setterAndValidationClampThePersistedValue() {
		ClientConfig config = new ClientConfig();
		config.setConfigurationNoticeSize(-1);
		assertEquals(0, config.getConfigurationNoticeSize());

		config.setConfigurationNoticeSize(501);
		assertEquals(500, config.getConfigurationNoticeSize());

		config.configurationNoticeSize = -42;
		config.validate((key, supplied, effective) -> {});
		assertEquals(0, config.configurationNoticeSize);

		config.configurationNoticeSize = 999;
		config.validate((key, supplied, effective) -> {});
		assertEquals(500, config.configurationNoticeSize);
	}
}
