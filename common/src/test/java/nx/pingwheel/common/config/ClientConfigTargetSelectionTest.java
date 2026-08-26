package nx.pingwheel.common.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigTargetSelectionTest {

	@Test
	void targetSelectionTogglesDefaultToDisabled() {
		ClientConfig config = new ClientConfig();

		assertFalse(config.isPassThroughTransparentBlocks());
		assertFalse(config.isMarkBlacklistedTargets());
		assertFalse(config.isMarkFluids());
	}

	@Test
	void targetSelectionTogglesRoundTripThroughConfigJson() {
		ClientConfig original = new ClientConfig();
		original.setPassThroughTransparentBlocks(true);
		original.setMarkBlacklistedTargets(true);
		original.setMarkFluids(true);

		String json = new Gson().toJson(original);
		ClientConfig restored = new Gson().fromJson(json, ClientConfig.class);

		assertTrue(restored.isPassThroughTransparentBlocks());
		assertTrue(restored.isMarkBlacklistedTargets());
		assertTrue(restored.isMarkFluids());
	}
}
