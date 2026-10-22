package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.HolderLookup;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import nx.pingwheel.common.client.spatial.InventoryListModel;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.wheel.WheelLabelLayout;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay;

/**
 * Native Minecraft overlay for the spatial selector read models.
 *
 * <p>Every option is square and straight: options are framed by thin
 * rectangular borders, the pointer is a straight-line crosshair, the trail is a
 * series of small squares, and the Back hover progress is a square perimeter
 * walked in four straight segments. Two guides sample straight strokes: a
 * semi-transparent circular-sector backdrop painted beneath the radial nodes,
 * using each entry's own bearing span, and the centre guides at each menu's
 * origin — one divider per true shared sector boundary, from the frozen
 * deadzone circle to that menu's backdrop outer radius, plus the closed circle
 * at the deadzone radius itself. A bearing still only positions a node along a
 * straight guide line.
 *
 * <p>Wheel opacity governs only the sector underlay; target opacity governs
 * every text-bearing frame together with its label and external name preview.
	 * Neither preference reaches the pointer, guides or trail. The trail alone has
	 * an independent opacity; centre guides follow their menu's transition fade and dim for an
 * ancestor menu exactly like that menu's nodes.
 *
 * <p>Every fill additionally carries an independent normalized background
 * coverage through the transition model: the sector underlay contributes 30%
 * and every text-bearing box or panel contributes 60% of its base paint,
 * multiplied by the background emphasis, the structural ancestor/disabled
 * dimming, the state's own coverage transition and only that layer's opacity
 * preference, and rounded once at the end. The foreground alpha, its
 * 0.8/0.35 dimming, the disabled/reserved factor, borders, icons, text,
 * acknowledgement and scale rules are unaffected by that channel.
 *
 * <p>The controller's geometry is virtual: menu origins and the pointer are
 * relative to the gesture origin. Every point is offset by the GUI center, and
 * while a menu is active the whole overlay additionally receives one rigid
 * translation that projects that menu's origin onto the GUI center, so opening
 * a submenu recenters the pattern, returning restores the parent level, and the
 * root restores the plain GUI center. Because it is a single translation per
 * frame, parent/child menu vectors, the pointer, the trail and inventory row
 * anchors keep their controller-space relationships. GUI scaling of physical
 * input is owned by the future actor; this class only paints in GUI space.
 *
 * <p>While the active menu's focused entry is an eligible action or branch, that
 * node scales to the selection scale and its current-menu siblings shrink to the
 * resting sibling scale; Back, disabled, reserved and deadzone focus keep the
 * established selected/unselected resting scales. Each ancestor of the active
 * path keeps only the breadcrumb entry that opened the next child plus its Back
 * navigation, so every other sibling frame and sector leaves planning and fades
 * through the ordinary exit; the controller's logical choices and selection
 * geometry are never changed by this presentation filter.
 *
 * <p>{@link #drawFrame} is the complete-frame entry point: it transitions radial
 * nodes, list content and outgoing content together with caller-owned time and
 * style. An empty/inactive frame fades out; keep drawing while {@link #isAnimating}
 * is true. {@link #reset()} is hard disposal, not a normal close. A native session
 * may own its own {@link Session} rather than use the shared convenience methods.
 *
 * <p>Labels are never classified by their content. Radial choices use the
 * default localization-key contract ({@link #TRANSLATION_KEY_LABELS}), a caller
 * can supply its own {@code ChoiceView} to {@link Component} resolver, and
 * inventory rows are always rendered as literal display names.
 *
 * <p>The inventory list projection gets its own square panel entry point; the
 * returned {@link RowLayout} is the actual rendered centre and width of the
 * selected row, which the item submenu can anchor to without recomputing row
 * geometry.
 */
public final class SpatialOverlayRenderer {

	private SpatialOverlayRenderer() {}

	static final int TEXT_COLOR = 0xFFFFFFFF;
	static final int TEXT_DIMMED_COLOR = 0xFFB0B0B0;
	static final int TEXT_DISABLED_COLOR = 0xFF7A7A7A;
	static final int BORDER_COLOR = 0xFF8A8A8A;
	static final int BORDER_SELECTED_FALLBACK = 0xFFFFFFFF;
	static final int NODE_BACKGROUND = 0x99000000;
	static final int NODE_SELECTED_BACKGROUND = 0xCC1A1A1A;
	static final int PANEL_BACKGROUND = 0xB0101010;
	static final int PANEL_HEADER_BACKGROUND = 0xD0181818;
	static final int ROW_SELECTED_BACKGROUND = 0xD0303030;
	static final int GUIDE_COLOR = 0x558A8A8A;
	static final int POINTER_COLOR = 0xFFFFFFFF;
	static final int PROGRESS_COLOR = 0xFFBAFFD2;
	static final int TRAIL_COLOR = 0x99FFFFFF;
	static final int SECTOR_BACKGROUND = 0x26FFFFFF;

	/** Fixed angular samples of the closed deadzone circle guide (chord below ~2.1 px at the largest radius). */
	static final int GUIDE_CIRCLE_SAMPLES = 96;
	/** Degrees of float slack accepted as one shared boundary between two neighbours. */
	static final double SHARED_BOUNDARY_TOLERANCE_DEGREES = 1.0e-6;

	static final int BORDER_THICKNESS = 1;
	static final int ITEM_ICON_SIZE = SpatialInventoryLayout.ITEM_ICON_SIZE;
	static final double BOX_HEIGHT = 14.0;
	static final double MIN_BOX_WIDTH = 14.0;
	static final double ORBIT_MIN = 32.0;
	static final double ORBIT_MAX = 66.0;
	static final double ORBIT_RATIO = 0.11;
	static final double ORBIT_SELECTED_PUSH = 5.0;
	static final double SECTOR_PADDING = ORBIT_SELECTED_PUSH + BOX_HEIGHT / 2.0;
	/** Transition scales of the active menu's focused and unfocused nodes. */
	static final double NODE_SELECTED_SCALE = 1.12;
	static final double NODE_SCALE = 0.95;
	/**
	 * Selection scaling while the active menu's focused entry is an eligible
	 * action or branch: that node and every current-menu sibling. Back, disabled
	 * and reserved focus keeps the established {@link #NODE_SELECTED_SCALE} and
	 * {@link #NODE_SCALE} resting appearance.
	 */
	static final double NODE_FOCUSED_SCALE = 1.2;
	static final double NODE_FOCUSED_SIBLING_SCALE = 0.85;
	/** Ancestor menus keep the established dimmed resting scale. */
	static final double NODE_ANCESTOR_SCALE = 0.85;
	/** Horizontal and vertical inset of node text inside its frame. */
	static final double NODE_TEXT_INSET = 4.0;
	/** Total vertical padding of the title frame: three pixels above and below. */
	static final double NODE_VERTICAL_PADDING = 6.0;
	/** Visible space between the title frame and its unframed candidate-name preview. */
	static final double NODE_DETAIL_GAP = 3.0;
	/** The Precise branch's fixed slots: the five target types plus Back. */
	static final int PRECISE_SLOT_COUNT = 6;
	/**
	 * Safe fraction of the adjacent-node chord used as every Precise node's
	 * external preview cell. Title frames keep the ordinary natural single-line
	 * bounds and are not constrained by this value; the cell also reserves local
	 * pixels for its own rounding.
	 */
	static final double PRECISE_NODE_EXTENT_FRACTION = 0.68;
	static final double ANIMATION_RATE_PER_MILLI = 0.02;
	static final double MAX_FRAME_MILLIS = 100.0;
	static final double ANCESTOR_ALPHA = 0.35;
	static final double DISABLED_ALPHA_FACTOR = 0.5;
	/**
	 * Normalized background coverage of one entry: 30% for a sector underlay and
	 * 60% for a text-bearing title box, panel, header or selected-row overlay.
	 * The percentage is applied before the focus emphasis so the endpoint byte
	 * keeps its fractional half.
	 */
	static final int SECTOR_BACKGROUND_PERCENT = 30;
	static final int BOX_BACKGROUND_PERCENT = 60;
	/** Background emphasis behind an eligible focused action or branch. */
	static final double SECTOR_FOCUSED_GAIN = 1.4;
	static final double BOX_FOCUSED_GAIN = 1.5;
	static final double BACKGROUND_SIBLING_GAIN = 0.4;
	/** Inventory panel/header/selected-row coverage gains of an active or inactive list. */
	static final double INVENTORY_ACTIVE_GAIN = 1.4;
	static final double INVENTORY_IDLE_GAIN = 0.7;
	/** Clearance metric: the outer-coverage factor and a conservative rounding margin. */
	static final double CLEARANCE_OUTER_FACTOR = 1.1;
	static final double CLEARANCE_ROUNDING_MARGIN = 2.0;

	private static final long TRANSITION_NANOS = 160_000_000L;
	private static final int TRANSITION_CAPACITY = 512;
	private static final double APPEARANCE_SCALE = 0.92;
	private static final double TWO_PI = Math.PI * 2.0;
	private static final Session SHARED = new Session();

	/**
	 * Caller-resolved visual preferences; no configuration access occurs here.
	 * Scales are actual text scales, not persisted percentage values. The facade
	 * can pass normalized old font percentages to fromLegacyFontSizes, preserving
	 * the old radial option base scale and independent target/list text scale.
	 * The two opacity percentages are independent: wheel opacity reaches only the
	 * sector underlay, while target opacity reaches every text-bearing frame
	 * (radial nodes, inventory panel, rows, header and footer) and its label.
	 * {@code submenuRadiusScale} multiplies the viewport-derived non-root orbit
	 * only; the root menu keeps its caller distance and the inventory list keeps
	 * its own content-driven size, capped by the held inventory proportions of
	 * the current GUI viewport without shrinking its readable text/icon minimum.
	 */
	public record Style(int opacityPercent, int targetOpacityPercent, double optionTextScale, double inventoryTextScale,
		double rootDistance, double submenuRadiusScale, boolean showTrail, boolean reduceMotion,
		int trailLength, int trailOpacityPercent, double inventoryMaxWidthFraction, double inventoryMaxHeightFraction) {

		/** Compatibility form; native holds supply their frozen inventory proportions explicitly. */
		public Style(int opacityPercent, int targetOpacityPercent, double optionTextScale, double inventoryTextScale,
			double rootDistance, double submenuRadiusScale, boolean showTrail, boolean reduceMotion,
			int trailLength, int trailOpacityPercent) {
			this(opacityPercent, targetOpacityPercent, optionTextScale, inventoryTextScale, rootDistance, submenuRadiusScale,
				showTrail, reduceMotion, trailLength, trailOpacityPercent,
				nx.pingwheel.common.config.SpatialSelectorSettings.DEFAULT_INVENTORY_MAX_WIDTH_FRACTION.doubleValue(),
				nx.pingwheel.common.config.SpatialSelectorSettings.DEFAULT_INVENTORY_MAX_HEIGHT_FRACTION.doubleValue());
		}

		/** Compatibility form; native holds supply their frozen trail preferences explicitly. */
		public Style(int opacityPercent, int targetOpacityPercent, double optionTextScale, double inventoryTextScale,
			double rootDistance, double submenuRadiusScale, boolean showTrail, boolean reduceMotion) {
			this(opacityPercent, targetOpacityPercent, optionTextScale, inventoryTextScale, rootDistance, submenuRadiusScale,
				showTrail, reduceMotion, nx.pingwheel.common.config.SpatialSelectorSettings.DEFAULT_TRAIL_LENGTH,
				nx.pingwheel.common.config.SpatialSelectorSettings.DEFAULT_TRAIL_OPACITY);
		}

		public static final Style NATIVE = new Style(100, 100, 1.0, 1.0, 0.0, 1.0, true, false);

		/**
		 * Compatibility form with one opacity value feeding both the sector
		 * underlay and the text-bearing frames.
		 */
		public Style(int opacityPercent, double optionTextScale, double inventoryTextScale,
			double rootDistance, boolean showTrail, boolean reduceMotion) {
			this(opacityPercent, opacityPercent, optionTextScale, inventoryTextScale, rootDistance, 1.0, showTrail, reduceMotion);
		}

		/** Zero rootDistance retains viewport-derived spacing for old overloads only. */
		public Style(int opacityPercent, double optionTextScale, double inventoryTextScale,
			boolean showTrail, boolean reduceMotion) {
			this(opacityPercent, opacityPercent, optionTextScale, inventoryTextScale, 0.0, 1.0, showTrail, reduceMotion);
		}

		/** Zero rootDistance retains viewport-derived spacing for this form. */
		public Style(int opacityPercent, int targetOpacityPercent, double optionTextScale, double inventoryTextScale,
			boolean showTrail, boolean reduceMotion) {
			this(opacityPercent, targetOpacityPercent, optionTextScale, inventoryTextScale, 0.0, 1.0, showTrail, reduceMotion);
		}

		/** Compatibility form predating the submenu radius scale; keeps the baseline 1.0. */
		public Style(int opacityPercent, int targetOpacityPercent, double optionTextScale, double inventoryTextScale,
			double rootDistance, boolean showTrail, boolean reduceMotion) {
			this(opacityPercent, targetOpacityPercent, optionTextScale, inventoryTextScale, rootDistance, 1.0, showTrail, reduceMotion);
		}

		public Style {
			opacityPercent = WheelOpacity.clampPercent(opacityPercent);
			targetOpacityPercent = WheelOpacity.clampPercent(targetOpacityPercent);
			trailOpacityPercent = WheelOpacity.clampPercent(trailOpacityPercent);
			if (trailLength <= 0 || trailLength > 512) throw new IllegalArgumentException("invalid trail bound");
			if (!Double.isFinite(optionTextScale) || optionTextScale <= 0.0
				|| !Double.isFinite(inventoryTextScale) || inventoryTextScale <= 0.0
				|| !Double.isFinite(rootDistance) || rootDistance < 0.0
				|| !Double.isFinite(submenuRadiusScale) || submenuRadiusScale <= 0.0
				|| !Double.isFinite(inventoryMaxWidthFraction) || inventoryMaxWidthFraction <= 0.0
				|| !Double.isFinite(inventoryMaxHeightFraction) || inventoryMaxHeightFraction <= 0.0) {
				throw new IllegalArgumentException(
					"visual scales must be finite and positive, and root distance finite and non-negative");
			}
		}

		/** Values are caller-normalized; neither old nor new config is read here. */
		public static Style fromLegacyFontSizes(int opacityPercent, int optionFontPercent, int targetFontPercent,
			boolean showTrail, boolean reduceMotion) {
			return fromLegacyFontSizes(opacityPercent, optionFontPercent, targetFontPercent, 0.0, showTrail, reduceMotion);
		}

		/** rootDistance is visual spacing only; no gesture thresholds or old radii are consumed. */
		public static Style fromLegacyFontSizes(int opacityPercent, int optionFontPercent, int targetFontPercent,
			double rootDistance, boolean showTrail, boolean reduceMotion) {
			return fromLegacyFontSizes(opacityPercent, opacityPercent, optionFontPercent, targetFontPercent,
				rootDistance, 1.0, showTrail, reduceMotion);
		}

		/**
		 * Full form with independent backdrop and target-frame opacities. Font
		 * percentages stay in their persisted units: the option percentage keeps
		 * the radial base scale, and the target percentage is an actual text
		 * scale. This compatibility form keeps the baseline submenu radius scale.
		 */
		public static Style fromLegacyFontSizes(int opacityPercent, int targetOpacityPercent, int optionFontPercent,
			int targetFontPercent, double rootDistance, boolean showTrail, boolean reduceMotion) {
			return fromLegacyFontSizes(opacityPercent, targetOpacityPercent, optionFontPercent, targetFontPercent,
				rootDistance, 1.0, showTrail, reduceMotion);
		}

		/** Full form including the frozen non-root submenu radius scale. */
		public static Style fromLegacyFontSizes(int opacityPercent, int targetOpacityPercent, int optionFontPercent,
			int targetFontPercent, double rootDistance, double submenuRadiusScale,
			boolean showTrail, boolean reduceMotion) {
			return new Style(opacityPercent, targetOpacityPercent,
				WheelLabelLayout.BASE_TEXT_SCALE * optionFontPercent / 100.0,
				targetFontPercent / 100.0, rootDistance, submenuRadiusScale, showTrail, reduceMotion);
		}

		public static Style fromLegacyFontSizes(int opacityPercent, int targetOpacityPercent, int optionFontPercent,
			int targetFontPercent, double rootDistance, double submenuRadiusScale,
			boolean showTrail, boolean reduceMotion, int trailLength, int trailOpacityPercent) {
			return new Style(opacityPercent, targetOpacityPercent,
				WheelLabelLayout.BASE_TEXT_SCALE * optionFontPercent / 100.0,
				targetFontPercent / 100.0, rootDistance, submenuRadiusScale, showTrail, reduceMotion, trailLength, trailOpacityPercent);
		}

		/** Native full form: every value is resolved once by the press-time caller. */
		public static Style fromLegacyFontSizes(int opacityPercent, int targetOpacityPercent, int optionFontPercent,
			int targetFontPercent, double rootDistance, double submenuRadiusScale,
			boolean showTrail, boolean reduceMotion, int trailLength, int trailOpacityPercent,
			double inventoryMaxWidthFraction, double inventoryMaxHeightFraction) {
			return new Style(opacityPercent, targetOpacityPercent,
				WheelLabelLayout.BASE_TEXT_SCALE * optionFontPercent / 100.0,
				targetFontPercent / 100.0, rootDistance, submenuRadiusScale, showTrail, reduceMotion, trailLength, trailOpacityPercent,
				inventoryMaxWidthFraction, inventoryMaxHeightFraction);
		}
	}

	record VisualKey(String kind, String owner, String entry) {}
	sealed interface Paint permits NodePaint, PanelPaint, RowPaint, HeaderPaint, FooterPaint, ChromePaint,
		SectorPaint, InventoryBackPaint, InventoryBackSectorPaint, GuidePaint, TrailPaint, LinkPaint {}
	/** Immutable prior-frame visual history, painted once below every text-bearing surface. */
	record TrailPaint(List<SpatialController.Point> points) implements Paint {
		TrailPaint { points = List.copyOf(points); }
	}
	/** Current menu origin and frozen circle boundary for the active follower's connector. */
	record Follower(double originX, double originY, double deadzone) {}
	/**
	 * One node's paint payload. {@code targetDetail} is the caller-resolved
	 * external name preview, or {@code null} for a title-only node. The Precise
	 * menu's shared {@code maxExtent} is the preview wrapping cell, retained even
	 * for disabled slots, Back and exit paint; an infinite extent means the node
	 * has no external preview budget, as on ordinary menus.
	 */
	record NodePaint(SpatialController.ChoiceView choice, Component label, Component targetDetail,
		boolean selected, double hoverProgress, double maxExtent, Follower follower) implements Paint {

		NodePaint(SpatialController.ChoiceView choice, Component label, Component targetDetail,
			boolean selected, double hoverProgress, double maxExtent) {
			this(choice, label, targetDetail, selected, hoverProgress, maxExtent, null);
		}

		NodePaint(SpatialController.ChoiceView choice, Component label, Component targetDetail,
			boolean selected, double hoverProgress) {
			this(choice, label, targetDetail, selected, hoverProgress, Double.POSITIVE_INFINITY);
		}

		/** Compatibility form for callers that predate the target detail. */
		NodePaint(SpatialController.ChoiceView choice, Component label, boolean selected, double hoverProgress) {
			this(choice, label, null, selected, hoverProgress);
		}

		boolean hasDetail() {
			return targetDetail != null && !SpatialTargetDetailText.limit(targetDetail).getString().isBlank();
		}
	}
	/** Logical source retained with exit paint so a resize can remeasure, not scale, its content box. */
	record InventoryGeometry(SpatialInventoryView view, SpatialInventoryLayout layout) {}
	record PanelPaint(double width, double height, double headerHeight, InventoryGeometry geometry) implements Paint {
		PanelPaint(double width, double height, double headerHeight) { this(width, height, headerHeight, null); }
	}
	record RowPaint(SpatialInventoryView.Row row, double width, double height, boolean selected,
		boolean grey, Integer outlineColor, InventoryGeometry geometry) implements Paint {
		RowPaint(SpatialInventoryView.Row row, double width, double height, boolean selected, boolean grey, Integer outlineColor) {
			this(row, width, height, selected, grey, outlineColor, null);
		}
		RowPaint(SpatialInventoryView.Row row, double width, double height, boolean selected, boolean grey) {
			this(row, width, height, selected, grey, null);
		}
	}
	record HeaderPaint(double width, double height, String position, SpatialInventoryView.Status status,
		InventoryGeometry geometry) implements Paint {
		HeaderPaint(double width, double height, String position, SpatialInventoryView.Status status) {
			this(width, height, position, status, null);
		}
	}
	record FooterPaint(double width, double height, boolean backLeft,
		SpatialInventoryView.BackAffordance back, boolean forwardAvailable, InventoryGeometry geometry) implements Paint {
		FooterPaint(double width, double height, boolean backLeft, SpatialInventoryView.BackAffordance back, boolean forwardAvailable) {
			this(width, height, backLeft, back, forwardAvailable, null);
		}

		/** Historical form: independent paint callers always showed the Forward hint. */
		FooterPaint(double width, double height, boolean backLeft, SpatialInventoryView.BackAffordance back) {
			this(width, height, backLeft, back, true);
		}
	}
	/** Navigation paint, never a fabricated radial choice, follower, or release candidate. */
	record InventoryBackPaint(Component label, double width, double height, SpatialInventoryView.BackAffordance back,
		InventoryGeometry geometry) implements Paint {}
	/** Outward-facing 180-degree underlay with its own wheel-opacity/background transition. */
	record InventoryBackSectorPaint(double radius, double startDegrees, InventoryGeometry geometry) implements Paint {
		double spanDegrees() { return 180.0; }
	}
	record ChromePaint(SpatialController.Snapshot snapshot, boolean pointerHidden) implements Paint {
		ChromePaint(SpatialController.Snapshot snapshot) { this(snapshot, false); }
	}
	record LinkPaint(List<SpatialController.MenuView> menus) implements Paint {}
	record SectorPaint(SpatialController.ChoiceView choice, double radius) implements Paint {}
	/** One straight guide stroke relative to its menu origin. */
	record GuideLine(double x1, double y1, double x2, double y2) {}
	/**
	 * Straight centre guides of one radial menu: one shared-boundary divider per
	 * adjacent pair whose end and start meet, plus the closed circle at the
	 * frozen deadzone radius. Painted at the menu origin and following only the
	 * menu's transition fade, never the wheel or target opacity preference.
	 */
	record GuidePaint(List<GuideLine> boundaries, List<GuideLine> circle) implements Paint {
		GuidePaint {
			boundaries = List.copyOf(boundaries);
			circle = List.copyOf(circle);
		}
	}

	/**
	 * Default radial label contract: {@link SpatialController.ChoiceView#label()}
	 * is a localization key. Callers whose labels are literal text pass their own
	 * resolver to the overloaded {@code draw}; this renderer never infers a
	 * label's kind from spaces, dots, or any other content heuristic.
	 */
	public static final Function<SpatialController.ChoiceView, Component> TRANSLATION_KEY_LABELS =
		choice -> choice.label() == null || choice.label().isBlank()
			? Component.empty()
			: Component.translatable(choice.label());

	/**
	 * Actual displayed selected-row geometry in ABSOLUTE GUI coordinates,
	 * including the current rigid view translation, not controller coordinates.
	 * Subtracting guiHeight / 2 yields the displayed row position relative to
	 * the current view center (the active menu origin), not the gesture origin.
	 * Width includes the row's animated scale. Outgoing rows return NONE.
	 */
	public record RowLayout(double centerY, double width) {

		public static final RowLayout NONE = new RowLayout(Double.NaN, 0.0);

		public boolean isPresent() {
			return !Double.isNaN(centerY);
		}

		public double controllerY(double guiHeight) {
			return centerY - guiHeight / 2.0;
		}
	}

	/** Title-only frame bounds; the optional name preview has its own paint geometry. */
	record NodeLayout(double width, double height, double titleScale, DetailLayout detail) {
		int left() { return (int) Math.round(-width / 2.0); }
		int top() { return (int) Math.round(-height / 2.0); }
		int right() { return (int) Math.round(width / 2.0); }
		int bottom() { return (int) Math.round(height / 2.0); }
		double titleTop(int lineHeight) { return -lineHeight * titleScale / 2.0; }
		double detailScale() { return detail == null ? 0.0 : detail.scale(); }
	}

	/** Each wrapped line is centered independently, with no background, border or frame clipping. */
	record DetailLine(FormattedText text, double width) {
		double left(double scale) { return -width * scale / 2.0; }
	}

	record DetailLayout(Component text, List<DetailLine> lines, double scale, double top, double height) {
		double width() { return lines.stream().mapToDouble(line -> line.width() * scale).max().orElse(0.0); }
		double left() { return -width() / 2.0; }
		double right() { return width() / 2.0; }
		double bottom() { return top + height; }
	}

	/**
	 * Historical single-line node bounds: the title at its own scale with the
	 * existing minimum floors. Ordinary nodes without a detail line keep this
	 * form, so their established appearance is unchanged.
	 */
	static NodeLayout nodeLayout(double titleWidth, int lineHeight, double optionScale) {
		double width = Math.max(MIN_BOX_WIDTH, titleWidth * optionScale + NODE_TEXT_INSET * 2.0);
		double height = Math.max(BOX_HEIGHT, lineHeight * optionScale + NODE_VERTICAL_PADDING);
		return new NodeLayout(width, height, optionScale, null);
	}

	/**
	 * Production planning seam: every title uses the ordinary natural single-line
	 * bounds. Only the external name preview consumes the menu's finite cell, so
	 * a candidate name can never resize, shrink or clip the title frame above it.
	 */
	static NodeLayout nodeLayout(NodePaint node, ToIntFunction<FormattedText> widths, int lineHeight, Style style,
		StringSplitter splitter) {
		Component label = nodeLabel(node.choice(), node.label());
		NodeLayout layout = nodeLayout(widths.applyAsInt(label), lineHeight, style.optionTextScale());
		if (node.targetDetail() == null) return layout;
		Component text = SpatialTargetDetailText.limit(node.targetDetail());
		if (text.getString().isBlank()) return layout;
		double scale = style.inventoryTextScale();
		List<FormattedText> lines = detailLines(text, node.maxExtent(), scale, splitter);
		if (Double.isFinite(node.maxExtent()) && !lines.isEmpty()) {
			// Width uses the full cell, not the title inset. Its vertical budget
			// includes the rounded title frame and the gap: budgeting only the
			// preview height lets its lower lines paint over a sibling frame.
			// Fit before rewrapping, retaining every capped glyph and the floor.
			double floor = Math.min(scale, WheelLabelLayout.BASE_TEXT_SCALE);
			double availableHeight = Math.max(0.0, node.maxExtent() - (layout.bottom() - layout.top()) - NODE_DETAIL_GAP);
			scale = Math.min(scale, Math.max(floor, availableHeight / (lineHeight * lines.size())));
			lines = detailLines(text, node.maxExtent(), scale, splitter);
		}
		List<DetailLine> measured = lines.stream().map(line -> new DetailLine(line, widths.applyAsInt(line))).toList();
		return new NodeLayout(layout.width(), layout.height(), layout.titleScale(),
			new DetailLayout(text, measured, scale, layout.bottom() + NODE_DETAIL_GAP, lineHeight * scale * lines.size()));
	}

	private static List<FormattedText> detailLines(Component text, double width, double scale, StringSplitter splitter) {
		int limit = Double.isFinite(width) ? Math.max(1, (int) Math.floor(width / scale)) : Integer.MAX_VALUE;
		return splitter.splitLines(text, limit, net.minecraft.network.chat.Style.EMPTY);
	}

	/**
	 * Shared external preview wrapping cell for every node of the Precise branch.
	 * The adjacent chord of the six equal slots including Back bounds that cell;
	 * title frames keep the ordinary natural single-line bounds and are not
	 * measured against it.
	 */
	static double preciseNodeExtentBudget(double viewportOrbit, double submenuRadiusScale) {
		double radius = viewportOrbit * submenuRadiusScale;
		double chord = 2.0 * radius * Math.sin(Math.PI / PRECISE_SLOT_COUNT);
		return Math.max(MIN_BOX_WIDTH, PRECISE_NODE_EXTENT_FRACTION * chord);
	}

	/**
	 * Hard disposal for a screen/world/connection discontinuity. For a smooth
	 * normal exit pass an inactive frame instead and continue drawing its tail.
	 */
	public static void reset() {
		SHARED.reset();
	}

	public static boolean isAnimating() {
		return SHARED.isAnimating();
	}

	/**
	 * Paints the active radial snapshot using the default localization-key label
	 * contract ({@link #TRANSLATION_KEY_LABELS}). An inactive snapshot fades out.
	 *
	 * @param partialTick reserved for the caller's own frame interpolation; node
	 *                    smoothing uses monotonic elapsed time instead
	 */
	public static void draw(GuiGraphics guiGraphics, SpatialController.Snapshot snapshot, float partialTick) {
		draw(guiGraphics, snapshot, partialTick, TRANSLATION_KEY_LABELS);
	}

	/**
	 * Paints the active radial snapshot with a caller-supplied label resolver.
	 * Inventory display names must be resolved as literal text by their caller;
	 * this renderer applies no name heuristic.
	 *
	 * @param labelResolver maps each choice to its display component; a null
	 *                      resolver falls back to {@link #TRANSLATION_KEY_LABELS}
	 */
	public static void draw(
		GuiGraphics guiGraphics,
		SpatialController.Snapshot snapshot,
		float partialTick,
		Function<SpatialController.ChoiceView, Component> labelResolver
	) {
		draw(guiGraphics, snapshot, partialTick, labelResolver, System.nanoTime());
	}

	/**
	 * Radial-only compatibility entry point. Native integration should use
	 * drawFrame once per HUD frame so radial/list mode switches share one cache.
	 */
	public static void draw(
		GuiGraphics guiGraphics,
		SpatialController.Snapshot snapshot,
		float partialTick,
		Function<SpatialController.ChoiceView, Component> labelResolver,
		long nowNanos
	) {
		drawFrame(guiGraphics, snapshot, null, labelResolver, Style.NATIVE, nowNanos);
	}

	/**
	 * Compatibility projection without a viewport in its snapshot. It shows the
	 * caller's entire remaining window rather than inventing a nine-row viewport.
	 * Native callers should pass their model to inventoryView or construct a view
	 * explicitly, then use drawFrame with the configured style and one clock.
	 *
	 * @param itemIds optional row key to item id map for real item textures;
	 *                missing or unresolvable ids render no icon
	 */
	public static RowLayout drawInventoryList(
		GuiGraphics guiGraphics,
		InventoryListModel.Snapshot snapshot,
		Map<String, String> itemIds,
		float partialTick
	) {
		SpatialInventoryView view = snapshot == null ? null : inventoryView("legacy-list", snapshot,
			Math.max(1, snapshot.entries().size()), itemIds,
			SpatialInventoryView.Status.fromName(snapshot.status().name()));
		return drawFrame(guiGraphics, null, view, null, Style.NATIVE, System.nanoTime());
	}

	/** Projection preserving the list owner's viewport, ordering and selection. */
	public static SpatialInventoryView inventoryView(String listKey, InventoryListModel model,
		Map<String, String> itemIds, SpatialInventoryView.Status status) {
		return inventoryView(listKey, model, itemIds, null, status);
	}

	/**
	 * Projection preserving the list owner's viewport, ordering and selection
	 * with each row's server-bounded encoded display value. Both maps are keyed
	 * by row key; a missing entry keeps the native registry-name fallback.
	 */
	public static SpatialInventoryView inventoryView(String listKey, InventoryListModel model,
		Map<String, String> itemIds, Map<String, String> displayJson, SpatialInventoryView.Status status) {
		return inventoryView(listKey, model.snapshot(), model.visibleRows(), itemIds, displayJson, status);
	}

	public static SpatialInventoryView inventoryView(String listKey, InventoryListModel.Snapshot snapshot,
		int visibleRows, Map<String, String> itemIds, SpatialInventoryView.Status status) {
		return inventoryView(listKey, snapshot, visibleRows, itemIds, null, status);
	}

	public static SpatialInventoryView inventoryView(String listKey, InventoryListModel.Snapshot snapshot,
		int visibleRows, Map<String, String> itemIds, Map<String, String> displayJson, SpatialInventoryView.Status status) {
		List<SpatialInventoryView.Row> rows = snapshot.entries().stream().map(entry ->
			new SpatialInventoryView.Row(entry.key(), entry.label(), entry.count(),
				itemIds == null ? null : itemIds.get(entry.key()),
				displayJson == null ? null : displayJson.get(entry.key()),
				SpatialInventoryView.Status.fromName(entry.quality())))
			.toList();
		return new SpatialInventoryView(listKey, snapshot.open(), rows, snapshot.selectedIndex(), snapshot.windowFirst(),
			visibleRows, snapshot.direction().back() == InventoryListModel.Side.LEFT, status, snapshot.axisX(), snapshot.glideBaseY());
	}

	/**
	 * Paint once per frame. Pass only the radial menus/list that are presented in
	 * this mode (both may be supplied for a row-anchored submenu). Null/inactive
	 * inputs are an exit, not disposal. The active menu's origin is projected
	 * onto the GUI center by one rigid view translation; an inactive frame keeps
	 * the last displayed translation for the exit tail. No target acquisition or
	 * sends occur.
	 */
	public static RowLayout drawFrame(GuiGraphics graphics, SpatialController.Snapshot radial,
		SpatialInventoryView inventory, Function<SpatialController.ChoiceView, Component> labels,
		Style style, long nowNanos) {
		return drawFrame(graphics, radial, inventory, labels, null, style, nowNanos);
	}

	/**
	 * Paint once per frame with an independent external-name resolver. The detail
	 * resolver is applied to every presented choice exactly once per frame and
	 * its component is retained in the node's paint payload, so an exit tail
	 * keeps the detail it was painted with and no live lookup happens while the
	 * overlay is only animating out. A null resolver omits the preview.
	 */
	public static RowLayout drawFrame(GuiGraphics graphics, SpatialController.Snapshot radial,
		SpatialInventoryView inventory, Function<SpatialController.ChoiceView, Component> labels,
		Function<SpatialController.ChoiceView, Component> details, Style style, long nowNanos) {
		return SHARED.drawFrame(graphics, radial, inventory, labels, details, style, nowNanos);
	}

	/**
	 * Controller-space origin of the currently active (last) menu, or null when
	 * no menu is presented. The rigid view translation is its negation.
	 */
	static SpatialController.Point activeOrigin(SpatialController.Snapshot radial) {
		if (radial == null || !radial.active() || radial.menus().isEmpty()) {
			return null;
		}
		return radial.menus().getLast().origin();
	}

	/** An open inventory surface hides the crosshair only while its empty list menu is active. */
	private static boolean activeInventoryList(SpatialController.Snapshot radial, SpatialInventoryView inventory) {
		return inventory != null && inventory.open() && radial != null && radial.active() && !radial.menus().isEmpty()
			&& radial.menus().getLast().choices().isEmpty();
	}

	/**
	 * Samples the single rigid view translation for one frame. An active origin
	 * re-aims the offset at its negation and samples it; a null origin (inactive
	 * radial, or no presented menu) leaves the last sample in place, so the exit
	 * tail keeps its translation instead of snapping back to the GUI center.
	 */
	static void sampleViewOffset(SpatialViewOffset offset, SpatialController.Point activeOrigin,
		long nowNanos, boolean reduceMotion) {
		if (activeOrigin == null) {
			return;
		}
		offset.centerOn(activeOrigin.x(), activeOrigin.y(), nowNanos, reduceMotion);
		offset.sample(nowNanos);
	}

	/** Per-native-session render state, main-thread confined and finitely retained. */
	public static final class Session {
		private Set<String> paintedChoiceIds = Set.of();
		/** Current active nodes whose visible paint completed successfully, never detached exit nodes. */
		public Set<String> paintedChoiceIds() { return paintedChoiceIds; }
		private final SpatialOverlayTransitions<VisualKey, Paint> transitions =
			new SpatialOverlayTransitions<>(TRANSITION_NANOS, TRANSITION_CAPACITY, APPEARANCE_SCALE);
		private final SpatialViewOffset viewOffset = new SpatialViewOffset(TRANSITION_NANOS);
		private final SpatialVisualTrail<VisualKey> trail = new SpatialVisualTrail<>(TRANSITION_NANOS);
		private final SpatialLabelAvoidance labelAvoidance = new SpatialLabelAvoidance();
		private SpatialController.Viewport publishedViewport;
		/** One prior geometry sample, not a second animation/history or an input baseline. */
		private FrameGeometry frameGeometry;

		private record FrameGeometry(double width, double height, String itemMenu, SpatialController.Point itemOrigin,
			SpatialInventoryLayout.Metrics inventoryMetrics) {
			boolean relayoutFrom(FrameGeometry previous) {
				return previous != null && (width != previous.width || height != previous.height
					|| !inventoryMetrics.equals(previous.inventoryMetrics)
					|| itemMenu != null && itemMenu.equals(previous.itemMenu) && !itemOrigin.equals(previous.itemOrigin));
			}
		}

		/** Interaction-frame preparation only: sample the pan once, then paint this immutable projection. */
		public SpatialController.Viewport prepareViewport(SpatialController.Snapshot radial, int width, int height,
			Style style, long nowNanos) {
			sampleViewOffset(viewOffset, activeOrigin(radial), nowNanos, style.reduceMotion());
			publishedViewport = new SpatialController.Viewport(width, height, viewOffset.x(), viewOffset.y());
			return publishedViewport;
		}

		/** Navigation in that frame re-aims from the sample, without sampling the pan twice. */
		public SpatialController.Viewport retargetViewport(SpatialController.Snapshot radial, Style style, long nowNanos) {
			var origin = activeOrigin(radial);
			if (origin != null) viewOffset.centerOn(origin.x(), origin.y(), nowNanos, style.reduceMotion());
			publishedViewport = new SpatialController.Viewport(publishedViewport.width(), publishedViewport.height(), viewOffset.x(), viewOffset.y());
			return publishedViewport;
		}

		/** Resize/move/release do not advance the animation clock. */
		public SpatialController.Viewport lastViewport(int width, int height) {
			return new SpatialController.Viewport(width, height,
				publishedViewport == null ? viewOffset.x() : publishedViewport.viewX(),
				publishedViewport == null ? viewOffset.y() : publishedViewport.viewY());
		}

		public void reset() {
			transitions.clear();
			trail.clear();
			labelAvoidance.clear();
			viewOffset.clear();
			publishedViewport = null;
			frameGeometry = null;
			paintedChoiceIds = Set.of();
		}

		/** Whether inactive frames still need painting/cleanup, not an input-active predicate. */
		public boolean isAnimating() {
			return !transitions.isEmpty();
		}

		public RowLayout drawFrame(GuiGraphics graphics, SpatialController.Snapshot radial,
			SpatialInventoryView inventory, Function<SpatialController.ChoiceView, Component> labels,
			Style style, long nowNanos) {
			return drawFrame(graphics, radial, inventory, labels, null, style, nowNanos);
		}

		public RowLayout drawFrame(GuiGraphics graphics, SpatialController.Snapshot radial,
			SpatialInventoryView inventory, Function<SpatialController.ChoiceView, Component> labels,
			Function<SpatialController.ChoiceView, Component> details, Style style, long nowNanos) {
			return drawFrame(graphics, radial, inventory, labels, details, style, nowNanos, null);
		}

		/** Paint the already-published projection without resampling pan or mutating interaction. */
		public RowLayout drawFrame(GuiGraphics graphics, SpatialController.Snapshot radial,
			SpatialInventoryView inventory, Function<SpatialController.ChoiceView, Component> labels,
			Function<SpatialController.ChoiceView, Component> details, Style style, long nowNanos,
			SpatialController.Viewport viewport) {
			paintedChoiceIds = Set.of();
			if (graphics == null || style == null) return RowLayout.NONE;
			Font font = font();
			if (font == null) return RowLayout.NONE;
			return drawFrame(radial, inventory, labels, details, style, nowNanos, viewport,
				graphics.guiWidth(), graphics.guiHeight(), new FramePainter() {
					private final List<TitleBounds> titles = new ArrayList<>();
					public int lineHeight() { return font.lineHeight; }
					public int width(FormattedText text) { return font.width(text); }
					public StringSplitter splitter() { return font.getSplitter(); }
					public void flush() { graphics.flush(); }
					public boolean paint(SpatialOverlayTransitions.State<VisualKey, Paint> state,
						NodeLayout layout, Style preference) {
						boolean focused = state.present() && state.data() instanceof NodePaint node && node.selected();
						// A managed GuiGraphics may batch text separately from rectangles.
						// Keep the focused node after all earlier batches, not just later in CPU order.
						if (focused) graphics.flush();
						SpatialOverlayRenderer.paint(graphics, font, state, preference, layout);
						if (focused) graphics.flush();
						if (layout != null) {
							titles.add(titleBounds(state, layout));
							if (layout.detail() != null) {
								var detail = layout.detail();
								double scale = (float) state.scale();
								titles.add(new TitleBounds(state.x() + detail.left() * scale - 1,
									state.y() + detail.top() * scale - 1, state.x() + detail.right() * scale + 1,
									state.y() + detail.bottom() * scale + 1));
							}
						}
						return true;
					}
					public void connector(GuideLine stroke, double alpha) {
						// The connector is admitted only after successful paint. Exclude
						// painted frames so even translucent titles cannot be crossed by it.
						for (GuideLine visible : uncoveredLine(stroke, titles))
							line(graphics, visible.x1(), visible.y1(), visible.x2(), visible.y2(),
								withAlpha(GUIDE_COLOR, alpha), BORDER_THICKNESS);
					}
				});
		}

		/** Headless production frame seam; successful paint and actual title visibility gate acknowledgement. */
		RowLayout drawFrame(SpatialController.Snapshot radial, SpatialInventoryView inventory,
			Function<SpatialController.ChoiceView, Component> labels,
			Function<SpatialController.ChoiceView, Component> details, Style style, long nowNanos,
			SpatialController.Viewport viewport, double width, double height, FramePainter painter) {
			paintedChoiceIds = Set.of();
			// The active list keeps its moving crosshair but hides every trail
			// paint; an item radial keeps the ordinary follower/trail rules.
			boolean listActive = activeInventoryList(radial, inventory);
			List<SpatialOverlayTransitions.Target<VisualKey, Paint>> targets = new ArrayList<>();
			trail.prepare(radial, style.trailLength(), nowNanos, style.reduceMotion());
			TrailPaint historicalTrail = new TrailPaint(trail.points());
			double guiWidth = viewport == null ? width : viewport.width();
			double guiHeight = viewport == null ? height : viewport.height();
			double centerX = guiWidth / 2.0;
			double centerY = guiHeight / 2.0;
			SpatialController.Point origin = activeOrigin(radial);
			var geometry = new FrameGeometry(guiWidth, guiHeight,
				origin != null && inventory != null && inventory.open() && inventory.retainedRowKey() != null
					? radial.menus().getLast().menuId() : null, origin,
				SpatialInventoryLayout.measure(inventory, guiWidth, guiHeight, painter.lineHeight(), style.inventoryTextScale(),
					style.inventoryMaxWidthFraction(), style.inventoryMaxHeightFraction(), painter::width));
			boolean relayout = geometry.relayoutFrom(frameGeometry);
			if (frameGeometry != null && (guiWidth != frameGeometry.width() || guiHeight != frameGeometry.height())
				&& (origin != null || inventory != null && inventory.open())) {
				// Every cached pose is absolute GUI space. Rebase that entire pattern,
				// including detached stream/mode tails, before retargeting to the new
				// GUI center; otherwise an old panel/sector can use a different center.
				transitions.translateGeometry((guiWidth - frameGeometry.width()) / 2.0,
					(guiHeight - frameGeometry.height()) / 2.0);
			}
			frameGeometry = origin == null && (inventory == null || !inventory.open()) ? null : geometry;
			if (viewport == null) sampleViewOffset(viewOffset, origin, nowNanos, style.reduceMotion());
			double viewX = viewport == null ? viewOffset.x() : viewport.viewX();
			double viewY = viewport == null ? viewOffset.y() : viewport.viewY();
			if (radial != null && radial.active()) {
				addRadial(targets, radial, labels == null ? TRANSLATION_KEY_LABELS : labels, details,
					centerX, centerY, orbitFor(Math.min(guiWidth, guiHeight)), style.rootDistance(), style.submenuRadiusScale());
			}
			if (inventory != null && inventory.open()) {
				addInventory(targets, inventory, painter, style, guiWidth, guiHeight, listActive);
			}
			avoidLabels(targets, radial, inventory, centerX, centerY, style, painter);
			VisualKey desiredOwner = targets.stream().filter(target -> target.data() instanceof NodePaint node && node.follower() != null)
				.map(SpatialOverlayTransitions.Target::key).findFirst().orElse(null);
			List<SpatialOverlayTransitions.State<VisualKey, Paint>> states = new ArrayList<>(
				// Radius/font payloads and logical guides/rays already use this frame's
				// geometry. Commit every presented layout anchor on a resize or item
				// clearance relayout in the same update; easing old anchors separately
				// would let a new sector or Back frame cover the still-old panel.
				// The shared pan and all non-position channels keep their transitions.
				transitions.update(targets, nowNanos, style.reduceMotion(), relayout));
			// Links share chrome's detached geometry/fade but live below all text.
			var linkSource = states.stream().filter(state -> state.data() instanceof ChromePaint).findFirst().orElse(null);
			if (linkSource != null && ((ChromePaint) linkSource.data()).snapshot().menus().size() > 1)
				states.add(new SpatialOverlayTransitions.State<>(new VisualKey("links", "radial", ""),
					linkSource.x(), linkSource.y(), linkSource.scale(), linkSource.alpha(), linkSource.present(),
					new LinkPaint(((ChromePaint) linkSource.data()).snapshot().menus()), 0.0));
			// Share chrome's finite fade/projection, not its payload or topmost paint rank.
			// No second history or transition-cache entry is needed, even at capacity.
			if (style.showTrail() && style.trailOpacityPercent() > 0 && !historicalTrail.points().isEmpty()
				&& !listActive) {
				var chrome = states.stream().filter(state -> state.data() instanceof ChromePaint).findFirst().orElse(null);
				if (chrome != null) states.add(new SpatialOverlayTransitions.State<>(new VisualKey("trail", "radial", ""),
					chrome.x(), chrome.y(), chrome.scale(), chrome.alpha(), chrome.present(), historicalTrail,
					chrome.backgroundAlpha()));
			}
			SpatialController.Point trailOrigin = trailOrigin(states, viewport, centerX, centerY, viewX, viewY);
			if (origin == null && transitions.isEmpty()) {
				// The exit tail is gone: hard-reset so a later session starts centered.
				viewOffset.clear();
				publishedViewport = null;
				trail.clear();
				labelAvoidance.clear();
			}
			RowLayout selected = RowLayout.NONE;
			Set<String> painted = new HashSet<>();
			String activeMenu = origin == null ? null : radial.menus().getLast().menuId();
			boolean hidePointer = false;
			String retainedRowKey = inventory == null ? null : inventory.retainedRowKey();
			String selectedRowKey = listActive && inventory.selectedIndex() >= 0
				&& inventory.selectedIndex() < inventory.rows().size()
				? inventory.rows().get(inventory.selectedIndex()).key() : null;
			SpatialController.Point followerPoint = null;
			Map<InventoryGeometry, SpatialInventoryLayout> inventoryLayouts = new java.util.IdentityHashMap<>();
			boolean underlayBatch = false;
			for (var state : states.stream().sorted(Comparator.comparingInt(value -> paintRank(value, activeMenu))).toList()) {
				state = remeasureInventory(state, guiWidth, guiHeight, painter, style, inventoryLayouts);
				if (state == null) continue;
				// While an item submenu is active every other row, including its
				// detached exit tail, is suppressed: only the entered item row paints.
				if (retainedRowKey != null && state.data() instanceof RowPaint row
					&& !retainedRowKey.equals(row.row().key())) {
					continue;
				}
				var displayed = new SpatialOverlayTransitions.State<>(state.key(), state.x() + viewX, state.y() + viewY,
					state.scale(), state.alpha(), state.present(), state.data(), state.backgroundAlpha());
				// A detached former selection still fades. Clear its cached border
				// too, so closing the list cannot restore the canceled decoration.
				if (listActive && state.data() instanceof RowPaint row && row.outlineColor() != null
					&& (!state.present() || !state.key().owner().equals(inventory.listKey())
						|| !row.row().key().equals(selectedRowKey) || !inventory.selectedRowCommittable()
						|| inventory.backAffordance().focused())) {
					var uncolored = new RowPaint(row.row(), row.width(), row.height(), row.selected(), row.grey(), null, row.geometry());
					transitions.updatePaintData(state.key(), uncolored);
					displayed = new SpatialOverlayTransitions.State<>(displayed.key(), displayed.x(), displayed.y(),
						displayed.scale(), displayed.alpha(), displayed.present(), uncolored,
						displayed.backgroundAlpha());
				}
				if (state.present() && state.data() instanceof GuidePaint) {
					// A guide is the actual frozen interaction boundary, not a moving
					// node. Fade it at its current menu origin without scaling its radius.
					String guideOwner = state.key().owner();
					var menu = radial.menus().stream().filter(value -> value.menuId().equals(guideOwner)).findFirst().orElseThrow();
					displayed = new SpatialOverlayTransitions.State<>(state.key(), centerX + menu.origin().x() + viewX,
						centerY + menu.origin().y() + viewY, 1.0, state.alpha(), true, state.data(), state.backgroundAlpha());
				}
				if (state.data() instanceof TrailPaint || state.data() instanceof LinkPaint) {
					displayed = alignChrome(displayed, viewport);
					if (viewport == null && state.present()) displayed = new SpatialOverlayTransitions.State<>(state.key(),
						centerX + viewX, centerY + viewY, state.scale(), state.alpha(), true, state.data(),
						state.backgroundAlpha());
				}
				if (state.data() instanceof ChromePaint chrome) {
					displayed = alignChrome(displayed, viewport);
					displayed = new SpatialOverlayTransitions.State<>(state.key(),
						viewport == null && state.present() ? centerX + viewX : displayed.x(),
						viewport == null && state.present() ? centerY + viewY : displayed.y(),
						state.scale(), state.alpha(), state.present(), new ChromePaint(chrome.snapshot(), hidePointer),
						state.backgroundAlpha());
				}
				if (state.present() && state.data() instanceof RowPaint row && row.selected())
					selected = new RowLayout(displayed.y(), row.width() * state.scale());
				if (!renders(state.data(), state.alpha(), state.backgroundAlpha(), style)) continue;
				boolean underlay = isRadialUnderlay(state.data());
				if (underlay != underlayBatch) {
					// Earlier GUI queues finish before direct-buffer underlays; the whole
					// underlay batch finishes before any vanilla fill, text or icon can flush.
					painter.flush();
					underlayBatch = underlay;
				}
				NodeLayout layout = state.data() instanceof NodePaint node
					? nodeLayout(node, painter::width, painter.lineHeight(), style, painter.splitter()) : null;
				if (!painter.paint(displayed, layout, style)) continue;
				if (state.data() instanceof NodePaint && titleFrameIntersectsViewport(displayed, layout, guiWidth, guiHeight)
					&& (withAlpha(BORDER_COLOR, targetAlpha(state.alpha(), style)) >>> 24) != 0
					&& (origin == null ? !state.present() && state.key().equals(trail.owner())
						: state.present() && state.key().equals(desiredOwner))) {
					// The displayed pose is already rigidly projected: undo exactly the projection
					// origin the frame's trail paint used, which stays detached during an exit.
					followerPoint = new SpatialController.Point(displayed.x() - trailOrigin.x(), displayed.y() - trailOrigin.y());
				}
				if (state.data() instanceof NodePaint node && acknowledgesNodePaint(state.present(),
					state.key().owner().equals(activeMenu), state.alpha(), style)
					&& titleFrameIntersectsViewport(displayed, layout, guiWidth, guiHeight)) {
					painted.add(node.choice().id());
					if (node.follower() != null) {
						hidePointer = true;
						GuideLine stroke = followerConnector(displayed, layout, viewX, viewY);
						if (stroke != null) painter.connector(stroke, state.alpha());
					}
				}
			}
			if (underlayBatch) painter.flush();
			paintedChoiceIds = Set.copyOf(painted);
			if (!listActive) trail.commit(radial, desiredOwner, followerPoint, nowNanos, style.reduceMotion());
			return selected;
		}

		private void avoidLabels(List<SpatialOverlayTransitions.Target<VisualKey, Paint>> targets,
			SpatialController.Snapshot radial, SpatialInventoryView inventory, double centerX, double centerY,
			Style style, FramePainter painter) {
			if (radial == null || !radial.active() || radial.menus().isEmpty()) {
				labelAvoidance.clear();
				return;
			}
			var menu = radial.menus().getLast();
			boolean itemMenu = inventory != null && inventory.open() && inventory.retainedRowKey() != null;
			double ox = centerX + menu.origin().x(), oy = centerY + menu.origin().y();
			List<SpatialLabelAvoidance.Label> labels = new ArrayList<>();
			for (var target : targets) {
				if (!target.key().owner().equals(menu.menuId()) || !(target.data() instanceof NodePaint node)) continue;
				NodeLayout box = nodeLayout(node, painter::width, painter.lineHeight(), style, painter.splitter());
				double scale = (float) target.scale();
				int edge = node.choice().disabled() || node.choice().reserved() ? BORDER_THICKNESS : 0;
				// A title node owns the same visual space the frame path paints: its
				// rounded title box plus any external preview below it. The union keeps
				// a moved Precise node's candidate name clear of sibling slots too.
				double left = box.left() * scale;
				double top = box.top() * scale;
				double right = (box.right() + edge) * scale;
				double bottom = (box.bottom() + edge) * scale;
				if (box.detail() != null) {
					DetailLayout detail = box.detail();
					left = Math.min(left, detail.left() * scale);
					top = Math.min(top, detail.top() * scale);
					right = Math.max(right, detail.right() * scale);
					bottom = Math.max(bottom, detail.bottom() * scale);
				}
				labels.add(new SpatialLabelAvoidance.Label(target.key().entry(), target.x() - ox, target.y() - oy,
					left, top, right, bottom,
					node.choice().startDegrees(), node.choice().spanDegrees(),
					!node.selected() && !(itemMenu && node.choice().back()), node.selected()));
			}
			Map<String, Double> offsets = labelAvoidance.place(menu.menuId(), labels);
			for (int i = 0; i < targets.size(); i++) {
				var target = targets.get(i);
				if (!target.key().owner().equals(menu.menuId()) || !(target.data() instanceof NodePaint node)
					|| node.selected()) continue;
				double dy = offsets.getOrDefault(target.key().entry(), 0.0);
				if (dy == 0.0) continue;
				// One ordinary finite transition owns the resulting pose. Appearance
				// and detached exits keep the resulting bearing, without a second animation.
				double radius = Math.hypot(target.x() - ox, target.y() - oy);
				double entryRatio = radius == 0.0 ? 0.0 : Math.hypot(target.entryX() - ox, target.entryY() - oy) / radius;
				targets.set(i, new SpatialOverlayTransitions.Target<>(target.key(), target.x(), target.y() + dy,
					target.scale(), target.alpha(), target.entryX(), target.entryY() + dy * entryRatio, target.data(), target.ray(),
					target.backgroundAlpha()));
			}
		}

		int retainedSize() { return transitions.retainedSize(); }
		List<SpatialController.Point> trailPoints() { return trail.points(); }
	}

	/** Production frame port: the same planned layout is painted and used by its connector. */
	interface FramePainter {
		int lineHeight();
		int width(FormattedText text);
		StringSplitter splitter();
		/** Phase boundary only: never one flush per sector, band or quad. */
		default void flush() {}
		boolean paint(SpatialOverlayTransitions.State<VisualKey, Paint> state, NodeLayout layout, Style style);
		void connector(GuideLine line, double alpha);
	}

	static SpatialOverlayTransitions.State<VisualKey, Paint> alignChrome(
		SpatialOverlayTransitions.State<VisualKey, Paint> state, SpatialController.Viewport viewport) {
		return viewport != null && state.present()
			&& (state.data() instanceof ChromePaint || state.data() instanceof TrailPaint || state.data() instanceof LinkPaint)
			? new SpatialOverlayTransitions.State<>(state.key(), viewport.centerX() + viewport.viewX(), viewport.centerY() + viewport.viewY(),
				state.scale(), state.alpha(), true, state.data(), state.backgroundAlpha())
			: state;
	}

	/**
	 * The exact displayed origin of this frame's detached trail paint. A presented
	 * menu uses the current GUI center plus the sampled pan; an exit keeps the
	 * frozen pose. Follower sampling must invert this same origin rather than the
	 * frame's current GUI center, or a resize during the exit tail displaces the
	 * committed endpoint by the center delta.
	 */
	static SpatialController.Point trailOrigin(List<SpatialOverlayTransitions.State<VisualKey, Paint>> states,
		SpatialController.Viewport viewport, double centerX, double centerY, double viewX, double viewY) {
		for (var state : states) {
			if (state.data() instanceof ChromePaint || state.data() instanceof TrailPaint) {
				if (!state.present()) return new SpatialController.Point(state.x() + viewX, state.y() + viewY);
				return viewport == null ? new SpatialController.Point(centerX + viewX, centerY + viewY)
					: new SpatialController.Point(viewport.centerX() + viewport.viewX(), viewport.centerY() + viewport.viewY());
			}
		}
		return new SpatialController.Point(centerX + viewX, centerY + viewY);
	}

	/** Called only after successful node paint; actual title visibility is checked separately in the frame path. */
	static boolean acknowledgesNodePaint(boolean present, boolean activeMenu, double alpha, Style style) {
		return present && activeMenu && (withAlpha(BORDER_COLOR, targetAlpha(alpha, style)) >>> 24) != 0;
	}

	/** Clip the actual title frame, not its pointer target, preview or connector. Tangency contributes no area. */
	static boolean titleFrameIntersectsViewport(SpatialOverlayTransitions.State<VisualKey, Paint> state,
		NodeLayout layout, double width, double height) {
		// PoseStack narrows translation and scale to floats before transforming
		// the rounded local fill bounds. A dashed border can add one endpoint pixel.
		float x = (float) state.x(), y = (float) state.y(), scale = (float) state.scale();
		NodePaint node = (NodePaint) state.data();
		int endpoint = node.choice().disabled() || node.choice().reserved() ? BORDER_THICKNESS : 0;
		return x + layout.left() * scale < width && x + (layout.right() + endpoint) * scale > 0.0
			&& y + layout.top() * scale < height && y + (layout.bottom() + endpoint) * scale > 0.0;
	}

	static void addRadial(List<SpatialOverlayTransitions.Target<VisualKey, Paint>> targets,
		SpatialController.Snapshot snapshot, Function<SpatialController.ChoiceView, Component> labels,
		Function<SpatialController.ChoiceView, Component> details,
		double centerX, double centerY, double orbit, double rootDistance, double submenuRadiusScale) {
		add(targets, new VisualKey("chrome", "radial", ""), centerX, centerY, 1.0, 1.0, 0.0,
			centerX, centerY, new ChromePaint(snapshot));
		List<SpatialController.MenuView> menus = snapshot.menus();
		double deadzone = snapshot.deadzone();
		for (int i = 0; i < menus.size(); i++) {
			SpatialController.MenuView menu = menus.get(i);
			boolean active = i == menus.size() - 1;
			String childEntry = active ? null : menus.get(i + 1).entryChoiceId();
			// An ancestor keeps only the choice that opened the next child plus its
			// Back navigation; hidden sibling frames and sectors leave planning and
			// fade through the ordinary exit without ever becoming interactive.
			List<SpatialController.ChoiceView> visible = active ? menu.choices() : breadcrumbChoices(menu, childEntry);
			boolean focusScaling = active && focusedActionable(menu);
			double menuX = centerX + menu.origin().x();
			double menuY = centerY + menu.origin().y();
			double baseRadius = nodeRadius(i, orbit, rootDistance, submenuRadiusScale, false);
			if (deadzone > 0.0 && !visible.isEmpty()) {
				// Centre guides are chrome for their own menu: only the menu's
				// transition fade dims them, and an ancestor menu dims like its
				// nodes. Hidden options contribute no divider; the deadzone circle
				// stays the defined centre guide of the remaining wheel.
				add(targets, new VisualKey("guide", menu.menuId(), ""), menuX, menuY, 1.0,
					nodeAlpha(active, false, false), 0.0, menuX, menuY,
					new GuidePaint(menuBoundaries(visible, deadzone, sectorRadius(baseRadius)),
						circleSegments(deadzone)));
			}
			double maxExtent = visible.stream().anyMatch(choice -> choice.id().startsWith("precise:"))
				? preciseNodeExtentBudget(orbit, submenuRadiusScale) : Double.POSITIVE_INFINITY;
			for (SpatialController.ChoiceView choice : visible) {
				double bearing = Math.toRadians(choice.startDegrees() + choice.spanDegrees() / 2.0);
				boolean selected = active && choice.id().equals(menu.focusId());
				double radius = baseRadius + (selected ? ORBIT_SELECTED_PUSH : 0.0);
				boolean disabledOrReserved = choice.disabled() || choice.reserved();
				double alpha = nodeAlpha(active, selected, disabledOrReserved);
				double structural = backgroundStructural(active, disabledOrReserved);
				double sectorCoverage = backgroundCoverage(SECTOR_BACKGROUND_PERCENT,
					backgroundGain(active, selected, focusScaling, SECTOR_FOCUSED_GAIN), structural);
				double boxCoverage = backgroundCoverage(BOX_BACKGROUND_PERCENT,
					backgroundGain(active, selected, focusScaling, BOX_FOCUSED_GAIN), structural);
				// The ancestor entry that opened the next child keeps its frame but
				// never its sector underlay; Back frames and underlays stay.
				if (active || choice.back() || !choice.id().equals(childEntry)) {
					add(targets, new VisualKey("sector", menu.menuId(), choice.id()),
						menuX, menuY, 1.0, alpha, sectorCoverage, menuX, menuY,
						new SectorPaint(choice, sectorRadius(baseRadius)));
				}
				boolean follows = active && selected && actionable(choice) && snapshot.pointer() != null
					&& Math.hypot(snapshot.pointer().x() - menu.origin().x(),
						snapshot.pointer().y() - menu.origin().y()) > deadzone;
				boolean breadcrumb = !active && !choice.back() && choice.id().equals(childEntry);
				double homeX = breadcrumb ? menuX : menuX + Math.sin(bearing) * radius;
				double homeY = breadcrumb ? menuY : menuY - Math.cos(bearing) * radius;
				NodePaint paint = new NodePaint(choice, labels.apply(choice),
					details == null ? null : details.apply(choice), selected, snapshot.hoverProgress(), maxExtent,
					follows ? new Follower(menuX, menuY, deadzone) : null);
				if (follows) {
					double dx = snapshot.pointer().x() - menu.origin().x();
					double dy = snapshot.pointer().y() - menu.origin().y();
					double distance = Math.hypot(dx, dy);
					targets.add(new SpatialOverlayTransitions.Target<>(new VisualKey("follow", menu.menuId(), choice.id()),
						centerX + snapshot.pointer().x(), centerY + snapshot.pointer().y(), NODE_FOCUSED_SCALE, alpha,
						homeX, homeY, paint, new SpatialOverlayTransitions.Ray(menuX, menuY, dx / distance, dy / distance),
						boxCoverage));
				} else {
					add(targets, new VisualKey("node", menu.menuId(), choice.id()), homeX, homeY,
						nodeScale(active, selected, focusScaling), alpha, boxCoverage,
						breadcrumb ? menuX : menuX + Math.sin(bearing) * (radius - 12.0),
						breadcrumb ? menuY : menuY - Math.cos(bearing) * (radius - 12.0), paint);
				}
			}
		}
	}

	/**
	 * Ancestor levels of the active path keep only the entry choice that leads to
	 * the next child plus Back navigation. The controller still owns the complete
	 * logical menu, so this filter changes presentation only; a caller-built view
	 * without a radial entry keeps its Back choices alone.
	 */
	static List<SpatialController.ChoiceView> breadcrumbChoices(SpatialController.MenuView menu, String childEntryChoiceId) {
		List<SpatialController.ChoiceView> visible = new ArrayList<>(2);
		for (SpatialController.ChoiceView choice : menu.choices()) {
			if (choice.back() || choice.id().equals(childEntryChoiceId)) visible.add(choice);
		}
		return visible;
	}

	/** Whether the active menu's focused entry is an enabled action or branch. */
	static boolean focusedActionable(SpatialController.MenuView menu) {
		String focusId = menu.focusId();
		if (focusId == null) return false;
		for (SpatialController.ChoiceView choice : menu.choices()) {
			if (focusId.equals(choice.id())) return actionable(choice);
		}
		return false;
	}

	/**
	 * Active-menu node scale: an eligible focused entry selects the 1.2/0.85
	 * presentation, while Back, disabled, reserved and deadzone focus keep the
	 * established selected/unselected resting scales. Ancestors never scale up.
	 */
	static double nodeScale(boolean active, boolean selected, boolean focusedActionable) {
		if (!active) return NODE_ANCESTOR_SCALE;
		if (focusedActionable) return selected ? NODE_FOCUSED_SCALE : NODE_FOCUSED_SIBLING_SCALE;
		return selected ? NODE_SELECTED_SCALE : NODE_SCALE;
	}

	private static boolean actionable(SpatialController.ChoiceView choice) {
		return !choice.back() && !choice.disabled() && !choice.reserved() && (choice.action() != null || choice.branch());
	}

	/** Uses the actual frame pose and its rounded local title bounds, including float GPU scale. */
	static GuideLine followerConnector(SpatialOverlayTransitions.State<VisualKey, Paint> state,
		NodeLayout layout, double viewX, double viewY) {
		NodePaint node = (NodePaint) state.data();
		Follower follower = node.follower();
		double originX = follower.originX() + viewX;
		double originY = follower.originY() + viewY;
		double dx = state.x() - originX;
		double dy = state.y() - originY;
		double radius = Math.hypot(dx, dy);
		if (!(radius > 0.0)) return null;
		double ux = dx / radius;
		double uy = dy / radius;
		double scale = (float) state.scale();
		// Looking back from the node centre, whichever axis reaches a frame
		// boundary first is the near intersection with the current ray.
		double horizontal = ux == 0.0 ? Double.POSITIVE_INFINITY
			: (ux > 0.0 ? -layout.left() : layout.right()) * scale / Math.abs(ux);
		double vertical = uy == 0.0 ? Double.POSITIVE_INFINITY
			: (uy > 0.0 ? -layout.top() : layout.bottom()) * scale / Math.abs(uy);
		double near = radius - Math.min(horizontal, vertical);
		if (near <= follower.deadzone()) return null; // the title covers this segment
		return new GuideLine(originX + ux * follower.deadzone(), originY + uy * follower.deadzone(),
			originX + ux * near, originY + uy * near);
	}

	record TitleBounds(double left, double top, double right, double bottom) {}

	static TitleBounds titleBounds(SpatialOverlayTransitions.State<VisualKey, Paint> state, NodeLayout layout) {
		double scale = (float) state.scale();
		NodePaint node = (NodePaint) state.data();
		int edge = node.choice().disabled() || node.choice().reserved() ? BORDER_THICKNESS : 0;
		return new TitleBounds((float) state.x() + layout.left() * scale - 1,
			(float) state.y() + layout.top() * scale - 1,
			(float) state.x() + (layout.right() + edge) * scale + 1,
			(float) state.y() + (layout.bottom() + edge) * scale + 1);
	}

	/** Segment subtraction against actual painted boxes; the final connector never paints over a title. */
	static List<GuideLine> uncoveredLine(GuideLine line, List<TitleBounds> boxes) {
		List<double[]> intervals = new ArrayList<>();
		intervals.add(new double[] {0, 1});
		double dx = line.x2() - line.x1(), dy = line.y2() - line.y1();
		for (TitleBounds box : boxes) {
			double lo = 0, hi = 1;
			if (dx == 0) {
				if (line.x1() < box.left() || line.x1() > box.right()) continue;
			} else {
				double a = (box.left() - line.x1()) / dx, b = (box.right() - line.x1()) / dx;
				lo = Math.max(lo, Math.min(a, b)); hi = Math.min(hi, Math.max(a, b));
			}
			if (dy == 0) {
				if (line.y1() < box.top() || line.y1() > box.bottom()) continue;
			} else {
				double a = (box.top() - line.y1()) / dy, b = (box.bottom() - line.y1()) / dy;
				lo = Math.max(lo, Math.min(a, b)); hi = Math.min(hi, Math.max(a, b));
			}
			if (hi <= lo) continue;
			List<double[]> remaining = new ArrayList<>();
			for (double[] interval : intervals) {
				if (hi <= interval[0] || lo >= interval[1]) remaining.add(interval);
				else {
					if (lo > interval[0]) remaining.add(new double[] {interval[0], lo});
					if (hi < interval[1]) remaining.add(new double[] {hi, interval[1]});
				}
			}
			intervals = remaining;
		}
		return intervals.stream().map(interval -> new GuideLine(line.x1() + dx * interval[0], line.y1() + dy * interval[0],
			line.x1() + dx * interval[1], line.y1() + dy * interval[1])).toList();
	}

	/**
	 * Shared node/backdrop opacity: selection and ancestor dimming plus the
	 * disabled/reserved factor, so both layers fade together.
	 */
	static double nodeAlpha(boolean active, boolean selected, boolean disabledOrReserved) {
		double alpha = active ? (selected ? 1.0 : 0.8) : ANCESTOR_ALPHA;
		return disabledOrReserved ? alpha * DISABLED_ALPHA_FACTOR : alpha;
	}

	/**
	 * Background emphasis of one entry. Only an eligible focused action or branch
	 * activates the mode: the focused entry takes its own gain and every other
	 * entry of that menu the sibling gain; Back, disabled, reserved and deadzone
	 * focus keep every entry at the resting gain. Ancestor levels rest.
	 */
	static double backgroundGain(boolean active, boolean selected, boolean focusedActionable, double focusedGain) {
		if (!active || !focusedActionable) return 1.0;
		return selected ? focusedGain : BACKGROUND_SIBLING_GAIN;
	}

	/**
	 * Structural background dimming: an ancestor keeps {@link #ANCESTOR_ALPHA}
	 * and a disabled or reserved entry multiplies {@link #DISABLED_ALPHA_FACTOR}.
	 * The foreground's unselected 0.8 factor is deliberately not applied again.
	 */
	static double backgroundStructural(boolean active, boolean disabledOrReserved) {
		double structural = active ? 1.0 : ANCESTOR_ALPHA;
		return disabledOrReserved ? structural * DISABLED_ALPHA_FACTOR : structural;
	}

	/**
	 * Normalized background coverage of one fill. The percentage stays
	 * fractional until the final byte packing, so the 60% focused endpoint keeps
	 * its exact half and rounds to 230 rather than 229.
	 */
	static double backgroundCoverage(double basePercent, double gain, double structural) {
		return basePercent * gain * structural / 100.0;
	}

	/**
	 * Final background alpha byte: exactly one rounding of
	 * {@code 255 * coverage * opacity percent}. The base paint's own alpha byte is
	 * never pre-rounded and a gain above one is never clamped into it.
	 */
	static int backgroundChannel(double coverage, double opacityPercent) {
		double bounded = clamp(coverage, 0.0, 1.0);
		return (int) Math.round(255.0 * bounded * clamp(opacityPercent, 0.0, 100.0) / 100.0);
	}

	/** Background fill of one base colour; its RGB is kept and its alpha byte is recomputed. */
	static int withBackgroundAlpha(int baseColor, double coverage, double opacityPercent) {
		return (backgroundChannel(coverage, opacityPercent) << 24) | (baseColor & 0x00FFFFFF);
	}

	/**
	 * Background coverage of one transition state after its layer's own opacity
	 * knob: wheel opacity reaches only the sector underlay, target opacity only
	 * the text-bearing panel, header, row and box fills. Chrome, guides and the
	 * trail have no background layer.
	 */
	static double backgroundLayerAlpha(Object data, double stateBackgroundAlpha, Style style) {
		if (data instanceof SectorPaint || data instanceof InventoryBackSectorPaint)
			return stateBackgroundAlpha * style.opacityPercent() / 100.0;
		if (data instanceof NodePaint || data instanceof PanelPaint
			|| data instanceof HeaderPaint || data instanceof RowPaint || data instanceof InventoryBackPaint) {
			return stateBackgroundAlpha * style.targetOpacityPercent() / 100.0;
		}
		return 0.0;
	}

	/** Wheel opacity reaches only the sector underlay. */
	static double backdropAlpha(double stateAlpha, Style style) {
		return stateAlpha * style.opacityPercent() / 100.0;
	}

	/** Target opacity reaches every text-bearing frame together with its label. */
	static double targetAlpha(double stateAlpha, Style style) {
		return stateAlpha * style.targetOpacityPercent() / 100.0;
	}

	/**
	 * Layer alpha for one transition state: the sector underlay follows wheel
	 * opacity, text-bearing frames follow target opacity, and the interaction
		 * chrome — the pointer, links and centre guides — follows only the transition
		 * fade. Trail opacity is a separate multiplier applied exactly once.
	 */
	static double layerAlpha(Object data, double stateAlpha, Style style) {
		if (data instanceof SectorPaint || data instanceof InventoryBackSectorPaint) return backdropAlpha(stateAlpha, style);
		if (data instanceof TrailPaint) return style.showTrail() ? stateAlpha * style.trailOpacityPercent() / 100.0 : 0.0;
		if (data instanceof GuidePaint || data instanceof ChromePaint || data instanceof LinkPaint) return stateAlpha;
		return targetAlpha(stateAlpha, style);
	}

	/**
	 * Whether one transition layer still contributes visible paint. The sector
	 * underlay needs wheel opacity, every text-bearing frame needs target
	 * opacity, and the interaction chrome follows only the transition fade, so a
	 * zero opacity pair hides the underlay and frames without hiding the
	 * pointer, guides, or trail. {@link #paint} applies the same alpha again as
	 * its own guard, including the font-color transparency protection.
	 */
	static boolean renders(Object data, double stateAlpha, Style style) {
		return renders(data, stateAlpha, isRadialUnderlay(data) ? stateAlpha : 0.0, style);
	}

	/**
	 * Paint admission of one state: either channel may keep the layer visible.
	 * A bright background still paints while the foreground alpha is zero, and a
	 * visible foreground still paints while the background coverage is zero; a
	 * layer whose data has no background contributes no second gate.
	 */
	static boolean renders(Object data, double stateAlpha, double stateBackgroundAlpha, Style style) {
		if (isRadialUnderlay(data)) return backgroundChannel(stateBackgroundAlpha, style.opacityPercent()) != 0;
		return layerAlpha(data, stateAlpha, style) > 0.0
			|| backgroundLayerAlpha(data, stateBackgroundAlpha, style) > 0.0;
	}

	/**
	 * Base node radius before the selected push. The root keeps its caller
	 * distance unscaled; every non-root menu multiplies the viewport-derived
	 * orbit by the frozen submenu radius scale, and its sector underlay follows
	 * that same orbit.
	 */
	static double nodeRadius(int menuIndex, double viewportOrbit, double rootDistance,
		double submenuRadiusScale, boolean selected) {
		double base = menuIndex == 0
			? (rootDistance > 0.0 ? rootDistance : viewportOrbit)
			: viewportOrbit * submenuRadiusScale;
		return base + (selected ? ORBIT_SELECTED_PUSH : 0.0);
	}

	/** Backdrop outer radius: the base node orbit plus one focus margin. */
	static double sectorRadius(double nodeRadius) {
		return nodeRadius + SECTOR_PADDING;
	}

	/**
	 * One boundary divider per adjacent choice pair whose end and start meet at
	 * the same bearing, drawn from {@code innerRadius} to {@code outerRadius}
	 * relative to the menu origin. A menu with fewer than two choices has no
	 * adjacent pair, so a single full disc never gains a phantom divider, and a
	 * gap between neighbours never gains a line either. Each shared bearing is
	 * emitted exactly once, including the pair that wraps past bearing 0.
	 */
	static List<GuideLine> menuBoundaries(List<SpatialController.ChoiceView> choices,
		double innerRadius, double outerRadius) {
		if (choices.size() < 2 || !(innerRadius > 0.0) || !(outerRadius > innerRadius)) {
			return List.of();
		}
		List<GuideLine> boundaries = new ArrayList<>(choices.size());
		for (int i = 0; i < choices.size(); i++) {
			SpatialController.ChoiceView choice = choices.get(i);
			SpatialController.ChoiceView previous = choices.get((i + choices.size() - 1) % choices.size());
			double start = normalizeDegrees(choice.startDegrees());
			double previousEnd = normalizeDegrees(previous.startDegrees() + previous.spanDegrees());
			if (angularDistance(start, previousEnd) > SHARED_BOUNDARY_TOLERANCE_DEGREES) {
				continue;
			}
			double radians = Math.toRadians(start);
			double sine = Math.sin(radians);
			double cosine = -Math.cos(radians);
			boundaries.add(new GuideLine(sine * innerRadius, cosine * innerRadius,
				sine * outerRadius, cosine * outerRadius));
		}
		return List.copyOf(boundaries);
	}

	/**
	 * Closed polyline of the deadzone circle around the menu origin, starting
	 * straight up (bearing 0) and walking clockwise. The last segment returns
	 * exactly to the first vertex; a non-positive or non-finite radius yields no
	 * segments.
	 */
	static List<GuideLine> circleSegments(double radius) {
		if (!(radius > 0.0) || !Double.isFinite(radius)) {
			return List.of();
		}
		List<GuideLine> segments = new ArrayList<>(GUIDE_CIRCLE_SAMPLES);
		for (int i = 0; i < GUIDE_CIRCLE_SAMPLES; i++) {
			double startRadians = TWO_PI * i / GUIDE_CIRCLE_SAMPLES;
			double endRadians = i + 1 == GUIDE_CIRCLE_SAMPLES ? 0.0 : TWO_PI * (i + 1) / GUIDE_CIRCLE_SAMPLES;
			segments.add(new GuideLine(Math.sin(startRadians) * radius, -Math.cos(startRadians) * radius,
				Math.sin(endRadians) * radius, -Math.cos(endRadians) * radius));
		}
		return List.copyOf(segments);
	}

	private static double normalizeDegrees(double degrees) {
		return ((degrees % 360.0) + 360.0) % 360.0;
	}

	private static double angularDistance(double first, double second) {
		double difference = Math.abs(first - second) % 360.0;
		return Math.min(difference, 360.0 - difference);
	}

	/** Historical trail lies above underlays/guides but below ALL text-bearing paints. */
	static int paintRank(Object data) {
		if (data instanceof SectorPaint || data instanceof InventoryBackSectorPaint) return 0;
		if (data instanceof GuidePaint || data instanceof LinkPaint) return 1;
		if (data instanceof TrailPaint) return 2;
		if (data instanceof PanelPaint) return 3;
		if (data instanceof ChromePaint) return 6; // current-frame successful nodes decide pointer visibility
		return 4;
	}

	/** Only the current menu's current focus is promoted; detached former focus never covers it. */
	static int paintRank(SpatialOverlayTransitions.State<VisualKey, Paint> state, String activeMenu) {
		return state.present() && state.key().owner().equals(activeMenu)
			&& state.data() instanceof NodePaint node && node.selected() ? 5 : paintRank(state.data());
	}

	private static void addInventory(List<SpatialOverlayTransitions.Target<VisualKey, Paint>> targets,
		SpatialInventoryView view, FramePainter painter, Style style, double guiWidth, double guiHeight, boolean listActive) {
		var metrics = SpatialInventoryLayout.measure(view, guiWidth, guiHeight, painter.lineHeight(), style.inventoryTextScale(),
			style.inventoryMaxWidthFraction(), style.inventoryMaxHeightFraction(), painter::width);
		view = metrics.window(view);
		SpatialInventoryLayout layout = metrics.layout(view, guiWidth / 2.0, guiHeight / 2.0);
		var source = new InventoryGeometry(view, metrics.layout(view, 0, 0));
		double rowHeight = layout.rowHeight();
		double headerHeight = layout.headerHeight();
		double footerHeight = layout.footerHeight();
		double x = layout.centerX();
		double y = layout.centerY();
		String owner = view.listKey();
		// An empty active list still emphasizes its panel and header; under an item
		// submenu the list drops to the resting inventory coverage.
		double surfaceCoverage = backgroundCoverage(BOX_BACKGROUND_PERCENT,
			listActive ? INVENTORY_ACTIVE_GAIN : INVENTORY_IDLE_GAIN, 1.0);
		// Row/window changes resize the same surface, not a second fading panel
		// whose stale content width could overlap an already-relayouted item child.
		add(targets, new VisualKey("panel", owner, ""), x, y, 1.0, 1.0, surfaceCoverage,
			x, y + 8.0, new PanelPaint(layout.width(), layout.height(), layout.headerHeight(), source));
		String position = view.selectedIndex() >= 0 && view.selectedIndex() < view.rows().size()
			? (view.selectedIndex() + 1) + " / " + view.rows().size() : "";
		double headerY = y - layout.height() / 2.0 + headerHeight / 2.0;
		add(targets, new VisualKey("header", owner, view.status().name()), x, headerY, 1.0, 1.0, surfaceCoverage,
			x, headerY + 4.0, new HeaderPaint(layout.width(), headerHeight, position, view.status(), source));
		// The complete window, header and footer keep their full layout; while an
		// item submenu is active only the entered row is planned at all.
		String retainedRowKey = view.retainedRowKey();
		for (int i = layout.first(); i < layout.first() + layout.count(); i++) {
			SpatialInventoryView.Row row = view.rows().get(i);
			if (retainedRowKey != null && !retainedRowKey.equals(row.key())) {
				continue;
			}
			double rowY = layout.rowCenterY(i);
			boolean selected = i == view.selectedIndex();
			double rowCoverage = selected
				? backgroundCoverage(BOX_BACKGROUND_PERCENT, selectedRowGain(view, listActive), 1.0) : 0.0;
			add(targets, new VisualKey("row", owner, row.key()), x, rowY, 1.0, 1.0, rowCoverage, x, rowY + 6.0,
				new RowPaint(row, layout.width(), rowHeight, selected,
					view.status().grey() || row.quality().grey(),
					listActive && selected && view.selectedRowCommittable() && !view.backAffordance().focused()
						? row.outlineColor() : null, source));
		}
		double footerY = y + layout.height() / 2.0 - footerHeight / 2.0;
		add(targets, new VisualKey("footer", owner, ""), x, footerY, 1.0, 1.0, 0.0, x, footerY + 4.0,
			new FooterPaint(layout.width(), footerHeight, view.backLeft(), view.backAffordance(),
				view.forwardAvailable(), source));
		var back = inventoryBackLayout(layout, painter, style, view.backLeft());
		boolean focused = listActive && view.backAffordance().focused();
		var affordance = focused ? view.backAffordance() : SpatialInventoryView.BackAffordance.NONE;
		add(targets, new VisualKey("inventory-back-sector", owner, ""), back.originX(), y, 1.0, 1.0,
			backgroundCoverage(SECTOR_BACKGROUND_PERCENT, focused ? SECTOR_FOCUSED_GAIN : 1.0, 1.0),
			back.originX(), y, new InventoryBackSectorPaint(back.radius(), view.backLeft() ? 180.0 : 0.0, source));
		add(targets, new VisualKey("inventory-back", owner, ""), back.frameX(), y, 1.0, 1.0,
			backgroundCoverage(BOX_BACKGROUND_PERCENT, 1.0, 1.0), back.frameX(), y,
			new InventoryBackPaint(Component.translatable("pingforit.spatial.inventory.back"), back.width(), back.height(), affordance, source));
	}

	/** Back's half-disc starts just outside the panel; its measured frame stays within that outward half. */
	record InventoryBackLayout(double originX, double frameX, double radius, double width, double height) {}
	static InventoryBackLayout inventoryBackLayout(SpatialInventoryLayout panel, FramePainter painter, Style style, boolean backLeft) {
		var frame = nodeLayout(painter.width(Component.translatable("pingforit.spatial.inventory.back")),
			painter.lineHeight(), style.optionTextScale());
		double sign = backLeft ? -1.0 : 1.0;
		double origin = panel.centerX() + sign * (panel.width() / 2.0 + NODE_TEXT_INSET);
		double distance = frame.width() / 2.0 + NODE_TEXT_INSET;
		double radius = Math.max(ORBIT_MIN, Math.hypot(distance + frame.width() / 2.0, frame.height() / 2.0) + NODE_TEXT_INSET);
		return new InventoryBackLayout(origin, origin + sign * distance, radius, frame.width(), frame.height());
	}

	/** Re-measure detached content under the same viewport, retaining its finite fade and rigid projection. */
	private static SpatialOverlayTransitions.State<VisualKey, Paint> remeasureInventory(
		SpatialOverlayTransitions.State<VisualKey, Paint> state, double width, double height, FramePainter painter, Style style,
		Map<InventoryGeometry, SpatialInventoryLayout> layouts) {
		if (state.present()) return state; // current targets already share the freshly measured metrics
		InventoryGeometry source = switch (state.data()) {
			case PanelPaint value -> value.geometry();
			case RowPaint value -> value.geometry();
			case HeaderPaint value -> value.geometry();
			case FooterPaint value -> value.geometry();
			case InventoryBackPaint value -> value.geometry();
			case InventoryBackSectorPaint value -> value.geometry();
			default -> null;
		};
		if (source == null) return state;
		var next = layouts.computeIfAbsent(source, value -> SpatialInventoryLayout.measure(value.view(), width, height,
			painter.lineHeight(), style.inventoryTextScale(), style.inventoryMaxWidthFraction(), style.inventoryMaxHeightFraction(),
			painter::width).layout(value.view(), 0, 0));
		var previous = source.layout();
		if (next.equals(previous) && !(state.data() instanceof InventoryBackPaint)
			&& !(state.data() instanceof InventoryBackSectorPaint)) return state;
		double dx = next.centerX() - previous.centerX();
		double dy = next.centerY() - previous.centerY();
		Paint paint = state.data();
		if (paint instanceof PanelPaint) {
			paint = new PanelPaint(next.width(), next.height(), next.headerHeight(), source);
		} else if (paint instanceof HeaderPaint value) {
			dy += (-next.height() + next.headerHeight() + previous.height() - previous.headerHeight()) / 2.0;
			paint = new HeaderPaint(next.width(), next.headerHeight(), value.position(), value.status(), source);
		} else if (paint instanceof FooterPaint value) {
			dy += (next.height() - next.footerHeight() - previous.height() + previous.footerHeight()) / 2.0;
			paint = new FooterPaint(next.width(), next.footerHeight(), value.backLeft(), value.back(), value.forwardAvailable(), source);
		} else if (paint instanceof RowPaint value) {
			int index = -1;
			for (int i = 0; i < source.view().rows().size(); i++)
				if (source.view().rows().get(i).key().equals(value.row().key())) { index = i; break; }
			if (index < next.first() || index >= next.first() + next.count()) return null;
			dy = next.rowCenterY(index) - previous.rowCenterY(index);
			paint = new RowPaint(value.row(), next.width(), next.rowHeight(), value.selected(), value.grey(), value.outlineColor(), source);
		} else {
			var oldBack = inventoryBackLayout(previous, painter, style, source.view().backLeft());
			var nextBack = inventoryBackLayout(next, painter, style, source.view().backLeft());
			if (paint instanceof InventoryBackPaint value) {
				dx = nextBack.frameX() - oldBack.frameX();
				paint = new InventoryBackPaint(value.label(), nextBack.width(), nextBack.height(), value.back(), source);
			} else if (paint instanceof InventoryBackSectorPaint value) {
				dx = nextBack.originX() - oldBack.originX();
				paint = new InventoryBackSectorPaint(nextBack.radius(), value.startDegrees(), source);
			}
		}
		return new SpatialOverlayTransitions.State<>(state.key(), state.x() + dx, state.y() + dy,
			state.scale(), state.alpha(), state.present(), paint, state.backgroundAlpha());
	}

	/**
	 * Selected-row background gain: only an active list whose selected row is
	 * committable and whose Back affordance is unfocused emphasizes. The
	 * session-owned committability is never reconstructed from Forward
	 * availability or the grey status.
	 */
	static double selectedRowGain(SpatialInventoryView view, boolean listActive) {
		return listActive && view.selectedRowCommittable() && !view.backAffordance().focused()
			? INVENTORY_ACTIVE_GAIN : INVENTORY_IDLE_GAIN;
	}

	/**
	 * Logical target geometry using the same font metrics as painting. All layout
	 * coordinates are absolute GUI pixels and are not polluted by the view
	 * translation. For a logical submenu origin the facade converts
	 * rowCenterY(selectedIndex) minus guiHeight / 2 exactly once; drawFrame's
	 * RowLayout instead reports the animated displayed row position after the
	 * rigid view translation.
	 */
	public static SpatialInventoryLayout inventoryLayout(SpatialInventoryView view, double guiWidth, double guiHeight,
		int fontLineHeight, Style style) {
		Font font = font();
		ToIntFunction<FormattedText> widths = font == null ? text -> 0 : font::width;
		return inventoryLayout(view, guiWidth, guiHeight, fontLineHeight, style, widths);
	}

	/** Explicit font-width port for independent/headless callers; native measurement uses the same function. */
	public static SpatialInventoryLayout inventoryLayout(SpatialInventoryView view, double guiWidth, double guiHeight,
		int fontLineHeight, Style style, ToIntFunction<FormattedText> widths) {
		return SpatialInventoryLayout.measure(view, guiWidth, guiHeight, fontLineHeight, style.inventoryTextScale(),
			style.inventoryMaxWidthFraction(), style.inventoryMaxHeightFraction(), widths)
			.layout(view, guiWidth / 2.0, guiHeight / 2.0);
	}

	/**
	 * Sole renderer-owned clearance metric: the distance from the inventory
	 * panel's forward edge to the item menu's centre at which the item menu's
	 * geometry and its toward-panel Back frame stay clear of the panel.
	 *
	 * <p>The result is at least the larger of {@code 1.1 * outerSectorRadius} —
	 * the non-root item orbit including the selected push and its sector focus
	 * margin — and the pushed Back-node centre distance plus the rounded title
	 * frame's larger half extent at the maximum existing Back scale
	 * ({@link #NODE_SELECTED_SCALE}; Back is never an eligible 1.2 follower)
	 * plus a conservative rounding margin. The caller measures the actual
	 * localized Back label; its frame uses the option text scale, never the
	 * inventory scale, the deadzone or a fixture width. Session and runtime may
	 * only consume the resulting distance.
	 */
	public static double itemMenuClearance(int guiWidth, int guiHeight, int fontLineHeight,
		double backLabelWidth, Style style) {
		if (guiWidth <= 0 || guiHeight <= 0) {
			throw new IllegalArgumentException("gui dimensions must be positive");
		}
		if (fontLineHeight <= 0) {
			throw new IllegalArgumentException("fontLineHeight must be positive");
		}
		if (!Double.isFinite(backLabelWidth) || backLabelWidth < 0.0) {
			throw new IllegalArgumentException("back label width must be finite and non-negative");
		}
		double orbit = orbitFor(Math.min(guiWidth, guiHeight));
		double backCenter = nodeRadius(1, orbit, 0.0, style.submenuRadiusScale(), true);
		double outerBound = CLEARANCE_OUTER_FACTOR * sectorRadius(backCenter);
		NodeLayout back = nodeLayout(backLabelWidth, fontLineHeight, style.optionTextScale());
		double halfExtent = Math.max(Math.max(Math.abs(back.left()), back.right()),
			Math.max(Math.abs(back.top()), back.bottom()));
		double backBound = backCenter + halfExtent * NODE_SELECTED_SCALE + CLEARANCE_ROUNDING_MARGIN;
		return Math.max(outerBound, backBound);
	}

	private static void add(List<SpatialOverlayTransitions.Target<VisualKey, Paint>> targets,
		VisualKey key, double x, double y, double scale, double alpha, double backgroundAlpha,
		double entryX, double entryY, Paint paint) {
		targets.add(new SpatialOverlayTransitions.Target<>(key, x, y, scale, alpha, entryX, entryY, paint, null, backgroundAlpha));
	}

	private static void paint(GuiGraphics graphics, Font font,
		SpatialOverlayTransitions.State<VisualKey, Paint> state, Style style, NodeLayout layout) {
		double alpha = layerAlpha(state.data(), state.alpha(), style);
		double background = backgroundLayerAlpha(state.data(), state.backgroundAlpha(), style);
		if (alpha <= 0.0 && background <= 0.0) {
			return;
		}
		if (state.data() instanceof NodePaint node) {
			drawNode(graphics, font, node, state, alpha, layout, style);
			return;
		}
		if (isRadialUnderlay(state.data())) {
			drawRadialUnderlay(graphics, state, style);
			return;
		}
		if (state.data() instanceof GuidePaint guide) {
			drawGuides(graphics, guide, state, alpha);
			return;
		}
		if (state.data() instanceof ChromePaint chrome) {
			if (!chrome.pointerHidden()) drawPointer(graphics, chrome.snapshot().pointer(), state.x(), state.y(), alpha);
			return;
		}
		if (state.data() instanceof LinkPaint links) {
			drawMenuLinks(graphics, links.menus(), state.x(), state.y(), alpha);
			return;
		}
		if (state.data() instanceof TrailPaint trail) {
			for (TrailPixel pixel : trailPixels(trail.points(), state.x(), state.y(), alpha))
				graphics.fill(pixel.x(), pixel.y(), pixel.x() + 1, pixel.y() + 1, pixel.color());
			return;
		}
		var pose = graphics.pose();
		pose.pushPose();
		try {
			pose.translate(state.x(), state.y(), 0.0);
			pose.scale((float) state.scale(), (float) state.scale(), 1.0f);
			if (state.data() instanceof PanelPaint panel) {
				int left = (int) Math.round(-panel.width() / 2.0);
				int top = (int) Math.round(-panel.height() / 2.0);
				int right = (int) Math.round(panel.width() / 2.0);
				int bottom = (int) Math.round(panel.height() / 2.0);
				int fill = withBackgroundAlpha(PANEL_BACKGROUND, state.backgroundAlpha(), style.targetOpacityPercent());
				graphics.fill(left, top, right, bottom, fill);
				graphics.fill(left, top, right, top + (int) Math.round(panel.headerHeight()),
					withBackgroundAlpha(PANEL_HEADER_BACKGROUND, state.backgroundAlpha(), style.targetOpacityPercent()));
				strokeRect(graphics, left, top, right, bottom, withAlpha(BORDER_COLOR, alpha));
			} else if (state.data() instanceof RowPaint row) {
				paintRow(graphics, font, row, style, alpha, state.backgroundAlpha());
			} else if (state.data() instanceof HeaderPaint header) {
				double left = -header.width() / 2.0 + 4.0;
				double top = -header.height() / 2.0 + 3.0;
				drawInventoryText(graphics, font, Component.literal(header.position()), left, top, header.width() - 8.0,
					style.inventoryTextScale(), TEXT_DIMMED_COLOR, alpha);
				String key = header.status().translationKey();
				if (key != null) {
					drawInventoryText(graphics, font, Component.translatable(key), left,
						top + font.lineHeight * style.inventoryTextScale() + 2.0, header.width() - 8.0,
						style.inventoryTextScale(), TEXT_DIMMED_COLOR, alpha);
				}
			} else if (state.data() instanceof FooterPaint footer) {
				Component forward = Component.translatable("pingforit.spatial.inventory.forward");
				double available = footer.width() / 2.0 - 8.0;
				double y = -footer.height() / 2.0 + 2.0;
				if (footer.forwardAvailable()) {
					double textWidth = Math.min(available, font.width(forward) * style.inventoryTextScale());
					double x = footer.backLeft() ? footer.width() / 2.0 - 4.0 - textWidth : -footer.width() / 2.0 + 4.0;
					drawInventoryText(graphics, font, forward, x, y, available, style.inventoryTextScale(), TEXT_DIMMED_COLOR, alpha);
				}
			} else if (state.data() instanceof InventoryBackPaint back) {
				int left = (int) Math.round(-back.width() / 2.0), right = (int) Math.round(back.width() / 2.0);
				int top = (int) Math.round(-back.height() / 2.0), bottom = (int) Math.round(back.height() / 2.0);
				graphics.fill(left, top, right, bottom,
					withBackgroundAlpha(NODE_BACKGROUND, state.backgroundAlpha(), style.targetOpacityPercent()));
				strokeRect(graphics, left, top, right, bottom, withAlpha(BORDER_COLOR, alpha));
				drawText(graphics, font, back.label(), left + NODE_TEXT_INSET, -font.lineHeight * style.optionTextScale() / 2.0,
					style.optionTextScale(), back.back().focused() ? TEXT_COLOR : TEXT_DIMMED_COLOR, alpha);
				if (state.present() && back.back().focused()) drawProgressSegments(graphics,
					backProgressSegments(left, top, right, bottom, back.back().progress()), withAlpha(PROGRESS_COLOR, alpha));
			}
		} finally {
			pose.popPose();
		}
	}

	private static void paintRow(GuiGraphics graphics, Font font, RowPaint paint, Style style,
		double alpha, double backgroundAlpha) {
		SpatialInventoryView.Row row = paint.row();
		double textScale = style.inventoryTextScale();
		int left = (int) Math.round(-paint.width() / 2.0);
		int right = (int) Math.round(paint.width() / 2.0);
		int top = (int) Math.round(-paint.height() / 2.0);
		paintRowFills(paint, style, alpha, backgroundAlpha, graphics::fill);
		double textLeft = left + 5.0;
		ClientItemDisplay.Display display = rowDisplay(row);
		ItemStack stack = display.stack();
		if (!stack.isEmpty()) {
			float tint = paint.grey() ? 0.65f : 1.0f;
			SpatialItemIconRenderer.draw(graphics, stack, (int) textLeft, -ITEM_ICON_SIZE / 2, tint, (float) alpha);
			textLeft += ITEM_ICON_SIZE + 3.0;
		}
		Component quantity = SpatialInventoryLayout.quantity(row.count());
		double quantityWidth = Math.min(font.width(quantity) * textScale,
			Math.max(0.0, (right - 4.0 - textLeft) / 2.0));
		double textTop = top + 2.0;
		int textColor = paint.grey() ? TEXT_DISABLED_COLOR : paint.selected() ? TEXT_COLOR : TEXT_DIMMED_COLOR;
		drawInventoryText(graphics, font, display.name(), textLeft, textTop,
			Math.max(0.0, right - 8.0 - quantityWidth - textLeft), textScale, textColor, alpha);
		drawInventoryText(graphics, font, quantity, right - 4.0 - quantityWidth, textTop, quantityWidth, textScale, textColor, alpha);
		String qualityKey = row.quality().translationKey();
		if (qualityKey != null) {
			drawInventoryText(graphics, font, Component.translatable(qualityKey), textLeft,
				textTop + font.lineHeight * textScale + 1.0, right - 4.0 - textLeft,
				textScale, TEXT_DISABLED_COLOR, alpha);
		}
	}

	/** The production row decoration draw seam, in the row's already-transformed local coordinates. */
	@FunctionalInterface
	interface FillPainter {
		void fill(int left, int top, int right, int bottom, int color);
	}

	static void paintRowFills(RowPaint paint, Style style, double alpha, double backgroundAlpha, FillPainter painter) {
		int left = (int) Math.round(-paint.width() / 2.0);
		int right = (int) Math.round(paint.width() / 2.0);
		int top = (int) Math.round(-paint.height() / 2.0);
		int bottom = (int) Math.round(paint.height() / 2.0);
		int background = withBackgroundAlpha(ROW_SELECTED_BACKGROUND, backgroundAlpha, style.targetOpacityPercent());
		// Selection changes the foreground immediately, but the old overlay keeps
		// drawing until its independent displayed coverage quantizes to zero.
		if ((background >>> 24) != 0) painter.fill(left + 1, top, right - 1, bottom, background);
		if (paint.outlineColor() != null) {
			int color = withAlpha(0xFF000000 | paint.outlineColor(), alpha);
			if ((color >>> 24) != 0) {
				painter.fill(left + 1, top, right - 1, top + 1, color);
				painter.fill(left + 1, bottom - 1, right - 1, bottom, color);
				painter.fill(left + 1, top + 1, left + 2, bottom - 1, color);
				painter.fill(right - 2, top + 1, right - 1, bottom - 1, color);
			}
		} else if (paint.selected()) {
			painter.fill(left + 1, top, left + 2, bottom, withAlpha(BORDER_SELECTED_FALLBACK, alpha));
		}
	}

	/** Fixed configured text scale; clipping is a content operation, never a font-size adjustment. */
	record InventoryText(FormattedText text, double scale, double width) {}
	static InventoryText inventoryText(Component label, double width, double scale,
		ToIntFunction<FormattedText> widths, StringSplitter splitter) {
		int budget = Math.max(0, (int) Math.floor(width / scale));
		FormattedText text = label;
		if (widths.applyAsInt(label) > budget) {
			Component ellipsis = Component.literal("…");
			int suffix = widths.applyAsInt(ellipsis);
			text = suffix > budget ? FormattedText.EMPTY : FormattedText.composite(
				splitter.headByWidth(label, budget - suffix, net.minecraft.network.chat.Style.EMPTY), ellipsis);
		}
		return new InventoryText(text, scale, Math.max(0.0, width));
	}

	private static void drawInventoryText(GuiGraphics graphics, Font font, Component label, double x, double y,
		double width, double scale, int color, double alpha) {
		if (width <= 0.0) return;
		InventoryText text = inventoryText(label, width, scale, font::width, font.getSplitter());
		var transform = graphics.pose().last().pose();
		var near = transform.transformPosition((float) x, (float) y, 0.0f, new org.joml.Vector3f());
		var far = transform.transformPosition((float) (x + width), (float) (y + font.lineHeight * scale), 0.0f, new org.joml.Vector3f());
		graphics.enableScissor((int) Math.ceil(near.x()), (int) Math.floor(near.y()),
			Math.max((int) Math.ceil(near.x()), (int) Math.floor(far.x())), (int) Math.ceil(far.y()));
		try {
			drawText(graphics, font, Language.getInstance().getVisualOrder(text.text()), x, y, text.scale(), color, alpha);
		} finally {
			graphics.disableScissor();
		}
	}

	private static void drawText(GuiGraphics graphics, Font font, Component label, double x, double y,
		double scale, int color, double alpha) {
		drawText(graphics, font, label.getVisualOrderText(), x, y, scale, color, alpha);
	}

	private static void drawText(GuiGraphics graphics, Font font, FormattedCharSequence label, double x, double y,
		double scale, int color, double alpha) {
		int faded = withAlpha(color, alpha);
		int channel = faded >>> 24;
		if (channel == 0) {
			return; // Minecraft would promote a transparent font colour to opaque.
		}
		if (channel < 4) {
			faded = (faded & 0x00FFFFFF) | 0x04000000;
		}
		var pose = graphics.pose();
		pose.pushPose();
		try {
			pose.translate(x, y, 0.0);
			pose.scale((float) scale, (float) scale, 1.0f);
			graphics.drawString(font, label, 0, 0, faded, false);
		} finally {
			pose.popPose();
		}
	}

	private static void drawNode(
		GuiGraphics guiGraphics,
		Font font,
		NodePaint node,
		SpatialOverlayTransitions.State<VisualKey, Paint> state,
		double alpha,
		NodeLayout layout,
		Style style
	) {
		SpatialController.ChoiceView choice = node.choice();
		boolean selected = node.selected();
		Component label = nodeLabel(choice, node.label());
		int left = layout.left();
		int top = layout.top();
		int right = layout.right();
		int bottom = layout.bottom();

		var pose = guiGraphics.pose();
		pose.pushPose();

		try {
			pose.translate(state.x(), state.y(), 0.0);
			pose.scale((float) state.scale(), (float) state.scale(), 1.0f);
			// Independent background coverage: the box's 60% layer by itself,
			// never multiplied into the foreground border/text alpha below.
			guiGraphics.fill(left, top, right, bottom,
				withBackgroundAlpha(selected ? NODE_SELECTED_BACKGROUND : NODE_BACKGROUND,
					state.backgroundAlpha(), style.targetOpacityPercent()));

			int borderColor = nodeBorderColor(choice, selected);

			if (choice.disabled() || choice.reserved()) {
				dashedRect(guiGraphics, left, top, right, bottom, withAlpha(borderColor, alpha * DISABLED_ALPHA_FACTOR));
			} else {
				strokeRect(guiGraphics, left, top, right, bottom, withAlpha(borderColor, alpha));
			}

			int textColor = choice.disabled() ? TEXT_DISABLED_COLOR : selected ? TEXT_COLOR : TEXT_DIMMED_COLOR;
			drawText(guiGraphics, font, label, left + 4.0,
				layout.titleTop(font.lineHeight), layout.titleScale(), textColor, alpha);
			if (layout.detail() != null) {
				DetailLayout detail = layout.detail();
				for (int i = 0; i < detail.lines().size(); i++) {
					DetailLine line = detail.lines().get(i);
					drawText(guiGraphics, font, Language.getInstance().getVisualOrder(line.text()),
						line.left(detail.scale()), detail.top() + i * font.lineHeight * detail.scale(),
						detail.scale(), textColor, alpha);
				}
			}

			if (choice.back() && selected && state.present() && node.hoverProgress() > 0.0) {
				drawProgressSegments(
					guiGraphics,
					backProgressSegments(left, top, right, bottom, node.hoverProgress()),
					withAlpha(PROGRESS_COLOR, alpha));
			}
		} finally {
			pose.popPose();
		}
	}

	/** Keeps the renderer defensive when an older projection still supplies no Back label. */
	private static Component nodeLabel(SpatialController.ChoiceView choice, Component resolved) {
		if (resolved != null && !resolved.getString().isBlank()) return resolved;
		return choice.back() ? Component.translatable("pingforit.spatial.back") : Component.empty();
	}

	/**
	 * Returns a progress path over the exact node frame, rather than a square
	 * derived from the frame's width or height alone. The frame is painted by
	 * {@code fill(left, top, right, bottom)} plus a one-pixel
	 * {@code strokeRect}, so right and bottom are exclusive bounds: the path
	 * must end on the last painted column and row, {@code right - 1} and
	 * {@code bottom - 1}, or {@link #line} paints one pixel past the border.
	 */
	static List<SpatialSquareProgress.Segment> backProgressSegments(
		int left, int top, int right, int bottom, double progress) {
		int lastX = right - BORDER_THICKNESS;
		int lastY = bottom - BORDER_THICKNESS;
		if (!Double.isFinite(progress) || progress <= 0.0 || lastX <= left || lastY <= top) {
			return List.of();
		}
		double clamped = Math.min(1.0, progress);
		double[] xs = {left, lastX, lastX, left, left};
		double[] ys = {top, top, lastY, lastY, top};
		double[] lengths = {lastX - left, lastY - top, lastX - left, lastY - top};
		double perimeter = 0.0;
		for (double length : lengths) perimeter += length;
		double remaining = perimeter * clamped;
		List<SpatialSquareProgress.Segment> result = new ArrayList<>(4);
		for (int i = 0; i < lengths.length && remaining > 0.0; i++) {
			double fraction = Math.min(1.0, remaining / lengths[i]);
			result.add(new SpatialSquareProgress.Segment(xs[i], ys[i],
				xs[i] + (xs[i + 1] - xs[i]) * fraction,
				ys[i] + (ys[i + 1] - ys[i]) * fraction));
			remaining -= lengths[i] * fraction;
		}
		return List.copyOf(result);
	}

	private static void drawProgressSegments(GuiGraphics graphics,
		List<SpatialSquareProgress.Segment> segments, int color) {
		for (SpatialSquareProgress.Segment segment : segments) {
			line(graphics, segment.x1(), segment.y1(), segment.x2(), segment.y2(), color, BORDER_THICKNESS);
		}
	}

	static boolean isRadialUnderlay(Object data) {
		return data instanceof SectorPaint || data instanceof InventoryBackSectorPaint;
	}

	/** Primitive draw port shared by native submission and recording production-frame tests. */
	@FunctionalInterface
	interface RadialUnderlayPainter {
		void draw(double radius, double startDegrees, double spanDegrees, int rgb, double coverage, double opacityPercent);
	}

	/** Only circular underlays take this route; rectangular backgrounds and foreground never do. */
	static void paintRadialUnderlay(SpatialOverlayTransitions.State<VisualKey, Paint> state, Style style,
		RadialUnderlayPainter painter) {
		if (!isRadialUnderlay(state.data()) || backgroundChannel(state.backgroundAlpha(), style.opacityPercent()) == 0) return;
		if (state.data() instanceof SectorPaint sector) {
			painter.draw(sector.radius(), sector.choice().startDegrees(), sector.choice().spanDegrees(),
				SECTOR_BACKGROUND, state.backgroundAlpha(), style.opacityPercent());
		} else if (state.data() instanceof InventoryBackSectorPaint sector) {
			painter.draw(sector.radius(), sector.startDegrees(), sector.spanDegrees(),
				SECTOR_BACKGROUND, state.backgroundAlpha(), style.opacityPercent());
		}
	}

	private static void drawRadialUnderlay(GuiGraphics graphics,
		SpatialOverlayTransitions.State<VisualKey, Paint> state, Style style) {
		var pose = graphics.pose();
		pose.pushPose();
		try {
			pose.translate(state.x(), state.y(), 0.0);
			pose.scale((float) state.scale(), (float) state.scale(), 1.0f);
			paintRadialUnderlay(state, style, (radius, start, span, rgb, coverage, opacity) ->
				RadialGradient.draw(graphics, radius, start, span, rgb, coverage, opacity));
		} finally {
			pose.popPose();
		}
	}

	/**
	 * Paints one menu's centre guides as straight one-pixel strokes at the
	 * menu's own origin, under the same rigid view translation as its backdrop.
	 * The shared guide grey is already combined with the transition-fade alpha.
	 */
	private static void drawGuides(GuiGraphics graphics, GuidePaint guide,
		SpatialOverlayTransitions.State<VisualKey, Paint> state, double alpha) {
		int color = withAlpha(GUIDE_COLOR, alpha);
		if ((color >>> 24) == 0) {
			return;
		}
		var pose = graphics.pose();
		pose.pushPose();
		try {
			pose.translate(state.x(), state.y(), 0.0);
			pose.scale((float) state.scale(), (float) state.scale(), 1.0f);
			for (GuideLine boundary : guide.boundaries()) {
				line(graphics, boundary.x1(), boundary.y1(), boundary.x2(), boundary.y2(), color, BORDER_THICKNESS);
			}
			for (GuideLine segment : guide.circle()) {
				line(graphics, segment.x1(), segment.y1(), segment.x2(), segment.y2(), color, BORDER_THICKNESS);
			}
		} finally {
			pose.popPose();
		}
	}

	/**
	 * Pure scanline probe for one backdrop row, in coordinates relative to the
	 * sector origin: {@code bounds[0]} is the leftmost and {@code bounds[1]} the
	 * rightmost covered x, and {@code bounds[0] > bounds[1]} means the row is
	 * empty. Exact for the supported spans the controller emits: a convex sector
	 * of at most a half turn, or a full disc. Retained for CPU geometry callers;
	 * native underlay painting uses the radial vertex mesh instead.
	 */
	static double[] sectorRowBounds(double dy, double radius, double startDegrees, double spanDegrees) {
		double[] bounds = new double[2];
		sectorRow(dy, radius, startDegrees, spanDegrees, bounds);
		return bounds;
	}

	private static void sectorRow(double dy, double radius, double startDegrees, double spanDegrees, double[] bounds) {
		bounds[0] = Double.POSITIVE_INFINITY;
		bounds[1] = Double.NEGATIVE_INFINITY;
		if (!(radius > 0.0) || !(spanDegrees > 0.0)) {
			return;
		}
		double halfWidthSquared = radius * radius - dy * dy;
		if (halfWidthSquared < 0.0) {
			return;
		}
		double halfWidth = Math.sqrt(halfWidthSquared);
		// The row through the origin meets the shared sector vertex; the covered
		// side is decided by the row's own horizontal bearings.
		if (dy == 0.0) {
			boolean right = bearingWithin(90.0, startDegrees, spanDegrees);
			boolean left = bearingWithin(-90.0, startDegrees, spanDegrees);
			if (right || left) {
				bounds[0] = left ? -halfWidth : 0.0;
				bounds[1] = right ? halfWidth : 0.0;
			}
			return;
		}
		// A convex sector's row interval ends on the circle or on one of its two
		// radial edges, so the inside candidates bound the interval exactly.
		considerRowPoint(bounds, -halfWidth, dy, radius, startDegrees, spanDegrees);
		considerRowPoint(bounds, halfWidth, dy, radius, startDegrees, spanDegrees);
		for (int edge = 0; edge < 2; edge++) {
			double radians = Math.toRadians(startDegrees + edge * spanDegrees);
			double cosine = Math.cos(radians);
			if (Math.abs(cosine) < 1.0e-9) {
				continue; // a horizontal edge only meets the origin row
			}
			double distance = -dy / cosine;
			if (distance < 0.0) {
				continue; // the edge points away from this row
			}
			considerRowPoint(bounds, distance * Math.sin(radians), dy, radius, startDegrees, spanDegrees);
		}
	}

	private static void considerRowPoint(double[] bounds, double x, double dy, double radius,
		double startDegrees, double spanDegrees) {
		if (x * x + dy * dy > radius * radius + 1.0e-9) {
			return;
		}
		if (!bearingWithin(Math.toDegrees(Math.atan2(x, -dy)), startDegrees, spanDegrees)) {
			return;
		}
		if (x < bounds[0]) bounds[0] = x;
		if (x > bounds[1]) bounds[1] = x;
	}

	private static boolean bearingWithin(double bearingDegrees, double startDegrees, double spanDegrees) {
		if (spanDegrees >= 360.0) {
			return true;
		}
		double offset = (bearingDegrees - startDegrees) % 360.0;
		if (offset < 0.0) offset += 360.0;
		if (offset >= 360.0 - 1.0e-9) offset = 0.0;
		return offset <= spanDegrees + 1.0e-9;
	}

	private static void drawMenuLinks(
		GuiGraphics guiGraphics,
		List<SpatialController.MenuView> menus,
		double centerX,
		double centerY,
		double alpha
	) {
		for (int i = 1; i < menus.size(); i++) {
			SpatialController.MenuView menu = menus.get(i);
			SpatialController.Point parent = menu.parentOrigin();
			SpatialController.Point child = menu.origin();

			line(
				guiGraphics,
				centerX + parent.x(),
				centerY + parent.y(),
				centerX + child.x(),
				centerY + child.y(),
				withAlpha(GUIDE_COLOR, alpha),
				BORDER_THICKNESS);
		}
	}

	record TrailPixel(int x, int y, int color) {}
	/** At most nine pixels per retained point; overlapping rounded squares use max alpha, never additive paint. */
	static List<TrailPixel> trailPixels(List<SpatialController.Point> trail, double centerX, double centerY, double alpha) {
		Map<Long, TrailPixel> pixels = new LinkedHashMap<>();
		int size = trail.size();

		for (int i = 0; i < size; i++) {
			SpatialController.Point point = trail.get(i);
			int x = (int) Math.round(centerX + point.x());
			int y = (int) Math.round(centerY + point.y());
			double progress = size <= 1 ? 1.0 : (double) i / (size - 1);
			int color = withAlpha(TRAIL_COLOR, alpha * (0.15 + 0.65 * progress));
			if ((color >>> 24) == 0) continue;
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
				int px = x + dx, py = y + dy;
				long key = ((long) px << 32) | (py & 0xFFFFFFFFL);
				TrailPixel old = pixels.get(key);
				if (old == null || (old.color() >>> 24) < (color >>> 24)) pixels.put(key, new TrailPixel(px, py, color));
			}
		}
		return List.copyOf(pixels.values());
	}

	private static void drawPointer(
		GuiGraphics guiGraphics,
		SpatialController.Point pointer,
		double centerX,
		double centerY,
		double alpha
	) {
		if (pointer == null) {
			return;
		}

		int x = (int) Math.round(centerX + pointer.x());
		int y = (int) Math.round(centerY + pointer.y());
		int arm = 5;

		int color = withAlpha(POINTER_COLOR, alpha);
		guiGraphics.fill(x - arm, y, x + arm + 1, y + 1, color);
		guiGraphics.fill(x, y - arm, x + 1, y + arm + 1, color);
		guiGraphics.fill(x - 1, y - 1, x + 2, y + 2, color);
	}

	/**
	 * Draws a straight line as one-pixel-step axis-aligned quads. The step never
	 * exceeds one pixel along the dominant axis, so the line stays continuous
	 * and the fill count is bounded by the line length.
	 */
	private static void line(
		GuiGraphics guiGraphics,
		double x1,
		double y1,
		double x2,
		double y2,
		int color,
		int thickness
	) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
		int half = thickness / 2;

		for (int i = 0; i <= steps; i++) {
			double t = steps == 0 ? 0.0 : (double) i / steps;
			int x = (int) Math.round(x1 + dx * t) - half;
			int y = (int) Math.round(y1 + dy * t) - half;
			guiGraphics.fill(x, y, x + thickness, y + thickness, color);
		}
	}

	private static void strokeRect(GuiGraphics guiGraphics, int left, int top, int right, int bottom, int color) {
		guiGraphics.fill(left, top, right, top + BORDER_THICKNESS, color);
		guiGraphics.fill(left, bottom - BORDER_THICKNESS, right, bottom, color);
		guiGraphics.fill(left, top, left + BORDER_THICKNESS, bottom, color);
		guiGraphics.fill(right - BORDER_THICKNESS, top, right, bottom, color);
	}

	private static void dashedRect(GuiGraphics guiGraphics, int left, int top, int right, int bottom, int color) {
		dashedLine(guiGraphics, left, top, right, top, color);
		dashedLine(guiGraphics, right, top, right, bottom, color);
		dashedLine(guiGraphics, right, bottom, left, bottom, color);
		dashedLine(guiGraphics, left, bottom, left, top, color);
	}

	private static void dashedLine(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2, int color) {
		int dash = 3;
		int gap = 3;
		double dx = x2 - x1;
		double dy = y2 - y1;
		int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));

		for (int i = 0; i <= steps; i++) {
			if (i % (dash + gap) >= dash) {
				continue;
			}

			double t = steps == 0 ? 0.0 : (double) i / steps;
			int x = (int) Math.round(x1 + dx * t);
			int y = (int) Math.round(y1 + dy * t);
			guiGraphics.fill(x, y, x + 1, y + 1, color);
		}
	}

	/**
	 * Border colour for one radial choice. A choice carrying a detached Ping Type
	 * outline uses that colour in both focus states; a choice without one keeps
	 * the legacy selected fallback (including its {@code ping:} action form) and
	 * the ordinary border colour when unfocused. The opaque action is never
	 * decoded for a typed choice.
	 */
	static int nodeBorderColor(SpatialController.ChoiceView choice, boolean selected) {
		if (choice.outlineColor() != null) {
			return 0xFF000000 | choice.outlineColor();
		}

		return selected ? accentColor(choice.action()) : BORDER_COLOR;
	}

	private static int accentColor(String action) {
		if (action != null && action.startsWith("ping:")) {
			return PingTypeCatalog.builtIn()
				.findById(action.substring("ping:".length()))
				.map(pingType -> 0xFF000000 | pingType.outlineColor())
				.orElse(BORDER_SELECTED_FALLBACK);
		}

		return BORDER_SELECTED_FALLBACK;
	}

	/**
	 * Production row display seam: the same resolution the row paint path uses.
	 * The encoded display value is decoded with the current client registry
	 * context, and a missing or failed value keeps the native bare stack and
	 * registry translation key.
	 */
	static ClientItemDisplay.Display rowDisplay(SpatialInventoryView.Row row) {
		if (row.display() != null) return new ClientItemDisplay.Display(row.display().icon(), row.display().name());
		return ClientItemDisplay.resolve(row.itemId(), row.label(), row.displayJson());
	}

	/** Explicit-registry form of the row display seam, used by headless tests. */
	static ClientItemDisplay.Display rowDisplay(SpatialInventoryView.Row row, HolderLookup.Provider registryAccess) {
		return ClientItemDisplay.resolve(registryAccess, row.itemId(), row.label(), row.displayJson());
	}

	private static Font font() {
		Minecraft game = Minecraft.getInstance();
		return game == null ? null : game.font;
	}

	/** Viewport-derived child orbit, clamped to the half-scale window. */
	static double orbitFor(double smallestGuiDimension) {
		return clamp(smallestGuiDimension * ORBIT_RATIO, ORBIT_MIN, ORBIT_MAX);
	}

	static int withAlpha(int color, double alpha) {
		int channel = (int) Math.round(clamp(alpha, 0.0, 1.0) * ((color >>> 24) & 0xFF));
		return (channel << 24) | (color & 0x00FFFFFF);
	}

	/**
	 * Exponential smoothing fraction for one bounded elapsed interval. A
	 * negative clock delta clamps to zero and an exceptionally long gap clamps to
	 * {@link #MAX_FRAME_MILLIS}, so a paused or rewound clock can never snap a
	 * node to its target. Because the fraction is purely time based,
	 * {@code (1 - f(dt1)) * (1 - f(dt2))} equals {@code 1 - f(dt1 + dt2)} for any
	 * partition of the same interval.
	 */
	static double smoothingFactor(double elapsedMillis, double ratePerMilli) {
		double bounded = clamp(elapsedMillis, 0.0, MAX_FRAME_MILLIS);
		return 1.0 - Math.exp(-ratePerMilli * bounded);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

}
