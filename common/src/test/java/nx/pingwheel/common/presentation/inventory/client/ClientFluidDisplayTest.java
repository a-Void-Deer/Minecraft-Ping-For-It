package nx.pingwheel.common.presentation.inventory.client;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ClientFluidDisplayTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void strictLiteralProjectionUsesBaseFluidNotBucketAndNeverExposesRawJson() {
		var display = ClientFluidDisplay.resolve("minecraft:water", "block.minecraft.water",
			"{\"version\":1,\"id\":\"minecraft:water\",\"name\":\"{literal name}\"}");
		assertSame(Fluids.WATER, display.fluid());
		assertTrue(display.name().getString().contains("{literal name}"));
		for (String bad : new String[] {"{\"text\":\"raw JSON\"}", "{\"version\":1,\"id\":\"minecraft:lava\"}"}) {
			var fallback = ClientFluidDisplay.resolve("minecraft:water", "block.minecraft.water", bad);
			assertNull(fallback.fluid()); assertFalse(fallback.name().getString().contains("raw JSON"));
		}
		var unknown = ClientFluidDisplay.resolve("missing:fluid", "{\"unsafe\":true}", "{\"version\":1,\"id\":\"missing:fluid\"}");
		assertNull(unknown.fluid()); assertFalse(unknown.name().getString().contains("unsafe"));
	}
}
