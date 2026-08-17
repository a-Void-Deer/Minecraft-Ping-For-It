package nx.pingwheel.common.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigValidationTest {

	@Test
	void defaultsMatchTheCurrentWheelLook() {
		ClientConfig config = new ClientConfig();

		assertEquals(14, config.getWheelInnerRadius());
		assertEquals(39, config.getWheelOuterRadius());
		assertEquals(100, config.getWheelOpacity());
		assertEquals(100, config.getWheelFontSize());
	}

	@Test
	void directJsonValuesAreValidatedBeforeUse() {
		ClientConfig config = new Gson().fromJson(
			"{\"wheelInnerRadius\":-100,\"wheelOuterRadius\":1000,"
				+ "\"wheelOpacity\":-5,\"wheelFontSize\":999}",
			ClientConfig.class);

		config.validate();

		assertEquals(8, config.getWheelInnerRadius());
		assertEquals(75, config.getWheelOuterRadius());
		assertEquals(0, config.getWheelOpacity());
		assertEquals(200, config.getWheelFontSize());
		assertTrue(config.getWheelOuterRadius() - config.getWheelInnerRadius() >= 9);
	}
}
