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

		assertEquals(6, config.getWheelInnerRadius());
		assertEquals(75, config.getWheelOuterRadius());
		assertEquals(0, config.getWheelOpacity());
		assertEquals(200, config.getWheelFontSize());
		assertTrue(config.getWheelOuterRadius() - config.getWheelInnerRadius() >= 8);
	}

	@Test
	void directJsonPairConvergesToTheMinimumValidAnnulus() {
		ClientConfig config = new Gson().fromJson(
			"{\"wheelInnerRadius\":30,\"wheelOuterRadius\":20}",
			ClientConfig.class);

		config.validate();

		assertEquals(12, config.getWheelInnerRadius());
		assertEquals(20, config.getWheelOuterRadius());
	}

	@Test
	void radiusSettersKeepEveryLivePairValidInEitherMutationOrder() {
		ClientConfig config = new ClientConfig();

		config.setWheelOuterRadius(20);
		assertEquals(12, config.getWheelInnerRadius());
		assertEquals(20, config.getWheelOuterRadius());

		config.setWheelInnerRadius(30);
		assertEquals(12, config.getWheelInnerRadius());
		assertEquals(20, config.getWheelOuterRadius());

		config.setWheelOuterRadius(75);
		assertEquals(12, config.getWheelInnerRadius());
		assertEquals(75, config.getWheelOuterRadius());

		config.setWheelInnerRadius(30);
		assertEquals(30, config.getWheelInnerRadius());
		assertEquals(75, config.getWheelOuterRadius());

		config.setWheelInnerRadius(Integer.MIN_VALUE);
		assertEquals(6, config.getWheelInnerRadius());
		assertEquals(75, config.getWheelOuterRadius());

		config.setWheelOuterRadius(Integer.MIN_VALUE);
		assertEquals(6, config.getWheelInnerRadius());
		assertEquals(20, config.getWheelOuterRadius());
		assertTrue(config.getWheelOuterRadius() - config.getWheelInnerRadius() >= 8);
	}
}
