package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import nx.pingwheel.common.client.spatial.InventoryListModel;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.wheel.WheelLabelLayout;
import nx.pingwheel.common.domain.PingTypeCatalog;

/**
 * Native Minecraft overlay for the spatial selector read models.
 *
 * <p>Every option is square and straight: options are framed by thin
 * rectangular borders, the pointer is a straight-line crosshair, the trail is a
 * series of small squares, and the Back hover progress is a square perimeter
 * walked in four straight segments. The one curved element is a semi-transparent
 * circular-sector backdrop painted beneath the radial nodes, using each entry's
 * own bearing span; a bearing still only positions a node along a straight
 * guide line.
 *
 * <p>Wheel opacity governs only the sector underlay; target opacity governs
	 * every text-bearing frame together with its label and external name preview. Neither preference reaches
 * the interaction chrome (pointer, guides, trail), which follows only the
 * transition fade.
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

	static final int BORDER_THICKNESS = 1;
	static final int ROW_HEIGHT = 12;
	static final int LIST_WIDTH = 200;
	static final int ITEM_ICON_SIZE = 16;
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
	/** Horizontal and vertical inset of node text inside its frame. */
	static final double NODE_TEXT_INSET = 4.0;
	/** Total vertical padding of the title frame: three pixels above and below. */
	static final double NODE_VERTICAL_PADDING = 6.0;
	/** Visible space between the title frame and its unframed candidate-name preview. */
	static final double NODE_DETAIL_GAP = 3.0;
	/** The Precise branch's fixed slots: the five target types plus Back. */
	static final int PRECISE_SLOT_COUNT = 6;
	/**
	 * Safe fraction of the adjacent-node chord for every Precise node's larger
	 * painted extent. With the selected 1.12 and ordinary 0.95 node scales,
	 * {@code 2.07 / 2 * 0.68 = 0.7038} stays below the 0.7071 minimum of
	 * {@code max(|dx|, |dy|)} between adjacent equal-sector nodes, so node
	 * frames cannot overlap at any menu rotation; the selected node's outward
	 * push adds further margin. Constrained layout also reserves local pixels
	 * for frame rounding and the disabled dashed border's inclusive endpoints.
	 */
	static final double PRECISE_NODE_EXTENT_FRACTION = 0.68;
	static final double ANIMATION_RATE_PER_MILLI = 0.02;
	static final double MAX_FRAME_MILLIS = 100.0;
	static final double ANCESTOR_ALPHA = 0.35;
	static final double DISABLED_ALPHA_FACTOR = 0.5;

	private static final long TRANSITION_NANOS = 160_000_000L;
	private static final int TRANSITION_CAPACITY = 512;
	private static final double APPEARANCE_SCALE = 0.92;
	private static final Session SHARED = new Session();

	/** Main-thread scratch row; backdrop painting must not allocate per frame. */
	private static final double[] SECTOR_ROW_BOUNDS = new double[2];

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
	 * its own width and height.
	 */
	public record Style(int opacityPercent, int targetOpacityPercent, double optionTextScale, double inventoryTextScale,
		double rootDistance, double submenuRadiusScale, boolean showTrail, boolean reduceMotion) {

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
			if (!Double.isFinite(optionTextScale) || optionTextScale <= 0.0
				|| !Double.isFinite(inventoryTextScale) || inventoryTextScale <= 0.0
				|| !Double.isFinite(rootDistance) || rootDistance < 0.0
				|| !Double.isFinite(submenuRadiusScale) || submenuRadiusScale <= 0.0) {
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
	}

	record VisualKey(String kind, String owner, String entry) {}
	sealed interface Paint permits NodePaint, PanelPaint, RowPaint, HeaderPaint, FooterPaint, ChromePaint, SectorPaint {}
	/**
	 * One node's paint payload. {@code targetDetail} is the caller-resolved
	 * external name preview, or {@code null} for a title-only node. The menu's shared
	 * {@code maxExtent} is retained even for disabled slots, Back and exit paint;
	 * an infinite extent keeps ordinary menus unconstrained.
	 */
	record NodePaint(SpatialController.ChoiceView choice, Component label, Component targetDetail,
		boolean selected, double hoverProgress, double maxExtent) implements Paint {

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
	record PanelPaint(double width, double height, double headerHeight) implements Paint {}
	record RowPaint(SpatialInventoryView.Row row, double width, double height, boolean selected,
		boolean grey) implements Paint {}
	record HeaderPaint(double width, double height, String position, SpatialInventoryView.Status status)
		implements Paint {}
	record FooterPaint(double width, double height, boolean backLeft,
		SpatialInventoryView.BackAffordance back) implements Paint {}
	record ChromePaint(SpatialController.Snapshot snapshot) implements Paint {}
	record SectorPaint(SpatialController.ChoiceView choice, double radius) implements Paint {}

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
		double contentWidth() { return right() - left() - NODE_TEXT_INSET * 2.0; }
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

	/** Production planning seam: menu membership, not detail availability, owns the constraint. */
	static NodeLayout nodeLayout(NodePaint node, ToIntFunction<FormattedText> widths, int lineHeight, Style style,
		StringSplitter splitter) {
		Component label = nodeLabel(node.choice(), node.label());
		double titleWidth = widths.applyAsInt(label);
		NodeLayout layout;
		if (!Double.isFinite(node.maxExtent())) {
			layout = nodeLayout(titleWidth, lineHeight, style.optionTextScale());
		} else {
			double extent = constrainedExtent(node.maxExtent());
			double titleScale = Math.min(style.optionTextScale(), (extent - NODE_VERTICAL_PADDING) / lineHeight);
			layout = new NodeLayout(Math.min(extent, Math.max(MIN_BOX_WIDTH,
				titleWidth * titleScale + NODE_TEXT_INSET * 2.0)),
				Math.min(extent, Math.max(BOX_HEIGHT, lineHeight * titleScale + NODE_VERTICAL_PADDING)), titleScale, null);
			layout = new NodeLayout(layout.width(), layout.height(),
				visibleNodeScale(label, widths, layout.contentWidth(), layout.titleScale(), style.optionTextScale()), null);
		}
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

	/** Fit one visible glyph, never the whole name; keep the height-fit and readable floors. */
	private static double visibleNodeScale(Component label, ToIntFunction<FormattedText> widths,
		double contentWidth, double scale, double preference) {
		var prefix = Component.empty();
		label.visit((textStyle, text) -> {
			int end = 0;
			while (end < text.length()) {
				int codePoint = text.codePointAt(end);
				end += Character.charCount(codePoint);
				if (!Character.isWhitespace(codePoint)) {
					prefix.append(Component.literal(text.substring(0, end)).withStyle(textStyle));
					return Optional.of(prefix);
				}
			}
			prefix.append(Component.literal(text).withStyle(textStyle));
			return Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		int firstWidth = widths.applyAsInt(prefix);
		if (firstWidth <= 0 || Math.floor(contentWidth / scale) >= firstWidth) return scale;
		// A resource-pack glyph or whitespace prefix wider than the frame even at
		// the floor cannot satisfy both constraints; retain the hard bounds/floor.
		double floor = Math.min(preference, WheelLabelLayout.BASE_TEXT_SCALE);
		return Math.min(scale, Math.max(floor, Math.nextDown(contentWidth / firstWidth)));
	}

	private static double constrainedExtent(double maxExtent) {
		// Integer rounding and a dashed edge can otherwise exceed the chord budget.
		return Math.max(MIN_BOX_WIDTH, Math.floor(maxExtent) - 2.0);
	}

	/** Same style-preserving font clipping used to paint a constrained node. */
	static FormattedText clippedNodeText(Component label, double width, double scale, StringSplitter splitter) {
		return splitter.headByWidth(label, Math.max(0, (int) Math.floor(width / scale)),
			net.minecraft.network.chat.Style.EMPTY);
	}

	/**
	 * Shared frame budget for every node of the Precise branch, also used as
	 * the external preview's wrapping cell. The adjacent chord of the six equal
	 * slots including Back bounds the frame's larger painted extent; the orbit
	 * and scale are the same visual geometry the nodes are placed with, so no
	 * radius or sector geometry changes. Preview glyphs are never frame-clipped.
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
		return inventoryView(listKey, model.snapshot(), model.visibleRows(), itemIds, status);
	}

	public static SpatialInventoryView inventoryView(String listKey, InventoryListModel.Snapshot snapshot,
		int visibleRows, Map<String, String> itemIds, SpatialInventoryView.Status status) {
		List<SpatialInventoryView.Row> rows = snapshot.entries().stream().map(entry ->
			new SpatialInventoryView.Row(entry.key(), entry.label(), entry.count(),
				itemIds == null ? null : itemIds.get(entry.key()), SpatialInventoryView.Status.fromName(entry.quality())))
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

		public void reset() {
			transitions.clear();
			viewOffset.clear();
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
			paintedChoiceIds = Set.of();
			if (graphics == null || style == null) {
				return RowLayout.NONE;
			}
			Font font = font();
			if (font == null) {
				return RowLayout.NONE;
			}
			List<SpatialOverlayTransitions.Target<VisualKey, Paint>> targets = new ArrayList<>();
			double guiWidth = graphics.guiWidth();
			double guiHeight = graphics.guiHeight();
			double centerX = guiWidth / 2.0;
			double centerY = guiHeight / 2.0;
			SpatialController.Point origin = activeOrigin(radial);
			sampleViewOffset(viewOffset, origin, nowNanos, style.reduceMotion());
			if (radial != null && radial.active()) {
				addRadial(targets, radial, labels == null ? TRANSLATION_KEY_LABELS : labels, details,
					centerX, centerY, orbit(graphics), style.rootDistance(), style.submenuRadiusScale());
			}
			if (inventory != null && inventory.open()) {
				addInventory(targets, inventory, font, style, guiWidth, guiHeight);
			}
			List<SpatialOverlayTransitions.State<VisualKey, Paint>> states =
				transitions.update(targets, nowNanos, style.reduceMotion());
			if (origin == null && transitions.isEmpty()) {
				// The exit tail is gone: hard-reset so a later session starts centered.
				viewOffset.clear();
			}
			RowLayout selected = RowLayout.NONE;
			Set<String> painted = new HashSet<>();
			String activeMenu = origin == null ? null : radial.menus().getLast().menuId();
			// Sector backdrops and replacement panels may be admitted after
			// surviving rows, so rank every layer explicitly: radial backdrops,
			// inventory panels, then rows, nodes, and chrome. The single view
			// translation wraps every layer so the whole pattern stays rigid.
			var pose = graphics.pose();
			pose.pushPose();
			try {
				pose.translate(viewOffset.x(), viewOffset.y(), 0.0);
				for (SpatialOverlayTransitions.State<VisualKey, Paint> state : states.stream()
					.sorted(Comparator.comparingInt(value -> paintRank(value.data()))).toList()) {
					if (state.present() && state.data() instanceof RowPaint row && row.selected()) {
						selected = new RowLayout(state.y() + viewOffset.y(), row.width() * state.scale());
					}
					if (renders(state.data(), state.alpha(), style)) {
						paint(graphics, font, state, style);
						if (state.data() instanceof NodePaint node && acknowledgesNodePaint(state.present(),
							state.key().owner().equals(activeMenu), state.alpha(), style))
							painted.add(node.choice().id());
					}
				}
			} finally {
				pose.popPose();
			}
			paintedChoiceIds = Set.copyOf(painted);
			return selected;
		}
	}

	/** Called only after successful node paint; detached/ancestor/fully transparent states cannot acknowledge. */
	static boolean acknowledgesNodePaint(boolean present, boolean activeMenu, double alpha, Style style) {
		return present && activeMenu && (withAlpha(BORDER_COLOR, targetAlpha(alpha, style)) >>> 24) != 0;
	}

	static void addRadial(List<SpatialOverlayTransitions.Target<VisualKey, Paint>> targets,
		SpatialController.Snapshot snapshot, Function<SpatialController.ChoiceView, Component> labels,
		Function<SpatialController.ChoiceView, Component> details,
		double centerX, double centerY, double orbit, double rootDistance, double submenuRadiusScale) {
		add(targets, new VisualKey("chrome", "radial", ""), centerX, centerY, 1.0, 1.0,
			centerX, centerY, new ChromePaint(snapshot));
		List<SpatialController.MenuView> menus = snapshot.menus();
		for (int i = 0; i < menus.size(); i++) {
			SpatialController.MenuView menu = menus.get(i);
			boolean active = i == menus.size() - 1;
			double menuX = centerX + menu.origin().x();
			double menuY = centerY + menu.origin().y();
			double maxExtent = menu.choices().stream().anyMatch(choice -> choice.id().startsWith("precise:"))
				? preciseNodeExtentBudget(orbit, submenuRadiusScale) : Double.POSITIVE_INFINITY;
			for (SpatialController.ChoiceView choice : menu.choices()) {
				double bearing = Math.toRadians(choice.startDegrees() + choice.spanDegrees() / 2.0);
				boolean selected = active && choice.id().equals(menu.focusId());
				double baseRadius = nodeRadius(i, orbit, rootDistance, submenuRadiusScale, false);
				double radius = baseRadius + (selected ? ORBIT_SELECTED_PUSH : 0.0);
				double alpha = nodeAlpha(active, selected, choice.disabled() || choice.reserved());
				add(targets, new VisualKey("sector", menu.menuId(), choice.id()),
					menuX, menuY, 1.0, alpha, menuX, menuY,
					new SectorPaint(choice, sectorRadius(baseRadius)));
				add(targets, new VisualKey("node", menu.menuId(), choice.id()),
					menuX + Math.sin(bearing) * radius, menuY - Math.cos(bearing) * radius,
					active ? (selected ? NODE_SELECTED_SCALE : NODE_SCALE) : 0.85, alpha,
					menuX + Math.sin(bearing) * (radius - 12.0), menuY - Math.cos(bearing) * (radius - 12.0),
					new NodePaint(choice, labels.apply(choice),
						details == null ? null : details.apply(choice), selected, snapshot.hoverProgress(), maxExtent));
			}
		}
	}

	/**
	 * Shared node/backdrop opacity: selection and ancestor dimming plus the
	 * disabled/reserved factor, so both layers fade together.
	 */
	static double nodeAlpha(boolean active, boolean selected, boolean disabledOrReserved) {
		double alpha = active ? (selected ? 1.0 : 0.8) : ANCESTOR_ALPHA;
		return disabledOrReserved ? alpha * DISABLED_ALPHA_FACTOR : alpha;
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
	 * chrome follows only the transition fade so neither preference can hide
	 * the pointer or its guides.
	 */
	static double layerAlpha(Object data, double stateAlpha, Style style) {
		if (data instanceof SectorPaint) return backdropAlpha(stateAlpha, style);
		if (data instanceof ChromePaint) return stateAlpha;
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
		return layerAlpha(data, stateAlpha, style) > 0.0;
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

	/** Radial sector backdrops first, then inventory panels, then every node/chrome layer. */
	static int paintRank(Object data) {
		if (data instanceof SectorPaint) return 0;
		if (data instanceof PanelPaint) return 1;
		return 2;
	}

	private static void addInventory(List<SpatialOverlayTransitions.Target<VisualKey, Paint>> targets,
		SpatialInventoryView view, Font font, Style style, double guiWidth, double guiHeight) {
		SpatialInventoryLayout layout = inventoryLayout(view, guiWidth, guiHeight, font.lineHeight, style);
		double rowHeight = layout.rowHeight();
		double headerHeight = layout.headerHeight();
		double footerHeight = layout.footerHeight();
		double x = layout.centerX();
		double y = layout.centerY();
		String owner = view.listKey();
		add(targets, new VisualKey("panel", owner, Integer.toString(layout.count())), x, y, 1.0, 1.0, x, y + 8.0,
			new PanelPaint(layout.width(), layout.height(), layout.headerHeight()));
		String position = view.selectedIndex() >= 0 && view.selectedIndex() < view.rows().size()
			? (view.selectedIndex() + 1) + " / " + view.rows().size() : "";
		double headerY = y - layout.height() / 2.0 + headerHeight / 2.0;
		add(targets, new VisualKey("header", owner, view.status().name()), x, headerY, 1.0, 1.0, x, headerY + 4.0,
			new HeaderPaint(layout.width(), headerHeight, position, view.status()));
		for (int i = layout.first(); i < layout.first() + layout.count(); i++) {
			SpatialInventoryView.Row row = view.rows().get(i);
			double rowY = layout.rowCenterY(i);
			add(targets, new VisualKey("row", owner, row.key()), x, rowY, 1.0, 1.0, x, rowY + 6.0,
				new RowPaint(row, layout.width(), rowHeight, i == view.selectedIndex(),
					view.status().grey() || row.quality().grey()));
		}
		double footerY = y + layout.height() / 2.0 - footerHeight / 2.0;
		add(targets, new VisualKey("footer", owner, ""), x, footerY, 1.0, 1.0, x, footerY + 4.0,
			new FooterPaint(layout.width(), footerHeight, view.backLeft(), view.backAffordance()));
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
		if (fontLineHeight <= 0) {
			throw new IllegalArgumentException("fontLineHeight must be positive");
		}
		double textHeight = fontLineHeight * style.inventoryTextScale();
		double rowHeight = Math.max(ROW_HEIGHT, Math.max(ITEM_ICON_SIZE + 2.0, textHeight * 2.0 + 4.0));
		return SpatialInventoryLayout.of(view, guiWidth / 2.0, guiHeight / 2.0,
			LIST_WIDTH, textHeight * 2.0 + 8.0, rowHeight, textHeight + 6.0);
	}

	private static void add(List<SpatialOverlayTransitions.Target<VisualKey, Paint>> targets,
		VisualKey key, double x, double y, double scale, double alpha, double entryX, double entryY, Paint paint) {
		targets.add(new SpatialOverlayTransitions.Target<>(key, x, y, scale, alpha, entryX, entryY, paint));
	}

	private static void paint(GuiGraphics graphics, Font font,
		SpatialOverlayTransitions.State<VisualKey, Paint> state, Style style) {
		double alpha = layerAlpha(state.data(), state.alpha(), style);
		if (alpha <= 0.0) {
			return;
		}
		if (state.data() instanceof NodePaint node) {
			drawNode(graphics, font, node, state, style, alpha);
			return;
		}
		if (state.data() instanceof SectorPaint sector) {
			drawSector(graphics, sector, state, alpha);
			return;
		}
		if (state.data() instanceof ChromePaint chrome) {
			if (style.showTrail()) {
				drawTrail(graphics, chrome.snapshot().trail(), state.x(), state.y(), alpha);
			}
			drawMenuLinks(graphics, chrome.snapshot().menus(), state.x(), state.y(), alpha);
			drawPointer(graphics, chrome.snapshot().pointer(), state.x(), state.y(), alpha);
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
				graphics.fill(left, top, right, bottom, withAlpha(PANEL_BACKGROUND, alpha));
				graphics.fill(left, top, right, top + (int) Math.round(panel.headerHeight()), withAlpha(PANEL_HEADER_BACKGROUND, alpha));
				strokeRect(graphics, left, top, right, bottom, withAlpha(BORDER_COLOR, alpha));
			} else if (state.data() instanceof RowPaint row) {
				paintRow(graphics, font, row, style.inventoryTextScale(), alpha);
			} else if (state.data() instanceof HeaderPaint header) {
				double left = -header.width() / 2.0 + 4.0;
				double top = -header.height() / 2.0 + 3.0;
				drawText(graphics, font, Component.literal(header.position()), left, top,
					style.inventoryTextScale(), TEXT_DIMMED_COLOR, alpha);
				String key = header.status().translationKey();
				if (key != null) {
					drawFittedText(graphics, font, Component.translatable(key), left,
						top + font.lineHeight * style.inventoryTextScale() + 2.0, header.width() - 8.0,
						style.inventoryTextScale(), TEXT_DIMMED_COLOR, alpha);
				}
			} else if (state.data() instanceof FooterPaint footer) {
				Component back = Component.translatable("pingforit.spatial.inventory.back");
				Component forward = Component.translatable("pingforit.spatial.inventory.forward");
				Component left = footer.backLeft() ? back : forward;
				Component right = footer.backLeft() ? forward : back;
				double available = footer.width() / 2.0 - 8.0;
				int squareSize = Math.max(6, (int) Math.round(footer.height() - 4.0));
				double backAvailable = Math.max(0.0, available - squareSize - 4.0);
				double y = -footer.height() / 2.0 + 2.0;
				int backColor = footer.back().focused() && state.present() ? TEXT_COLOR : TEXT_DIMMED_COLOR;
				double leftWidth = footer.backLeft() ? backAvailable : available;
				double leftX = -footer.width() / 2.0 + 4.0 + (footer.backLeft() ? squareSize + 4.0 : 0.0);
				drawFittedText(graphics, font, left, leftX, y, leftWidth, style.inventoryTextScale(),
					footer.backLeft() ? backColor : TEXT_DIMMED_COLOR, alpha);
				double rightAvailable = footer.backLeft() ? available : backAvailable;
				double rightWidth = Math.min(rightAvailable, font.width(right) * style.inventoryTextScale());
				double rightEdge = footer.width() / 2.0 - 4.0 - (footer.backLeft() ? 0.0 : squareSize + 4.0);
				drawFittedText(graphics, font, right, rightEdge - rightWidth, y, rightAvailable,
					style.inventoryTextScale(), footer.backLeft() ? TEXT_DIMMED_COLOR : backColor, alpha);
				if (footer.back().focused() && state.present()) {
					int squareX = (int) Math.round(footer.backLeft() ? -footer.width() / 2.0 + 4.0
						: footer.width() / 2.0 - 4.0 - squareSize);
					int squareY = (int) Math.round(y);
					strokeRect(graphics, squareX, squareY, squareX + squareSize, squareY + squareSize,
						withAlpha(BORDER_COLOR, alpha));
					drawSquareProgress(graphics, squareX, squareY, squareSize, footer.back().progress(),
						withAlpha(PROGRESS_COLOR, alpha));
				}
			}
		} finally {
			pose.popPose();
		}
	}

	private static void paintRow(GuiGraphics graphics, Font font, RowPaint paint, double textScale, double alpha) {
		SpatialInventoryView.Row row = paint.row();
		int left = (int) Math.round(-paint.width() / 2.0);
		int right = (int) Math.round(paint.width() / 2.0);
		int top = (int) Math.round(-paint.height() / 2.0);
		int bottom = (int) Math.round(paint.height() / 2.0);
		if (paint.selected()) {
			graphics.fill(left + 1, top, right - 1, bottom, withAlpha(ROW_SELECTED_BACKGROUND, alpha));
			graphics.fill(left + 1, top, left + 2, bottom, withAlpha(BORDER_SELECTED_FALLBACK, alpha));
		}
		double textLeft = left + 5.0;
		ItemStack stack = itemStack(row.itemId());
		if (!stack.isEmpty()) {
			float tint = paint.grey() ? 0.65f : 1.0f;
			SpatialItemIconRenderer.draw(graphics, stack, (int) textLeft, -ITEM_ICON_SIZE / 2, tint, (float) alpha);
			textLeft += ITEM_ICON_SIZE + 3.0;
		}
		Component quantity = row.count() == null ? Component.empty() : Component.literal("x" + row.count());
		double quantityWidth = font.width(quantity) * textScale;
		double textTop = top + 2.0;
		int textColor = paint.grey() ? TEXT_DISABLED_COLOR : paint.selected() ? TEXT_COLOR : TEXT_DIMMED_COLOR;
		drawClippedText(graphics, font, Component.literal(row.label()), textLeft, textTop,
			Math.max(0.0, right - 8.0 - quantityWidth - textLeft), textScale, textColor, alpha);
		drawText(graphics, font, quantity, right - 4.0 - quantityWidth, textTop, textScale, textColor, alpha);
		String qualityKey = row.quality().translationKey();
		if (qualityKey != null) {
			drawFittedText(graphics, font, Component.translatable(qualityKey), textLeft,
				textTop + font.lineHeight * textScale + 1.0, right - 4.0 - textLeft,
				textScale, TEXT_DISABLED_COLOR, alpha);
		}
	}

	private static void drawClippedText(GuiGraphics graphics, Font font, Component label, double x, double y,
		double width, double scale, int color, double alpha) {
		String clipped = font.plainSubstrByWidth(label.getString(), Math.max(0, (int) Math.floor(width / scale)));
		drawText(graphics, font, Component.literal(clipped), x, y, scale, color, alpha);
	}

	/** Status semantics must remain readable, even with a larger font override. */
	private static void drawFittedText(GuiGraphics graphics, Font font, Component label, double x, double y,
		double width, double scale, int color, double alpha) {
		int labelWidth = font.width(label);
		if (width > 0.0 && labelWidth > 0) {
			drawText(graphics, font, label, x, y, Math.min(scale, width / labelWidth), color, alpha);
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
		Style style,
		double alpha
	) {
		SpatialController.ChoiceView choice = node.choice();
		boolean selected = node.selected();
		Component label = nodeLabel(choice, node.label());
		NodeLayout layout = nodeLayout(node, font::width, font.lineHeight, style, font.getSplitter());
		int left = layout.left();
		int top = layout.top();
		int right = layout.right();
		int bottom = layout.bottom();

		var pose = guiGraphics.pose();
		pose.pushPose();

		try {
			pose.translate(state.x(), state.y(), 0.0);
			pose.scale((float) state.scale(), (float) state.scale(), 1.0f);
			guiGraphics.fill(left, top, right, bottom,
				withAlpha(selected ? NODE_SELECTED_BACKGROUND : NODE_BACKGROUND, alpha));

			int borderColor = nodeBorderColor(choice, selected);

			if (choice.disabled() || choice.reserved()) {
				dashedRect(guiGraphics, left, top, right, bottom, withAlpha(borderColor, alpha * DISABLED_ALPHA_FACTOR));
			} else {
				strokeRect(guiGraphics, left, top, right, bottom, withAlpha(borderColor, alpha));
			}

			int textColor = choice.disabled() ? TEXT_DISABLED_COLOR : selected ? TEXT_COLOR : TEXT_DIMMED_COLOR;
			if (Double.isFinite(node.maxExtent())) {
				drawText(guiGraphics, font, Language.getInstance().getVisualOrder(
					clippedNodeText(label, layout.contentWidth(), layout.titleScale(), font.getSplitter())),
					left + NODE_TEXT_INSET, layout.titleTop(font.lineHeight), layout.titleScale(), textColor, alpha);
			} else {
				drawText(guiGraphics, font, label, left + 4.0,
					layout.titleTop(font.lineHeight), layout.titleScale(), textColor, alpha);
			}
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

	private static void drawSector(GuiGraphics graphics, SectorPaint sector,
		SpatialOverlayTransitions.State<VisualKey, Paint> state, double alpha) {
		int color = withAlpha(SECTOR_BACKGROUND, alpha);
		if ((color >>> 24) == 0) {
			return;
		}
		var pose = graphics.pose();
		pose.pushPose();
		try {
			pose.translate(state.x(), state.y(), 0.0);
			pose.scale((float) state.scale(), (float) state.scale(), 1.0f);
			fillSector(graphics, sector.radius(), sector.choice().startDegrees(), sector.choice().spanDegrees(), color);
		} finally {
			pose.popPose();
		}
	}

	/**
	 * Fills a sector as one bounded quad per scanline. Rows are derived from the
	 * transition state's origin, so no per-frame allocation occurs here.
	 */
	private static void fillSector(GuiGraphics graphics, double radius, double startDegrees,
		double spanDegrees, int color) {
		int firstRow = (int) Math.ceil(-radius);
		int lastRow = (int) Math.floor(radius);
		for (int dy = firstRow; dy <= lastRow; dy++) {
			sectorRow(dy, radius, startDegrees, spanDegrees, SECTOR_ROW_BOUNDS);
			if (SECTOR_ROW_BOUNDS[0] > SECTOR_ROW_BOUNDS[1]) {
				continue;
			}
			int left = (int) Math.round(SECTOR_ROW_BOUNDS[0]);
			int right = (int) Math.round(SECTOR_ROW_BOUNDS[1]);
			if (right <= left) {
				right = left + 1;
			}
			graphics.fill(left, dy, right, dy + 1, color);
		}
	}

	/**
	 * Pure scanline probe for one backdrop row, in coordinates relative to the
	 * sector origin: {@code bounds[0]} is the leftmost and {@code bounds[1]} the
	 * rightmost covered x, and {@code bounds[0] > bounds[1]} means the row is
	 * empty. Exact for the supported spans the controller emits: a convex sector
	 * of at most a half turn, or a full disc. Only callers asking for the returned
	 * array allocate; painting reuses a main-thread scratch row.
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

	private static void drawTrail(
		GuiGraphics guiGraphics,
		List<SpatialController.Point> trail,
		double centerX,
		double centerY,
		double alpha
	) {
		int size = trail.size();

		for (int i = 0; i < size; i++) {
			SpatialController.Point point = trail.get(i);
			int x = (int) Math.round(centerX + point.x());
			int y = (int) Math.round(centerY + point.y());
			double progress = size <= 1 ? 1.0 : (double) i / (size - 1);
			guiGraphics.fill(x - 1, y - 1, x + 2, y + 2, withAlpha(TRAIL_COLOR, alpha * (0.15 + 0.65 * progress)));
		}
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

	private static void drawSquareProgress(
		GuiGraphics guiGraphics,
		int x,
		int y,
		int size,
		double progress,
		int color
	) {
		for (SpatialSquareProgress.Segment segment : SpatialSquareProgress.segments(x, y, size, progress)) {
			line(guiGraphics, segment.x1(), segment.y1(), segment.x2(), segment.y2(), color, BORDER_THICKNESS);
		}
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

	private static ItemStack itemStack(String itemId) {
		if (itemId == null || itemId.isBlank()) {
			return ItemStack.EMPTY;
		}

		ResourceLocation id = ResourceLocation.tryParse(itemId);

		if (id == null) {
			return ItemStack.EMPTY;
		}

		Item item = BuiltInRegistries.ITEM.get(id);

		if (item == null || item == Items.AIR) {
			return ItemStack.EMPTY;
		}

		return new ItemStack(item);
	}

	private static Font font() {
		Minecraft game = Minecraft.getInstance();
		return game == null ? null : game.font;
	}

	private static double orbit(GuiGraphics guiGraphics) {
		return orbitFor(Math.min(guiGraphics.guiWidth(), guiGraphics.guiHeight()));
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
