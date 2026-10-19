package nx.pingwheel.common.screen;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import nx.pingwheel.common.config.SpatialSelectorSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Decimal settings keep fractional persisted values instead of truncating them into integer ticks. */
class OptionUtilsDecimalTest {
	@Test
	void decimalCodecRoundTripsEveryQuarterStepAndRejectsValuesOutsideTheRange() {
		OptionInstance<BigDecimal> option = option(new AtomicReference<>(new BigDecimal("1.5")));
		for (int quarters = 1; quarters <= 80; quarters++) {
			BigDecimal value = new BigDecimal("0.25").multiply(BigDecimal.valueOf(quarters));
			var encoded = option.codec().encodeStart(JsonOps.INSTANCE, value).getOrThrow();
			assertEquals(0, value.compareTo(encoded.getAsBigDecimal()));
			assertEquals(0, value.compareTo(option.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow()));
		}
		assertTrue(option.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(0)).error().isPresent());
		assertTrue(option.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(20.25)).error().isPresent());
	}

	@Test
	void openingAnOptionDoesNotSnapAValidOffGridPreferenceOrMutateTheConfig() {
		AtomicReference<BigDecimal> value = new AtomicReference<>(new BigDecimal("1.3"));
		OptionInstance<BigDecimal> option = option(value);
		assertEquals(new BigDecimal("1.3"), value.get());
		assertEquals(new BigDecimal("1.3"), option.get());
	}

	private static OptionInstance<BigDecimal> option(AtomicReference<BigDecimal> value) {
		return OptionUtils.ofDecimal("test.decimal", new BigDecimal("0.25"), new BigDecimal("20"),
			new BigDecimal("0.25"), number -> Component.literal(number.toPlainString()),
			null, value::get, value::set);
	}

	@Test
	void inventoryFractionSlidersAddressBothEndpointsAndEveryFiveHundredthsStep() throws ReflectiveOperationException {
		SpatialSelectorSettings settings = new SpatialSelectorSettings();
		OptionInstance<BigDecimal> width = inventoryWidthOption(settings);
		OptionInstance<BigDecimal> height = inventoryHeightOption(settings);
		assertGrid(width, 20, 80);
		assertGrid(height, 30, 95);
		assertTrue(width.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(new BigDecimal("0.19"))).error().isPresent());
		assertTrue(width.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(new BigDecimal("0.81"))).error().isPresent());
		assertTrue(height.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(new BigDecimal("0.29"))).error().isPresent());
		assertTrue(height.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(new BigDecimal("0.96"))).error().isPresent());
	}

	@Test
	void inventoryFractionOptionCodecsRetainOffGridPrecisionWithoutChangingTheModel() {
		SpatialSelectorSettings settings = new SpatialSelectorSettings();
		settings.setInventoryMaxWidthFraction(new BigDecimal("0.473"));
		settings.setInventoryMaxHeightFraction(new BigDecimal("0.81234"));
		OptionInstance<BigDecimal> width = inventoryWidthOption(settings);
		OptionInstance<BigDecimal> height = inventoryHeightOption(settings);
		assertEquals(new BigDecimal("0.473"), width.get());
		assertEquals(new BigDecimal("0.81234"), height.get());
		assertCodecRoundTrip(width, width.get(), new BigDecimal("0.473"));
		assertCodecRoundTrip(height, height.get(), new BigDecimal("0.81234"));
		assertEquals(new BigDecimal("0.473"), settings.getInventoryMaxWidthFraction());
		assertEquals(new BigDecimal("0.81234"), settings.getInventoryMaxHeightFraction());
	}

	private static void assertGrid(OptionInstance<BigDecimal> option, int minimumHundredths, int maximumHundredths)
		throws ReflectiveOperationException {
		// Vanilla keeps the slider value-set interface package-private. Invoke its
		// real mapping rather than reconstructing OptionUtils' index arithmetic.
		Object slider = option.values();
		var fromSliderValue = slider.getClass().getMethod("fromSliderValue", double.class);
		var toSliderValue = slider.getClass().getMethod("toSliderValue", Object.class);
		fromSliderValue.setAccessible(true);
		toSliderValue.setAccessible(true);
		int positions = (maximumHundredths - minimumHundredths) / 5;
		for (int position = 0; position <= positions; position++) {
			BigDecimal expected = BigDecimal.valueOf(minimumHundredths + position * 5, 2);
			double fraction = position / (double) positions;
			BigDecimal actual = (BigDecimal) fromSliderValue.invoke(slider, fraction);
			assertEquals(0, expected.compareTo(actual), "slider position " + position);
			double mappedFraction = (double) toSliderValue.invoke(slider, actual);
			assertTrue(mappedFraction >= 0.0 && mappedFraction <= 1.0);
			assertEquals(0, expected.compareTo((BigDecimal) fromSliderValue.invoke(slider, mappedFraction)));
			assertCodecRoundTrip(option, actual, expected);
		}
	}

	private static void assertCodecRoundTrip(OptionInstance<BigDecimal> option, BigDecimal actual, BigDecimal expected) {
		var encoded = option.codec().encodeStart(JsonOps.INSTANCE, actual).getOrThrow();
		assertEquals(0, expected.compareTo(encoded.getAsBigDecimal()));
		assertEquals(0, expected.compareTo(option.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow()));
	}

	private static OptionInstance<BigDecimal> inventoryWidthOption(SpatialSelectorSettings settings) {
		return OptionUtils.ofDecimal("test.inventory.width", SpatialSelectorSettings.MIN_INVENTORY_MAX_WIDTH_FRACTION,
			SpatialSelectorSettings.MAX_INVENTORY_MAX_WIDTH_FRACTION, SpatialSelectorSettings.INVENTORY_MAX_WIDTH_FRACTION_STEP,
			number -> Component.literal(number.toPlainString()), null,
			settings::getInventoryMaxWidthFraction, settings::setInventoryMaxWidthFraction);
	}

	private static OptionInstance<BigDecimal> inventoryHeightOption(SpatialSelectorSettings settings) {
		return OptionUtils.ofDecimal("test.inventory.height", SpatialSelectorSettings.MIN_INVENTORY_MAX_HEIGHT_FRACTION,
			SpatialSelectorSettings.MAX_INVENTORY_MAX_HEIGHT_FRACTION, SpatialSelectorSettings.INVENTORY_MAX_HEIGHT_FRACTION_STEP,
			number -> Component.literal(number.toPlainString()), null,
			settings::getInventoryMaxHeightFraction, settings::setInventoryMaxHeightFraction);
	}
}
