package nx.pingwheel.common.render;

import java.util.ArrayList;
import net.minecraft.network.chat.Component;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.monitor.client.MonitorDisplayModel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryMonitorHudTest {

	@Test void paintUsesOnlyThePreparedModelAndGuiScaledViewportWithChatAndNoticeClearance() {
		var model = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, Metric.defaults(),
			ClientMonitorState.Status.OFF, ClientMonitorState.Status.OFF, ClientMonitorState.Status.DEPENDENCY_OFF, null, null),
			metric -> Component.literal(metric.id()), metric -> Component.literal(metric.id()), scope -> Component.literal(scope.id()));
		var viewport = new InventoryMonitorHud.Viewport(320, 180, 50, 30);
		var before = model.rows().stream().map(row -> row.text().getString()).toList();
		var painted = new ArrayList<MonitorDisplayModel.Row>();
		InventoryMonitorHud.paint(model, viewport, 9, (row, x, y, width) -> {
			assertEquals(4, x); assertEquals(312, width); assertTrue(y >= 34); assertTrue(y + 9 <= 126);
			painted.add(row);
		});
		assertEquals(7, painted.size()); assertSame(model.rows().getFirst(), painted.getFirst());
		assertEquals(before, model.rows().stream().map(row -> row.text().getString()).toList());
		var resized = InventoryMonitorHud.layout(model, new InventoryMonitorHud.Viewport(160, 90, 20, 15), 9);
		assertEquals(4, resized.visibleRows()); assertEquals(66, resized.bottom()); assertTrue(resized.top() >= 19);
		assertEquals(0, InventoryMonitorHud.layout(model, new InventoryMonitorHud.Viewport(0, 0, 999, 999), 9).visibleRows());
		assertEquals(0, InventoryMonitorHud.layout(MonitorDisplayModel.EMPTY, viewport, 9).visibleRows());
	}
}
