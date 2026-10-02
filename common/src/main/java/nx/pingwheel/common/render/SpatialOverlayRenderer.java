package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 * <p>Every shape is square and straight: options are framed by thin
 * rectangular borders, the pointer is a straight-line crosshair, the trail is a
 * series of small squares, and the Back hover progress is a square perimeter
 * walked in four straight segments. There are no arcs, circles, or rounded
 * corners anywhere; the radial bearing only positions a node along a straight
 * guide line.
 *
 * <p>The controller's geometry is virtual: menu origins and the pointer are
 * relative to the gesture origin, so this renderer offsets every point by the
 * GUI center. GUI scaling of physical input is owned by the future actor; this
 * class only paints in GUI space.
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
	static final int PROGRESS_COLOR = 0xFFFFFFFF;
	static final int TRAIL_COLOR = 0x99FFFFFF;

	static final int BORDER_THICKNESS = 1;
	static final int ROW_HEIGHT = 12;
	static final int LIST_WIDTH = 200;
	static final int ITEM_ICON_SIZE = 16;
	static final double BOX_HEIGHT = 14.0;
	static final double MIN_BOX_WIDTH = 14.0;
	static final double ORBIT_MIN = 64.0;
	static final double ORBIT_MAX = 132.0;
	static final double ORBIT_RATIO = 0.22;
	static final double ORBIT_SELECTED_PUSH = 10.0;
	static final double ANIMATION_RATE_PER_MILLI = 0.02;
	static final double MAX_FRAME_MILLIS = 100.0;
	static final double ANCESTOR_ALPHA = 0.35;
	static final double DISABLED_ALPHA_FACTOR = 0.5;

	private static final long TRANSITION_NANOS = 160_000_000L;
	private static final int TRANSITION_CAPACITY = 512;
	private static final double APPEARANCE_SCALE = 0.92;
	private static final Session SHARED = new Session();

	/**
	 * Caller-resolved visual preferences; no configuration access occurs here.
	 * Scales are actual text scales, not persisted percentage values. The facade
	 * can pass normalized old font percentages to fromLegacyFontSizes, preserving
	 * the old radial option base scale and independent target/list text scale.
	 */
	public record Style(int opacityPercent, double optionTextScale, double inventoryTextScale,
		double rootDistance, boolean showTrail, boolean reduceMotion) {

		public static final Style NATIVE = new Style(100, 1.0, 1.0, 0.0, true, false);

		/** Zero rootDistance retains viewport-derived spacing for old overloads only. */
		public Style(int opacityPercent, double optionTextScale, double inventoryTextScale,
			boolean showTrail, boolean reduceMotion) {
			this(opacityPercent, optionTextScale, inventoryTextScale, 0.0, showTrail, reduceMotion);
		}

		public Style {
			opacityPercent = WheelOpacity.clampPercent(opacityPercent);
			if (!Double.isFinite(optionTextScale) || optionTextScale <= 0.0
				|| !Double.isFinite(inventoryTextScale) || inventoryTextScale <= 0.0
				|| !Double.isFinite(rootDistance) || rootDistance < 0.0) {
				throw new IllegalArgumentException("text scales must be finite and positive");
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
			return new Style(opacityPercent, WheelLabelLayout.BASE_TEXT_SCALE * optionFontPercent / 100.0,
				targetFontPercent / 100.0, rootDistance, showTrail, reduceMotion);
		}
	}

	private record VisualKey(String kind, String owner, String entry) {}
	private sealed interface Paint permits NodePaint, PanelPaint, RowPaint, HeaderPaint, FooterPaint, ChromePaint {}
	private record NodePaint(SpatialController.ChoiceView choice, Component label, boolean selected,
		double hoverProgress) implements Paint {}
	private record PanelPaint(double width, double height, double headerHeight) implements Paint {}
	private record RowPaint(SpatialInventoryView.Row row, double width, double height, boolean selected,
		boolean grey) implements Paint {}
	private record HeaderPaint(double width, double height, String position, SpatialInventoryView.Status status)
		implements Paint {}
	private record FooterPaint(double width, double height, boolean backLeft,
		SpatialInventoryView.BackAffordance back) implements Paint {}
	private record ChromePaint(SpatialController.Snapshot snapshot) implements Paint {}

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
	 * Actual selected-row geometry in ABSOLUTE GUI coordinates, not controller
	 * coordinates. Before enterExternal/rebase, subtract guiHeight / 2 exactly
	 * once. Width includes the row's animated scale. Outgoing rows return NONE.
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
	 * inputs are an exit, not disposal. No target acquisition or sends occur.
	 */
	public static RowLayout drawFrame(GuiGraphics graphics, SpatialController.Snapshot radial,
		SpatialInventoryView inventory, Function<SpatialController.ChoiceView, Component> labels,
		Style style, long nowNanos) {
		return SHARED.drawFrame(graphics, radial, inventory, labels, style, nowNanos);
	}

	/** Per-native-session render state, main-thread confined and finitely retained. */
	public static final class Session {
		private final SpatialOverlayTransitions<VisualKey, Paint> transitions =
			new SpatialOverlayTransitions<>(TRANSITION_NANOS, TRANSITION_CAPACITY, APPEARANCE_SCALE);

		public void reset() {
			transitions.clear();
		}

		/** Whether inactive frames still need painting/cleanup, not an input-active predicate. */
		public boolean isAnimating() {
			return !transitions.isEmpty();
		}

		public RowLayout drawFrame(GuiGraphics graphics, SpatialController.Snapshot radial,
			SpatialInventoryView inventory, Function<SpatialController.ChoiceView, Component> labels,
			Style style, long nowNanos) {
			if (graphics == null || style == null) {
				return RowLayout.NONE;
			}
			Font font = font();
			if (font == null) {
				return RowLayout.NONE;
			}
			List<SpatialOverlayTransitions.Target<VisualKey, Paint>> targets = new ArrayList<>();
			double centerX = graphics.guiWidth() / 2.0;
			double centerY = graphics.guiHeight() / 2.0;
			if (radial != null && radial.active()) {
				addRadial(targets, radial, labels == null ? TRANSLATION_KEY_LABELS : labels,
					centerX, centerY, orbit(graphics), style.rootDistance());
			}
			if (inventory != null && inventory.open()) {
				addInventory(targets, inventory, font, style, centerX, centerY);
			}
			List<SpatialOverlayTransitions.State<VisualKey, Paint>> states =
				transitions.update(targets, nowNanos, style.reduceMotion());
			RowLayout selected = RowLayout.NONE;
			// A replacement panel may have been admitted after surviving rows. Paint
			// all backdrops first so cache insertion order never covers their text.
			for (SpatialOverlayTransitions.State<VisualKey, Paint> state : states.stream()
				.sorted(Comparator.comparingInt(value -> value.data() instanceof PanelPaint ? 0 : 1)).toList()) {
				if (state.present() && state.data() instanceof RowPaint row && row.selected()) {
					selected = new RowLayout(state.y(), row.width() * state.scale());
				}
				if (WheelOpacity.shouldRender(style.opacityPercent())) {
					paint(graphics, font, state, style);
				}
			}
			return selected;
		}
	}

	private static void addRadial(List<SpatialOverlayTransitions.Target<VisualKey, Paint>> targets,
		SpatialController.Snapshot snapshot, Function<SpatialController.ChoiceView, Component> labels,
		double centerX, double centerY, double orbit, double rootDistance) {
		add(targets, new VisualKey("chrome", "radial", ""), centerX, centerY, 1.0, 1.0,
			centerX, centerY, new ChromePaint(snapshot));
		List<SpatialController.MenuView> menus = snapshot.menus();
		for (int i = 0; i < menus.size(); i++) {
			SpatialController.MenuView menu = menus.get(i);
			boolean active = i == menus.size() - 1;
			double menuX = centerX + menu.origin().x();
			double menuY = centerY + menu.origin().y();
			for (SpatialController.ChoiceView choice : menu.choices()) {
				double bearing = Math.toRadians(choice.startDegrees() + choice.spanDegrees() / 2.0);
				boolean selected = active && choice.id().equals(menu.focusId());
				double radius = nodeRadius(i, orbit, rootDistance, selected);
				double alpha = active ? (selected ? 1.0 : 0.8) : ANCESTOR_ALPHA;
				if (choice.disabled() || choice.reserved()) {
					alpha *= DISABLED_ALPHA_FACTOR;
				}
				add(targets, new VisualKey("node", menu.menuId(), choice.id()),
					menuX + Math.sin(bearing) * radius, menuY - Math.cos(bearing) * radius,
					active ? (selected ? 1.12 : 0.95) : 0.85, alpha,
					menuX + Math.sin(bearing) * (radius - 12.0), menuY - Math.cos(bearing) * (radius - 12.0),
					new NodePaint(choice, labels.apply(choice), selected, snapshot.hoverProgress()));
			}
		}
	}

	static double nodeRadius(int menuIndex, double viewportOrbit, double rootDistance, boolean selected) {
		return (menuIndex == 0 && rootDistance > 0.0 ? rootDistance : viewportOrbit)
			+ (selected ? ORBIT_SELECTED_PUSH : 0.0);
	}

	private static void addInventory(List<SpatialOverlayTransitions.Target<VisualKey, Paint>> targets,
		SpatialInventoryView view, Font font, Style style, double centerX, double centerY) {
		SpatialInventoryLayout layout = inventoryLayout(view, centerX * 2.0, centerY * 2.0, font.lineHeight, style);
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
	 * coordinates are absolute GUI pixels. For a logical submenu origin the
	 * facade converts rowCenterY(selectedIndex) minus guiHeight / 2 exactly once;
	 * drawFrame's RowLayout instead reports the animated displayed row position.
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
		double alpha = state.alpha() * style.opacityPercent() / 100.0;
		if (alpha <= 0.0) {
			return;
		}
		if (state.data() instanceof NodePaint node) {
			drawNode(graphics, font, node, state, style, alpha);
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
		Component label = node.label() == null ? Component.empty() : node.label();
		double boxWidth = Math.max(MIN_BOX_WIDTH, font.width(label) * style.optionTextScale() + 8.0);
		double boxHeight = Math.max(BOX_HEIGHT, font.lineHeight * style.optionTextScale() + 6.0);
		int left = (int) Math.round(-boxWidth / 2.0);
		int top = (int) Math.round(-boxHeight / 2.0);
		int right = (int) Math.round(boxWidth / 2.0);
		int bottom = (int) Math.round(boxHeight / 2.0);

		var pose = guiGraphics.pose();
		pose.pushPose();

		try {
			pose.translate(state.x(), state.y(), 0.0);
			pose.scale((float) state.scale(), (float) state.scale(), 1.0f);
			guiGraphics.fill(left, top, right, bottom,
				withAlpha(selected ? NODE_SELECTED_BACKGROUND : NODE_BACKGROUND, alpha));

			int borderColor = selected ? accentColor(choice.action()) : BORDER_COLOR;

			if (choice.disabled() || choice.reserved()) {
				dashedRect(guiGraphics, left, top, right, bottom, withAlpha(borderColor, alpha * DISABLED_ALPHA_FACTOR));
			} else {
				strokeRect(guiGraphics, left, top, right, bottom, withAlpha(borderColor, alpha));
			}

			int textColor = choice.disabled() ? TEXT_DISABLED_COLOR : selected ? TEXT_COLOR : TEXT_DIMMED_COLOR;
			drawText(guiGraphics, font, label, left + 4.0,
				-font.lineHeight * style.optionTextScale() / 2.0, style.optionTextScale(), textColor, alpha);

			if (choice.back() && selected && state.present() && node.hoverProgress() > 0.0) {
				drawSquareProgress(
					guiGraphics,
					left - 2,
					top - 2,
					Math.max(right - left, bottom - top) + 3,
					node.hoverProgress(),
					withAlpha(PROGRESS_COLOR, alpha));
			}
		} finally {
			pose.popPose();
		}
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
		double smallest = Math.min(guiGraphics.guiWidth(), guiGraphics.guiHeight());
		return clamp(smallest * ORBIT_RATIO, ORBIT_MIN, ORBIT_MAX);
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
