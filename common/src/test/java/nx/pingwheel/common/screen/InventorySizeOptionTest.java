package nx.pingwheel.common.screen;

import net.minecraft.network.chat.contents.TranslatableContents;
import nx.pingwheel.common.config.InventorySizeScale;
import nx.pingwheel.common.resource.LanguageUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The size slider is the supported native replacement for the option slider:
 * it addresses the ordered legal positions of its scale, not the shared
 * quarter-multiplier wire quanta, and it carries its keyboard adjustment mode
 * across the page rebuild that refreshes the dependent maximum and notice.
 */
class InventorySizeOptionTest {
	private static final int SPACE = 32;
	private static final int LEFT_ARROW = 263;
	private static final int RIGHT_ARROW = 262;

	@Test
	void heldArrowKeysKeepAdjustingThroughASliderReplacement() {
		var state = new InventorySizeOption.AdjustmentState();
		var values = new ArrayList<BigDecimal>();
		var slider = slider(InventorySizeScale.FRAME, 3, new BigDecimal("0.25"), values::add, state);
		slider.setWidth(11);

		assertTrue(slider.keyPressed(SPACE, 0, 0));
		assertTrue(slider.keyPressed(RIGHT_ARROW, 0, 0));
		assertTrue(slider.keyPressed(RIGHT_ARROW, 0, 0));
		assertEquals(List.of(new BigDecimal("0.5"), new BigDecimal("0.75")), values);

		// The page rebuild replaces the native widget; the carried mode keeps
		// the next arrow adjusting without another Enter/Space press.
		var replacement = slider(InventorySizeScale.FRAME, 3, values.get(values.size() - 1), values::add, state);
		replacement.setWidth(11);
		assertTrue(replacement.keyPressed(RIGHT_ARROW, 0, 0));
		assertEquals(List.of(new BigDecimal("0.5"), new BigDecimal("0.75"), BigDecimal.ONE), values);
		assertTrue(state.isAdjusting());
	}

	@Test
	void anOrdinaryFocusLossClearsTheAdjustmentModeButACarriedReplacementKeepsIt() {
		var state = new InventorySizeOption.AdjustmentState();
		var values = new ArrayList<BigDecimal>();
		var slider = slider(InventorySizeScale.FRAME, 3, new BigDecimal("0.25"), values::add, state);
		slider.setWidth(11);
		assertTrue(slider.keyPressed(SPACE, 0, 0));
		assertTrue(state.isAdjusting());

		slider.setFocused(false);
		assertFalse(state.isAdjusting());
		assertFalse(slider.keyPressed(RIGHT_ARROW, 0, 0));
		assertTrue(values.isEmpty());

		assertTrue(slider.keyPressed(SPACE, 0, 0));
		state.carry(() -> slider.setFocused(false));
		assertTrue(state.isAdjusting());
		assertTrue(slider.keyPressed(RIGHT_ARROW, 0, 0));
		assertEquals(List.of(new BigDecimal("0.5")), values);
	}

	@Test
	void aReadOnlySliderIgnoresKeyboardAdjustment() {
		var state = new InventorySizeOption.AdjustmentState();
		var values = new ArrayList<BigDecimal>();
		var slider = slider(InventorySizeScale.FRAME, 3, new BigDecimal("0.25"), values::add, state);
		slider.setWidth(11);
		slider.active = false;

		assertFalse(slider.keyPressed(SPACE, 0, 0));
		assertFalse(slider.keyPressed(RIGHT_ARROW, 0, 0));
		assertFalse(state.isAdjusting());
		assertTrue(values.isEmpty());
	}

	@Test
	void sliderAddressesOrderedPositionsInsteadOfWireQuanta() {
		var state = new InventorySizeOption.AdjustmentState();
		var values = new ArrayList<BigDecimal>();
		var slider = slider(InventorySizeScale.DISPLAY, 43, new BigDecimal("2"), values::add, state);
		slider.setWidth(8 + 44);

		assertEquals(new BigDecimal("2"), slider.currentValue());
		assertTrue(slider.keyPressed(SPACE, 0, 0));
		assertTrue(slider.keyPressed(RIGHT_ARROW, 0, 0));
		assertEquals(List.of(new BigDecimal("2.5")), values);
		assertEquals(new BigDecimal("2.5"), slider.currentValue());
	}

	@Test
	void realWidthArrowStepsAccumulateToTheNextLegalPositionAcrossTheRebuild() {
		var state = new InventorySizeOption.AdjustmentState();
		var values = new ArrayList<BigDecimal>();
		var slider = slider(InventorySizeScale.FRAME, 63, BigDecimal.ONE, values::add, state);
		slider.setWidth(SettingsScreenLayout.LARGE_WIDGET_WIDTH);
		assertEquals(3, InventorySizeScale.FRAME.indexOf(slider.currentValue()));
		assertTrue(slider.keyPressed(SPACE, 0, 0));

		// At 310 px the arrow step is 1/302 of the track: the first two presses
		// stay inside the default position and must not call the model, while
		// the third accumulates across the boundary to the next legal position.
		assertTrue(slider.keyPressed(RIGHT_ARROW, 0, 0));
		assertTrue(slider.keyPressed(RIGHT_ARROW, 0, 0));
		assertTrue(values.isEmpty());
		assertTrue(slider.keyPressed(RIGHT_ARROW, 0, 0));
		assertEquals(List.of(new BigDecimal("1.25")), values);
		assertEquals(new BigDecimal("1.25"), slider.currentValue());

		// The screen rebuild after that model call replaces the widget; the same
		// carried mode keeps adjusting and Left presses accumulate back.
		var replacement = slider(InventorySizeScale.FRAME, 63, values.get(0), values::add, state);
		replacement.setWidth(SettingsScreenLayout.LARGE_WIDGET_WIDTH);
		assertTrue(replacement.keyPressed(LEFT_ARROW, 0, 0));
		assertTrue(replacement.keyPressed(LEFT_ARROW, 0, 0));
		assertEquals(List.of(new BigDecimal("1.25")), values);
		assertTrue(replacement.keyPressed(LEFT_ARROW, 0, 0));
		assertEquals(List.of(new BigDecimal("1.25"), BigDecimal.ONE), values);
		assertEquals(BigDecimal.ONE, replacement.currentValue());
	}

	@Test
	void fractionOnlyArrowMovesNeverCallTheModel() {
		var state = new InventorySizeOption.AdjustmentState();
		var values = new ArrayList<BigDecimal>();
		var slider = slider(InventorySizeScale.FRAME, 63, BigDecimal.ONE, values::add, state);
		slider.setWidth(SettingsScreenLayout.LARGE_WIDGET_WIDTH);
		assertTrue(slider.keyPressed(SPACE, 0, 0));

		for (int press = 0; press < 2; press++) {
			assertTrue(slider.keyPressed(RIGHT_ARROW, 0, 0));
		}
		for (int press = 0; press < 2; press++) {
			assertTrue(slider.keyPressed(LEFT_ARROW, 0, 0));
		}
		assertTrue(values.isEmpty());
		assertEquals(BigDecimal.ONE, slider.currentValue());
		assertEquals(3, InventorySizeScale.FRAME.indexOf(slider.currentValue()));
	}

	@Test
	void displaySliderRespectsAVaryingMaximumAcrossRebuilds() {
		// A 32 KiB frame allows 19 display positions; at the maximum a Right
		// press cannot leave the grid and must not call the model.
		var cappedValues = new ArrayList<BigDecimal>();
		var capped = slider(InventorySizeScale.DISPLAY, 18, new BigDecimal("7.5"), cappedValues::add,
			new InventorySizeOption.AdjustmentState());
		capped.setWidth(SettingsScreenLayout.LARGE_WIDGET_WIDTH);
		assertTrue(capped.keyPressed(SPACE, 0, 0));
		assertTrue(capped.keyPressed(RIGHT_ARROW, 0, 0));
		assertTrue(capped.keyPressed(RIGHT_ARROW, 0, 0));
		assertTrue(cappedValues.isEmpty());
		assertEquals(new BigDecimal("7.5"), capped.currentValue());

		// The same 19-position grid: 7 fraction-only presses then the crossing.
		var narrowValues = new ArrayList<BigDecimal>();
		var narrow = slider(InventorySizeScale.DISPLAY, 18, BigDecimal.ONE, narrowValues::add,
			new InventorySizeOption.AdjustmentState());
		narrow.setWidth(SettingsScreenLayout.LARGE_WIDGET_WIDTH);
		assertTrue(narrow.keyPressed(SPACE, 0, 0));
		for (int press = 0; press < 7; press++) {
			assertTrue(narrow.keyPressed(RIGHT_ARROW, 0, 0));
		}
		assertTrue(narrowValues.isEmpty());
		assertTrue(narrow.keyPressed(RIGHT_ARROW, 0, 0));
		assertEquals(List.of(new BigDecimal("1.25")), narrowValues);

		// A raised frame replaces the slider with the full 44-position grid: the
		// same accepted value now crosses with fewer presses.
		var raisedValues = new ArrayList<BigDecimal>();
		var raised = slider(InventorySizeScale.DISPLAY, 43, new BigDecimal("1.25"), raisedValues::add,
			new InventorySizeOption.AdjustmentState());
		raised.setWidth(SettingsScreenLayout.LARGE_WIDGET_WIDTH);
		assertTrue(raised.keyPressed(SPACE, 0, 0));
		assertTrue(raised.keyPressed(RIGHT_ARROW, 0, 0));
		assertTrue(raised.keyPressed(RIGHT_ARROW, 0, 0));
		assertTrue(raised.keyPressed(RIGHT_ARROW, 0, 0));
		assertTrue(raisedValues.isEmpty());
		assertTrue(raised.keyPressed(RIGHT_ARROW, 0, 0));
		assertEquals(List.of(new BigDecimal("1.5")), raisedValues);
		assertEquals(new BigDecimal("1.5"), raised.currentValue());
	}

	@Test
	void thePhysicalKiBLabelUsesTheLocalizedUnitPattern() {
		var display = assertInstanceOf(TranslatableContents.class,
			InventorySizeOption.kibLabel(InventorySizeScale.DISPLAY, new BigDecimal("1.5")).getContents());
		assertEquals(LanguageUtils.of("unit", "kib").getKey(), display.getKey());
		assertArrayEquals(new Object[] {6L}, display.getArgs());

		var frame = assertInstanceOf(TranslatableContents.class,
			InventorySizeOption.kibLabel(InventorySizeScale.FRAME, new BigDecimal("16")).getContents());
		assertArrayEquals(new Object[] {512L}, frame.getArgs());
	}

	private static InventorySizeOption.InventorySizeSlider slider(
		InventorySizeScale scale,
		int maximumPosition,
		BigDecimal initial,
		Consumer<BigDecimal> setter,
		InventorySizeOption.AdjustmentState state
	) {
		return InventorySizeOption.slider(
			LanguageUtils.of("test", "size"),
			scale,
			() -> maximumPosition,
			null,
			() -> initial,
			setter,
			state);
	}
}
