package nx.pingwheel.common.math;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySelectionBlacklistDefaultRuleTest {

	@Test
	void simulatedHoneyGlueRegistryIdIsIgnored() {
		assertTrue(EntitySelectionBlacklist.isDefaultIgnoredEntityId(
			ResourceLocation.fromNamespaceAndPath("simulated", "honey_glue")));
	}

	@Test
	void otherRegistryIdsAreNotIgnored() {
		assertFalse(EntitySelectionBlacklist.isDefaultIgnoredEntityId(
			ResourceLocation.fromNamespaceAndPath("simulated", "honey_glue_entity")));
		assertFalse(EntitySelectionBlacklist.isDefaultIgnoredEntityId(
			ResourceLocation.fromNamespaceAndPath("minecraft", "honey_glue")));
		assertFalse(EntitySelectionBlacklist.isDefaultIgnoredEntityId(null));
	}
}
