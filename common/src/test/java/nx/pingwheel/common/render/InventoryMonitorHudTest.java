package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import nx.pingwheel.common.monitor.MonitorHudLabels;
import nx.pingwheel.common.monitor.MonitorHudLabels.Labels;
import nx.pingwheel.common.monitor.MonitorSchema.*;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.monitor.client.MonitorDisplayModel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryMonitorHudTest {

	@Test void referenceAndOrdinaryViewportsPaintEveryPreparedRowFromTheSameTopLeftMargin() {
		var model = model();
		for (var viewport : List.of(new InventoryMonitorHud.Viewport(427, 240), new InventoryMonitorHud.Viewport(320, 240))) {
			var fitted = model.fit(InventoryMonitorHud.availableTextWidth(viewport.width()), splitter());
			assertAllRowsPainted(fitted, viewport);
		}
	}

	@Test void fixedSemanticGroupsAreAllPaintedAtTheSameMarginIncludingBelowTheViewport() {
		var model = activeModel();
		var viewport = new InventoryMonitorHud.Viewport(427, 1);
		var fitted = model.fit(InventoryMonitorHud.availableTextWidth(viewport.width()), splitter());
		assertTrue(fitted.rows().size() > model.rows().size(), "fixed groups start new visual rows");
		assertTrue(fitted.rows().size() > 5, "the reference viewport is not squeezed back into five logical rows");
		assertAllRowsPainted(fitted, viewport);
	}

	@Test void heightOnlyResizeNeverReanchorsOrDropsRowsIncludingRowsBelowTheFramebuffer() {
		var model = model().fit(InventoryMonitorHud.availableTextWidth(320), splitter());
		var tall = record(model, new InventoryMonitorHud.Viewport(320, 240));
		var onePixel = record(model, new InventoryMonitorHud.Viewport(320, 1));
		var zeroHeight = record(model, new InventoryMonitorHud.Viewport(320, 0));
		assertFalse(tall.isEmpty());
		assertEquals(tall, onePixel);
		assertEquals(tall, zeroHeight);
		assertTrue(onePixel.getLast().y() > 1, "bottom-offscreen rows still enter the placement loop");
	}

	@Test void horizontalBudgetProtectsShadowAndRemainsSafeAtZeroNarrowAndExtremeWidths() {
		assertEquals(418, InventoryMonitorHud.availableTextWidth(427));
		assertEquals(311, InventoryMonitorHud.availableTextWidth(320));
		assertEquals(0, InventoryMonitorHud.availableTextWidth(0));
		assertEquals(0, InventoryMonitorHud.availableTextWidth(1));
		assertEquals(Integer.MAX_VALUE - 9, InventoryMonitorHud.availableTextWidth(Integer.MAX_VALUE));
		var model = model();
		for (int width : List.of(0, 1, 8, 9, 10, 20, Integer.MAX_VALUE)) {
			var fitted = model.fit(InventoryMonitorHud.availableTextWidth(width), splitter());
			assertAllRowsPainted(fitted, new InventoryMonitorHud.Viewport(width, 1));
		}
		// paint itself does not silently discard an already prepared model, even
		// if a caller supplies an empty framebuffer. Width fitting owns the rows.
		assertAllRowsPainted(model, new InventoryMonitorHud.Viewport(0, 0));
		assertTrue(record(MonitorDisplayModel.EMPTY, new InventoryMonitorHud.Viewport(320, 240)).isEmpty());
	}

	@Test void nativeGlyphWrappingPreservesLongUnspacedLatinCjkAndOffColorThroughTheActualPaintLoop() {
		String label = "InventoryPreviewABCDEFGHIJKLMNOPQRSTUVWXYZ容器预览库存跟踪";
		var labels = new Labels() {
			@Override public Component prefix(Scope scope) { return Component.literal(label); }
			@Override public Component field(Metric metric) { return Component.literal(metric.id()); }
			@Override public Component stat(Stat stat) { return Component.literal(stat.name()); }
			@Override public Component status(ClientMonitorState.Status status) { return Component.literal("OFF"); }
			@Override public Component unavailable() { return Component.literal("N/A"); }
			@Override public Component format(Format format, Object... args) {
				if (format == Format.ROW) return Component.empty().append(component(args[0])).append(": ").append(component(args[1]));
				return Component.literal("; ");
			}
		};
		var logical = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, List.of(Metric.SUMMARY_PROCESSING),
			ClientMonitorState.Status.OFF, ClientMonitorState.Status.OFF, ClientMonitorState.Status.DEPENDENCY_OFF, null, null), labels);
		var splitter = splitter();
		var viewport = new InventoryMonitorHud.Viewport(40, 1);
		var fitted = logical.fit(InventoryMonitorHud.availableTextWidth(viewport.width()), splitter);
		assertTrue(fitted.rows().size() > logical.rows().size());
		String all = fitted.rows().stream().map(row -> row.text().getString()).collect(java.util.stream.Collectors.joining());
		assertTrue(all.contains(label), "long token and CJK code points are split rather than truncated");
		for (var row : fitted.rows()) assertTrue(splitter.stringWidth(row.text()) <= 31, "native glyph width, not character count");
		var red = new StringBuilder();
		for (var placed : record(fitted, viewport)) placed.row().text().visit((style, text) -> {
			if (style.getColor() != null && style.getColor().getValue() == ChatFormatting.RED.getColor()) red.append(text);
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		assertEquals("OFFOFF", red.toString(), "only the two server OFF tokens remain red after wrapping");
		assertAllRowsPainted(fitted, viewport);
	}

	private static void assertAllRowsPainted(MonitorDisplayModel model, InventoryMonitorHud.Viewport viewport) {
		var before = model.rows().stream().map(row -> row.text().getString()).toList();
		var painted = record(model, viewport);
		assertEquals(model.rows().size(), painted.size());
		for (int index = 0; index < painted.size(); index++) {
			assertSame(model.rows().get(index), painted.get(index).row());
			assertEquals(4, painted.get(index).x());
			assertEquals(4 + index * 11, painted.get(index).y());
			assertEquals(InventoryMonitorHud.availableTextWidth(viewport.width()), painted.get(index).width());
		}
		assertEquals(before, model.rows().stream().map(row -> row.text().getString()).toList());
	}

	private static List<Placed> record(MonitorDisplayModel model, InventoryMonitorHud.Viewport viewport) {
		var placed = new ArrayList<Placed>();
		InventoryMonitorHud.paint(model, viewport, 9, (row, x, y, width) -> placed.add(new Placed(row, x, y, width)));
		return placed;
	}

	private static MonitorDisplayModel activeModel() {
		var remote = new MonitorSample(1, 1, 1, 10, 14, 5_000_000_000L, serverLane(Lane.PREVIEW),
			serverLane(Lane.TRACKING), new SharedSample(List.of(),
				List.of(new GaugeReading(Gauge.CONSUMERS, 4)), List.of(
					new BudgetReading(Budget.PHYSICAL_SLOTS, new BudgetUsage(true, 1, 512, 256, 0, 256, 256)),
					new BudgetReading(Budget.PROCESSING_CHARGE, new BudgetUsage(true, 2, 12, 3, 0, 9, 9))),
				MemoryUsage.accounted(96 * 1024 * 1024, 32 * 1024 * 1024, 512 * 1024 * 1024)));
		var local = new MonitorSample(1, 1, 1, 10, 14, 2_000_000_000L, localLane(Lane.PREVIEW),
			localLane(Lane.TRACKING), null);
		return MonitorDisplayModel.prepare(new ClientMonitorState.View(true, Metric.defaults(),
			ClientMonitorState.Status.ACTIVE, ClientMonitorState.Status.ACTIVE, ClientMonitorState.Status.ACTIVE, local, remote),
			MonitorHudLabels.DEFAULT);
	}

	private static LaneSample serverLane(Lane lane) {
		boolean preview = lane == Lane.PREVIEW;
		return new LaneSample(Source.SERVER, lane, List.of(new Timing(Phase.INGRESS,
				new TimingStats(preview ? 12_000_000 : 18_000_000, 3, preview ? 8_000_000 : 10_000_000))),
			List.of(new CounterReading(Counter.REQUESTS_RECEIVED, preview ? 12 : 7, false),
				new CounterReading(Counter.REQUESTS_ADMITTED, preview ? 9 : 5, false),
				new CounterReading(Counter.HANDOFF_BYTES, preview ? 20_480 : 40_960, false),
				new CounterReading(Counter.UNOBSERVED_HANDOFF_FRAMES, preview ? 0 : 1, false)),
			List.of(new GaugeReading(Gauge.REQUESTS_OPEN, preview ? 2 : 0)),
			List.of(new BudgetReading(Budget.LOGICAL_SLOTS, new BudgetUsage(true, 1, 64, 7, 0, 57, 57)),
				new BudgetReading(Budget.WIRE_BYTES, new BudgetUsage(true, 1, 20_480, 10_240, 0, 10_240, 10_240))));
	}

	private static LaneSample localLane(Lane lane) {
		boolean preview = lane == Lane.PREVIEW;
		return new LaneSample(Source.LOCAL, lane, List.of(new Timing(Phase.APPLY,
				new TimingStats(preview ? 2_000_000 : 9_000_000, preview ? 2 : 3, preview ? 1_500_000 : 4_000_000))),
			List.of(new CounterReading(Counter.ACCEPTED, preview ? 8 : 6, false),
				new CounterReading(Counter.REJECTED, preview ? 1 : 2, false),
				new CounterReading(preview ? Counter.FRAMES_PREVIEW : Counter.FRAMES_SNAPSHOT, preview ? 4 : 6, false)),
			List.of(new GaugeReading(Gauge.BUFFER_ESTIMATED_BYTES, preview ? 2560 : 10_240)), List.of());
	}

	private static MonitorDisplayModel model() {
		return MonitorDisplayModel.prepare(new ClientMonitorState.View(true, Metric.defaults(),
			ClientMonitorState.Status.OFF, ClientMonitorState.Status.OFF, ClientMonitorState.Status.DEPENDENCY_OFF, null, null),
			MonitorHudLabels.DEFAULT);
	}

	private static StringSplitter splitter() { return new StringSplitter((codePoint, style) -> codePoint > 127 ? 9 : 6); }
	private static Component component(Object value) { return value instanceof Component text ? text : Component.literal(value.toString()); }

	@NotNullByDefault
	private record Placed(MonitorDisplayModel.Row row, int x, int y, int width) {}
}
