package nx.pingwheel.common.client.hud;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import org.joml.Matrix3x2fStack;

import nx.pingwheel.common.client.marker.MarkerView;
import nx.pingwheel.common.client.resource.PingTextureState;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.PlayerInfoMode;
import nx.pingwheel.common.config.TeamColorMode;
import nx.pingwheel.common.math.MathUtils;
import nx.pingwheel.common.math.ScreenPos;

import static nx.pingwheel.common.resource.ResourceConstants.ARROW_TEXTURE_ID;
import static nx.pingwheel.common.resource.ResourceConstants.PING_TEXTURE_ID;

/**
 * M6.5 HUD pass for the prepared marker views: the 26.3 port of the upstream
 * {@code OverlayRenderer} + {@code PingLocationRenderer} +
 * {@code DirectionIndicatorRenderer} composition.
 *
 * <p>Upstream references (1.21.1, {@code nx.pingwheel.common.render}) are
 * {@code deferred/}: {@code OverlayRenderer.java:12-57} (per-view dispatch and
 * layering), {@code PingLocationRenderer.java:19-60} (icon + labels),
 * {@code DrawContext.java:62-117} (icon geometry, arrow) and
 * {@code DirectionIndicatorRenderer.java:18-83} (off-screen arrows).
 *
 * <p>Per marker, mirroring {@code PingLocationRenderer.draw}:
 * <ol>
 *   <li>{@code pose.pushMatrix -> translate(screenPos) -> scale(scale)};</li>
 *   <li>distance label at {@code -1.5} line heights ({@code :40-41});</li>
 *   <li>the ping icon at the origin ({@code :42}):
 *     <ul>
 *       <li>item target + {@code itemIconVisible}: a real 16x16 GUI item icon
 *           ({@code DrawContext.java:72-76});</li>
 *       <li>otherwise the upstream default icon: a 5x5 square rotated 45
 *           degrees around the marker origin ({@code DrawContext.java:78-84});</li>
 *       <li>the upstream custom-texture branch ({@code DrawContext.java:84-113})
 *           is gated by {@code PingTextureState#hasCustomTexture()}, which counts
 *           how many packs provide {@code textures/ping.png} (M8b, v1 reload
 *           listener). Without a custom pack the diamond/icon path wins, exactly
 *           as upstream does.</li>
 *     </ul>
 *   </li>
 *   <li>target-name label at {@code -2.75} line heights ({@code :47});</li>
 *   <li>in {@code PlayerInfoMode.COMPACT} the distance label carries the 8x8
 *       owner head, and in {@code ALWAYS} - or {@code HOLD} while the player
 *       list key is down - the owner display name is drawn at {@code +1.75}
 *       line heights with its own head ({@code :49-57},
 *       {@code DrawContext.java:35-62});</li>
 *   <li>markers behind the camera or outside the viewport additionally get the
 *       edge arrow ({@code OverlayRenderer.java:41-53},
 *       {@code DirectionIndicatorRenderer.java:28-83}).</li>
 * </ol>
 *
 * <p><strong>Layering.</strong> Upstream wraps the whole pass in
 * {@code translate(0, 0, -views*16)} and adds {@code +16} per view
 * ({@code OverlayRenderer.java:29-45}) so earlier (farther) views sit deeper.
 * 26.3's {@code GuiGraphicsExtractor} has no z parameter for {@code fill},
 * {@code item}, {@code text} or {@code blit}, so this class instead relies on
 * paint order alone: {@link nx.pingwheel.common.client.marker.MarkerOverlayState}
 * exports the views sorted by descending distance, so a nearer marker is drawn
 * later and therefore covers a farther one - the same visible result as the
 * upstream z ladder without inventing a depth buffer.
 *
 * <p>Input contract: the caller has already run
 * {@link nx.pingwheel.common.client.marker.MarkerOverlayState#prepare} for this
 * frame, so every view leads with a current {@link ScreenPos} and is in the
 * local dimension. This class never mutates marker state and never logs per
 * frame.
 *
 * <p>Known gap, outside the HUD layer: world-space outlining
 * ({@code deferred/client/outline/}) is still absent, so the HUD marker is the
 * only spatial anchor until M7 restores it.
 */
public final class MarkerHudRenderer {

	/**
	 * Default ping icon (upstream {@code DrawContext.java:78-84}): a
	 * {@link #ICON_SIZE}x{@link #ICON_SIZE} square rotated 45 degrees about the
	 * marker origin, then translated by half its size so it is centered.
	 */
	private static final float ICON_ROTATION_RADIANS = (float) (Math.PI / 4.0);
	private static final int ICON_SIZE = 5;
	private static final float ICON_HALF_SIZE = ICON_SIZE / 2.0f;

	/**
	 * Item icon top-left offset (upstream {@code DrawContext.java:74-76}:
	 * {@code renderItem(itemStack, -8, -8, 0, -150)}; 26.3's
	 * {@code item(stack, x, y, seed)} takes the same top-left corner and has no
	 * z argument).
	 */
	private static final int ITEM_ICON_TOP_LEFT = -8;

	/** {@code arrow.png} is a 64x64 texture drawn into a 10x10 quad. */
	private static final int ARROW_TEXTURE_SIZE = 64;
	private static final int ARROW_SIZE = 10;

	/**
	 * Edge-arrow transform (upstream {@code DirectionIndicatorRenderer.java:71-79}
	 * and {@code DrawContext.java:115-117}): rotate by the marker angle, apply
	 * the {@code pingSize} multiplier, then the fixed 0.25 sprite scale, then
	 * shift the 10px arrow half a width so it sits before the edge point.
	 */
	private static final float ARROW_SCALE = 0.25f;
	private static final float ARROW_HALF_SIZE = ARROW_SIZE / 2.0f;

	/** Inward offset of the ping icon from the safe-zone edge (upstream :54-55). */
	private static final double INDICATOR_ICON_OFFSET = 12.0;

	/**
	 * Guard for a degenerate {@link MarkerView#getScale()} (0 or non-finite):
	 * a zero/NaN matrix would silently swallow every draw of the frame. The
	 * distance-compensated value itself is never recomputed here.
	 */
	private static final float MIN_RENDER_SCALE = 0.01f;

	private static final Vec2 SCREEN_TOP_LEFT = Vec2.ZERO;

	private MarkerHudRenderer() {}

	/**
	 * Draws every marker view of this frame: the icon and its labels for
	 * markers in front of the camera, and the safe-zone edge arrow for markers
	 * behind the camera or outside the viewport.
	 */
	public static void render(GuiGraphicsExtractor graphics, List<MarkerView> views) {
		if (graphics == null || views.isEmpty()) {
			return;
		}

		Minecraft game = Minecraft.getInstance();

		if (game == null || game.font == null) {
			return;
		}

		ClientConfig config = ClientConfig.HANDLER.getConfig();
		boolean showDirectionIndicator = config.isDirectionIndicatorVisible();
		Vec2 screenSize = new Vec2(graphics.guiWidth(), graphics.guiHeight());
		SafeZone safeZone = showDirectionIndicator ? SafeZone.of(config, screenSize) : null;

		for (MarkerView view : views) {
			ScreenPos screenPos = view.getScreenPos();

			if (screenPos == null
				|| !Float.isFinite(screenPos.x)
				|| !Float.isFinite(screenPos.y)) {
				// A degenerate projection (NaN/Infinity from a near-zero clip w)
				// would poison this marker's translate; skip it rather than push a
				// non-finite matrix into the GUI state.
				continue;
			}

			boolean behindCamera = screenPos.isBehindCamera();
			boolean offScreen = behindCamera
				|| !screenPos.isInBounds(SCREEN_TOP_LEFT, screenSize);

			// Upstream OverlayRenderer.java:47-53: the direction indicator is
			// evaluated for every view and decides on its own whether it applies.
			if (safeZone != null && offScreen) {
				drawDirectionIndicator(graphics, game.font, view, config, screenPos, safeZone);
			}

			// Upstream :51-53 draws the projected marker only while it is in
			// front of the camera; a front-facing but off-screen marker is still
			// drawn and simply clipped by the GUI scissor, exactly like upstream.
			if (!behindCamera) {
				drawMarker(graphics, game.font, view, config, screenPos, game);
			}
		}
	}

	/**
	 * Draws one projected marker: the shared {@code translate + scale} frame of
	 * upstream {@code PingLocationRenderer.java:30-32}, then the two labels and
	 * the icon ({@code :40-47}).
	 */
	private static void drawMarker(
		GuiGraphicsExtractor graphics,
		Font font,
		MarkerView view,
		ClientConfig config,
		ScreenPos screenPos,
		Minecraft game
	) {
		// PingLocationRenderer.java:37-38: the owner head only rides the distance
		// label in COMPACT mode.
		PlayerInfo author = view.getPlayerInfo();
		PlayerInfo compactPlayerInfo = config.getPlayerInfoMode() == PlayerInfoMode.COMPACT ? author : null;
		// PingLocationRenderer.java:34: label team colors are FULL / LABELS_ONLY.
		boolean labelUseTeamColor = config.getTeamColorMode() == TeamColorMode.FULL
			|| config.getTeamColorMode() == TeamColorMode.LABELS_ONLY;

		float scale = renderScale(view);
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();

		try {
			pose.translate(screenPos.x, screenPos.y);
			pose.scale(scale, scale);

			// PingLocationRenderer.java:40-47.
			MarkerLabelRenderer.drawDistance(graphics, font, view, compactPlayerInfo);
			drawPingIcon(graphics, font, view, config);
			MarkerLabelRenderer.drawTargetName(graphics, font, view);

			// PingLocationRenderer.java:49-57: the verbose owner label is shown in
			// ALWAYS mode, or in HOLD mode while the player list key is held.
			boolean playerListHeld = config.getPlayerInfoMode() == PlayerInfoMode.HOLD
				&& game.options.keyPlayerList.isDown();
			boolean showVerbosePlayerInfo = config.getPlayerInfoMode() == PlayerInfoMode.ALWAYS
				|| playerListHeld;

			if (showVerbosePlayerInfo && author != null) {
				MarkerLabelRenderer.drawPlayerName(
					graphics,
					font,
					MarkerLabelRenderer.playerDisplayName(author, labelUseTeamColor),
					author);
			}
		} finally {
			pose.popMatrix();
		}
	}

	/**
	 * The upstream ping icon, in the marker's already-scaled frame.
	 *
	 * <p>{@code DrawContext.java:64-72} chooses item icon -> custom texture ->
	 * default icon. Only the first and last branches exist on 26.3: the custom
	 * texture is a transparent placeholder asset.
	 *
	 * <p>26.3 split the 1.21.1 {@code renderItem} into {@code item(...)} plus
	 * {@code itemDecorations(...)}; upstream's item ping showed the stack count
	 * and durability bar, so both calls are required for parity.
	 */
	private static void drawPingIcon(
		GuiGraphicsExtractor graphics,
		Font font,
		MarkerView view,
		ClientConfig config
	) {
		ItemStack itemStack = view.getItemStack();

		if (itemStack != null && config.isItemIconVisible()) {
			// DrawContext.java:74-76: a 16x16 GUI item centered on the origin.
			graphics.item(itemStack, ITEM_ICON_TOP_LEFT, ITEM_ICON_TOP_LEFT, 0);
			// Upstream renderItem also drew the count/durability decorations.
			graphics.itemDecorations(font, itemStack, ITEM_ICON_TOP_LEFT, ITEM_ICON_TOP_LEFT);
			return;
		}

		if (PingTextureState.hasCustomTexture()) {
			// Upstream DrawContext.renderTexture(PING_TEXTURE_ID, 12, color): a 12x12
			// quad centered on the origin, tinted with the same colour as the diamond.
			graphics.blit(
				RenderPipelines.GUI_TEXTURED,
				PING_TEXTURE_ID,
				-6,
				-6,
				0.0f,
				0.0f,
				12,
				12,
				12,
				12,
				12,
				12,
				view.getPingColor());
			return;
		}

		// DrawContext.java:78-84: rotate 45 degrees, then draw the 5x5 square at
		// (-2.5, -2.5) so the rotated diamond is centered on the origin.
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();

		try {
			pose.rotate(ICON_ROTATION_RADIANS);
			pose.translate(-ICON_HALF_SIZE, -ICON_HALF_SIZE);
			graphics.fill(0, 0, ICON_SIZE, ICON_SIZE, view.getPingColor());
		} finally {
			pose.popMatrix();
		}
	}

	/**
	 * The off-screen / behind-camera edge arrow of
	 * {@code DirectionIndicatorRenderer.java:28-83}.
	 *
	 * <p>Geometry order, verbatim from upstream: screen-relative direction from
	 * the safe-zone center (negated behind the camera), the angle, the safe-zone
	 * rectangle intersection, the 12px inward icon offset, and the rotated
	 * {@code pingSize * 0.25} arrow.
	 */
	private static void drawDirectionIndicator(
		GuiGraphicsExtractor graphics,
		Font font,
		MarkerView view,
		ClientConfig config,
		ScreenPos screenPos,
		SafeZone safeZone
	) {
		if (safeZone.isEmpty()) {
			// Degenerate safe zone (a configuration wider than the screen) would
			// divide by zero in the rectangle intersection; draw nothing instead
			// of pushing a NaN matrix into the GUI state.
			return;
		}

		double directionX = screenPos.x - safeZone.left() - safeZone.centerX();
		double directionY = screenPos.y - safeZone.top() - safeZone.centerY();

		if (screenPos.isBehindCamera()) {
			// :45-47: a point behind the camera projects mirrored, so flip it.
			directionX = -directionX;
			directionY = -directionY;
		}

		float angle = (float) Math.atan2(directionY, directionX);
		Vec2 edge = MathUtils.calculateAngleRectIntersection(
			angle,
			safeZone.topLeft(),
			safeZone.bottomRight());

		if (!Float.isFinite(edge.x) || !Float.isFinite(edge.y)) {
			return;
		}

		float iconOffsetX = (float) (Math.cos(angle + Math.PI) * INDICATOR_ICON_OFFSET);
		float iconOffsetY = (float) (Math.sin(angle + Math.PI) * INDICATOR_ICON_OFFSET);
		float markerScale = renderScale(view);
		float pingSize = config.getPingSize() / 100f;
		Matrix3x2fStack pose = graphics.pose();

		pose.pushMatrix();

		try {
			pose.translate(edge.x, edge.y);

			// :61-69: the ping icon, offset 12px inward in the marker's scaled frame.
			pose.pushMatrix();

			try {
				pose.scale(markerScale, markerScale);
				pose.translate(iconOffsetX, iconOffsetY);
				drawPingIcon(graphics, font, view, config);
			} finally {
				pose.popMatrix();
			}

			// :71-80: the rotated arrow at the edge point.
			pose.pushMatrix();

			try {
				pose.rotate(angle);
				pose.scale(pingSize, pingSize);
				pose.scale(ARROW_SCALE, ARROW_SCALE);
				pose.translate(-ARROW_HALF_SIZE, 0f);
				drawArrow(graphics, view.getPingColor());
			} finally {
				pose.popMatrix();
			}
		} finally {
			pose.popMatrix();
		}
	}

	/**
	 * Draws {@code textures/arrow.png} (64x64, a real asset) into a 10x10 quad,
	 * tinted with the marker color.
	 *
	 * <p>Upstream ({@code DrawContext.java:115-117} ->
	 * {@code renderTexture(ARROW_TEXTURE_ID, 10, color)}, {@code :86-113}) tinted
	 * through {@code RenderSystem.setShaderColor}; 26.3 has no shader color, so
	 * the color is passed to {@code blit} instead (same ARGB multiply).
	 */
	private static void drawArrow(GuiGraphicsExtractor graphics, int color) {
		graphics.blit(
			RenderPipelines.GUI_TEXTURED,
			ARROW_TEXTURE_ID,
			(int) -ARROW_HALF_SIZE,
			(int) -ARROW_HALF_SIZE,
			0.0f,
			0.0f,
			ARROW_SIZE,
			ARROW_SIZE,
			ARROW_TEXTURE_SIZE,
			ARROW_TEXTURE_SIZE,
			ARROW_TEXTURE_SIZE,
			ARROW_TEXTURE_SIZE,
			color);
	}

	/**
	 * The view's distance-compensated scale, guarded only against a degenerate
	 * value so the frame can never inherit a collapsed matrix. The formula that
	 * produces it stays untouched in {@link MarkerView}.
	 */
	private static float renderScale(MarkerView view) {
		float scale = view.getScale();

		return Float.isFinite(scale) && scale > MIN_RENDER_SCALE ? scale : MIN_RENDER_SCALE;
	}

	/**
	 * The configuration-derived safe zone of
	 * {@code DirectionIndicatorRenderer.java:18-26}: the rectangle the edge
	 * arrows are clamped to, expressed in GUI coordinates.
	 */
	private record SafeZone(float left, float top, float centerX, float centerY, Vec2 topLeft, Vec2 bottomRight) {

		static SafeZone of(ClientConfig config, Vec2 screenSize) {
			float left = config.getSafeZoneLeft();
			float top = config.getSafeZoneTop();
			float right = screenSize.x - config.getSafeZoneRight();
			float bottom = screenSize.y - config.getSafeZoneBottom();

			return new SafeZone(
				left,
				top,
				(right - left) * 0.5f,
				(bottom - top) * 0.5f,
				new Vec2(left, top),
				new Vec2(right, bottom));
		}

		boolean isEmpty() {
			return this.bottomRight.x - this.topLeft.x <= 1.0f
				|| this.bottomRight.y - this.topLeft.y <= 1.0f;
		}
	}
}
