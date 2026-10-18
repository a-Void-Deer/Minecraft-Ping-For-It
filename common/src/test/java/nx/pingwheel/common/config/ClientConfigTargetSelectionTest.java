package nx.pingwheel.common.config;

import com.google.gson.Gson;
import nx.pingwheel.common.math.RaycastPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigTargetSelectionTest {

	@Test
	void defaultsSelectTransparentBlockOutlinesWithoutIncludingIgnoredEntitiesOrFluids() {
		ClientConfig config = new ClientConfig();

		assertTrue(config.isMarkTransparentBlocks());
		assertFalse(config.isMarkBlacklistedTargets());
		assertFalse(config.isMarkFluids());
		assertEquals(RaycastPolicy.BlockMode.OUTLINE, RaycastPolicy.from(
			config.isMarkTransparentBlocks(), config.isMarkBlacklistedTargets(), config.isMarkFluids()).blockMode());
	}

	@Test
	void targetSelectionTogglesRoundTripThroughConfigJson() {
		ClientConfig original = new ClientConfig();
		original.setMarkTransparentBlocks(false);
		original.setMarkBlacklistedTargets(true);
		original.setMarkFluids(true);

		String json = new Gson().toJson(original);
		ClientConfig restored = new Gson().fromJson(json, ClientConfig.class);

		assertFalse(restored.isMarkTransparentBlocks());
		assertTrue(restored.isMarkBlacklistedTargets());
		assertTrue(restored.isMarkFluids());
		var root = new Gson().toJsonTree(original).getAsJsonObject();
		assertFalse(root.get("markTransparentBlocks").getAsBoolean());
		assertFalse(root.has("passThroughTransparentBlocks"));
	}

	@Test
	void missingAndNullTransparentChoiceKeepTheOutlineDefault() {
		for (String json : java.util.List.of("{}", "{\"markTransparentBlocks\":null}")) {
			ClientConfig config = new Gson().fromJson(json, ClientConfig.class);
			config.validate();
			assertTrue(config.isMarkTransparentBlocks());
		}
	}
}
