package nx.pingwheel.common.monitor.client;

import java.math.BigInteger;
import nx.pingwheel.common.monitor.client.MonitorNumberFormat.Quality;
import nx.pingwheel.common.monitor.client.MonitorNumberFormat.Result;
import nx.pingwheel.common.monitor.client.MonitorNumberFormat.Value;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class MonitorNumberFormatTest {

	private static final BigInteger BYTES_PER_KIB = BigInteger.valueOf(1024);
	private static final BigInteger BYTES_PER_MIB = BYTES_PER_KIB.multiply(BYTES_PER_KIB);

	@Test
	void valueAndResultEnforceTheirDeclaredShape() {
		assertEquals(BigInteger.ONE, MonitorNumberFormat.integer(7, Quality.LOWER_BOUND).denominator());
		assertThrows(IllegalArgumentException.class,
			() -> new Value(BigInteger.ONE, BigInteger.ZERO, Quality.EXACT));
		assertThrows(IllegalArgumentException.class,
			() -> new Value(BigInteger.ONE, BigInteger.valueOf(-1), Quality.EXACT));
		assertThrows(IllegalArgumentException.class, () -> new Result(true, ""));
		assertThrows(IllegalArgumentException.class, () -> new Result(false, "N/A"));
	}

	@Test
	void byteUnitPromotionUsesTheStrictUnroundedBoundaryAlongTheWholeChain() {
		assertEquals("8192B", text(MonitorNumberFormat.bytes(bytesOfPower(8192, 0))));
		assertEquals("8KB", text(MonitorNumberFormat.bytes(bytesOfPower(8193, 0))));
		assertEquals("8192KB", text(MonitorNumberFormat.bytes(bytesOfPower(8192, 1))));
		assertEquals("8MB", text(MonitorNumberFormat.bytes(bytesOfPower(8193, 1))));
		assertEquals("8GB", text(MonitorNumberFormat.bytes(bytesOfPower(8193, 2))));
		assertEquals("8TB", text(MonitorNumberFormat.bytes(bytesOfPower(8193, 3))));
		assertEquals("8PB", text(MonitorNumberFormat.bytes(bytesOfPower(8193, 4))));
		assertEquals("8EB", text(MonitorNumberFormat.bytes(bytesOfPower(8193, 5))));
		assertEquals("8MB", text(MonitorNumberFormat.bytes(kilobytes(81920001, 10000, Quality.EXACT))));
	}

	@Test
	void byteRoundingUsesOnePassAndRechecksTheDigitCapacity() {
		assertEquals("1000KB", text(MonitorNumberFormat.bytes(kilobytes(99995, 100, Quality.EXACT))));
		assertEquals("999.4KB", text(MonitorNumberFormat.bytes(kilobytes(9994451, 10000, Quality.EXACT))));
		assertEquals("1000KB", text(MonitorNumberFormat.bytes(kilobytes(99996, 100, Quality.EXACT))));
		assertEquals("999.9KB", text(MonitorNumberFormat.bytes(kilobytes(99994, 100, Quality.EXACT))));
		assertEquals("8192KB", text(MonitorNumberFormat.bytes(kilobytes(81916, 10, Quality.EXACT))));
		assertEquals("8192KB", text(MonitorNumberFormat.bytes(kilobytes(8191999, 1000, Quality.EXACT))));
		assertEquals("1111B", text(MonitorNumberFormat.bytes(exact(1111))));
		assertEquals("1137B", text(MonitorNumberFormat.bytes(new Value(
			BigInteger.valueOf(113664), BigInteger.valueOf(100), Quality.EXACT))));
		assertEquals("111.1KB", text(MonitorNumberFormat.bytes(kilobytes(1111, 10, Quality.EXACT))));
		assertEquals("11.11MB", text(MonitorNumberFormat.bytes(mebibytes(1111, 100, Quality.EXACT))));
		assertEquals("1.11B", text(MonitorNumberFormat.bytes(new Value(
			BigInteger.valueOf(111), BigInteger.valueOf(100), Quality.EXACT))));
		assertEquals("25.2KB", text(MonitorNumberFormat.bytes(kilobytes(252, 10, Quality.EXACT))));
	}

	@Test
	void byteSeriesSelectOneUnitFromTheLargestAbsoluteComponent() {
		assertEquals("96/128/512MB", text(MonitorNumberFormat.byteTriplet(
			mebibytes(96, 1, Quality.EXACT), mebibytes(128, 1, Quality.EXACT),
			mebibytes(512, 1, Quality.EXACT))));
		assertEquals("96/128MB", text(MonitorNumberFormat.ratio(
			mebibytes(96, 1, Quality.EXACT), mebibytes(128, 1, Quality.EXACT), true)));
		assertEquals("0/512MB", text(MonitorNumberFormat.ratio(
			exact(0), mebibytes(512, 1, Quality.EXACT), true)));
		assertEquals("0/<0.01/9MB", text(MonitorNumberFormat.byteTriplet(
			exact(0), exact(1), mebibytes(9, 1, Quality.EXACT))));
		assertEquals("8/<0.01MB", text(MonitorNumberFormat.ratio(
			kilobytes(8193, 1, Quality.EXACT), kilobytes(1, 1, Quality.EXACT), true)));
		assertEquals("-512/<0.01MB", text(MonitorNumberFormat.ratio(
			mebibytes(-512, 1, Quality.EXACT), kilobytes(1, 1, Quality.EXACT), true)));
		assertEquals("5/0B", text(MonitorNumberFormat.ratio(exact(5), exact(0), true)));
		assertEquals("0/0", text(MonitorNumberFormat.ratio(exact(0), exact(0), false)));
		assertEquals("0/0B", text(MonitorNumberFormat.ratio(exact(0), exact(0), true)));
	}

	@Test
	void byteValuesPreserveSignAndTinyDirection() {
		assertEquals("0B", text(MonitorNumberFormat.bytes(exact(0))));
		assertEquals("<0.01B", text(MonitorNumberFormat.bytes(fraction(1, 1000, Quality.EXACT))));
		assertEquals(">-0.01B", text(MonitorNumberFormat.bytes(fraction(-1, 1000, Quality.EXACT))));
		assertEquals("-12.06KB", text(MonitorNumberFormat.bytes(exact(-12345))));
		assertEquals(">=0B", text(MonitorNumberFormat.bytes(fraction(1, 1000, Quality.LOWER_BOUND))));
		assertEquals(">=-0.01B", text(MonitorNumberFormat.bytes(fraction(-1, 1000, Quality.LOWER_BOUND))));
		assertEquals(">=999.9KB", text(MonitorNumberFormat.bytes(kilobytes(99995, 100, Quality.LOWER_BOUND))));
		assertEquals(">=-8.01KB", text(MonitorNumberFormat.bytes(
			new Value(BigInteger.valueOf(-8193), BigInteger.ONE, Quality.LOWER_BOUND))));
		assertEquals(">=123B", text(MonitorNumberFormat.bytes(exactQuality(123, Quality.SATURATED))));
	}

	@Test
	void byteValuesAboveTheHighestConfirmedUnitCapacityAreUnavailable() {
		assertEquals("8193EB", text(MonitorNumberFormat.bytes(bytesOfPower(8193, 6))));
		unavailable(MonitorNumberFormat.bytes(bytesOfPower(10000, 6)));
		unavailable(MonitorNumberFormat.bytes(new Value(BigInteger.TEN.pow(30), BigInteger.ONE, Quality.EXACT)));
	}

	@Test
	void byteValuesAtLongLimitsRemainExact() {
		assertEquals("8192PB", text(MonitorNumberFormat.bytes(new Value(
			BigInteger.ONE.shiftLeft(63), BigInteger.ONE, Quality.EXACT))));
		assertEquals("16EB", text(MonitorNumberFormat.bytes(new Value(
			BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), BigInteger.ONE, Quality.EXACT))));
	}

	@Test
	void nonByteNumbersKeepFullIntegerDigitsAndAtMostTwoDecimals() {
		assertEquals("123456789", text(MonitorNumberFormat.number(exact(123456789))));
		assertEquals("-9223372036854775808", text(MonitorNumberFormat.number(exact(Long.MIN_VALUE))));
		assertEquals("0.33", text(MonitorNumberFormat.number(fraction(1, 3, Quality.EXACT))));
		assertEquals("-0.67", text(MonitorNumberFormat.number(fraction(-2, 3, Quality.EXACT))));
		assertEquals("0.01", text(MonitorNumberFormat.number(fraction(1, 200, Quality.EXACT))));
		assertEquals("-0.01", text(MonitorNumberFormat.number(fraction(-1, 200, Quality.EXACT))));
		assertEquals("0", text(MonitorNumberFormat.number(exact(0))));
		assertEquals("<0.01", text(MonitorNumberFormat.number(fraction(1, 1000, Quality.EXACT))));
		assertEquals(">-0.01", text(MonitorNumberFormat.number(fraction(-1, 1000, Quality.EXACT))));
	}

	@Test
	void lowerBoundNumbersFloorInsteadOfOverstating() {
		assertEquals(">=1.23", text(MonitorNumberFormat.number(fraction(1239, 1000, Quality.LOWER_BOUND))));
		assertEquals(">=-1.24", text(MonitorNumberFormat.number(fraction(-1231, 1000, Quality.LOWER_BOUND))));
		assertEquals(">=0", text(MonitorNumberFormat.number(fraction(1, 1000, Quality.LOWER_BOUND))));
		assertEquals("1.24", text(MonitorNumberFormat.number(fraction(1239, 1000, Quality.EXACT))));
		assertEquals("-1.23", text(MonitorNumberFormat.number(fraction(-1231, 1000, Quality.EXACT))));
		assertEquals(">=5", text(MonitorNumberFormat.number(exactQuality(5, Quality.SATURATED))));
	}

	@Test
	void timeUsesTheRawMagnitudeForTheUnitSwitch() {
		assertEquals("1000ms", text(MonitorNumberFormat.timeNs(exact(999_999_000))));
		assertEquals("1s", text(MonitorNumberFormat.timeNs(exact(1_000_000_000))));
		assertEquals("999.95s", text(MonitorNumberFormat.timeNs(exact(999_950_000_000L))));
		assertEquals("123456s", text(MonitorNumberFormat.timeNs(exact(123_456_000_000_000L))));
		assertEquals("1.23ms", text(MonitorNumberFormat.timeNs(exact(1_234_567))));
		assertEquals("0ms", text(MonitorNumberFormat.timeNs(exact(0))));
		assertEquals("<0.01ms", text(MonitorNumberFormat.timeNs(exact(1000))));
		assertEquals(">-0.01ms", text(MonitorNumberFormat.timeNs(exact(-1000))));
		assertEquals("-1.5s", text(MonitorNumberFormat.timeNs(exact(-1_500_000_000))));
	}

	@Test
	void lowerBoundTimesPreserveTheirDirection() {
		assertEquals(">=1.23ms", text(MonitorNumberFormat.timeNs(
			new Value(BigInteger.valueOf(1_239_000), BigInteger.ONE, Quality.LOWER_BOUND))));
		assertEquals(">=-1.24ms", text(MonitorNumberFormat.timeNs(
			new Value(BigInteger.valueOf(-1_231_000), BigInteger.ONE, Quality.LOWER_BOUND))));
		assertEquals(">=1.5s", text(MonitorNumberFormat.timeNs(
			new Value(BigInteger.valueOf(1_500_000_000), BigInteger.ONE, Quality.SATURATED))));
	}

	@Test
	void ratesUseTheExactElapsedIntervalAndByteRules() {
		assertEquals("2000/s", text(MonitorNumberFormat.ratePerSecond(exact(2000), 1_000_000_000L, false)));
		assertEquals("123456789/s", text(MonitorNumberFormat.ratePerSecond(exact(123456789), 1_000_000_000L, false)));
		assertEquals("0.33/s", text(MonitorNumberFormat.ratePerSecond(exact(1), 3_000_000_000L, false)));
		assertEquals("8192B/s", text(MonitorNumberFormat.ratePerSecond(exact(8192), 1_000_000_000L, true)));
		assertEquals("8KB/s", text(MonitorNumberFormat.ratePerSecond(exact(8193), 1_000_000_000L, true)));
		assertEquals("12KB/s", text(MonitorNumberFormat.ratePerSecond(exact(12288), 1_000_000_000L, true)));
		assertEquals("0/s", text(MonitorNumberFormat.ratePerSecond(exact(0), 1_000_000_000L, false)));
		assertEquals("0B/s", text(MonitorNumberFormat.ratePerSecond(exact(0), 1_000_000_000L, true)));
		assertEquals("<0.01/s", text(MonitorNumberFormat.ratePerSecond(exact(1), 1_000_000_000_000L, false)));
		assertEquals("<0.01B/s", text(MonitorNumberFormat.ratePerSecond(exact(1), 1_000_000_000_000L, true)));
	}

	@Test
	void lowerBoundRatesKeepTheirComparisonAndFullDigits() {
		assertEquals(">=1000/s", text(MonitorNumberFormat.ratePerSecond(
			exactQuality(1000, Quality.LOWER_BOUND), 1_000_000_000L, false)));
		assertEquals(">=999.95/s", text(MonitorNumberFormat.ratePerSecond(
			new Value(BigInteger.valueOf(99995), BigInteger.valueOf(100), Quality.LOWER_BOUND),
			1_000_000_000L, false)));
		assertEquals(">=-1.24/s", text(MonitorNumberFormat.ratePerSecond(
			new Value(BigInteger.valueOf(-1231), BigInteger.valueOf(1000), Quality.LOWER_BOUND),
			1_000_000_000L, false)));
	}

	@Test
	void saturatedAndUndefinedRatesAreUnavailable() {
		unavailable(MonitorNumberFormat.ratePerSecond(
			exactQuality(1000, Quality.SATURATED), 1_000_000_000L, false));
		unavailable(MonitorNumberFormat.ratePerSecond(exact(1000), 0, false));
		unavailable(MonitorNumberFormat.ratePerSecond(exact(1000), -1, false));
		unavailable(MonitorNumberFormat.ratePerSecond(null, 1_000_000_000L, false));
	}

	@Test
	void percentagesRequireAnExactPositiveDenominator() {
		assertEquals("25%", text(MonitorNumberFormat.percent(exact(1), exact(4))));
		assertEquals("150%", text(MonitorNumberFormat.percent(exact(3), exact(2))));
		assertEquals("-50%", text(MonitorNumberFormat.percent(exact(-1), exact(2))));
		assertEquals("0%", text(MonitorNumberFormat.percent(exact(0), exact(1))));
		assertEquals("33.33%", text(MonitorNumberFormat.percent(exact(1), exact(3))));
		assertEquals("999.95%", text(MonitorNumberFormat.percent(
			new Value(BigInteger.valueOf(99995), BigInteger.valueOf(100), Quality.EXACT), exact(100))));
		assertEquals("922337203685477580800%", text(MonitorNumberFormat.percent(
			new Value(BigInteger.ONE.shiftLeft(63), BigInteger.ONE, Quality.EXACT), exact(1))));
		unavailable(MonitorNumberFormat.percent(exact(0), exact(0)));
		unavailable(MonitorNumberFormat.percent(exact(1), exact(0)));
		unavailable(MonitorNumberFormat.percent(exact(1), exact(-2)));
		unavailable(MonitorNumberFormat.percent(exact(1), exactQuality(4, Quality.LOWER_BOUND)));
		unavailable(MonitorNumberFormat.percent(exact(1), exactQuality(4, Quality.SATURATED)));
		unavailable(MonitorNumberFormat.percent(exactQuality(1, Quality.SATURATED), exact(4)));
	}

	@Test
	void percentageTinyValuesAndLowerBoundsKeepDirection() {
		assertEquals("0.01%", text(MonitorNumberFormat.percent(exact(1), exact(10000))));
		assertEquals("<0.01%", text(MonitorNumberFormat.percent(exact(1), exact(100000))));
		assertEquals(">-0.01%", text(MonitorNumberFormat.percent(exact(-1), exact(100000))));
		assertEquals(">=-0.01%", text(MonitorNumberFormat.percent(
			fraction(-1, 1, Quality.LOWER_BOUND), exact(100000))));
		assertEquals(">=25%", text(MonitorNumberFormat.percent(
			exactQuality(1, Quality.LOWER_BOUND), exact(4))));
		assertEquals(">=0%", text(MonitorNumberFormat.percent(
			exactQuality(1, Quality.LOWER_BOUND), exact(100000))));
	}

	@Test
	void comparisonMarkersStayWithTheComponentTheyQualify() {
		assertEquals(">=5/10", text(MonitorNumberFormat.ratio(
			exactQuality(5, Quality.LOWER_BOUND), exact(10), false)));
		assertEquals(">=5/10/>=20B", text(MonitorNumberFormat.byteTriplet(
			exactQuality(5, Quality.LOWER_BOUND), exact(10), exactQuality(20, Quality.SATURATED))));
	}

	@Test
	void missingRequiredInputsProduceEmptyUnavailableResults() {
		unavailable(MonitorNumberFormat.number(null));
		unavailable(MonitorNumberFormat.timeNs(null));
		unavailable(MonitorNumberFormat.bytes(null));
		unavailable(MonitorNumberFormat.ratio(null, exact(1), false));
		unavailable(MonitorNumberFormat.ratio(exact(1), null, true));
		unavailable(MonitorNumberFormat.byteTriplet(null, exact(1), exact(2)));
		unavailable(MonitorNumberFormat.byteTriplet(exact(1), null, exact(2)));
		unavailable(MonitorNumberFormat.byteTriplet(exact(1), exact(2), null));
		unavailable(MonitorNumberFormat.percent(null, exact(1)));
		unavailable(MonitorNumberFormat.percent(exact(1), null));
	}

	private static Value exact(long value) {
		return MonitorNumberFormat.integer(value, Quality.EXACT);
	}

	private static Value exactQuality(long value, Quality quality) {
		return MonitorNumberFormat.integer(value, quality);
	}

	private static Value fraction(long numerator, long denominator, Quality quality) {
		return new Value(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator), quality);
	}

	private static Value kilobytes(long numerator, long denominator, Quality quality) {
		return new Value(BigInteger.valueOf(numerator).multiply(BYTES_PER_KIB),
			BigInteger.valueOf(denominator), quality);
	}

	private static Value mebibytes(long numerator, long denominator, Quality quality) {
		return new Value(BigInteger.valueOf(numerator).multiply(BYTES_PER_MIB),
			BigInteger.valueOf(denominator), quality);
	}

	private static Value bytesOfPower(long value, int powerOf1024) {
		return new Value(BigInteger.valueOf(value).shiftLeft(10 * powerOf1024), BigInteger.ONE, Quality.EXACT);
	}

	private static String text(Result result) {
		assertTrue(result.available());
		return result.text();
	}

	private static void unavailable(Result result) {
		assertFalse(result.available());
		assertEquals("", result.text());
	}
}
