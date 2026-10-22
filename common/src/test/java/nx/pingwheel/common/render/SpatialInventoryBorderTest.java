package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.client.spatial.SpatialController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpatialInventoryBorderTest {
	private static final SpatialOverlayRenderer.Style STYLE = new SpatialOverlayRenderer.Style(100, 100, 1, 1, false, true);
	private record Fill(int left, int top, int right, int bottom, int color) {}
	private static final class Painter implements SpatialOverlayRenderer.FramePainter {
		final List<SpatialOverlayRenderer.RowPaint> rows = new ArrayList<>();
		public int lineHeight() { return 9; }
		public int width(FormattedText text) { return text.getString().length() * 6; }
		public StringSplitter splitter() { return new StringSplitter((code, style) -> 6f); }
		public boolean paint(SpatialOverlayTransitions.State<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> state,
			SpatialOverlayRenderer.NodeLayout layout, SpatialOverlayRenderer.Style style) {
			if (state.data() instanceof SpatialOverlayRenderer.RowPaint row) rows.add(row);
			return true;
		}
		public void connector(SpatialOverlayRenderer.GuideLine line, double alpha) {}
	}
	private static SpatialInventoryView view() {
		int color = PingTypeCatalog.builtIn().findById("request").orElseThrow().outlineColor();
		return new SpatialInventoryView("list", true, List.of(
			new SpatialInventoryView.Row("a", "Item A", null, null, null, SpatialInventoryView.Status.INCOMPLETE, color),
			new SpatialInventoryView.Row("b", "Item B", 3L, null, null, SpatialInventoryView.Status.READY, color),
			new SpatialInventoryView.Row("c", "No annotation", 1L, null, SpatialInventoryView.Status.READY)),
			0, 0, 3, true, SpatialInventoryView.Status.UNKNOWN, 0, 0).withNavigation(null, true, true);
	}
	private static List<SpatialOverlayRenderer.RowPaint> paint(SpatialInventoryView view) {
		var p = new Painter();
		var origin = new SpatialController.Point(0, 0);
		var choices = view.retainedRowKey() == null ? List.<SpatialController.ChoiceView>of()
			: List.of(new SpatialController.ChoiceView("back", "Back", null, true, false, false, false, true, 0, 360, null));
		var radial = new SpatialController.Snapshot(true, origin, List.of(origin), null,
			List.of(new SpatialController.MenuView("list", origin, origin, null, choices)), 0, 12);
		new SpatialOverlayRenderer.Session().drawFrame(radial, view, c -> Component.literal(c.label()), null,
			STYLE, 0, null, 400, 300, p);
		return p.rows;
	}

	@Test void onlySelectedCommittableRowHasCompleteAnnotationBorderEvenWithGreyQuality() {
		var rows = paint(view());
		var row = rows.stream().filter(SpatialOverlayRenderer.RowPaint::selected).findFirst().orElseThrow();
		assertTrue(row.grey(), "unknown quality affects text, not send capability");
		assertEquals(row.row().outlineColor(), row.outlineColor());
		List<Fill> fills = new ArrayList<>();
		SpatialOverlayRenderer.paintRowFills(row, STYLE, 1, 0,
			(l, t, r, b, color) -> fills.add(new Fill(l, t, r, b, color)));
		assertEquals(4, fills.size(), "a complete rectangle, not just a selected stripe");
		int rgb = row.outlineColor();
		assertTrue(fills.stream().allMatch(f -> f.color() == (0xFF000000 | rgb)));
		int left = (int) Math.round(-row.width() / 2) + 1, right = (int) Math.round(row.width() / 2) - 1;
		assertEquals(left, fills.get(0).left()); assertEquals(right, fills.get(0).right());
		assertEquals(left, fills.get(2).left()); assertEquals(right, fills.get(3).right());
		assertEquals(fills.get(0).top(), -fills.get(1).bottom());
		var unselected = rows.stream().filter(value -> value.row().key().equals("b")).findFirst().orElseThrow();
		assertNotNull(unselected.row().outlineColor(), "the DTO still carries annotation capability");
		assertNull(unselected.outlineColor());
		List<Fill> unselectedFills = new ArrayList<>();
		SpatialOverlayRenderer.paintRowFills(unselected, STYLE, 1, 0,
			(l, t, r, b, color) -> unselectedFills.add(new Fill(l, t, r, b, color)));
		assertTrue(unselectedFills.isEmpty(), "an unselected row gains neither a colored border nor a fill");
		assertNull(rows.get(2).outlineColor(), "the renderer never invents annotation authority");
	}

	@Test void backFocusAndRetainedItemRowRemoveDirectSendDecoration() {
		assertTrue(paint(view().withBackAffordance(true, 0.5)).stream().allMatch(row -> row.outlineColor() == null));
		var retained = paint(view().withNavigation("a", false, false));
		assertEquals(1, retained.size()); assertNull(retained.getFirst().outlineColor());
		assertNull(paint(view().withNavigation(null, true, false)).getFirst().outlineColor());
	}

	@Test void clippedFullSizeQuantityNeverChangesTheSelectedOnlyAnnotationBorder() {
		var original = view();
		var rows = new ArrayList<>(original.rows());
		var selected = rows.getFirst();
		rows.set(0, new SpatialInventoryView.Row(selected.key(), selected.label(), Long.MAX_VALUE, selected.itemId(),
			selected.displayJson(), selected.quality(), selected.outlineColor()));
		var longCount = new SpatialInventoryView("list", true, rows, 0, 0, 3, true, SpatialInventoryView.Status.UNKNOWN, 0, 0)
			.withNavigation(null, true, true);
		var painted = paint(longCount);
		assertEquals(List.of("a"), painted.stream().filter(row -> row.outlineColor() != null).map(row -> row.row().key()).toList());
		var row = painted.getFirst();
		var font = new StringSplitter((point, style) -> 6f);
		var quantity = SpatialOverlayRenderer.inventoryText(SpatialInventoryLayout.quantity(row.row().count()),
			18, STYLE.inventoryTextScale(), text -> (int) font.stringWidth(text), font);
		assertEquals(STYLE.inventoryTextScale(), quantity.scale());
		assertTrue(font.stringWidth(quantity.text()) * quantity.scale() <= 18);
		List<Fill> fills = new ArrayList<>();
		SpatialOverlayRenderer.paintRowFills(row, STYLE, 1, 0,
			(l, t, r, b, color) -> fills.add(new Fill(l, t, r, b, color)));
		assertEquals(4, fills.size());
		assertTrue(fills.stream().allMatch(fill -> fill.color() == (0xFF000000 | selected.outlineColor())));
	}
}
