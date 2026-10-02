package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.network.InventoryS2CPacket;

/** Paint-only dedicated inventory annotations, alongside—not in—the SECTION provider. */
public final class InventoryTrackingRenderer {
	private InventoryTrackingRenderer() {}
	public record Line(String label, Long count, String pingType, String statusKey, boolean grey) {}
	public record MarkerLines(double x, double y, float scale, List<Line> lines) {
		public MarkerLines { lines = List.copyOf(lines); }
	}
	public static List<Line> lines(ClientInventory.Tracking tracking) {
		if (tracking == null || tracking.entries().isEmpty()) return List.of();
		List<Line> result = new ArrayList<>();
		for (var entry : tracking.entries()) {
			if (result.size() == 3) break;
			if (entry.label() == null || entry.label().isBlank()) continue;
			InventoryS2CPacket.Status quality = entry.fallback() ? InventoryS2CPacket.Status.COMPONENT_TOO_LONG : entry.quality();
			var status = SpatialInventoryView.Status.fromName(tracking.status().name());
			var rowStatus = quality == null ? SpatialInventoryView.Status.READY : SpatialInventoryView.Status.fromName(quality.name());
			String key = rowStatus != SpatialInventoryView.Status.READY ? rowStatus.translationKey() : status.translationKey();
			String safe = entry.label().replaceAll("[\\p{Cntrl}]", " ").strip();
			result.add(new Line(safe.substring(0, Math.min(64, safe.length())), entry.count() < 0 ? null : entry.count(), entry.itemPingType(), key,
				tracking.grey() || status.grey() || rowStatus.grey()));
		}
		return List.copyOf(result);
	}
	public static void draw(GuiGraphics graphics, List<MarkerLines> markers) {
		DrawContext context = new DrawContext(graphics);
		for (var marker : markers) {
			var pose = graphics.pose(); pose.pushPose();
			try {
				pose.translate(marker.x(), marker.y(), 0);
				pose.scale(marker.scale(), marker.scale(), 1);
				int index = 0;
				for (var line : marker.lines()) {
					Component text = Component.literal(line.label());
					if (line.count() != null) text = text.copy().append(" x" + line.count());
					if (line.pingType() != null && PingTypeCatalog.builtIn().findById(line.pingType()).isPresent())
						text = Component.translatable("presentation.pingforit.type." + line.pingType() + ".phrase", text);
					if (line.statusKey() != null) text = text.copy().append(" — ").append(Component.translatable(line.statusKey()));
					String bounded = text.getString();
					text = Component.literal(bounded.substring(0, Math.min(64, bounded.length())));
					int color = line.grey() ? 0xFF7A7A7A : PingTypeCatalog.builtIn().findById(line.pingType() == null ? "" : line.pingType())
						.map(type -> 0xFF000000 | type.outlineColor()).orElse(RenderColorPolicy.targetTextColor());
					context.renderLabel(text, -7.75f - index++ * 1.25f, null, color);
				}
			} finally { pose.popPose(); }
		}
	}
}
