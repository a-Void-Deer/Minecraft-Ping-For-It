package nx.pingwheel.common.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionToggleNoticeRenderPolicyTest {
	@Test
	void anchorsAtCenterAndAQuarterOfTheGuiHeight() {
		assertEquals(400.0F, SelectionToggleNoticeRenderPolicy.anchorX(800));
		assertEquals(200.0F, SelectionToggleNoticeRenderPolicy.anchorY(800));
	}

	@Test
	void scalesAroundTheAnchorAndZeroDisablesRendering() {
		assertEquals(1.0F, SelectionToggleNoticeRenderPolicy.scaleFor(100));
		assertEquals(2.5F, SelectionToggleNoticeRenderPolicy.scaleFor(250));
		assertEquals(0.0F, SelectionToggleNoticeRenderPolicy.scaleFor(0));
		assertTrue(SelectionToggleNoticeRenderPolicy.isVisibleAtSize(10));
		assertFalse(SelectionToggleNoticeRenderPolicy.isVisibleAtSize(0));
	}
}
