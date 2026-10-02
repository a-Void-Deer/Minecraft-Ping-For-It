package nx.pingwheel.common.screen;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Decimal settings keep fractional persisted values instead of truncating them into integer ticks. */
class OptionUtilsDecimalTest {
	@Test
	void decimalCodecRoundTripsEveryQuarterStepAndRejectsValuesOutsideTheRange() {
		OptionInstance<BigDecimal> option = option(new AtomicReference<>(new BigDecimal("1.5")));
		for (int quarters = 1; quarters <= 12; quarters++) {
			BigDecimal value = new BigDecimal("0.25").multiply(BigDecimal.valueOf(quarters));
			var encoded = option.codec().encodeStart(JsonOps.INSTANCE, value).getOrThrow();
			assertEquals(0, value.compareTo(encoded.getAsBigDecimal()));
			assertEquals(0, value.compareTo(option.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow()));
		}
		assertTrue(option.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(0)).error().isPresent());
		assertTrue(option.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(4)).error().isPresent());
	}

	@Test
	void openingAnOptionDoesNotSnapAValidOffGridPreferenceOrMutateTheConfig() {
		AtomicReference<BigDecimal> value = new AtomicReference<>(new BigDecimal("1.3"));
		OptionInstance<BigDecimal> option = option(value);
		assertEquals(new BigDecimal("1.3"), value.get());
		assertEquals(new BigDecimal("1.3"), option.get());
	}

	private static OptionInstance<BigDecimal> option(AtomicReference<BigDecimal> value) {
		return OptionUtils.ofDecimal("test.decimal", new BigDecimal("0.25"), new BigDecimal("3"),
			new BigDecimal("0.25"), number -> Component.literal(number.toPlainString()),
			null, value::get, value::set);
	}
}
