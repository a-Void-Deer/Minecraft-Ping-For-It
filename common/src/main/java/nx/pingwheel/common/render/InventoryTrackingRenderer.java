package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.network.InventoryS2CPacket;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.NotNullByDefault;

/** Paint-only dedicated inventory annotations, alongside—not in—the SECTION provider. */
@NotNullByDefault
public final class InventoryTrackingRenderer {
	private InventoryTrackingRenderer() {}
	public record Line(String label, @Nullable Long count, @Nullable String pingType, @Nullable String statusKey,
		@Nullable String qualifierKey, boolean grey) {}
	public record MarkerLines(double x, double y, float scale, @Unmodifiable List<Line> lines) {
		public MarkerLines { lines = List.copyOf(lines); }
	}
	public static @Unmodifiable List<Line> lines(ClientInventory.@Nullable Tracking tracking) {
		if (tracking == null || tracking.entries().isEmpty()) return List.of();
		List<Line> result = new ArrayList<>();
		for (var entry : tracking.entries()) {
			if (result.size() == 3) break;
			// The shared projection decodes the same server-bounded display value
			// and name policy the inventory list uses; a missing or malformed
			// value falls back to the registry translation key.
			String name = ClientItemDisplay.resolve(entry.itemId(), entry.label(), entry.displayJson()).name().getString();
			if (name.isBlank()) continue;
			InventoryS2CPacket.@Nullable Status quality = entry.fallback() ? InventoryS2CPacket.Status.COMPONENT_TOO_LONG : entry.quality();
			var status = SpatialInventoryView.Status.fromName(tracking.status().name());
			var rowStatus = quality == null ? SpatialInventoryView.Status.READY : SpatialInventoryView.Status.fromName(quality.name());
			@Nullable String qualifier = rowStatus == SpatialInventoryView.Status.COMPONENT_TOO_LONG ? rowStatus.translationKey() : null;
			String safe = name.replaceAll("[\\p{Cntrl}]", " ").strip();
			result.add(new Line(safe.substring(0, Math.min(64, safe.length())), entry.count() < 0 ? null : entry.count(), entry.itemPingType(), status.translationKey(), qualifier,
				tracking.grey() || status.grey() || rowStatus.grey()));
		}
		return List.copyOf(result);
	}
	/** Shorten descriptive fields only; the localized quantity is an indivisible semantic field. */
	static Component text(Line line) {
		String lifecycle = line.statusKey() == null ? "" : Component.translatable(line.statusKey()).getString();
		String qualifier = line.qualifierKey() == null ? "" : Component.translatable(line.qualifierKey()).getString();
		String name = shorten(line.label(), 64);
		// Resolve grammar with a placeholder, so annotations may put the item
		// before or after their phrase without treating any count digits as prose.
		String slot = "\u0001";
		String template = line.pingType() != null && PingTypeCatalog.builtIn().findById(line.pingType()).isPresent()
			? Component.translatable("presentation.pingforit.type." + line.pingType() + ".phrase", slot).getString() : slot;
		String[] phrase = template.split(slot, -1);
		int copies = phrase.length - 1;
		if (copies == 0) { phrase = new String[] {"", ""}; copies = 1; }
		String suffix = suffix(lifecycle, qualifier);
		String item = itemCount(name, line.count());
		int phraseLength = String.join("", phrase).length();
		while (phraseLength + copies * item.length() + suffix.length() > 64 && !name.isEmpty()) {
			name = shorten(name, name.length() - 1);
			item = itemCount(name, line.count());
		}
		// Very long translations spend their remaining space on lifecycle and
		// identity labels before annotation wording. Never substring the item/count.
		int phraseBudget = Math.max(0, 64 - copies * item.length() - suffix.length());
		for (int i = 0; i < phrase.length; i++) {
			phrase[i] = shorten(phrase[i], phraseBudget);
			phraseBudget -= phrase[i].length();
		}
		int labelBudget = Math.max(0, 64 - copies * item.length() - String.join("", phrase).length());
		if (suffix.length() > labelBudget) {
			int labels = Math.max(0, labelBudget - (lifecycle.isEmpty() ? 0 : 3) - (qualifier.isEmpty() ? 0 : 3));
			int lifecycleBudget = Math.min(lifecycle.length(), Math.max(0, labels - (qualifier.isEmpty() ? 0 : 1)));
			qualifier = shorten(qualifier, labels - lifecycleBudget);
			lifecycle = shorten(lifecycle, lifecycleBudget);
			suffix = suffix(lifecycle, qualifier);
		}
		return Component.literal(String.join(item, phrase) + suffix);
	}
	private static String itemCount(String name, @Nullable Long count) {
		return count == null ? name : Component.translatableWithFallback("presentation.pingforit.format.item_count", "%s × %s", name, Long.toString(count)).getString();
	}
	private static String suffix(String lifecycle, String qualifier) {
		return (lifecycle.isEmpty() ? "" : " — " + lifecycle) + (qualifier.isEmpty() ? "" : " — " + qualifier);
	}
	private static String shorten(String text, int limit) {
		return text.length() <= limit ? text : limit <= 0 ? "" : text.substring(0, limit - 1) + "…";
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
					Component text = text(line);
					int color = line.grey() ? 0xFF7A7A7A : PingTypeCatalog.builtIn().findById(line.pingType() == null ? "" : line.pingType())
						.map(type -> 0xFF000000 | type.outlineColor()).orElse(RenderColorPolicy.targetTextColor());
					context.renderLabel(text, -7.75f - index++ * 1.25f, null, color);
				}
			} finally { pose.popPose(); }
		}
	}
}
