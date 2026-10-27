package nx.pingwheel.common.config;

import com.google.gson.Gson;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Owning-field substitution is independent of the compatibility APIs' finite 1x meaning. */
@NotNullByDefault
class ByteMultiplierDefaultsTest {

	@ParameterizedTest
	@CsvSource({"CLIENT, 2.75, 11264", "GLOBAL, 3.125, 12800"})
	void missingValuesUseTheOwningDefaultBeforeAndAfterIdempotentValidation(
		ByteMultiplierGrid grid, BigDecimal owningDefault, long expectedBytes) {
		Gson gson = new Gson();
		for (ByteMultiplier multiplier : List.of(new ByteMultiplier(),
			gson.fromJson("{}", ByteMultiplier.class),
			gson.fromJson("{\"value\":null}", ByteMultiplier.class))) {
			assertNull(multiplier.getValue(), "reconstruction must leave substitution to the owning field");
			assertEquals(expectedBytes, multiplier.appliedTo(4096, 99999, grid, owningDefault));
			assertNull(multiplier.getValue(), "reading bytes must not materialize the default");

			multiplier.validate(grid, owningDefault);
			assertEquals(0, owningDefault.compareTo(multiplier.getValue()));
			assertFalse(multiplier.isUnlimited());
			assertEquals(expectedBytes, multiplier.appliedTo(4096, 99999, grid, owningDefault));
			multiplier.validate(grid, owningDefault);
			assertEquals(0, owningDefault.compareTo(multiplier.getValue()));
			assertEquals(expectedBytes, multiplier.appliedTo(4096, 99999, grid, owningDefault));
		}

		ByteMultiplier restored = InventorySettings.validatedMultiplier(null, grid, owningDefault);
		assertFalse(restored.isUnlimited());
		assertEquals(0, owningDefault.compareTo(restored.getValue()));
		assertEquals(expectedBytes, restored.appliedTo(4096, 99999, grid, owningDefault));
		ByteMultiplier empty = gson.fromJson("{}", ByteMultiplier.class);
		assertSame(empty, InventorySettings.validatedMultiplier(empty, grid, owningDefault));
		assertEquals(0, owningDefault.compareTo(empty.getValue()));
	}

	@Test
	void explicitValuesOverrideTheFallbackAndReadingNeverAddsNormalization() {
		BigDecimal fallback = new BigDecimal("2.75");
		ByteMultiplier explicit = ByteMultiplier.finite(new BigDecimal("0.5"));
		assertEquals(2048, explicit.appliedTo(4096, 99999, ByteMultiplierGrid.CLIENT, fallback));
		assertSame(explicit, InventorySettings.validatedMultiplier(explicit, ByteMultiplierGrid.CLIENT, fallback));
		assertEquals(0, new BigDecimal("0.5").compareTo(explicit.getValue()));

		// A quantum-aligned value above the validated maximum is still read literally.
		explicit.setValue(new BigDecimal("17"));
		assertEquals(69632, explicit.appliedTo(4096, 99999, ByteMultiplierGrid.CLIENT, fallback));
		assertEquals(0, new BigDecimal("17").compareTo(explicit.getValue()));
		explicit.setValue(new BigDecimal("0.3"));
		assertThrows(ArithmeticException.class,
			() -> explicit.appliedTo(4096, 99999, ByteMultiplierGrid.CLIENT, fallback));
		assertEquals(0, new BigDecimal("0.3").compareTo(explicit.getValue()));
		explicit.validate(ByteMultiplierGrid.CLIENT, fallback);
		assertEquals(0, new BigDecimal("0.25").compareTo(explicit.getValue()));
	}

	@Test
	void unlimitedWithoutAValueRetainsItsModeAndReturnsBeforeConsultingTheGrid() {
		ByteMultiplier multiplier = new Gson().fromJson("{\"unlimited\":true}", ByteMultiplier.class);
		BigDecimal fallback = new BigDecimal("3.125");
		assertEquals(12345, multiplier.appliedTo(4096, 12345, null, fallback));
		assertNull(multiplier.getValue());
		assertThrows(IllegalArgumentException.class, () -> multiplier.validate(null, fallback));
		assertNull(multiplier.getValue());
		assertSame(multiplier,
			InventorySettings.validatedMultiplier(multiplier, ByteMultiplierGrid.GLOBAL, fallback));
		multiplier.validate(ByteMultiplierGrid.GLOBAL, fallback);
		assertTrue(multiplier.isUnlimited());
		assertEquals(0, fallback.compareTo(multiplier.getValue()));
		assertEquals(12345, multiplier.appliedTo(4096, 12345, ByteMultiplierGrid.GLOBAL, fallback));
		multiplier.setUnlimited(false);
		assertEquals(12800, multiplier.appliedTo(4096, 12345, ByteMultiplierGrid.GLOBAL, fallback));
	}

	@Test
	void compatibilityApisKeepTheirFiniteOneTimesMeaning() {
		ByteMultiplier one = ByteMultiplier.one();
		assertFalse(one.isUnlimited());
		assertEquals(0, BigDecimal.ONE.compareTo(one.getValue()));
		ByteMultiplier missing = new ByteMultiplier();
		assertEquals(4096, missing.appliedTo(4096, 99999, ByteMultiplierGrid.CLIENT));
		assertNull(missing.getValue());
		assertThrows(IllegalArgumentException.class, () -> missing.validate(null));
		missing.validate(ByteMultiplierGrid.GLOBAL);
		assertEquals(one, missing);
		assertEquals(4096, missing.appliedTo(4096, 99999, ByteMultiplierGrid.GLOBAL));
		missing.setUnlimited(true);
		assertEquals(99999, missing.appliedTo(4096, 99999, null));
	}
}
