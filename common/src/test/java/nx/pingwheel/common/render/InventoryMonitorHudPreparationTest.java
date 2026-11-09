package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.monitor.MonitorHudLabels;
import nx.pingwheel.common.monitor.MonitorSchema.*;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.monitor.client.MonitorDisplayModel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Native-width preparation and the production two-pass placement loop, without a GL context. */
@NotNullByDefault
class InventoryMonitorHudPreparationTest {

	@Test void preparationMeasuresEachNonblankVisualOnceAndRepeatedPaintNeverMeasures() {
		var model = modelWithReservedBlankRows();
		var font = new CountingFont();
		var prepared = InventoryMonitorHud.prepare(model, font);
		assertSame(model, prepared.model());
		var expected = model.rows().stream().filter(row -> !row.isBlank()).map(row -> row.text().getVisualOrderText()).toList();
		assertEquals(expected.size(), font.measured.size());
		for (int index = 0; index < expected.size(); index++) assertSame(expected.get(index), font.measured.get(index));
		font.rejectMeasurement = true;
		var first = record(prepared, new InventoryMonitorHud.Viewport(200, 1));
		var repeated = record(prepared, new InventoryMonitorHud.Viewport(200, 0));
		assertEquals(first.events, repeated.events, "height has no fitting or culling role");
		assertEquals(expected.size(), font.measured.size());
		assertSame(InventoryMonitorHud.Prepared.EMPTY, InventoryMonitorHud.prepare(MonitorDisplayModel.EMPTY, font));
	}

	@Test void backgroundsFollowActualCachedRowWidthsAndOneSubmissionPrecedesEveryTextRow() {
		var model = activeModel(12_000_000).fit(191, splitter());
		var font = new CountingFont();
		var prepared = InventoryMonitorHud.prepare(model, font);
		var viewport = new InventoryMonitorHud.Viewport(200, 1);
		var recorder = record(prepared, viewport);
		assertTrue(recorder.backgrounds.size() > 1);
		assertEquals(model.rows().size(), recorder.backgrounds.size());
		assertEquals(recorder.backgrounds.size(), recorder.text.size());
		assertTrue(recorder.backgrounds.stream().map(rect -> rect.right - rect.left).distinct().count() > 1,
			"separate row widths, never one viewport-wide panel");
		for (int index = 0; index < recorder.backgrounds.size(); index++) {
			var rect = recorder.backgrounds.get(index);
			assertEquals(new Rect(3, 3 + index * 11, 6 + CountingFont.visualWidth(model.rows().get(index).text().getVisualOrderText()),
				14 + index * 11, 0x26000000), rect);
			assertTrue(rect.right < viewport.width());
			if (index > 0) assertEquals(recorder.backgrounds.get(index - 1).bottom, rect.top,
				"one-pixel padding meets at the gap without double-alpha overlap");
		}
		int boundary = recorder.events.indexOf("submitted");
		assertEquals(recorder.backgrounds.size(), boundary);
		assertEquals(1, recorder.events.stream().filter("submitted"::equals).count());
		for (int index = 0; index < boundary; index++) assertEquals("background", recorder.events.get(index));
		for (int index = boundary + 1; index < recorder.events.size(); index++) assertEquals("text", recorder.events.get(index));
	}

	@Test void reservedBlankRowsConsumeTheirPlacementButEmitNeitherTextNorBackground() {
		var model = modelWithReservedBlankRows();
		assertTrue(model.rows().stream().anyMatch(MonitorDisplayModel.Row::isBlank));
		var prepared = InventoryMonitorHud.prepare(model, new CountingFont());
		var recorder = record(prepared, new InventoryMonitorHud.Viewport(200, 1));
		int painted = 0;
		for (int index = 0; index < model.rows().size(); index++) {
			var row = model.rows().get(index);
			if (row.isBlank()) continue;
			assertSame(row, recorder.text.get(painted).row);
			assertEquals(4 + index * 11, recorder.text.get(painted).y);
			assertEquals(3 + index * 11, recorder.backgrounds.get(painted).top);
			painted++;
		}
		assertEquals(painted, recorder.backgrounds.size());
		assertEquals(painted, recorder.text.size());
		assertTrue(recorder.text.getLast().y > 1, "off-bottom real text is still submitted");
		var ordinary = new ArrayList<MonitorDisplayModel.Row>();
		InventoryMonitorHud.paint(model, new InventoryMonitorHud.Viewport(200, 1), 9, (row, x, y, width) -> ordinary.add(row));
		assertEquals(recorder.text.stream().map(Placed::row).toList(), ordinary, "model-only seam keeps the same blank policy");
	}

	@Test void paddingAndShadowClampHorizontallyAtZeroNarrowAndExtremeWidthsWithoutBottomCulling() {
		var model = activeModel(12_000_000).fit(191, splitter());
		var prepared = InventoryMonitorHud.prepare(model, new CountingFont());
		for (int width : List.of(0, 1, 8, 9, 10, 20, Integer.MAX_VALUE)) {
			var recorder = record(prepared, new InventoryMonitorHud.Viewport(width, 1));
			assertEquals(model.rows().size(), recorder.backgrounds.size());
			assertEquals(model.rows().size(), recorder.text.size());
			for (int index = 0; index < recorder.backgrounds.size(); index++) {
				var rect = recorder.backgrounds.get(index);
				assertEquals(Math.min(3, width), rect.left);
				assertEquals(Math.min(width, 6 + Math.min(CountingFont.visualWidth(model.rows().get(index).text().getVisualOrderText()),
					InventoryMonitorHud.availableTextWidth(width))), rect.right);
				assertTrue(0 <= rect.left && rect.left <= rect.right && rect.right <= width);
				assertEquals(3 + index * 11, rect.top);
				assertEquals(14 + index * 11, rect.bottom);
			}
		}
	}

	@Test void preparationAndPaintRetainTitleOffAndOrdinaryWhiteRunsWithoutParsingLabels() {
		var model = activeModel(12_000_000).fit(28, splitter());
		var recorder = record(InventoryMonitorHud.prepare(model, new CountingFont()), new InventoryMonitorHud.Viewport(37, 1));
		var title = new StringBuilder();
		var red = new StringBuilder();
		var white = new StringBuilder();
		for (var placed : recorder.text) placed.row.text().getVisualOrderText().accept((index, style, codePoint) -> {
			@Nullable var color = style.getColor();
			assertNotNull(color, "prepared runs carry explicit styles");
			switch (color.getValue()) {
				case 0x01F7F7 -> title.appendCodePoint(codePoint);
				case 0xFF5555 -> red.appendCodePoint(codePoint);
				case 0xFFFFFF -> white.appendCodePoint(codePoint);
				default -> fail("unexpected prepared color " + color.getValue());
			}
			return true;
		});
		assertFalse(title.isEmpty());
		assertFalse(red.isEmpty());
		assertFalse(white.isEmpty());
		assertFalse(title.toString().contains("12"), "field values are not globally tinted cyan");
		assertTrue(white.toString().contains("12"));
	}

	private static Recorder record(InventoryMonitorHud.Prepared prepared, InventoryMonitorHud.Viewport viewport) {
		var recorder = new Recorder();
		InventoryMonitorHud.paint(prepared, viewport, 9, recorder);
		return recorder;
	}

	private static MonitorDisplayModel modelWithReservedBlankRows() {
		var large = activeModel(9_000_000_000_000_000_000L);
		var small = activeModel(12_000_000);
		for (int width = 10; width <= 80; width++) {
			var first = large.fit(width, splitter(), MonitorDisplayModel.Reservations.EMPTY);
			var next = small.fit(width, splitter(), first.nextReservations()).model();
			if (next.rows().stream().anyMatch(MonitorDisplayModel.Row::isBlank)) return next;
		}
		throw new AssertionError("native-width shrink must create reserved blank rows");
	}

	private static MonitorDisplayModel activeModel(long nanos) {
		var preview = new LaneSample(Source.SERVER, Lane.PREVIEW,
			List.of(new Timing(Phase.INGRESS, new TimingStats(nanos, 1, nanos))),
			List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 12, false)), List.of(), List.of());
		var remote = new MonitorSample(1, 1, 1, 10, 14, 5_000_000_000L, preview, null, null);
		return MonitorDisplayModel.prepare(new ClientMonitorState.View(true,
			List.of(Metric.SUMMARY_PROCESSING, Metric.SUMMARY_REQUESTS_QUEUE), ClientMonitorState.Status.ACTIVE,
			ClientMonitorState.Status.OFF, ClientMonitorState.Status.DEPENDENCY_OFF, null, remote), TestLabels.INSTANCE);
	}

	private static StringSplitter splitter() { return new StringSplitter((codePoint, style) -> 1); }

	@NotNullByDefault
	private enum TestLabels implements MonitorHudLabels.Labels {
		INSTANCE;
		@Override public Component prefix(Scope scope) { return Component.literal(scope.toString()); }
		@Override public Component field(Metric metric) { return Component.literal(metric.phase() == null ? "Received" : "Time"); }
		@Override public Component stat(Stat stat) { return Component.literal(stat.name()); }
		@Override public Component status(ClientMonitorState.Status status) { return Component.literal(status.name()); }
		@Override public Component unavailable() { return Component.literal("N/A"); }
		@Override public Component format(Format format, Object... args) {
			return switch (format) {
				case ROW -> Component.empty().append(component(args[0])).append(": ").append(component(args[1]));
				case ENTRY, STAT -> Component.empty().append(component(args[0])).append(" ").append(component(args[1]));
				case MEMORY -> Component.empty().append(component(args[0])).append(" ").append(component(args[1])).append(" ").append(component(args[2]));
				case FIELD_SEPARATOR -> Component.literal("; ");
				case DETAIL_SEPARATOR -> Component.literal(" ");
			};
		}
		private static Component component(Object value) { return value instanceof Component text ? text : Component.literal(value.toString()); }
	}

	@NotNullByDefault
	private static final class CountingFont extends Font {
		private final List<FormattedCharSequence> measured = new ArrayList<>();
		private boolean rejectMeasurement;
		private CountingFont() { super(ignored -> { throw new AssertionError("test width must not request native glyphs"); }, false); }
		@Override public int width(FormattedCharSequence text) {
			assertFalse(rejectMeasurement, "pure paint must not measure text");
			measured.add(text);
			return visualWidth(text);
		}
		private static int visualWidth(FormattedCharSequence text) {
			int[] width = {0};
			text.accept((index, style, codePoint) -> { width[0]++; return true; });
			return width[0];
		}
	}

	@NotNullByDefault
	private static final class Recorder implements InventoryMonitorHud.PreparedPainter {
		private final List<Rect> backgrounds = new ArrayList<>();
		private final List<Placed> text = new ArrayList<>();
		private final List<String> events = new ArrayList<>();
		@Override public void background(int left, int top, int right, int bottom, int color) {
			backgrounds.add(new Rect(left, top, right, bottom, color)); events.add("background");
		}
		@Override public void backgroundsComplete() { events.add("submitted"); }
		@Override public void row(MonitorDisplayModel.Row row, int x, int y, int maximumWidth) {
			text.add(new Placed(row, x, y, maximumWidth)); events.add("text");
		}
	}

	@NotNullByDefault
	private record Rect(int left, int top, int right, int bottom, int color) {}
	@NotNullByDefault
	private record Placed(MonitorDisplayModel.Row row, int x, int y, int maximumWidth) {}
}
