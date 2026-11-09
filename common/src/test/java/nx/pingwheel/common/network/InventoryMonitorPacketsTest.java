package nx.pingwheel.common.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import nx.pingwheel.common.monitor.MonitorSchema.Budget;
import nx.pingwheel.common.monitor.MonitorSchema.BudgetReading;
import nx.pingwheel.common.monitor.MonitorSchema.BudgetUsage;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.CounterReading;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.GaugeReading;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.LaneSample;
import nx.pingwheel.common.monitor.MonitorSchema.MemoryUsage;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.MonitorSchema.SharedSample;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorSchema.Timing;
import nx.pingwheel.common.monitor.MonitorSchema.TimingStats;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket.SampleCapability;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Raw fixtures are written independently from the production codec. */
@NotNullByDefault
class InventoryMonitorPacketsTest {
	private static final byte @Unmodifiable [] SUBSCRIBE = hex("01 11 01 02 96 0a 89 27");
	private static final byte @Unmodifiable [] STATUS = hex("01 11 02 03 04 01 01 01 00");
	private static final byte @Unmodifiable [] SAMPLE = hex(
		"01 11 02 03 04 05 06 07 08 02 "
		+ "03 01 01 0c 03 08 01 01 09 00 01 01 02 00 00 "
		+ "05 00 00 00 00 01 50 14 32 ff ff ff ff ff ff ff ce");

	@Test void goldenFixturesFixAllThreeRoutesAndWholeWireOrder() {
		assertEquals("pingforit-c2s:inventory-monitor-subscribe-v1", InventoryMonitorSubscribeC2SPacket.PACKET_ID.toString());
		assertEquals("pingforit-s2c:inventory-monitor-status-v1", InventoryMonitorStatusS2CPacket.PACKET_ID.toString());
		assertEquals("pingforit-s2c:inventory-monitor-sample-v1", InventoryMonitorSampleS2CPacket.PACKET_ID.toString());
		var subscribe = new InventoryMonitorSubscribeC2SPacket(17, true, List.of(1302, 5001));
		var status = new InventoryMonitorStatusS2CPacket(17, 2, 3, 4, true, SampleCapability.AVAILABLE, true, false);
		var lane = new LaneSample(Source.SERVER, Lane.PREVIEW,
			List.of(new Timing(Phase.INGRESS, new TimingStats(12, 3, 8))),
			List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 9, false)),
			List.of(new GaugeReading(Gauge.REQUESTS_OPEN, 2)), List.of());
		var sample = new InventoryMonitorSampleS2CPacket(17, 4, new MonitorSample(2, 3, 5, 6, 7, 8, lane, null,
			new SharedSample(List.of(), List.of(), List.of(), MemoryUsage.accounted(80, 20, 50))));
		assertFixture(SUBSCRIBE, subscribe, InventoryMonitorSubscribeC2SPacket::readSafe);
		assertFixture(STATUS, status, InventoryMonitorStatusS2CPacket::readSafe);
		assertFixture(SAMPLE, sample, InventoryMonitorSampleS2CPacket::readSafe);
	}

	@Test void stopAndStatusOnlyWatchAreDifferentAndSelectorsCannotControlCollection() {
		var watch = InventoryMonitorSubscribeC2SPacket.watch(4, List.of());
		assertTrue(watch.watching()); assertFalse(watch.demand().serverRequested());
		var stop = InventoryMonitorSubscribeC2SPacket.stop(5);
		assertFalse(stop.watching()); assertFalse(stop.demand().serverRequested());
		assertFixture(hex("01 04 01 00"), watch, InventoryMonitorSubscribeC2SPacket::readSafe);
		assertFixture(hex("01 05 00 00"), stop, InventoryMonitorSubscribeC2SPacket::readSafe);
		var summary = InventoryMonitorSubscribeC2SPacket.watch(6, List.of(Metric.SUMMARY_PROCESSING,
			Objects.requireNonNull(Metric.byId("server.preview.timing.ingress"))));
		assertFalse(summary.metricWireIds().isEmpty());
		assertTrue(summary.metricWireIds().stream().map(Metric::byWireId).allMatch(metric ->
			Objects.requireNonNull(metric).kind() != Metric.Kind.SUMMARY
				&& Objects.requireNonNull(metric.scope()).source() == Source.SERVER));
		assertEquals(summary.metricWireIds().size(), summary.metricWireIds().stream().distinct().count());
		assertThrows(UnsupportedOperationException.class, () -> summary.metricWireIds().clear());
		var ids = new ArrayList<>(List.of(1302));
		var copied = new InventoryMonitorSubscribeC2SPacket(1, true, ids);
		ids.clear(); assertEquals(List.of(1302), copied.metricWireIds());
	}

	@Test void subscribeRejectsUnknownSummaryLocalDuplicateOutOfOrderAndOverCapacityMetricIds() {
		for (String body : List.of("01 01 01 01 00", "01 01 01 01 01", // unknown, summary
			"01 01 01 01 dc 08", // local.preview.timing.apply, 1116
			"01 01 01 02 96 0a 96 0a", "01 01 01 02 89 27 96 0a",
			"01 01 00 01 96 0a", "01 01 02 00"))
			assertRejected(hex(body), InventoryMonitorSubscribeC2SPacket::readSafe);
		FriendlyByteBuf over = buffer();
		try {
			over.writeByte(1).writeByte(1).writeByte(1).writeVarInt(InventoryMonitorCodec.MAX_METRICS + 1);
			assertTrue(InventoryMonitorSubscribeC2SPacket.readSafe(over).isCorrupt());
			assertEquals(0, over.readableBytes());
		} finally { over.release(); }
		var maximum = InventoryMonitorSubscribeC2SPacket.watch(Long.MAX_VALUE, Metric.rawMetrics());
		assertEquals(InventoryMonitorCodec.MAX_METRICS, maximum.metricWireIds().size());
		assertTrue(maximum.metricWireIds().contains(5001), "high metric wire ID cannot be narrowed to a bit position");
		assertFixture(encode(maximum), maximum, InventoryMonitorSubscribeC2SPacket::readSafe);
	}

	@Test void everyFrameRejectsTruncationTrailingBytesOversizeAndNonCanonicalVersion() {
		assertFrameStrictness(SUBSCRIBE, InventoryMonitorSubscribeC2SPacket::readSafe, InventoryMonitorCodec.MAX_SUBSCRIBE_BYTES);
		assertFrameStrictness(STATUS, InventoryMonitorStatusS2CPacket::readSafe, InventoryMonitorCodec.MAX_STATUS_BYTES);
		assertFrameStrictness(SAMPLE, InventoryMonitorSampleS2CPacket::readSafe, InventoryMonitorCodec.MAX_SAMPLE_BYTES);
	}

	@Test void fencesAndUnsignedNumbersRejectZeroNegativeOverflowOverlongAndAliasEncodings() {
		for (byte[] bad : List.of(hex("00"), hex("81 00"), hex("80 80 80 80 80 80 80 80 80 00"),
			hex("ff ff ff ff ff ff ff ff ff 01"), hex("80 80 80 80 80 80 80 80 80 01"))) {
			assertRejected(replace(SUBSCRIBE, 1, bad), InventoryMonitorSubscribeC2SPacket::readSafe);
			for (int offset = 1; offset <= 4; offset++) assertRejected(replace(STATUS, offset, bad), InventoryMonitorStatusS2CPacket::readSafe);
			for (int offset = 1; offset <= 5; offset++) assertRejected(replace(SAMPLE, offset, bad), InventoryMonitorSampleS2CPacket::readSafe);
		}
		for (int offset : List.of(5, 7, 8)) assertRejected(replace(STATUS, offset, hex("02")), InventoryMonitorStatusS2CPacket::readSafe);
		assertRejected(replace(STATUS, 6, hex("03")), InventoryMonitorStatusS2CPacket::readSafe);
		for (SampleCapability capability : SampleCapability.values()) {
			var status = new InventoryMonitorStatusS2CPacket(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
				false, capability, false, true);
			assertFixture(encode(status), status, InventoryMonitorStatusS2CPacket::readSafe);
		}
	}

	@Test void remoteSampleRejectsLocalUnknownDuplicateOutOfOrderOrWrongScopeReadings() {
		assertRejected(replace(SAMPLE, 10, hex("01")), InventoryMonitorSampleS2CPacket::readSafe);
		assertRejected(replace(SAMPLE, 10, hex("06")), InventoryMonitorSampleS2CPacket::readSafe);
		assertRejected(replace(SAMPLE, 12, hex("08")), InventoryMonitorSampleS2CPacket::readSafe); // local-only apply
		assertRejected(replace(SAMPLE, 12, hex("7f")), InventoryMonitorSampleS2CPacket::readSafe);
		assertRejected(hex("01 01 01 01 01 01 00 00 00 02 03 00 00 00 00 00 03 00 00 00 00 00"), InventoryMonitorSampleS2CPacket::readSafe);
		assertRejected(hex("01 01 01 01 01 01 00 00 00 02 05 00 00 00 00 00 03 00 00 00 00 00"), InventoryMonitorSampleS2CPacket::readSafe);
		for (String readings : List.of("02 01 01 01 01 02 02", "02 02 02 02 01 01 01")) {
			// Two timing tuples with duplicate or descending IDs must not be sorted by the M0 constructor.
			assertRejected(hex("01 01 01 01 01 01 00 00 00 01 03 02 " + readings + " 00 00 00 00"), InventoryMonitorSampleS2CPacket::readSafe);
		}
		assertRejected(replace(SAMPLE, 9, hex("04")), InventoryMonitorSampleS2CPacket::readSafe);
		assertRejected(replace(SAMPLE, 11, hex("7f")), InventoryMonitorSampleS2CPacket::readSafe);
	}

	@Test void signedRemainingAvailabilityAndSaturationHaveIndependentRawFixtures() {
		// Shared budget 3: available, period 2, limit 10, used 13, reserved 0, remaining -3, admissible 0.
		byte[] budgetBytes = hex("01 01 01 01 01 01 00 00 00 01 05 00 00 00 01 03 01 02 0a 0d 00 ff ff ff ff ff ff ff fd 00 00");
		var expected = new InventoryMonitorSampleS2CPacket(1, 1, new MonitorSample(1, 1, 1, 0, 0, 0, null, null,
			new SharedSample(List.of(), List.of(), List.of(new BudgetReading(Budget.PHYSICAL_SLOTS,
				new BudgetUsage(true, 2, 10, 13, 0, -3, 0))), null)));
		assertFixture(budgetBytes, expected, InventoryMonitorSampleS2CPacket::readSafe);
		assertRejected(replace(budgetBytes, 16, hex("00")), InventoryMonitorSampleS2CPacket::readSafe); // unavailable with values
		assertRejected(replace(budgetBytes, 16, hex("02")), InventoryMonitorSampleS2CPacket::readSafe);
		byte[] saturatedBytes = hex("01 01 01 01 01 01 00 00 00 01 05 00 01 15 ff ff ff ff ff ff ff ff 7f 01 00 00 00");
		var saturated = new InventoryMonitorSampleS2CPacket(1, 1, new MonitorSample(1, 1, 1, 0, 0, 0, null, null,
			new SharedSample(List.of(new CounterReading(Counter.READ_FAILURES, Long.MAX_VALUE, true)), List.of(), List.of(), null)));
		assertFixture(saturatedBytes, saturated, InventoryMonitorSampleS2CPacket::readSafe);
		assertRejected(hex("01 01 01 01 01 01 00 00 00 01 05 00 01 15 01 01 00 00 00"), InventoryMonitorSampleS2CPacket::readSafe);
		var extreme = new InventoryMonitorSampleS2CPacket(Long.MAX_VALUE, Long.MAX_VALUE,
			new MonitorSample(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
				Long.MAX_VALUE, null, null, new SharedSample(List.of(), List.of(), List.of(), MemoryUsage.accounted(Long.MAX_VALUE, 1, 0))));
		assertEquals(Long.MIN_VALUE, Objects.requireNonNull(Objects.requireNonNull(extreme.sample()).shared()).memory().remaining());
		assertFixture(encode(extreme), extreme, InventoryMonitorSampleS2CPacket::readSafe);
	}

	@Test void maximumClosedSchemaFitsBodyCeilingWithoutDroppingFields() {
		var preview = fullLane(Lane.PREVIEW);
		var tracking = fullLane(Lane.TRACKING);
		var shared = new SharedSample(Arrays.stream(Counter.values()).filter(c -> c.supports(Source.SHARED, null))
			.map(c -> new CounterReading(c, Long.MAX_VALUE, true)).toList(),
			Arrays.stream(Gauge.values()).filter(g -> g.supports(Source.SHARED, null)).map(g -> new GaugeReading(g, Long.MAX_VALUE)).toList(),
			Arrays.stream(Budget.values()).filter(b -> b.supports(Source.SHARED, null)).map(b -> new BudgetReading(b,
				new BudgetUsage(true, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, 0))).toList(),
			MemoryUsage.accounted(Long.MAX_VALUE, 1, 0));
		var packet = new InventoryMonitorSampleS2CPacket(Long.MAX_VALUE, Long.MAX_VALUE,
			new MonitorSample(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0, Long.MAX_VALUE, Long.MAX_VALUE, preview, tracking, shared));
		byte[] bytes = encode(packet);
		assertTrue(bytes.length < InventoryMonitorCodec.MAX_SAMPLE_BYTES);
		assertFixture(bytes, packet, InventoryMonitorSampleS2CPacket::readSafe);
	}

	private static LaneSample fullLane(Lane lane) {
		return new LaneSample(Source.SERVER, lane,
			Arrays.stream(Phase.values()).filter(p -> p.supports(Source.SERVER, lane))
				.map(p -> new Timing(p, new TimingStats(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))).toList(),
			Arrays.stream(Counter.values()).filter(c -> c.supports(Source.SERVER, lane)).map(c -> new CounterReading(c, Long.MAX_VALUE, true)).toList(),
			Arrays.stream(Gauge.values()).filter(g -> g.supports(Source.SERVER, lane)).map(g -> new GaugeReading(g, Long.MAX_VALUE)).toList(),
			Arrays.stream(Budget.values()).filter(b -> b.supports(Source.SERVER, lane)).map(b -> new BudgetReading(b, BudgetUsage.unavailable())).toList());
	}
	private static <T extends IPacket> void assertFixture(byte[] bytes, T expected, Function<FriendlyByteBuf, T> reader) {
		assertArrayEquals(bytes, encode(expected));
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
		try { assertEquals(expected, reader.apply(buf)); assertEquals(0, buf.readableBytes()); }
		finally { buf.release(); }
	}
	private static void assertRejected(byte[] bytes, Function<FriendlyByteBuf, ? extends IPacket> reader) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
		try { assertTrue(reader.apply(buf).isCorrupt(), HexFormat.of().formatHex(bytes)); assertEquals(0, buf.readableBytes()); }
		finally { buf.release(); }
	}
	private static void assertFrameStrictness(byte[] bytes, Function<FriendlyByteBuf, ? extends IPacket> reader, int maximum) {
		for (int length = 0; length < bytes.length; length++) assertRejected(Arrays.copyOf(bytes, length), reader);
		assertRejected(Arrays.copyOf(bytes, bytes.length + 1), reader);
		assertRejected(new byte[maximum + 1], reader);
		assertRejected(replace(bytes, 0, hex("81 00")), reader);
		assertRejected(replace(bytes, 0, hex("02")), reader);
	}
	private static byte[] replace(byte[] original, int offset, byte[] replacement) {
		byte[] result = new byte[original.length - 1 + replacement.length];
		System.arraycopy(original, 0, result, 0, offset);
		System.arraycopy(replacement, 0, result, offset, replacement.length);
		System.arraycopy(original, offset + 1, result, offset + replacement.length, original.length - offset - 1);
		return result;
	}
	private static byte[] encode(IPacket packet) {
		FriendlyByteBuf buf = buffer();
		try { packet.write(buf); byte[] bytes = new byte[buf.readableBytes()]; buf.readBytes(bytes); return bytes; }
		finally { buf.release(); }
	}
	private static FriendlyByteBuf buffer() { return new FriendlyByteBuf(Unpooled.buffer()); }
	private static byte[] hex(String value) { return HexFormat.of().parseHex(value.replace(" ", "")); }
}
