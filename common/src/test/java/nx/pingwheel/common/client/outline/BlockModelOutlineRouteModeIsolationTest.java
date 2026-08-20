package nx.pingwheel.common.client.outline;

import nx.pingwheel.common.config.EntityBlockRenderMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockModelOutlineRouteModeIsolationTest {
	@Test
	void ordinaryBlockRouteRemainsIndependentOfEntityBlockMode() {
		for (EntityBlockRenderMode mode : EntityBlockRenderMode.values()) {
			assertEquals(BlockModelOutlineRoute.BLOCK_DISPLAY,
				BlockModelOutlineRoute.route(BlockModelOutlineRoute.TARGET_TYPE_BLOCK, true),
				mode.toString());
		}
	}
}
