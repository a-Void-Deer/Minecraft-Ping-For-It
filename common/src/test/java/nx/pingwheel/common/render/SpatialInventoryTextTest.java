package nx.pingwheel.common.render;

import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** The production inventory clipping plan, before native text/scissor submission. */
class SpatialInventoryTextTest {
	private static final StringSplitter FONT = new StringSplitter((point, style) -> 6f);
	private static int width(FormattedText text) { return (int) FONT.stringWidth(text); }

	@ParameterizedTest
	@ValueSource(doubles = {0.75, 1.0, 1.5, 2.0})
	void longQuantityKeepsTheConfiguredScaleAndFitsItsBoundedSlot(double scale) {
		Component quantity = SpatialInventoryLayout.quantity(Long.MAX_VALUE);
		double available = 36;
		var plan = SpatialOverlayRenderer.inventoryText(quantity, available, scale, SpatialInventoryTextTest::width, FONT);
		assertEquals(scale, plan.scale(), "large quantities are clipped, never shrunk to fit");
		assertEquals(available, plan.width());
		assertTrue(plan.text().getString().endsWith("…"));
		assertTrue(width(plan.text()) * plan.scale() <= available);
		assertTrue(plan.text().getString().length() < quantity.getString().length());
	}

	@ParameterizedTest
	@ValueSource(doubles = {0.75, 1.0, 1.5, 2.0})
	void headerStatusAndTinySlotsUseTheSameFixedScaleClippingPolicy(double scale) {
		for (Component text : new Component[] { Component.literal("123456 / 123456"),
			Component.literal("A long localized inventory status"), Component.literal("Forward") }) {
			for (double available : new double[] {0, 3 * scale, 18 * scale, 60 * scale}) {
				var plan = SpatialOverlayRenderer.inventoryText(text, available, scale, SpatialInventoryTextTest::width, FONT);
				assertEquals(scale, plan.scale());
				assertTrue(width(plan.text()) * scale <= available, "even an ellipsis must fit the actual text slot");
			}
		}
		Component shortText = Component.literal("x1");
		var unchanged = SpatialOverlayRenderer.inventoryText(shortText, 60 * scale, scale, SpatialInventoryTextTest::width, FONT);
		assertEquals(shortText.getString(), unchanged.text().getString());
		assertEquals(scale, unchanged.scale());
	}
}
