package nx.pingwheel.common.client.hud;

import java.util.List;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;

import nx.pingwheel.common.Global;
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
 * M6 ping-type wheel HUD pass on the 26.3 {@link GuiGraphicsExtractor} pipeline.
 *
 * <p>This is the 26.3 replacement for the deferred 1.21.1
 * {@code nx.pingwheel.common.render.WheelOverlayRenderer} ({@code GuiGraphics}),
 * wired into the same single per-frame HUD hook as
 * {@link MarkerHudRenderer} and running only while the interaction is in
 * {@link PingInteractionPhase#WHEEL_OPEN}.
 *
 * <p>Geometry, layout, and the mouse-to-sector mapping are <em>not</em>
 * reimplemented here: the frozen ping types come from
 * {@link ClientPingRuntime#wheelPresentation()}, the annular partition and the
 * mouse hit test come from {@link WheelGeometry}, text/icon placement comes
 * from {@link WheelLabelLayout}, and the resulting {@link WheelSelection} is fed
 * back through {@link ClientPingRuntime#setWheelSelection} so the existing
 * state machine still owns commit, cancellation, and timeout. This class never
 * changes interaction state and never logs per frame.
 *
 * <p>26.3 differences from the 1.21.1 renderer:
 * <ul>
 *   <li>{@code GuiGraphics} became {@link GuiGraphicsExtractor}: {@code fill}
 *       survives, {@code drawString} became {@code text}, and the pose stack is
 *       a 2D {@link Matrix3x2fStack};</li>
 *   <li>{@code RenderSystem.setShaderColor} tinting is gone, so the sector
 *       icon is tinted through the {@code blit} color argument instead - the
 *       same ARGB multiply upstream applied with the shader color;</li>
 *   <li>the wheel opacity loop is inlined (the deferred {@code WheelOpacity}
 *       helper stays in {@code deferred/}).</li>
 * </ul>
 *
 * <p>Visual content matches upstream {@code WheelOverlayRenderer} exactly: a
 * neutral translucent annulus backdrop ({@code :245-252}), per-sector inner and
 * outer arcs in the ping type color with the selected sector's arcs thickened
 * ({@code :254-264}), radial separators, the localized sector labels laid out by
 * {@link WheelLabelLayout}, the tinted {@code 6x6} {@code textures/ping.png}
 * icon at each sector midpoint ({@code :283-288}, {@code :486-520}), the center
 * cancel disk with its {@code X}, and the frozen target name above the wheel.
 * <ul>
 *   <li>No per-sector fill is invented: upstream only tints the ring backdrop.
 *       The selected sector is indicated by its thicker colored arcs plus its
 *       brighter label text, exactly as upstream does.</li>
 *   <li>The shipped {@code textures/ping.png} is a 1x1 fully transparent
 *       placeholder (upstream ships the same file), so the tinted icon is
 *       invisible until a resource pack replaces that texture - reproducing
 *       upstream's replaceable-icon behavior rather than inventing a shape.</li>
 * </ul>
 *
 * <p>Every drawing step is failure-isolated: one broken frame disables the
 * wheel pass for the rest of the session instead of ever escaping into the HUD
 * pass, so the marker pass and the frame itself survive.
 */
public final class WheelHudRenderer {

	/** Fixed total angular samples for a full sector ring. */
	private static final int ARC_SAMPLES_PER_FULL_RING = 72;

	/** Fixed angular samples for the center circle outline. */
	private static final int CENTER_CIRCLE_SAMPLES = 48;

	/** Upstream {@code WheelOverlayRenderer.java:73}: the tinted sector icon is 6x6. */
	private static final int ICON_SIZE = 6;

	private static final int ARC_THICKNESS = 1;
	private static final int SELECTED_ARC_THICKNESS = 2;
	private static final int SEPARATOR_THICKNESS = 1;
	private static final int CENTER_BORDER_THICKNESS = 1;
	private static final int SELECTED_CENTER_BORDER_THICKNESS = 2;
	private static final int CENTER_MARK_THICKNESS = 1;

	private static final int RING_BACKDROP_COLOR = 0x50000000;
	private static final int CENTER_BACKDROP_COLOR = 0x88000000;
	private static final int CENTER_BACKDROP_SELECTED_COLOR = 0x99FF6B6B;
	private static final int CENTER_BORDER_COLOR = 0xFFFF6B6B;
	private static final int CENTER_MARK_COLOR = 0xFFFF6B6B;
	private static final int TARGET_LABEL_COLOR = 0xFFF5F5F5;

	/** Matches the font renderer's minimum visible alpha (see deferred WheelOpacity). */
	private static final int MIN_FONT_ALPHA = 4;

	private static final ClientTargetNameResolver TARGET_NAME_RESOLVER = new ClientTargetNameResolver();

	/** One-shot guard so a failing wheel pass cannot spam the log every frame. */
	private static boolean failureLogged;

	private WheelHudRenderer() {}

	/** Re-arms the one-shot failure report for a new world/connection. */
	public static void resetFailureFlag() {
		failureLogged = false;
	}


	/**
	 * Draws the wheel for this frame and feeds the current mouse selection back
	 * to the interaction runtime.
	 *
	 * <p>Nothing is drawn unless the machine is in
	 * {@link PingInteractionPhase#WHEEL_OPEN} <em>and</em> the runtime exposes a
	 * completed capture; a stale queued selection is cleared in both cases so a
	 * later release can never commit a sector chosen for a closed wheel.
	 *
	 * @param graphics the current HUD state extractor (never null in the HUD pass)
	 * @param runtime  the nullable current runtime; a missing runtime draws
	 *                 nothing instead of failing
	 */
	public static void render(GuiGraphicsExtractor graphics, ClientPingRuntime runtime) {
		if (graphics == null) {
			return;
		}

		try {
			renderFrame(graphics, runtime);
		} catch (RuntimeException failure) {
			// A HUD pass must never crash the frame; the marker pass already ran
			// and keeps working on the following frames.
			if (!failureLogged) {
				failureLogged = true;
				Global.warnException("wheel HUD pass failed", failure);
			}
		}
	}

	private static void renderFrame(GuiGraphicsExtractor graphics, ClientPingRuntime runtime) {
		if (runtime == null || runtime.phase() != PingInteractionPhase.WHEEL_OPEN) {
			resetSelection(runtime);
			return;
		}

		Optional<WheelPresentationSnapshot> presentation = runtime.wheelPresentation();

		if (presentation.isEmpty()) {
			resetSelection(runtime);
			return;
		}

		List<PingType> pingTypes = presentation.get().pingTypes();

		if (pingTypes.isEmpty()) {
			resetSelection(runtime);
			return;
		}

		Minecraft game = Minecraft.getInstance();

		if (game == null || game.font == null) {
			resetSelection(runtime);
			return;
		}

		ClientConfig config = ClientConfig.HANDLER.getConfig();
		ClientConfigBounds.WheelRadii radii = ClientConfigBounds.clampWheelRadii(
			config.getWheelInnerRadius(),
			config.getWheelOuterRadius());

		if (radii.outerRadius() <= radii.innerRadius()) {
			resetSelection(runtime);
			return;
		}

		WheelGeometry geometry = new WheelGeometry(radii.innerRadius(), radii.outerRadius());

		// The crosshair is the wheel center: this HUD element is drawn in GUI space.
		double centerX = graphics.guiWidth() / 2.0;
		double centerY = graphics.guiHeight() / 2.0;

		// Selection is presentation input for the *next* machine advance; the
		// release event then commits exactly this value (or cancels at center).
		// The highlight below reuses this same fresh value, so the wheel never
		// lags a frame behind the cursor (the machine commits the queued copy).
		WheelSelection selection = selectionFromMouse(game, graphics, geometry, pingTypes, centerX, centerY);

		runtime.setWheelSelection(selection);

		int opacity = ClientConfigBounds.clampWheelOpacity(config.getWheelOpacity());

		if (opacity <= 0) {
			// A zero-alpha wheel is skipped entirely, exactly like the deferred
			// WheelOpacity#shouldRender gate.
			return;
		}

		List<WheelSector> sectors = geometry.sectors(pingTypes);
		double sectorLabelScale = WheelLabelLayout.BASE_TEXT_SCALE
			* ClientConfigBounds.clampWheelFontSize(config.getWheelFontSize()) / 100.0;
		double targetLabelScale = ClientConfigBounds.clampWheelTargetFontSize(
			config.getWheelTargetFontSize()) / 100.0;

		drawTargetName(
			graphics,
			game.font,
			geometry,
			centerX,
			centerY,
			targetLabelScale,
			opacity,
			presentation.get());
		drawRing(
			graphics,
			game.font,
			geometry,
			sectors,
			centerX,
			centerY,
			sectorLabelScale,
			opacity,
			selection);
		drawCenter(graphics, geometry, centerX, centerY, opacity, selection);
	}

	/**
	 * Clears a pending selection once the wheel is no longer visibly open.
	 * Mirrors the deferred renderer's reset so a stale sector can never be
	 * committed after a timeout or an aborted interaction.
	 */
	private static void resetSelection(ClientPingRuntime runtime) {
		if (runtime != null && runtime.wheelSelection() != WheelSelection.NONE) {
			runtime.setWheelSelection(WheelSelection.NONE);
		}
	}

	/**
	 * Converts the raw mouse position from window space into GUI space and maps
	 * it onto the wheel. A degenerate window or GUI size (for example a
	 * minimized window) selects nothing.
	 */
	private static WheelSelection selectionFromMouse(
		Minecraft game,
		GuiGraphicsExtractor graphics,
		WheelGeometry geometry,
		List<PingType> pingTypes,
		double centerX,
		double centerY
	) {
		double guiWidth = graphics.guiWidth();
		double guiHeight = graphics.guiHeight();

		if (!(guiWidth > 0.0) || !(guiHeight > 0.0)) {
			return WheelSelection.NONE;
		}

		// 26.3: Window#getWidth() is the framebuffer width while xpos()/ypos() are
		// screen coordinates, so the scale factor must come from MouseHandler's
		// GUI-scaled helpers (same math vanilla uses for its own cursor).
		double dx = game.mouseHandler.getScaledXPos(game.getWindow()) - centerX;
		double dy = game.mouseHandler.getScaledYPos(game.getWindow()) - centerY;

		if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
			// WheelGeometry#select rejects non-finite offsets; a degenerate input
			// must select nothing instead of disabling the whole pass.
			return WheelSelection.NONE;
		}

		return geometry.select(dx, dy, pingTypes);
	}

	private static void drawRing(
		GuiGraphicsExtractor graphics,
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
			graphics,
			centerX,
			centerY,
			geometry.innerRadius(),
			geometry.outerRadius(),
			applyOpacity(RING_BACKDROP_COLOR, opacity));

		for (WheelSector sector : sectors) {
			boolean selected = isSelected(sector, selection);
			int borderColor = applyOpacity(0xFF000000 | (sector.outlineColor() & 0x00FFFFFF), opacity);
			int thickness = selected ? SELECTED_ARC_THICKNESS : ARC_THICKNESS;

			drawArc(graphics, geometry, sector, geometry.innerRadius(), centerX, centerY, thickness, borderColor);
			drawArc(graphics, geometry, sector, geometry.outerRadius(), centerX, centerY, thickness, borderColor);

			if (sectors.size() > 1) {
				drawRadialSeparator(graphics, geometry, sector, centerX, centerY, borderColor);
			}
		}

		List<Component> labels = sectors.stream()
			.<Component>map(sector -> Component.translatable(sector.pingType().displayKey()))
			.toList();
		List<Integer> textWidths = labels.stream().map(label -> font.width(label)).toList();
		List<WheelLabelLayout.Placement> placements = WheelLabelLayout.layout(
			geometry,
			sectors,
			textWidths,
			font.lineHeight,
			sectorMaxScale);

		for (int i = 0; i < placements.size(); i++) {
			WheelLabelLayout.Placement placement = placements.get(i);
			boolean selected = isSelected(placement.pingType(), selection);
			int textColor = 0xFF000000 | (selected
				? placement.pingType().textColor()
				: placement.pingType().outlineColor());

			drawIcon(
				graphics,
				centerX + placement.iconAnchor().x(),
				centerY + placement.iconAnchor().y(),
				applyOpacity(0xFF000000 | placement.pingType().outlineColor(), opacity));
			drawLabel(
				graphics,
				font,
				labels.get(i),
				placement,
				centerX,
				centerY,
				applyTextOpacity(textColor, opacity));
		}
	}

	/**
	 * Draws the frozen target's local display name centered above the wheel. The
	 * name is never obtained by a new raycast: it is resolved from the frozen
	 * capture only.
	 */
	private static void drawTargetName(
		GuiGraphicsExtractor graphics,
		Font font,
		WheelGeometry geometry,
		double centerX,
		double centerY,
		double requestedScale,
		int opacity,
		WheelPresentationSnapshot snapshot
	) {
		Component targetName = TARGET_NAME_RESOLVER.resolve(snapshot.context().resolvedTarget().target());

		if (targetName == null) {
			return;
		}

		WheelLabelLayout.TargetLabelPlacement placement = WheelLabelLayout.targetLabelFit(
			centerX,
			centerY,
			graphics.guiWidth(),
			graphics.guiHeight(),
			geometry.outerRadius(),
			font.width(targetName),
			font.lineHeight,
			requestedScale);

		if (!(placement.scale() > 0.0)) {
			return;
		}

		drawScaledText(
			graphics,
			font,
			targetName,
			placement.x(),
			placement.topY(),
			placement.scale(),
			applyTextOpacity(TARGET_LABEL_COLOR, opacity));
	}

	private static void drawLabel(
		GuiGraphicsExtractor graphics,
		Font font,
		Component label,
		WheelLabelLayout.Placement placement,
		double centerX,
		double centerY,
		int color
	) {
		WheelPoint origin = WheelLabelLayout.labelOrigin(
			placement.labelAnchor(),
			placement.textWidth(),
			font.lineHeight,
			placement.scale());

		drawScaledText(
			graphics,
			font,
			label,
			centerX + origin.x(),
			centerY + origin.y(),
			placement.scale(),
			color);
	}

	private static void drawScaledText(
		GuiGraphicsExtractor graphics,
		Font font,
		Component text,
		double x,
		double y,
		double scale,
		int color
	) {
		if (text == null || !Double.isFinite(x) || !Double.isFinite(y)
			|| !Double.isFinite(scale) || scale <= 0.0 || color == 0) {
			return;
		}

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();

		try {
			pose.translate((float) x, (float) y);
			pose.scale((float) scale, (float) scale);
			graphics.text(font, text, 0, 0, color, true);
		} finally {
			pose.popMatrix();
		}
	}

	/**
	 * The tinted sector icon at its midpoint, verbatim from upstream
	 * {@code WheelOverlayRenderer.java:486-520}: a {@link #ICON_SIZE}px blit of
	 * {@code textures/ping.png}, centered on the anchor, tinted with the ping
	 * type's outline color.
	 *
	 * <p>The shipped texture is a 1x1 fully transparent placeholder (upstream
	 * ships the same file), so nothing is visible until a resource pack replaces
	 * it - that replaceability <em>is</em> the upstream behavior. 26.3 has no
	 * shader color, so the tint rides on the {@code blit} color argument.
	 */
	private static void drawIcon(GuiGraphicsExtractor graphics, double x, double y, int color) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || color == 0) {
			return;
		}

		int iconX = (int) Math.round(x);
		int iconY = (int) Math.round(y);

		graphics.blit(
			RenderPipelines.GUI_TEXTURED,
			PING_TEXTURE_ID,
			iconX - ICON_SIZE / 2,
			iconY - ICON_SIZE / 2,
			0.0f,
			0.0f,
			ICON_SIZE,
			ICON_SIZE,
			ICON_SIZE,
			ICON_SIZE,
			ICON_SIZE,
			ICON_SIZE,
			color);
	}

	private static void drawCenter(
		GuiGraphicsExtractor graphics,
		WheelGeometry geometry,
		double centerX,
		double centerY,
		int opacity,
		WheelSelection selection
	) {
		double radius = geometry.innerRadius();
		boolean selected = selection == WheelSelection.CENTER;

		fillCircle(
			graphics,
			centerX,
			centerY,
			radius,
			applyOpacity(selected ? CENTER_BACKDROP_SELECTED_COLOR : CENTER_BACKDROP_COLOR, opacity));
		drawCircleOutline(
			graphics,
			geometry,
			centerX,
			centerY,
			radius,
			selected ? SELECTED_CENTER_BORDER_THICKNESS : CENTER_BORDER_THICKNESS,
			applyOpacity(CENTER_BORDER_COLOR, opacity));

		double arm = radius * 0.4;
		int markColor = applyOpacity(CENTER_MARK_COLOR, opacity);

		fillLine(graphics, centerX - arm, centerY - arm, centerX + arm, centerY + arm, CENTER_MARK_THICKNESS, markColor);
		fillLine(graphics, centerX - arm, centerY + arm, centerX + arm, centerY - arm, CENTER_MARK_THICKNESS, markColor);
	}

	private static void drawArc(
		GuiGraphicsExtractor graphics,
		WheelGeometry geometry,
		WheelSector sector,
		double radius,
		double centerX,
		double centerY,
		int thickness,
		int color
	) {
		List<WheelPoint> points = geometry.arcPoints(sector, radius, ARC_SAMPLES_PER_FULL_RING);

		for (int i = 0; i + 1 < points.size(); i++) {
			WheelPoint from = points.get(i);
			WheelPoint to = points.get(i + 1);

			fillLine(graphics, centerX + from.x(), centerY + from.y(), centerX + to.x(), centerY + to.y(), thickness, color);
		}
	}

	private static void drawRadialSeparator(
		GuiGraphicsExtractor graphics,
		WheelGeometry geometry,
		WheelSector sector,
		double centerX,
		double centerY,
		int color
	) {
		WheelPoint inner = geometry.pointAt(sector.startAngleRadians(), geometry.innerRadius());
		WheelPoint outer = geometry.pointAt(sector.startAngleRadians(), geometry.outerRadius());

		fillLine(
			graphics,
			centerX + inner.x(),
			centerY + inner.y(),
			centerX + outer.x(),
			centerY + outer.y(),
			SEPARATOR_THICKNESS,
			color);
	}

	private static void drawCircleOutline(
		GuiGraphicsExtractor graphics,
		WheelGeometry geometry,
		double centerX,
		double centerY,
		double radius,
		int thickness,
		int color
	) {
		List<WheelPoint> points = geometry.circlePoints(radius, CENTER_CIRCLE_SAMPLES);

		for (int i = 0; i < points.size(); i++) {
			WheelPoint from = points.get(i);
			WheelPoint to = points.get((i + 1) % points.size());

			fillLine(graphics, centerX + from.x(), centerY + from.y(), centerX + to.x(), centerY + to.y(), thickness, color);
		}
	}

	/**
	 * Draws a line as 1px-stepped axis-aligned quads via {@code fill}. The step
	 * distance never exceeds one pixel along the dominant axis, so the fill
	 * count is bounded by the line length (the 1.21.1 renderer used the same
	 * sampled approach because neither pipeline has a line primitive).
	 */
	private static void fillLine(
		GuiGraphicsExtractor graphics,
		double x1,
		double y1,
		double x2,
		double y2,
		int thickness,
		int color
	) {
		if (color == 0 || !Double.isFinite(x1) || !Double.isFinite(y1)
			|| !Double.isFinite(x2) || !Double.isFinite(y2)) {
			return;
		}

		double dx = x2 - x1;
		double dy = y2 - y1;
		int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
		int half = thickness / 2;

		for (int i = 0; i <= steps; i++) {
			double t = steps == 0 ? 0.0 : (double) i / steps;
			int x = (int) Math.round(x1 + dx * t) - half;
			int y = (int) Math.round(y1 + dy * t) - half;

			graphics.fill(x, y, x + thickness, y + thickness, color);
		}
	}

	/**
	 * Fills a circle as horizontal scanline strips via {@code fill}; the fill
	 * count is bounded by the circle diameter.
	 */
	private static void fillCircle(
		GuiGraphicsExtractor graphics,
		double centerX,
		double centerY,
		double radius,
		int color
	) {
		if (color == 0 || !(radius > 0.0) || !Double.isFinite(radius)) {
			return;
		}

		int minY = (int) Math.ceil(centerY - radius);
		int maxY = (int) Math.floor(centerY + radius);

		for (int y = minY; y <= maxY; y++) {
			double dy = y + 0.5 - centerY;

			if (Math.abs(dy) > radius) {
				continue;
			}

			double half = Math.sqrt(radius * radius - dy * dy);
			fillRow(graphics, centerX - half, centerX + half, y, color);
		}
	}

	/**
	 * Fills an annulus as horizontal scanline strips via {@code fill}; the fill
	 * count is bounded by the outer diameter.
	 */
	private static void fillAnnulus(
		GuiGraphicsExtractor graphics,
		double centerX,
		double centerY,
		double innerRadius,
		double outerRadius,
		int color
	) {
		if (color == 0 || !(outerRadius > innerRadius) || !Double.isFinite(outerRadius)) {
			return;
		}

		int minY = (int) Math.ceil(centerY - outerRadius);
		int maxY = (int) Math.floor(centerY + outerRadius);

		for (int y = minY; y <= maxY; y++) {
			double dy = y + 0.5 - centerY;
			double absDy = Math.abs(dy);

			if (absDy > outerRadius) {
				continue;
			}

			double halfOuter = Math.sqrt(outerRadius * outerRadius - dy * dy);

			if (absDy < innerRadius) {
				double halfInner = Math.sqrt(innerRadius * innerRadius - dy * dy);
				fillRow(graphics, centerX - halfOuter, centerX - halfInner, y, color);
				fillRow(graphics, centerX + halfInner, centerX + halfOuter, y, color);
			} else {
				fillRow(graphics, centerX - halfOuter, centerX + halfOuter, y, color);
			}
		}
	}

	private static void fillRow(GuiGraphicsExtractor graphics, double fromX, double toX, int y, int color) {
		int x0 = (int) Math.floor(fromX);
		int x1 = (int) Math.ceil(toX);

		if (x1 > x0) {
			graphics.fill(x0, y, x1, y + 1, color);
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

	/**
	 * Multiplies an ARGB color's alpha by {@code opacityPercent} (0-100).
	 * Inlined equivalent of the deferred {@code WheelOpacity#apply}.
	 */
	private static int applyOpacity(int argb, int opacityPercent) {
		int percent = Math.clamp(opacityPercent, 0, 100);

		if (percent >= 100) {
			return argb;
		}

		int alpha = Math.clamp(Math.round(((argb >>> 24) & 0xFF) * percent / 100.0f), 0, 0xFF);

		return (argb & 0x00FFFFFF) | (alpha << 24);
	}

	/**
	 * Applies wheel opacity to a font color while preserving the font
	 * renderer's distinction between transparent text and the minimum visible
	 * alpha. Inlined equivalent of the deferred {@code WheelOpacity#applyText}.
	 */
	private static int applyTextOpacity(int argb, int opacityPercent) {
		int color = applyOpacity(argb, opacityPercent);
		int alpha = (color >>> 24) & 0xFF;

		if (alpha > 0 && alpha < MIN_FONT_ALPHA) {
			return (color & 0x00FFFFFF) | (MIN_FONT_ALPHA << 24);
		}

		return color;
	}
}
