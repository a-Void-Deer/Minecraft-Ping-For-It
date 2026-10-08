package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialMenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives controller snapshots through production radial planning, NodePaint
 * layout and font clipping. Collision expectations use the actual rounded
 * frame bounds (including the disabled border's inclusive endpoint), not six
 * copies of a hypothetical two-line box. Font advances model the vanilla
 * Latin narrow glyphs and nine-pixel CJK glyphs without loading a game client.
 * No GuiGraphics, GPU submission or manual rendering evidence is claimed.
 */
class SpatialOverlayRendererNodeLayoutTest {
	private static final String LONG_NAME = "Custom Very Long Name (Diamond Sword)";
	private static final String[] IDS = {"dropped_item", "entity", "entity_block", "block", "location"};
	private static final String[] TITLES = {"Dropped Item", "Entity", "Entity Block", "Block", "Location"};
	private static final String[] DETAILS = {LONG_NAME, "Pig", "方块实体", "Stone", "Here"};
	private static final String[] ZH_TITLES = {"掉落物", "实体", "方块实体", "方块", "位置"};
	private static final String[] ZH_DETAILS = {"自定义超长名字（钻石剑）", "猪", "方块实体", "石头", "这里"};
	private static final int LINE_HEIGHT = 9;
	private static final StringSplitter FONT = new StringSplitter((codePoint, style) ->
		glyphAdvance(codePoint) + (style.isBold() ? 1.0f : 0.0f));

	@Test
	void ordinaryMenuKeepsHistoricalSingleLineAppearanceRegardlessOfItsLabelText() {
		var menu = SpatialMenu.of("ordinary", SpatialMenu.Choice.leaf("ordinary-leaf", LONG_NAME, "ordinary-leaf"));
		var controller = controller(menu, 230.0);
		for (int optionPercent : new int[] {100, 400}) {
			var style = style(optionPercent, 100, 1.5);
			var nodes = nodes(controller.snapshot(), null, style, 400.0, 240.0);
			for (var target : nodes) {
				var node = (SpatialOverlayRenderer.NodePaint) target.data();
				var layout = layout(node, style);
				assertFalse(Double.isFinite(node.maxExtent()), "ordinary menus never gain the Precise constraint");
				assertEquals(width(node.label()) * style.optionTextScale() + 8.0, layout.width(), 1.0e-9);
				assertTrue(layout.height() >= LINE_HEIGHT * style.optionTextScale() + 6.0);
				assertEquals(style.optionTextScale(), layout.titleScale(), 1.0e-9);
				assertEquals(0.0, layout.detailScale());
			}
		}
	}

	@Test
	void mixedPreciseFramesSeparateTheReportedEntityAndDisabledEntityBlockPair() {
		var controller = preciseController(230.0, true);
		focus(controller, 5); // Back selected: both regression nodes use the ordinary 0.95 scale.
		var style = style(100, 100, 1.5);
		var nodes = nodes(controller.snapshot(), details(false), style, 400.0, 240.0);
		var entity = (SpatialOverlayRenderer.NodePaint) nodes.get(1).data();
		var entityBlock = (SpatialOverlayRenderer.NodePaint) nodes.get(2).data();

		assertTrue(entity.hasDetail());
		assertTrue(entityBlock.choice().disabled());
		assertFalse(entityBlock.hasDetail());
		assertEquals(170.0, midpoint(entity.choice()), 1.0e-9);
		assertEquals(230.0, midpoint(entityBlock.choice()), 1.0e-9);
		assertTrue(Double.isFinite(((SpatialOverlayRenderer.NodePaint) nodes.getLast().data()).maxExtent()),
			"Back receives its containing menu's constraint even without a precise: ID");
		assertFramesSeparated(nodes, style, "400x240, entry 230 degrees, Back selected");
	}

	@Test
	void mixedAndFullyAvailableRotatedMenusKeepActualPaintedFramesSeparated() {
		double[][] viewports = {{400.0, 240.0}, {480.0, 270.0}, {960.0, 540.0}};
		int[][] preferences = {{100, 100}, {400, 100}, {100, 500}, {1000, 500}};
		for (double[] viewport : viewports) {
			for (double radiusScale : new double[] {1.0, 1.5, 4.0}) {
				for (int[] preference : preferences) {
					var style = style(preference[0], preference[1], radiusScale);
					for (boolean mixed : new boolean[] {false, true}) {
						for (boolean chinese : new boolean[] {false, true}) {
							for (double entryBearing = 0.0; entryBearing < 60.0; entryBearing += 5.0) {
								var controller = preciseController(entryBearing, mixed);
								for (int selected = 0; selected < 6; selected++) {
									focus(controller, selected);
									var nodes = nodes(controller.snapshot(), details(chinese), style,
										viewport[0], viewport[1], chinese);
									assertEquals(6, nodes.size(), "the controller supplies five fixed slots and Back");
									assertFramesSeparated(nodes, style, "viewport " + viewport[0] + "x" + viewport[1]
										+ ", radius scale " + radiusScale + ", entry " + entryBearing + ", selected " + selected);
									for (var target : nodes) {
										var node = (SpatialOverlayRenderer.NodePaint) target.data();
										var layout = layout(node, style);
										assertTrue(Double.isFinite(node.maxExtent()));
										assertTrue(layout.right() - layout.left() + 1 <= node.maxExtent(),
											"rounded bounds plus a possible dashed endpoint stay within the budget");
										assertTrue(layout.bottom() - layout.top() + 1 <= node.maxExtent());
										assertFalse(clipped(node.label(), layout, true).isBlank(),
											"a nonblank type or Back label must retain a visible glyph");
										if (node.hasDetail()) {
											assertFalse(clipped(node.targetDetail(), layout, false).isBlank());
											assertRowsSeparated(layout);
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}

	@Test
	void smallestChineseMenuRetainsVisibleBackDisabledTypeAndCandidateText() {
		var controller = preciseController(230.0, true);
		focus(controller, 5);
		var style = style(400, 100, 1.0);
		var nodes = nodes(controller.snapshot(), details(true), style, 400.0, 240.0, true);
		for (int slot : new int[] {2, 5}) {
			var node = (SpatialOverlayRenderer.NodePaint) nodes.get(slot).data();
			var layout = layout(node, style);
			assertFalse(node.hasDetail());
			assertTrue(node.choice().disabled() || node.choice().back());
			assertClipped(node.label(), layout, true);
			assertTrue(layout.titleScale() >= style(100, 100, 1.0).optionTextScale());
		}
		var entity = (SpatialOverlayRenderer.NodePaint) nodes.get(1).data();
		for (String name : new String[] {"猪", LONG_NAME.repeat(16), "自定义超长名字（钻石剑）".repeat(16)}) {
			var node = new SpatialOverlayRenderer.NodePaint(entity.choice(), entity.label(), Component.literal(name),
				entity.selected(), entity.hoverProgress(), entity.maxExtent());
			var layout = layout(node, style);
			assertFalse(clipped(node.label(), layout, true).isBlank());
			assertFalse(clipped(node.targetDetail(), layout, false).isBlank());
			assertTrue(layout.detailScale() >= style(100, 100, 1.0).optionTextScale());
			assertRowsSeparated(layout);
		}
		assertFramesSeparated(nodes, style, "Chinese 400x240, radius scale 1, option scale 2");
	}

	@Test
	void visiblePrefixFitUsesStyledCodePointsWithoutSplittingSurrogatePairs() {
		var style = style(50, 500, 1.0);
		var entity = entityNode(preciseController(230.0, true), "Pig", style);
		var textStyle = net.minecraft.network.chat.Style.EMPTY.withBold(true).withColor(0x12AB56);
		var detail = Component.literal(" ").append(Component.literal("\uD840\uDC00名字".repeat(16)).withStyle(textStyle));
		var node = new SpatialOverlayRenderer.NodePaint(entity.choice(), entity.label(), detail,
			entity.selected(), entity.hoverProgress(), entity.maxExtent());
		var layout = layout(node, style);
		var clipped = SpatialOverlayRenderer.clippedNodeText(detail, layout.contentWidth(), layout.detailScale(), FONT);
		assertFalse(clipped.getString().isBlank());
		assertTrue(clipped.getString().startsWith(" \uD840\uDC00"), "retain the source's first whole visible code point");
		assertTrue(FONT.stringWidth(clipped) * layout.detailScale() <= layout.contentWidth());
		assertTrue(layout.detailScale() >= style(100, 100, 1.0).optionTextScale());
		var styles = new ArrayList<net.minecraft.network.chat.Style>();
		clipped.visit((resolved, text) -> {
			if (!text.isBlank()) styles.add(resolved);
			return Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		assertFalse(styles.isEmpty());
		for (var resolved : styles) {
			assertTrue(resolved.isBold());
			assertEquals(textStyle.getColor(), resolved.getColor());
		}
		assertRowsSeparated(layout);
	}

	@Test
	void longDefaultNameClipsAtTheSameReadableScaleAsPig() {
		var controller = preciseController(230.0, true);
		var style = style(100, 100, 1.5);
		var pig = entityNode(controller, "Pig", style);
		var longName = entityNode(controller, LONG_NAME, style);
		var pigLayout = layout(pig, style);
		var longLayout = layout(longName, style);

		assertEquals(199, width(longName.targetDetail()), "regression fixture uses the reported realistic font width");
		assertEquals(style.inventoryTextScale(), pigLayout.detailScale(), 1.0e-9);
		assertEquals(style.optionTextScale(), pigLayout.titleScale(), 1.0e-9);
		assertEquals("Pig", clipped(pig.targetDetail(), pigLayout, false));
		assertEquals(pigLayout.detailScale(), longLayout.detailScale(), 1.0e-9,
			"name length cannot reduce the requested target text to a few physical pixels");
		assertEquals(pigLayout.titleScale(), longLayout.titleScale(), 1.0e-9);
		assertClipped(longName.targetDetail(), longLayout, false);
		assertRowsSeparated(longLayout);
	}

	@Test
	void independentFontPreferencesSurviveWhenTheRowsHaveRoom() {
		var controller = preciseController(230.0, false);
		var baselineStyle = style(100, 100, 4.0);
		var baseline = layout(entityNode(controller, "Pig", baselineStyle), baselineStyle);
		var titleStyle = style(200, 100, 4.0);
		var title = layout(entityNode(controller, "Pig", titleStyle), titleStyle);
		var detailStyle = style(100, 200, 4.0);
		var detail = layout(entityNode(controller, "Pig", detailStyle), detailStyle);
		assertEquals(baseline.titleScale() * 2.0, title.titleScale(), 1.0e-9);
		assertEquals(baseline.detailScale(), title.detailScale(), 1.0e-9);
		assertEquals(baseline.titleScale(), detail.titleScale(), 1.0e-9);
		assertEquals(baseline.detailScale() * 2.0, detail.detailScale(), 1.0e-9);
		assertRowsSeparated(title);
		assertRowsSeparated(detail);
	}

	@Test
	void growingEnglishAndChineseNamesClipInsteadOfDrivingEitherLineScaleToZero() {
		var controller = preciseController(230.0, false);
		var style = style(100, 100, 1.5);
		var baseline = layout(entityNode(controller, "Pig", style), style);
		for (String prefix : new String[] {LONG_NAME, "自定义超长名字（钻石剑）"}) {
			for (int repetitions : new int[] {1, 4, 16, 64}) {
				var node = entityNode(controller, prefix.repeat(repetitions), style);
				var layout = layout(node, style);
				assertEquals(baseline.titleScale(), layout.titleScale(), 1.0e-9);
				assertEquals(baseline.detailScale(), layout.detailScale(), 1.0e-9);
				assertClipped(node.targetDetail(), layout, false);
				assertRowsSeparated(layout);
			}
		}
	}

	@Test
	void heightFitCannotUndoTheReadableFloorOrInflateSmallerPreferences() {
		var controller = preciseController(230.0, false);
		double radialBase = style(100, 100, 1.0).optionTextScale();
		for (int[] preference : new int[][] {{1000, 100}, {100, 500}, {1000, 500}, {50, 25}}) {
			var style = style(preference[0], preference[1], 1.0);
			var node = entityNode(controller, LONG_NAME.repeat(16), style);
			var layout = layout(node, style);
			assertTrue(layout.titleScale() >= Math.min(radialBase, style.optionTextScale()) - 1.0e-9);
			assertTrue(layout.detailScale() >= Math.min(radialBase, style.inventoryTextScale()) - 1.0e-9,
				"a large title or target font cannot shrink the other row below the radial base size");
			assertTrue(layout.titleScale() <= style.optionTextScale());
			assertTrue(layout.detailScale() <= style.inventoryTextScale());
			assertTrue(layout.bottom() - layout.top() + 1 <= node.maxExtent());
			assertClipped(node.targetDetail(), layout, false);
			assertRowsSeparated(layout);
		}
	}

	@Test
	void constrainedSingleLineTypeClipsWithoutWidthDrivenFontShrinking() {
		var controller = preciseController(230.0, true);
		var style = style(400, 100, 1.5);
		var nodes = nodes(controller.snapshot(), details(false), style, 480.0, 270.0);
		var node = (SpatialOverlayRenderer.NodePaint) nodes.get(2).data();
		var layout = layout(node, style);
		assertFalse(node.hasDetail());
		assertEquals(style.optionTextScale(), layout.titleScale(), 1.0e-9,
			"this title fits vertically; its width must clip, not rescale");
		assertClipped(node.label(), layout, true);
	}

	@Test
	void clippedNodeTextKeepsItsComponentStyle() {
		var label = Component.literal(LONG_NAME).withStyle(net.minecraft.network.chat.Style.EMPTY
			.withColor(0x12AB56).withItalic(true));
		var clipped = SpatialOverlayRenderer.clippedNodeText(label, 22.0, 1.0, FONT);
		assertFalse(clipped.getString().isEmpty());
		assertTrue(clipped.getString().length() < label.getString().length());
		var styles = new ArrayList<net.minecraft.network.chat.Style>();
		clipped.visit((style, text) -> {
			if (!text.isEmpty()) styles.add(style);
			return Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		assertFalse(styles.isEmpty());
		for (var style : styles) {
			assertEquals(label.getStyle().getColor(), style.getColor());
			assertTrue(style.isItalic());
		}
	}

	@Test
	void detailLineAddsNoOpacityOrAdmissionLayer() {
		var choice = new SpatialController.ChoiceView("precise:block", "pingforit.spatial.target_type.block", "precise:block",
			false, false, false, false, true, 0.0, 60.0, null);
		var plain = new SpatialOverlayRenderer.NodePaint(choice, Component.literal("Block"), true, 0.0);
		var detailed = new SpatialOverlayRenderer.NodePaint(choice, Component.literal("Block"),
			Component.literal("Stone"), true, 0.0);
		var style = new SpatialOverlayRenderer.Style(20, 80, 1.0, 1.0, true, false);

		assertEquals(SpatialOverlayRenderer.layerAlpha(plain, 1.0, style),
			SpatialOverlayRenderer.layerAlpha(detailed, 1.0, style), 1.0e-12);
		assertEquals(SpatialOverlayRenderer.renders(plain, 1.0, style),
			SpatialOverlayRenderer.renders(detailed, 1.0, style));
		assertTrue(SpatialOverlayRenderer.acknowledgesNodePaint(true, true, 1.0, style));
		assertFalse(SpatialOverlayRenderer.renders(detailed, 1.0,
			new SpatialOverlayRenderer.Style(0, 0, 1.0, 1.0, true, false)),
			"a zero target opacity still drops every text-bearing frame");
	}

	@Test
	void exitTransitionRetainsTheDetachedDetailComponent() {
		var detail = Component.literal("Stone");
		var style = style(100, 100, 1.5);
		var target = nodes(preciseController(230.0, true).snapshot(), choice ->
			choice.id().equals("precise:block") ? detail : details(false).apply(choice), style, 480.0, 270.0).get(3);
		var paint = (SpatialOverlayRenderer.NodePaint) target.data();
		var transitions = new SpatialOverlayTransitions<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>(
			160_000_000L, 8, 0.92);
		transitions.update(List.of(target), 0L, false);

		var exiting = transitions.update(List.of(), 1_000_000L, false);

		assertEquals(1, exiting.size());
		var retained = assertInstanceOf(SpatialOverlayRenderer.NodePaint.class, exiting.getFirst().data());
		assertSame(detail, retained.targetDetail(), "the exit tail keeps the detail it was painted with");
		assertTrue(Double.isFinite(retained.maxExtent()));
		assertEquals(layout(paint, style), layout(retained, style), "exit paint keeps its frame constraint with its name");
		assertFalse(exiting.getFirst().present());
	}

	private static float glyphAdvance(int codePoint) {
		if (codePoint >= 0x2E80) return 9.0f;
		return switch (codePoint) {
			case 'i', 'l' -> 2.0f;
			case ' ', 't', 'r', 'I', '(', ')' -> 4.0f;
			case 'C' -> 7.0f;
			case 'f' -> 5.0f;
			default -> 6.0f;
		};
	}

	private static int width(Component label) {
		return (int) Math.ceil(FONT.stringWidth(label));
	}

	private static SpatialOverlayRenderer.Style style(int optionPercent, int targetPercent, double radiusScale) {
		return SpatialOverlayRenderer.Style.fromLegacyFontSizes(100, 100, optionPercent, targetPercent,
			88.0, radiusScale, true, false);
	}

	private static SpatialController controller(SpatialMenu child, double entryBearing) {
		var root = SpatialMenu.of("root", SpatialMenu.Choice.branch("precise", "Entity Block", child)
			.withSector(entryBearing, 35.0));
		var controller = new SpatialController(root, new SpatialController.Tuning(7.0, 18.0, 1000L, false, 500L));
		controller.start(0L);
		double radians = Math.toRadians(entryBearing);
		controller.enterExternal(child, Math.sin(radians) * 120.0, -Math.cos(radians) * 120.0, 1L);
		return controller;
	}

	private static SpatialController preciseController(double entryBearing, boolean mixed) {
		var choices = new ArrayList<SpatialMenu.Choice>();
		for (int slot = 0; slot < IDS.length; slot++) {
			String id = "precise:" + IDS[slot];
			choices.add(mixed && slot == 2 ? SpatialMenu.Choice.disabled(id, TITLES[slot])
				: SpatialMenu.Choice.leaf(id, TITLES[slot], id));
		}
		return controller(new SpatialMenu("fixed-target-slots", choices), entryBearing);
	}

	private static void focus(SpatialController controller, int slot) {
		var menu = controller.snapshot().menus().getLast();
		double radians = Math.toRadians(midpoint(menu.choices().get(slot)));
		controller.rebase(menu.origin().x() + Math.sin(radians) * 60.0,
			menu.origin().y() - Math.cos(radians) * 60.0, 2L);
	}

	private static double midpoint(SpatialController.ChoiceView choice) {
		return (choice.startDegrees() + choice.spanDegrees() / 2.0) % 360.0;
	}

	private static int preciseSlot(SpatialController.ChoiceView choice) {
		for (int slot = 0; slot < IDS.length; slot++) {
			if (choice.id().equals("precise:" + IDS[slot])) return slot;
		}
		return -1;
	}

	private static Function<SpatialController.ChoiceView, Component> details(boolean chinese) {
		return choice -> {
			int slot = preciseSlot(choice);
			return slot < 0 || choice.disabled() ? null : Component.literal((chinese ? ZH_DETAILS : DETAILS)[slot]);
		};
	}

	private static List<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>>
		nodes(SpatialController.Snapshot snapshot, Function<SpatialController.ChoiceView, Component> details,
			SpatialOverlayRenderer.Style style, double guiWidth, double guiHeight) {
		return nodes(snapshot, details, style, guiWidth, guiHeight, false);
	}

	private static List<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>>
		nodes(SpatialController.Snapshot snapshot, Function<SpatialController.ChoiceView, Component> details,
			SpatialOverlayRenderer.Style style, double guiWidth, double guiHeight, boolean chinese) {
		var targets = new ArrayList<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>>();
		SpatialOverlayRenderer.addRadial(targets, snapshot, choice -> {
			int slot = preciseSlot(choice);
			return Component.literal(choice.back() ? (chinese ? "返回" : "Back")
				: chinese && slot >= 0 ? ZH_TITLES[slot] : choice.label());
		}, details, guiWidth / 2.0, guiHeight / 2.0, SpatialOverlayRenderer.orbitFor(Math.min(guiWidth, guiHeight)),
			style.rootDistance(), style.submenuRadiusScale());
		String activeMenu = snapshot.menus().getLast().menuId();
		return targets.stream().filter(target -> target.data() instanceof SpatialOverlayRenderer.NodePaint
			&& target.key().owner().equals(activeMenu)).toList();
	}

	private static SpatialOverlayRenderer.NodePaint entityNode(SpatialController controller, String detail,
		SpatialOverlayRenderer.Style style) {
		return (SpatialOverlayRenderer.NodePaint) nodes(controller.snapshot(), choice ->
			choice.id().equals("precise:entity") ? Component.literal(detail) : details(false).apply(choice),
			style, 480.0, 270.0).get(1).data();
	}

	private static SpatialOverlayRenderer.NodeLayout layout(SpatialOverlayRenderer.NodePaint node,
		SpatialOverlayRenderer.Style style) {
		return SpatialOverlayRenderer.nodeLayout(node, SpatialOverlayRendererNodeLayoutTest::width, LINE_HEIGHT, style);
	}

	private static String clipped(Component label, SpatialOverlayRenderer.NodeLayout layout, boolean title) {
		return SpatialOverlayRenderer.clippedNodeText(label, layout.contentWidth(),
			title ? layout.titleScale() : layout.detailScale(), FONT).getString();
	}

	private static void assertClipped(Component label, SpatialOverlayRenderer.NodeLayout layout, boolean title) {
		String clipped = clipped(label, layout, title);
		assertFalse(clipped.isEmpty(), "clipping must retain readable glyphs, not vanish the line");
		assertTrue(label.getString().startsWith(clipped));
		assertTrue(clipped.length() < label.getString().length());
		double scale = title ? layout.titleScale() : layout.detailScale();
		assertTrue(width(Component.literal(clipped)) * scale <= layout.contentWidth() + 1.0e-9);
	}

	private static void assertRowsSeparated(SpatialOverlayRenderer.NodeLayout layout) {
		assertTrue(layout.titleTop(LINE_HEIGHT) >= layout.top() + 1.0);
		assertTrue(layout.detailTop(LINE_HEIGHT) >= layout.titleTop(LINE_HEIGHT) + LINE_HEIGHT * layout.titleScale());
		assertTrue(layout.detailTop(LINE_HEIGHT) + LINE_HEIGHT * layout.detailScale() <= layout.bottom() - 1.0 + 1.0e-9,
			"the detail row remains inside the actual rounded frame and below the title");
	}

	private record Bounds(double left, double top, double right, double bottom) {}

	private static Bounds bounds(
		SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> target,
		SpatialOverlayRenderer.Style style) {
		var node = (SpatialOverlayRenderer.NodePaint) target.data();
		var layout = layout(node, style);
		// drawNode uses a float pose scale, and dashedLine can fill right/bottom inclusively.
		double scale = (float) target.scale();
		int endpoint = node.choice().disabled() || node.choice().reserved() ? 1 : 0;
		return new Bounds(target.x() + layout.left() * scale, target.y() + layout.top() * scale,
			target.x() + (layout.right() + endpoint) * scale, target.y() + (layout.bottom() + endpoint) * scale);
	}

	private static void assertFramesSeparated(
		List<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>> nodes,
		SpatialOverlayRenderer.Style style, String scenario) {
		for (int a = 0; a < nodes.size(); a++) {
			Bounds first = bounds(nodes.get(a), style);
			for (int b = a + 1; b < nodes.size(); b++) {
				Bounds second = bounds(nodes.get(b), style);
				boolean overlap = first.left() < second.right() && second.left() < first.right()
					&& first.top() < second.bottom() && second.top() < first.bottom();
				assertFalse(overlap, scenario + ", overlapping pair " + nodes.get(a).key().entry()
					+ "/" + nodes.get(b).key().entry() + ": " + first + " / " + second);
			}
		}
	}
}
