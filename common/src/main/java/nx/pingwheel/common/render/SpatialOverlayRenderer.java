package nx.pingwheel.common.render;

import java.util.HashMap;
import java.util.Iterator;
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
 * <p>Node positions, scales, and alphas are smoothed with a small per-key cache
 * keyed by menu id plus choice id, so re-parenting or mode switches never snap.
 * Each frame advances nodes by an exponential fraction of the monotonic elapsed
 * time, so the result is independent of frame count. The cache holds current
 * snapshot entries only and is main-thread confined; {@link #reset()} clears it
 * and the elapsed-time baseline when a session ends.
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
	static final int VISIBLE_ROWS = 9;
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

	/** Animation state per menu id plus choice id; main-thread confined. */
	private static final Map<String, Animation> ANIMATIONS = new HashMap<>();

	/** Baseline nanos of the previous animated frame; zero means "no baseline". */
	private static long lastFrameNanos;

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

	/** The actual rendered geometry of one inventory list row. */
	public record RowLayout(double centerY, double width) {

		public static final RowLayout NONE = new RowLayout(Double.NaN, 0.0);

		public boolean isPresent() {
			return !Double.isNaN(centerY);
		}
	}

	/**
	 * Clears every cached node position and the elapsed-time baseline so the
	 * next session starts crisp and its first frame contributes no delta.
	 */
	public static void reset() {
		ANIMATIONS.clear();
		lastFrameNanos = 0L;
	}

	/**
	 * Paints the active radial snapshot using the default localization-key label
	 * contract ({@link #TRANSLATION_KEY_LABELS}). An inactive snapshot clears the
	 * animation and time caches and draws nothing.
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
	 * Paints with explicit monotonic time. The caller must use one clock
	 * consistently for a whole session; mixing clocks distorts smoothing. Every
	 * frame advances nodes by {@code 1 - exp(-rate * elapsed)} clamped to
	 * {@code [0, MAX_FRAME_MILLIS]}, so the same elapsed interval produces the
	 * same offset regardless of how many frames it is split across.
	 */
	public static void draw(
		GuiGraphics guiGraphics,
		SpatialController.Snapshot snapshot,
		float partialTick,
		Function<SpatialController.ChoiceView, Component> labelResolver,
		long nowNanos
	) {
		if (guiGraphics == null) {
			return;
		}

		if (snapshot == null || !snapshot.active()) {
			reset();
			return;
		}

		Font font = font();

		if (font == null) {
			return;
		}

		Function<SpatialController.ChoiceView, Component> labels =
			labelResolver == null ? TRANSLATION_KEY_LABELS : labelResolver;
		long previousNanos = lastFrameNanos;
		lastFrameNanos = nowNanos;
		double elapsedMillis = previousNanos == 0L ? 0.0 : (nowNanos - previousNanos) / 1_000_000.0;
		double factor = smoothingFactor(elapsedMillis, ANIMATION_RATE_PER_MILLI);

		double centerX = guiGraphics.guiWidth() / 2.0;
		double centerY = guiGraphics.guiHeight() / 2.0;
		double orbit = orbit(guiGraphics);
		List<SpatialController.MenuView> menus = snapshot.menus();

		for (Animation animation : ANIMATIONS.values()) {
			animation.present = false;
		}

		drawTrail(guiGraphics, snapshot.trail(), centerX, centerY);
		drawMenuLinks(guiGraphics, menus, centerX, centerY);

		int lastMenuIndex = menus.size() - 1;

		for (int menuIndex = 0; menuIndex <= lastMenuIndex; menuIndex++) {
			SpatialController.MenuView menu = menus.get(menuIndex);
			boolean activeMenu = menuIndex == lastMenuIndex;
			double menuX = centerX + menu.origin().x();
			double menuY = centerY + menu.origin().y();

			for (SpatialController.ChoiceView choice : menu.choices()) {
				double bearing = choice.startDegrees() + choice.spanDegrees() / 2.0;
				boolean selected = activeMenu && choice.id().equals(menu.focusId());
				double radius = orbit + (selected ? ORBIT_SELECTED_PUSH : 0.0);
				double targetX = menuX + Math.sin(Math.toRadians(bearing)) * radius;
				double targetY = menuY - Math.cos(Math.toRadians(bearing)) * radius;
				double targetScale = activeMenu ? (selected ? 1.12 : 0.95) : 0.85;
				double targetAlpha = activeMenu ? (selected ? 1.0 : 0.8) : ANCESTOR_ALPHA;

				if (choice.disabled() || choice.reserved()) {
					targetAlpha *= DISABLED_ALPHA_FACTOR;
				}

				String key = menu.menuId() + "\u0000" + choice.id();
				Animation animation = ANIMATIONS.get(key);

				if (animation == null) {
					animation = new Animation(targetX, targetY, targetScale, targetAlpha);
					ANIMATIONS.put(key, animation);
				} else {
					animation.moveTo(targetX, targetY, targetScale, targetAlpha, factor);
				}

				animation.present = true;
				Component label = labels.apply(choice);
				drawNode(guiGraphics, font, choice, label, animation, selected, snapshot.hoverProgress());
			}
		}

		removeAbsentAnimations();
		drawPointer(guiGraphics, snapshot.pointer(), centerX, centerY);
	}

	/**
	 * Paints the streamed inventory list as a square panel and returns the
	 * actually rendered selected-row centre and panel width.
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
		if (guiGraphics == null || snapshot == null || !snapshot.open()) {
			return RowLayout.NONE;
		}

		Font font = font();

		if (font == null) {
			return RowLayout.NONE;
		}

		List<InventoryListModel.Entry> entries = snapshot.entries();
		int total = entries.size();
		int first = Math.max(0, Math.min(snapshot.windowFirst(), Math.max(0, total - 1)));
		int count = Math.min(VISIBLE_ROWS, total - first);

		if (count <= 0) {
			return RowLayout.NONE;
		}

		double centerX = guiGraphics.guiWidth() / 2.0 + snapshot.axisX();
		double centerY = guiGraphics.guiHeight() / 2.0 + snapshot.glideBaseY();
		int headerHeight = font.lineHeight + 6;
		int footerHeight = font.lineHeight + 4;
		int panelHeight = headerHeight + count * ROW_HEIGHT + footerHeight;
		int left = (int) Math.round(centerX - LIST_WIDTH / 2.0);
		int top = (int) Math.round(centerY - panelHeight / 2.0);
		int right = left + LIST_WIDTH;
		int bottom = top + panelHeight;

		guiGraphics.fill(left, top, right, bottom, PANEL_BACKGROUND);
		guiGraphics.fill(left, top, right, top + headerHeight, PANEL_HEADER_BACKGROUND);
		strokeRect(guiGraphics, left, top, right, bottom, BORDER_COLOR);

		String position = (snapshot.selectedIndex() + 1) + " / " + total;

		guiGraphics.drawString(font, Component.literal(position), left + 4, top + 3, TEXT_DIMMED_COLOR, false);

		if (snapshot.status() == InventoryListModel.Status.UPDATING) {
			Component updating = Component.translatable("pingforit.spatial.inventory.updating");
			guiGraphics.drawString(font, updating, right - 4 - font.width(updating), top + 3, TEXT_DIMMED_COLOR, false);
		}

		double selectedCenterY = Double.NaN;

		for (int i = 0; i < count; i++) {
			int index = first + i;
			InventoryListModel.Entry entry = entries.get(index);
			int rowTop = top + headerHeight + i * ROW_HEIGHT;
			int rowBottom = rowTop + ROW_HEIGHT;
			boolean selected = index == snapshot.selectedIndex();

			if (selected) {
				guiGraphics.fill(left + 1, rowTop, right - 1, rowBottom, ROW_SELECTED_BACKGROUND);
				guiGraphics.fill(left + 1, rowTop, left + 2, rowBottom, BORDER_SELECTED_FALLBACK);
				selectedCenterY = (rowTop + rowBottom) / 2.0;
			}

			int textColor = selected ? TEXT_COLOR : TEXT_DIMMED_COLOR;
			int textLeft = left + 5;

			ItemStack stack = itemIds == null ? ItemStack.EMPTY : itemStack(itemIds.get(entry.key()));

			if (!stack.isEmpty()) {
				guiGraphics.renderItem(stack, textLeft, rowTop + (ROW_HEIGHT - ITEM_ICON_SIZE) / 2);
				textLeft += ITEM_ICON_SIZE + 3;
			}

			Component label = Component.literal(entry.label());
			int maxLabelRight = right - 4 - font.width("x" + entry.count()) - 4;
			String clipped = font.plainSubstrByWidth(entry.label(), Math.max(0, maxLabelRight - textLeft));

			if (!clipped.equals(entry.label())) {
				label = Component.literal(clipped);
			}

			guiGraphics.drawString(font, label, textLeft, rowTop + (ROW_HEIGHT - font.lineHeight) / 2, textColor, false);
			guiGraphics.drawString(
				font,
				Component.literal("x" + entry.count()),
				right - 4 - font.width("x" + entry.count()),
				rowTop + (ROW_HEIGHT - font.lineHeight) / 2,
				TEXT_DIMMED_COLOR,
				false);
		}

		Component back = Component.translatable("pingforit.spatial.inventory.back");
		Component forward = Component.translatable("pingforit.spatial.inventory.forward");
		boolean backLeft = snapshot.direction().back() == InventoryListModel.Side.LEFT;
		int footerY = bottom - footerHeight + 2;

		if (backLeft) {
			guiGraphics.drawString(font, back, left + 4, footerY, TEXT_DIMMED_COLOR, false);
			guiGraphics.drawString(font, forward, right - 4 - font.width(forward), footerY, TEXT_DIMMED_COLOR, false);
		} else {
			guiGraphics.drawString(font, forward, left + 4, footerY, TEXT_DIMMED_COLOR, false);
			guiGraphics.drawString(font, back, right - 4 - font.width(back), footerY, TEXT_DIMMED_COLOR, false);
		}

		return new RowLayout(selectedCenterY, LIST_WIDTH);
	}

	private static void drawNode(
		GuiGraphics guiGraphics,
		Font font,
		SpatialController.ChoiceView choice,
		Component resolvedLabel,
		Animation animation,
		boolean selected,
		double hoverProgress
	) {
		if (animation.alpha <= 0.02) {
			return;
		}

		Component label = resolvedLabel == null ? Component.empty() : resolvedLabel;

		double boxWidth = Math.max(MIN_BOX_WIDTH, font.width(label) + 8.0);
		int left = (int) Math.round(-boxWidth / 2.0);
		int top = (int) Math.round(-BOX_HEIGHT / 2.0);
		int right = (int) Math.round(boxWidth / 2.0);
		int bottom = (int) Math.round(BOX_HEIGHT / 2.0);
		double alpha = animation.alpha;

		var pose = guiGraphics.pose();
		pose.pushPose();

		try {
			pose.translate(animation.x, animation.y, 0.0);
			pose.scale((float) animation.scale, (float) animation.scale, 1.0f);
			guiGraphics.fill(left, top, right, bottom,
				withAlpha(selected ? NODE_SELECTED_BACKGROUND : NODE_BACKGROUND, alpha));

			int borderColor = selected ? accentColor(choice.action()) : BORDER_COLOR;

			if (choice.disabled() || choice.reserved()) {
				dashedRect(guiGraphics, left, top, right, bottom, withAlpha(borderColor, alpha * DISABLED_ALPHA_FACTOR));
			} else {
				strokeRect(guiGraphics, left, top, right, bottom, withAlpha(borderColor, alpha));
			}

			int textColor = choice.disabled() ? TEXT_DISABLED_COLOR : selected ? TEXT_COLOR : TEXT_DIMMED_COLOR;
			guiGraphics.drawString(
				font,
				label,
				left + 4,
				(int) Math.round(-font.lineHeight / 2.0),
				withAlpha(textColor, alpha),
				false);

			if (choice.back() && selected && hoverProgress > 0.0) {
				drawSquareProgress(
					guiGraphics,
					left - 2,
					top - 2,
					Math.max(right - left, bottom - top) + 3,
					hoverProgress,
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
		double centerY
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
				GUIDE_COLOR,
				BORDER_THICKNESS);
		}
	}

	private static void drawTrail(
		GuiGraphics guiGraphics,
		List<SpatialController.Point> trail,
		double centerX,
		double centerY
	) {
		int size = trail.size();

		for (int i = 0; i < size; i++) {
			SpatialController.Point point = trail.get(i);
			int x = (int) Math.round(centerX + point.x());
			int y = (int) Math.round(centerY + point.y());
			double progress = size <= 1 ? 1.0 : (double) i / (size - 1);
			guiGraphics.fill(x - 1, y - 1, x + 2, y + 2, withAlpha(TRAIL_COLOR, 0.15 + 0.65 * progress));
		}
	}

	private static void drawPointer(
		GuiGraphics guiGraphics,
		SpatialController.Point pointer,
		double centerX,
		double centerY
	) {
		if (pointer == null) {
			return;
		}

		int x = (int) Math.round(centerX + pointer.x());
		int y = (int) Math.round(centerY + pointer.y());
		int arm = 5;

		guiGraphics.fill(x - arm, y, x + arm + 1, y + 1, POINTER_COLOR);
		guiGraphics.fill(x, y - arm, x + 1, y + arm + 1, POINTER_COLOR);
		guiGraphics.fill(x - 1, y - 1, x + 2, y + 2, POINTER_COLOR);
	}

	private static void drawSquareProgress(
		GuiGraphics guiGraphics,
		int x,
		int y,
		int size,
		double progress,
		int color
	) {
		if (size <= 0) {
			return;
		}

		double remaining = size * 4.0 * clamp(progress, 0.0, 1.0);
		int right = x + size;
		int bottom = y + size;

		remaining = drawProgressSegment(guiGraphics, x, y, right, y, remaining, color);
		remaining = drawProgressSegment(guiGraphics, right, y, right, bottom, remaining, color);
		remaining = drawProgressSegment(guiGraphics, right, bottom, x, bottom, remaining, color);
		drawProgressSegment(guiGraphics, x, bottom, x, y, remaining, color);
	}

	private static double drawProgressSegment(
		GuiGraphics guiGraphics,
		int x1,
		int y1,
		int x2,
		int y2,
		double remaining,
		int color
	) {
		if (remaining <= 0.0) {
			return 0.0;
		}

		double length = Math.hypot((double) (x2 - x1), (double) (y2 - y1));
		double fraction = Math.min(1.0, remaining / length);
		int endX = (int) Math.round(x1 + (x2 - x1) * fraction);
		int endY = (int) Math.round(y1 + (y2 - y1) * fraction);

		line(guiGraphics, x1, y1, endX, endY, color, BORDER_THICKNESS);
		return remaining - length * fraction;
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

	private static int withAlpha(int color, double alpha) {
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

	private static void removeAbsentAnimations() {
		Iterator<Map.Entry<String, Animation>> iterator = ANIMATIONS.entrySet().iterator();

		while (iterator.hasNext()) {
			if (!iterator.next().getValue().present) {
				iterator.remove();
			}
		}
	}

	/** Mutable per-node interpolation state; never exposed outside this class. */
	private static final class Animation {

		double x;
		double y;
		double scale;
		double alpha;
		boolean present;

		Animation(double x, double y, double scale, double alpha) {
			this.x = x;
			this.y = y;
			this.scale = scale;
			this.alpha = alpha;
		}

		void moveTo(double targetX, double targetY, double targetScale, double targetAlpha, double factor) {
			x += (targetX - x) * factor;
			y += (targetY - y) * factor;
			scale += (targetScale - scale) * factor;
			alpha += (targetAlpha - alpha) * factor;
		}
	}
}
