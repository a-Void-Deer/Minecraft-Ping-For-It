package nx.pingwheel.common.screen;

import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryPerformanceScaleTest {
	@Test
	void slotCountsKeepFineLowStopsAndOneConstantStopCountPerFullDoublingRegion() {
		var slots = List.of(Field.PHYSICAL_SLOTS_PER_TICK, Field.PREVIEW_MAX_SLOTS_PER_CLIENT,
			Field.PREVIEW_MAX_SLOTS_SERVER, Field.TRACKING_MAX_SLOTS_PER_TARGET);
		var variantsLow = finiteBelow(InventoryPerformanceScale.forField(
			Field.PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD), 256);
		for (Field field : slots) {
			var scale = InventoryPerformanceScale.forField(field);
			assertEquals(new BigDecimal("16"), scale.finiteValueAt(0));
			assertNeighbors(scale, "64", "62", "68");
			assertEquals(variantsLow, finiteBelow(scale, 256));
			assertDoublingRegions(field, scale, 256);
		}
	}

	@Test
	void variantsRetainTheirEstablishedFineGridSeparateFromSlotGrouping() {
		Field field = Field.PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD;
		var scale = InventoryPerformanceScale.forField(field);
		assertEquals(BigDecimal.valueOf(field.minimum()), scale.finiteValueAt(0));
		assertEquals(BigDecimal.valueOf(field.maximum()), scale.finiteValueAt(scale.size() - 2));
		assertTrue(scale.isUnlimited(scale.size() - 1));
		assertStrictlyIncreasingLegal(field, scale);

		var high = new ArrayList<BigDecimal>();
		for (var value : scale.finiteValues()) {
			if (value.compareTo(new BigDecimal("256")) >= 0) high.add(value);
		}
		assertEquals(new BigDecimal("256"), high.getFirst());
		for (int index = 1; index < high.size(); index++) {
			assertEquals(new BigDecimal("8"), high.get(index).subtract(high.get(index - 1)));
		}
	}

	@Test
	void trackingGlobalAndMemoryHaveTheirOwnExactPiecewiseStops() {
		var global = InventoryPerformanceScale.forField(Field.TRACKING_MAX_SLOTS_SERVER);
		assertEquals(new BigDecimal("128"), global.finiteValueAt(0));
		assertNeighbors(global, "512", "480", "640");
		assertNeighbors(global, "2048", "1920", "2560");
		assertEquals(new BigDecimal("262144"), global.finiteValueAt(global.size() - 2));
		assertDoublingRegions(Field.TRACKING_MAX_SLOTS_SERVER, global, 2048);

		var memory = InventoryPerformanceScale.forField(Field.PENDING_MEMORY_MIB);
		assertEquals(new BigDecimal("16"), memory.finiteValueAt(0));
		assertNeighbors(memory, "64", "60", "72");
		assertNeighbors(memory, "256", "248", "272");
		assertEquals(new BigDecimal("2048"), memory.finiteValueAt(memory.size() - 2));
		assertTrue(memory.isUnlimited(memory.size() - 1));
	}

	@Test
	void linearScalesDistinguishUnlimitedFromTheTwoZeroSentinels() {
		assertLinear(Field.PREVIEW_MAX_TARGETS_PER_CLIENT, 1, 16, true);
		assertLinear(Field.TRACKING_MAX_VARIANTS_PER_TARGET, 1, 128, true);
		assertLinear(Field.PREVIEW_PERIOD_TICKS, 1, 10, false);
		assertLinear(Field.TRACKING_PERIOD_TICKS, 1, 10, false);
		assertLinear(Field.TRACKING_RESYNC_MIN_PERIODS, 0, 10, false);
		assertLinear(Field.TRACKING_HEARTBEAT_PERIODS, 0, 32, false);
		assertLinear(Field.TRACKING_GRACE_PERIODS, 1, 32, false);
	}

	@Test
	void multiplierStopsKeepExactDecimalsAtEveryJointAndEndpoint() {
		for (Field field : List.of(Field.PREVIEW_CLIENT_BYTE_MULTIPLIER, Field.TRACKING_STREAM_BYTE_MULTIPLIER,
			Field.TRACKING_SNAPSHOT_BYTE_MULTIPLIER, Field.PREVIEW_GLOBAL_BYTE_MULTIPLIER,
			Field.TRACKING_GLOBAL_BYTE_MULTIPLIER)) {
			var scale = InventoryPerformanceScale.forField(field);
			boolean global = field == Field.PREVIEW_GLOBAL_BYTE_MULTIPLIER || field == Field.TRACKING_GLOBAL_BYTE_MULTIPLIER;
			assertEquals(new BigDecimal(global ? "0.125" : "0.25"), scale.finiteValueAt(0));
			assertNeighbors(scale, "4", global ? "3.875" : "3.75", global ? "4.25" : "4.5");
			assertNeighbors(scale, "8", global ? "7.75" : "7.5", global ? "8.5" : "9");
			if (global) assertNeighbors(scale, "16", "15.5", "17");
			assertEquals(new BigDecimal(global ? "32" : "16"), scale.finiteValueAt(scale.size() - 2));
			assertTrue(scale.isUnlimited(scale.size() - 1));
			for (var value : scale.finiteValues()) assertTrue(field.isSafe(new Value(false, value)));
		}
	}

	@Test
	void extraStopsAreOrderedImmutableAndOnlyAcceptLegalValues() {
		var original = InventoryPerformanceScale.forField(Field.PENDING_MEMORY_MIB);
		var extra = original.including(Value.limit(false, 66));
		assertFalse(original.finiteValues().contains(new BigDecimal("66")));
		assertNeighbors(extra, "66", "64", "72");
		assertSame(extra, extra.including(Value.limit(true, 66)));
		assertSame(extra, extra.including(Value.limit(false, 15)));
		assertSame(extra, extra.including(Value.limit(false, 2049)));
		assertSame(extra, extra.including(new Value(false, new BigDecimal("66.5"))));
		assertThrows(UnsupportedOperationException.class, () -> extra.finiteValues().add(BigDecimal.ZERO));
	}

	private static void assertNeighbors(InventoryPerformanceScale scale, String value, String previous, String next) {
		int index = scale.positionOf(new Value(false, new BigDecimal(value)));
		assertEquals(new BigDecimal(previous), scale.finiteValueAt(index - 1));
		assertEquals(new BigDecimal(next), scale.finiteValueAt(index + 1));
	}

	private static List<BigDecimal> finiteBelow(InventoryPerformanceScale scale, int exclusiveMaximum) {
		var values = new ArrayList<BigDecimal>();
		for (var value : scale.finiteValues()) {
			if (value.compareTo(BigDecimal.valueOf(exclusiveMaximum)) < 0) values.add(value);
		}
		return List.copyOf(values);
	}

	private static void assertStrictlyIncreasingLegal(Field field, InventoryPerformanceScale scale) {
		var values = scale.finiteValues();
		for (int index = 0; index < values.size(); index++) {
			assertTrue(field.isSafe(new Value(false, values.get(index))));
			if (index > 0) assertTrue(values.get(index - 1).compareTo(values.get(index)) < 0);
		}
	}

	/**
	 * Every full power-of-two region has one spacing and the same stop count;
	 * it intentionally does not pin the region count or the individual spacing.
	 */
	private static void assertDoublingRegions(Field field, InventoryPerformanceScale scale, int highStart) {
		assertEquals(BigDecimal.valueOf(field.minimum()), scale.finiteValueAt(0));
		assertEquals(BigDecimal.valueOf(field.maximum()), scale.finiteValueAt(scale.size() - 2));
		assertTrue(scale.isUnlimited(scale.size() - 1));
		assertStrictlyIncreasingLegal(field, scale);

		var regions = new TreeMap<Integer, List<Integer>>();
		for (var value : scale.finiteValues()) {
			int integer = value.intValueExact();
			if (integer < highStart) continue;
			regions.computeIfAbsent(31 - Integer.numberOfLeadingZeros(integer), exponent -> new ArrayList<>())
				.add(integer);
		}
		int expectedStops = -1;
		int previousGap = -1;
		for (var region : regions.entrySet()) {
			if ((1L << (region.getKey() + 1)) > field.maximum()) continue;
			var stops = region.getValue();
			assertTrue(stops.size() > 1);
			int gap = stops.get(1) - stops.getFirst();
			for (int index = 2; index < stops.size(); index++) {
				assertEquals(gap, stops.get(index) - stops.get(index - 1));
			}
			if (expectedStops < 0) expectedStops = stops.size();
			else assertEquals(expectedStops, stops.size());
			if (previousGap > 0) assertTrue(gap > previousGap);
			previousGap = gap;
		}
		assertTrue(expectedStops > 1);
	}

	private static void assertLinear(Field field, int minimum, int maximum, boolean unlimited) {
		var scale = InventoryPerformanceScale.forField(field);
		assertEquals(maximum - minimum + 1 + (unlimited ? 1 : 0), scale.size());
		for (int value = minimum; value <= maximum; value++) {
			assertEquals(BigDecimal.valueOf(value), scale.finiteValueAt(value - minimum));
		}
		assertEquals(unlimited, scale.isUnlimited(scale.size() - 1));
	}
}
