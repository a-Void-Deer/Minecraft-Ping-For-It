package nx.pingwheel.common.render;

import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FastColor;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.client.ClientPingRuntime;
import nx.pingwheel.common.client.WheelPresentationSnapshot;
import nx.pingwheel.common.client.wheel.WheelGeometry;
import nx.pingwheel.common.client.wheel.WheelLabelLayout;
import nx.pingwheel.common.client.wheel.WheelPoint;
import nx.pingwheel.common.client.wheel.WheelSector;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.ClientConfigBounds;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import nx.pingwheel.common.interaction.wheel.WheelSelection;
import nx.pingwheel.common.name.ClientTargetNameResolver;

import static nx.pingwheel.common.resource.ResourceConstants.PING_TEXTURE_ID;

/**
 * Phase-7 ping type wheel overlay, drawn every GUI frame while the wheel is
 * open.
 *
 * <p>Geometry comes from the pure {@link WheelGeometry} (wheel center, radii,
 * sector partition, mouse selection); this class only rasterizes it with
 * loader-neutral 1.21.1 {@link GuiGraphics} methods. Minecraft 1.21.1 has no
 * arc/circle API, so every arc, separator, circle, and line is drawn with a
 * deterministic sampled {@code fill}-based helper whose cost is bounded by
 * {@link #ARC_SAMPLES_PER_FULL_RING} angular samples.
 *
 * <p>Rendering rules:
 * <ul>
 *   <li>every sector's inner and outer border uses exactly
 *       {@code 0xFF000000 | PingType.outlineColor()};</li>
 *   <li>radial separators reuse the sector's color;</li>
 *   <li>the ring backdrop is a neutral translucent fill;</li>
 *   <li>the selected sector keeps its type color but gets a thicker border;
 *       the selected center gets a thicker border and a brighter fill;</li>
 *   <li>the existing {@link nx.pingwheel.common.resource.ResourceConstants#PING_TEXTURE_ID}
 *       icon is tinted with the sector color at each sector midpoint (1.21.1
 *       has no {@code GuiGraphics.setColor}, so tinting uses the
 *       {@link RenderSystem} shader color like the existing
 *       {@link DrawContext}, always reset to white afterwards);</li>
 *   <li>the center is a dark translucent disk with a light-red border and an
 *       {@code X} mark, while each sector also carries its localized display
 *       name;</li>
 *   <li>the frozen target's presentation name is centered above the wheel and
 *       is never obtained by a new raycast.</li>
 * </ul>
 *
 * <p>This class never logs: selection-change logging is owned by the state
 * machine, so nothing here produces per-frame log spam.
 */
public final class WheelOverlayRenderer {
	private WheelOverlayRenderer() {}

	private static final ClientTargetNameResolver TARGET_NAME_RESOLVER = new ClientTargetNameResolver();

	/** Fixed total angular samples for the whole sector ring. */
	private static final int ARC_SAMPLES_PER_FULL_RING = 72;

	/** Fixed angular samples for the center circle outline. */
	private static final int CENTER_CIRCLE_SAMPLES = 48;

	static final int ICON_SIZE = 6;

	private static final int RING_BACKDROP_COLOR = 0x50000000;
	private static final int CENTER_BACKDROP_COLOR = 0x88000000;
	private static final int CENTER_BACKDROP_SELECTED_COLOR = 0x99FF6B6B;
	private static final int CENTER_BORDER_COLOR = 0xFFFF6B6B;
	private static final int CENTER_MARK_COLOR = 0xFFFF6B6B;
	private static final int TARGET_LABEL_COLOR = 0xFFF5F5F5;

	static final int ARC_THICKNESS = 1;
	static final int SELECTED_ARC_THICKNESS = 2;
	static final int SEPARATOR_THICKNESS = 1;
	static final int CENTER_BORDER_THICKNESS = 1;
	static final int SELECTED_CENTER_BORDER_THICKNESS = 2;
	static final int CENTER_MARK_THICKNESS = 1;

	/**
	 * Draws the wheel overlay for the current frame.
	 *
	 * <p>When the runtime is missing or the machine is not in
	 * {@link PingInteractionPhase#WHEEL_OPEN}, any stale queued wheel selection
	 * is reset to {@link WheelSelection#NONE} and nothing is drawn. When the
	 * wheel is open, the frozen wheel ping types, the GUI center, and the raw
	 * mouse position (converted from window space to GUI space via the
	 * window/gui scale ratios) drive {@link WheelGeometry#select}, and the
	 * resulting selection is fed back through
	 * {@link ClientPingRuntime#setWheelSelection} before anything is drawn.
	 */
	public static void draw(GuiGraphics guiGraphics, float tickDelta) {
		Minecraft game = CommonClient.Game;

		if (game == null) {
			return;
		}

		ClientPingRuntime runtime = CommonClient.INSTANCE.getPingRuntime();

		if (runtime == null || runtime.phase() != PingInteractionPhase.WHEEL_OPEN) {
			resetSelectionIfNeeded(runtime);
			return;
		}

		Optional<WheelPresentationSnapshot> presentation = runtime.wheelPresentation();

		if (presentation.isEmpty()) {
			resetSelectionIfNeeded(runtime);
			return;
		}

		WheelPresentationSnapshot snapshot = presentation.get();
		List<PingType> pingTypes = snapshot.pingTypes();

		if (pingTypes.isEmpty()) {
			resetSelectionIfNeeded(runtime);
			return;
		}

		ClientConfig config = ClientConfig.HANDLER.getConfig();
		ClientConfigBounds.WheelRadii radii = ClientConfigBounds.clampWheelRadii(
			config.getWheelInnerRadius(),
			config.getWheelOuterRadius());
		WheelGeometry geometry = new WheelGeometry(
			radii.innerRadius(),
			radii.outerRadius());
		int opacity = ClientConfigBounds.clampWheelOpacity(config.getWheelOpacity());

		List<WheelSector> sectors = geometry.sectors(pingTypes);
		double centerX = guiGraphics.guiWidth() / 2.0;
		double centerY = guiGraphics.guiHeight() / 2.0;
		WheelSelection selection = selectionFromMouse(
			game,
			guiGraphics,
			geometry,
			pingTypes,
			centerX,
			centerY);

		runtime.setWheelSelection(selection);

		if (!WheelOpacity.shouldRender(opacity)) {
			return;
		}

		int fontSize = ClientConfigBounds.clampWheelFontSize(config.getWheelFontSize());
		double sectorMaxScale = WheelLabelLayout.BASE_TEXT_SCALE * fontSize / 100.0;
		double targetLabelScale = fontSize / 100.0;

		var pose = guiGraphics.pose();
		pose.pushPose();

		try {
			drawTargetName(
				guiGraphics,
				game.font,
				geometry,
				centerX,
				centerY,
				targetLabelScale,
				opacity,
				snapshot);
			drawRing(
				guiGraphics,
				game.font,
				geometry,
				sectors,
				centerX,
				centerY,
				sectorMaxScale,
				opacity,
				selection);
			drawCenter(guiGraphics, geometry, centerX, centerY, opacity, selection);
		} finally {
			pose.popPose();
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}
	}

	private static void resetSelectionIfNeeded(ClientPingRuntime runtime) {
		if (runtime != null && runtime.wheelSelection() != WheelSelection.NONE) {
			runtime.setWheelSelection(WheelSelection.NONE);
		}
	}

	/**
	 * Converts the raw mouse position from window (framebuffer) space into GUI
	 * space and selects the wheel region under it. A degenerate window or GUI
	 * size (for example a minimized window) selects nothing.
	 */
	private static WheelSelection selectionFromMouse(
		Minecraft game,
		GuiGraphics guiGraphics,
		WheelGeometry geometry,
		List<PingType> pingTypes,
		double centerX,
		double centerY
	) {
		var window = game.getWindow();
		double windowWidth = window.getWidth();
		double windowHeight = window.getHeight();
		double guiWidth = guiGraphics.guiWidth();
		double guiHeight = guiGraphics.guiHeight();

		if (windowWidth <= 0.0 || windowHeight <= 0.0 || guiWidth <= 0.0 || guiHeight <= 0.0) {
			return WheelSelection.NONE;
		}

		double dx = game.mouseHandler.xpos() / windowWidth * guiWidth - centerX;
		double dy = game.mouseHandler.ypos() / windowHeight * guiHeight - centerY;

		return geometry.select(dx, dy, pingTypes);
	}

	private static void drawRing(
		GuiGraphics guiGraphics,
		Font font,
		WheelGeometry geometry,
		List<WheelSector> sectors,
		double centerX,
		double centerY,
		double sectorMaxScale,
		int opacity,
		WheelSelection selection
	) {
		fillAnnulus(
			guiGraphics,
			centerX,
			centerY,
			geometry.innerRadius(),
			geometry.outerRadius(),
			RING_BACKDROP_COLOR,
			opacity);

		for (WheelSector sector : sectors) {
			int borderColor = 0xFF000000 | sector.outlineColor();
			int thickness = isSelected(sector, selection) ? SELECTED_ARC_THICKNESS : ARC_THICKNESS;

			drawArc(guiGraphics, geometry, sector, geometry.innerRadius(), centerX, centerY, thickness, borderColor, opacity);
			drawArc(guiGraphics, geometry, sector, geometry.outerRadius(), centerX, centerY, thickness, borderColor, opacity);

			if (sectors.size() > 1) {
				drawRadialSeparator(guiGraphics, geometry, sector, centerX, centerY, borderColor, opacity);
			}
		}

		List<Component> labels = sectors.stream()
			.<Component>map(sector -> Component.translatable(sector.pingType().displayKey()))
			.toList();
		List<Integer> textWidths = labels.stream().map(font::width).toList();
		List<WheelLabelLayout.Placement> placements = WheelLabelLayout.layout(
			geometry,
			sectors,
			textWidths,
			font.lineHeight,
			sectorMaxScale);

		for (int i = 0; i < placements.size(); i++) {
			WheelLabelLayout.Placement placement = placements.get(i);
			int color = 0xFF000000 | (isSelected(placement.pingType(), selection)
				? placement.pingType().textColor()
				: placement.pingType().outlineColor());

			drawIcon(
				guiGraphics,
				centerX + placement.iconAnchor().x(),
				centerY + placement.iconAnchor().y(),
				0xFF000000 | placement.pingType().outlineColor(),
				opacity);
			drawLabel(
				guiGraphics,
				font,
				labels.get(i),
				placement,
				centerX,
				centerY,
				color,
				opacity);
		}
	}

	private static void drawTargetName(
		GuiGraphics guiGraphics,
		Font font,
		WheelGeometry geometry,
		double centerX,
		double centerY,
		double requestedScale,
		int opacity,
		WheelPresentationSnapshot snapshot
	) {
		Component targetName = TARGET_NAME_RESOLVER.resolve(
			snapshot.context().resolvedTarget().target());
		WheelLabelLayout.TargetLabelPlacement placement = WheelLabelLayout.targetLabelFit(
			centerX,
			centerY,
			guiGraphics.guiWidth(),
			guiGraphics.guiHeight(),
			geometry.outerRadius(),
			font.width(targetName),
			font.lineHeight,
			requestedScale);
		int color = WheelOpacity.applyText(TARGET_LABEL_COLOR, opacity);

		var pose = guiGraphics.pose();
		pose.pushPose();

		try {
			pose.translate(placement.x(), placement.topY(), 0.0);
			float labelScale = (float) placement.scale();
			pose.scale(labelScale, labelScale, 1.0f);
			guiGraphics.drawString(font, targetName, 0, 0, color, true);
		} finally {
			pose.popPose();
		}
	}

	private static void drawLabel(
		GuiGraphics guiGraphics,
		Font font,
		Component label,
		WheelLabelLayout.Placement placement,
		double centerX,
		double centerY,
		int color,
		int opacity
	) {
		var pose = guiGraphics.pose();
		pose.pushPose();

		try {
			float labelScale = (float) placement.scale();
			WheelPoint labelOrigin = WheelLabelLayout.labelOrigin(
				placement.labelAnchor(),
				placement.textWidth(),
				font.lineHeight,
				labelScale);
			pose.translate(
				centerX + labelOrigin.x(),
				centerY + labelOrigin.y(),
				0.0);
			pose.scale(labelScale, labelScale, 1.0f);
			guiGraphics.drawString(
				font,
				label,
				0,
				0,
				WheelOpacity.applyText(color, opacity),
				true);
		} finally {
			pose.popPose();
		}
	}

	private static void drawCenter(
		GuiGraphics guiGraphics,
		WheelGeometry geometry,
		double centerX,
		double centerY,
		int opacity,
		WheelSelection selection
	) {
		double radius = geometry.innerRadius();
		boolean selected = selection == WheelSelection.CENTER;

		fillCircle(
			guiGraphics,
			centerX,
			centerY,
			radius,
			selected ? CENTER_BACKDROP_SELECTED_COLOR : CENTER_BACKDROP_COLOR,
			opacity);
		drawCircleOutline(
			guiGraphics,
			geometry,
			centerX,
			centerY,
			radius,
			selected ? SELECTED_CENTER_BORDER_THICKNESS : CENTER_BORDER_THICKNESS,
			CENTER_BORDER_COLOR,
			opacity);

		double arm = radius * 0.4;
		fillLine(guiGraphics, centerX - arm, centerY - arm, centerX + arm, centerY + arm, CENTER_MARK_THICKNESS, CENTER_MARK_COLOR, opacity);
		fillLine(guiGraphics, centerX - arm, centerY + arm, centerX + arm, centerY - arm, CENTER_MARK_THICKNESS, CENTER_MARK_COLOR, opacity);
	}

	private static void drawArc(
		GuiGraphics guiGraphics,
		WheelGeometry geometry,
		WheelSector sector,
		double radius,
		double centerX,
		double centerY,
		int thickness,
		int color,
		int opacity
	) {
		List<WheelPoint> points = geometry.arcPoints(sector, radius, ARC_SAMPLES_PER_FULL_RING);

		for (int i = 0; i + 1 < points.size(); i++) {
			WheelPoint from = points.get(i);
			WheelPoint to = points.get(i + 1);
			fillLine(
				guiGraphics,
				centerX + from.x(),
				centerY + from.y(),
				centerX + to.x(),
				centerY + to.y(),
				thickness,
				color,
				opacity);
		}
	}

	private static void drawRadialSeparator(
		GuiGraphics guiGraphics,
		WheelGeometry geometry,
		WheelSector sector,
		double centerX,
		double centerY,
		int color,
		int opacity
	) {
		WheelPoint inner = geometry.pointAt(sector.startAngleRadians(), geometry.innerRadius());
		WheelPoint outer = geometry.pointAt(sector.startAngleRadians(), geometry.outerRadius());

		fillLine(
			guiGraphics,
			centerX + inner.x(),
			centerY + inner.y(),
			centerX + outer.x(),
			centerY + outer.y(),
			SEPARATOR_THICKNESS,
			color,
			opacity);
	}

	private static void drawCircleOutline(
		GuiGraphics guiGraphics,
		WheelGeometry geometry,
		double centerX,
		double centerY,
		double radius,
		int thickness,
		int color,
		int opacity
	) {
		List<WheelPoint> points = geometry.circlePoints(radius, CENTER_CIRCLE_SAMPLES);

		for (int i = 0; i < points.size(); i++) {
			WheelPoint from = points.get(i);
			WheelPoint to = points.get((i + 1) % points.size());

			fillLine(
				guiGraphics,
				centerX + from.x(),
				centerY + from.y(),
				centerX + to.x(),
				centerY + to.y(),
				thickness,
				color,
				opacity);
		}
	}

	private static void drawIcon(GuiGraphics guiGraphics, double x, double y, int color, int opacity) {
		color = WheelOpacity.apply(color, opacity);
		float alpha = FastColor.ARGB32.alpha(color) / 255f;
		float red = FastColor.ARGB32.red(color) / 255f;
		float green = FastColor.ARGB32.green(color) / 255f;
		float blue = FastColor.ARGB32.blue(color) / 255f;

		// 1.21.1 GuiGraphics has no setColor API: tint via the RenderSystem
		// shader color (same pattern as the existing DrawContext) and always
		// reset to white.
		RenderSystem.setShaderColor(red, green, blue, alpha);
		RenderSystem.enableBlend();

		try {
			// Math.round keeps the icon symmetric around the sampled midpoint
			// instead of truncating towards zero for negative coordinates.
			int iconX = (int) Math.round(x);
			int iconY = (int) Math.round(y);

			guiGraphics.blit(
				PING_TEXTURE_ID,
				iconX - ICON_SIZE / 2,
				iconY - ICON_SIZE / 2,
				0,
				0,
				0,
				ICON_SIZE,
				ICON_SIZE,
				ICON_SIZE,
				ICON_SIZE);
		} finally {
			RenderSystem.disableBlend();
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}
	}

	/**
	 * Draws a line as deterministic 1px-stepped axis-aligned quads via
	 * {@link GuiGraphics#fill}. The step distance never exceeds one pixel
	 * along the dominant axis, so the result is a continuous line of the given
	 * thickness, and the fill count is bounded by the line length.
	 */
	private static void fillLine(
		GuiGraphics guiGraphics,
		double x1,
		double y1,
		double x2,
		double y2,
		int thickness,
		int color,
		int opacity
	) {
		int effectiveColor = WheelOpacity.apply(color, opacity);
		double dx = x2 - x1;
		double dy = y2 - y1;
		int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
		int half = thickness / 2;

		for (int i = 0; i <= steps; i++) {
			double t = steps == 0 ? 0.0 : (double) i / steps;
			int x = (int) Math.round(x1 + dx * t) - half;
			int y = (int) Math.round(y1 + dy * t) - half;
			guiGraphics.fill(x, y, x + thickness, y + thickness, effectiveColor);
		}
	}

	/**
	 * Fills a circle as horizontal scanline strips via {@link GuiGraphics#fill},
	 * sampled at each pixel row center. The fill count is bounded by the
	 * circle's diameter.
	 */
	private static void fillCircle(
		GuiGraphics guiGraphics,
		double centerX,
		double centerY,
		double radius,
		int color,
		int opacity
	) {
		int effectiveColor = WheelOpacity.apply(color, opacity);
		int minY = (int) Math.ceil(centerY - radius);
		int maxY = (int) Math.floor(centerY + radius);

		for (int y = minY; y <= maxY; y++) {
			double dy = y - centerY + 0.5;

			if (Math.abs(dy) > radius) {
				continue;
			}

			double half = Math.sqrt(radius * radius - dy * dy);
			fillRow(guiGraphics, centerX - half, centerX + half, y, effectiveColor);
		}
	}

	/**
	 * Fills an annulus as horizontal scanline strips via
	 * {@link GuiGraphics#fill}. The fill count is bounded by the outer
	 * diameter.
	 */
	private static void fillAnnulus(
		GuiGraphics guiGraphics,
		double centerX,
		double centerY,
		double innerRadius,
		double outerRadius,
		int color,
		int opacity
	) {
		int effectiveColor = WheelOpacity.apply(color, opacity);
		int minY = (int) Math.ceil(centerY - outerRadius);
		int maxY = (int) Math.floor(centerY + outerRadius);

		for (int y = minY; y <= maxY; y++) {
			double dy = y - centerY + 0.5;
			double absDy = Math.abs(dy);

			if (absDy > outerRadius) {
				continue;
			}

			double halfOuter = Math.sqrt(outerRadius * outerRadius - dy * dy);

			if (absDy < innerRadius) {
				double halfInner = Math.sqrt(innerRadius * innerRadius - dy * dy);
				fillRow(guiGraphics, centerX - halfOuter, centerX - halfInner, y, effectiveColor);
				fillRow(guiGraphics, centerX + halfInner, centerX + halfOuter, y, effectiveColor);
			} else {
				fillRow(guiGraphics, centerX - halfOuter, centerX + halfOuter, y, effectiveColor);
			}
		}
	}

	private static void fillRow(
		GuiGraphics guiGraphics,
		double xFrom,
		double xTo,
		int y,
		int color
	) {
		int x0 = (int) Math.floor(xFrom);
		int x1 = (int) Math.ceil(xTo);

		if (x1 > x0) {
			guiGraphics.fill(x0, y, x1, y + 1, color);
		}
	}

	private static boolean isSelected(WheelSector sector, WheelSelection selection) {
		return selection instanceof WheelSelection.Sector selected
			&& selected.pingType().equals(sector.pingType());
	}

	private static boolean isSelected(PingType pingType, WheelSelection selection) {
		return selection instanceof WheelSelection.Sector selected
			&& selected.pingType().equals(pingType);
	}
}
