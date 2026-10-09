package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import net.minecraft.client.StringSplitter;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialMenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives controller snapshots through production radial planning, NodePaint
 * layout, title clipping and external preview wrapping. Collision expectations
 * use the actual rounded frame bounds (including the disabled border's inclusive
 * endpoint), not six copies of a hypothetical merged box. Font advances model the vanilla
 * Latin bitmap advances and nine-pixel CJK test glyphs without loading a game client.
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
										assertFalse(clipped(node.label(), layout).isBlank(),
											"a nonblank type or Back label must retain a visible glyph");
										if (node.hasDetail()) assertPreviewOutside(layout);
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
			assertClipped(node.label(), layout);
			assertTrue(layout.titleScale() >= style(100, 100, 1.0).optionTextScale());
		}
		var entity = (SpatialOverlayRenderer.NodePaint) nodes.get(1).data();
		for (String name : new String[] {"猪", LONG_NAME.repeat(16), "自定义超长名字（钻石剑）".repeat(16)}) {
			var node = new SpatialOverlayRenderer.NodePaint(entity.choice(), entity.label(), Component.literal(name),
				entity.selected(), entity.hoverProgress(), entity.maxExtent());
			var layout = layout(node, style);
			assertFalse(clipped(node.label(), layout).isBlank());
			assertFalse(layout.detail().text().getString().isBlank());
			assertTrue(layout.detailScale() >= style(100, 100, 1.0).optionTextScale());
			assertPreviewOutside(layout);
		}
		assertFramesSeparated(nodes, style, "Chinese 400x240, radius scale 1, option scale 2");
	}

	@Test
	void previewCapPreservesStyledSupplementaryCodePointsAcrossComponentSegments() {
		var style = style(50, 100, 1.5);
		var entity = entityNode(preciseController(230.0, true), "Pig", style);
		var firstStyle = net.minecraft.network.chat.Style.EMPTY.withItalic(true).withColor(0x12AB56);
		var secondStyle = net.minecraft.network.chat.Style.EMPTY.withBold(true).withColor(0xAB1256);
		var detail = Component.empty().append(Component.literal("a".repeat(29)).withStyle(firstStyle))
			.append(Component.literal("\uD83D").withStyle(secondStyle))
			.append(Component.literal("\uDE00\uD840\uDC00XYZ").withStyle(secondStyle));
		var node = new SpatialOverlayRenderer.NodePaint(entity.choice(), entity.label(), detail,
			entity.selected(), entity.hoverProgress(), entity.maxExtent());
		var layout = layout(node, style);
		assertEquals("a".repeat(29) + "\uD83D\uDE00\uD840\uDC00\u2026", layout.detail().text().getString());
		assertEquals(32, codePoints(layout.detail().text().getString()));
		assertEquals(layout.detail().text().getString(), paintedDetail(layout), "actual painted lines retain every capped glyph");
		var points = styledPoints(layout.detail().text());
		for (int i = 0; i < 29; i++) assertEquals(firstStyle, points.get(i).style());
		for (int i = 29; i < 32; i++) assertEquals(secondStyle, points.get(i).style());
		assertEquals(0x1F600, points.get(29).codePoint());
		assertEquals(0x20000, points.get(30).codePoint());
		assertPreviewOutside(layout);
	}

	@Test
	void namesNeverResizeTheTitleFrameAndWideShortNamesAreNotButtonClipped() {
		var controller = preciseController(230.0, true);
		var style = style(100, 100, 1.5);
		var pig = entityNode(controller, "Pig", style);
		var longName = entityNode(controller, LONG_NAME, style);
		var wideName = entityNode(controller, "W".repeat(32), style);
		var pigLayout = layout(pig, style);
		var longLayout = layout(longName, style);
		var wideLayout = layout(wideName, style);
		var plainLayout = layout(new SpatialOverlayRenderer.NodePaint(pig.choice(), pig.label(), null,
			pig.selected(), pig.hoverProgress(), pig.maxExtent()), style);

		assertEquals(202, width(longName.targetDetail()), "fixture uses vanilla Latin bitmap advances");
		assertEquals(style.inventoryTextScale(), pigLayout.detailScale(), 1.0e-9);
		assertEquals(style.optionTextScale(), pigLayout.titleScale(), 1.0e-9);
		assertEquals("Pig", paintedDetail(pigLayout));
		for (var preview : List.of(pigLayout, longLayout, wideLayout)) {
			assertEquals(plainLayout.width(), preview.width());
			assertEquals(plainLayout.height(), preview.height());
			assertEquals(plainLayout.titleScale(), preview.titleScale());
			assertPreviewOutside(preview);
		}
		assertEquals(LONG_NAME.substring(0, 31) + "\u2026", longLayout.detail().text().getString());
		assertEquals("W".repeat(32), paintedDetail(wideLayout));
		assertTrue(wideLayout.detail().width() > wideLayout.contentWidth(), "preview uses cell space beyond the button inset");
		assertNull(plainLayout.detail(), "missing detail allocates no external text area");
		assertEquals(0.0, plainLayout.detailScale());
	}

	@Test
	void exactCodePointBoundaryIsUnchangedAndOverflowIsExactly31PlusEllipsis() {
		var controller = preciseController(230.0, false);
		var style = style(100, 100, 1.5);
		for (String unit : new String[] {"W", "方", "\uD83D\uDE00"}) {
			for (int count : new int[] {1, 31, 32, 33, 256}) {
				String source = unit.repeat(count);
				var layout = layout(entityNode(controller, source, style), style);
				String expected = count <= 32 ? source : unit.repeat(31) + "\u2026";
				assertEquals(expected, layout.detail().text().getString());
				assertEquals(expected, paintedDetail(layout));
				assertEquals(Math.min(32, count), codePoints(paintedDetail(layout)));
				assertTrue(layout.detailScale() >= Math.min(style.inventoryTextScale(), 0.5));
				assertPreviewOutside(layout);
			}
		}
	}

	@Test
	void shortMixedStylePreviewKeepsTheActualPaintedGlyphsAndWhitespace() {
		var controller = preciseController(230.0, true);
		var style = style(100, 100, 1.5);
		var entity = entityNode(controller, "Pig", style);
		var red = net.minecraft.network.chat.Style.EMPTY.withColor(0xFF0000).withBold(true);
		var blue = net.minecraft.network.chat.Style.EMPTY.withColor(0x0000FF).withItalic(true);
		var source = Component.empty().append(Component.literal("Pig ").withStyle(red))
			.append(Component.literal("\uD83D\uDE00").withStyle(blue));
		var node = new SpatialOverlayRenderer.NodePaint(entity.choice(), entity.label(), source,
			entity.selected(), entity.hoverProgress(), entity.maxExtent());
		var layout = layout(node, style);
		assertEquals("Pig \uD83D\uDE00", layout.detail().text().getString());
		assertEquals(layout.detail().text().getString(), paintedDetail(layout));
		assertEquals(styledPoints(source), layout.detail().lines().stream()
			.flatMap(line -> styledPoints(line.text()).stream()).toList());
		assertPreviewOutside(layout);
	}

	@Test
	void legacyBoldPreviewKeepsVanillaVisualOrderAndPaintedStyle() {
		Component source = Component.literal("\u00A7lPig");
		var layout = preview(source, style(200, 100, 1.5));
		assertEquals("Pig", layout.detail().text().getString());
		assertEquals(visualPoints(source.getVisualOrderText()), visualPoints(layout.detail().text().getVisualOrderText()));
		assertEquals(visualPoints(source.getVisualOrderText()), paintedVisualPoints(layout));
		assertTrue(paintedVisualPoints(layout).stream().allMatch(point -> point.style().isBold()));
		assertEquals("\u00A7lPig", source.getString(), "format normalization never mutates its input");
	}

	@Test
	void legacyColorsResetAndExplicitStyleInheritanceMatchVanilla() {
		var inherited = net.minecraft.network.chat.Style.EMPTY.withColor(0x12AB56).withItalic(true);
		Component source = Component.empty().withStyle(inherited)
			.append(Component.literal("\u00A7lPig\u00A7rQ"))
			.append(Component.literal("\u00A7cR\u00A7rS").withStyle(net.minecraft.network.chat.Style.EMPTY.withBold(true)))
			.append(Component.literal("T"));
		var layout = preview(source, style(200, 100, 1.5));
		var expected = visualPoints(source.getVisualOrderText());
		assertEquals("PigQRST", layout.detail().text().getString());
		assertEquals(expected, visualPoints(layout.detail().text().getVisualOrderText()));
		assertEquals(expected, paintedVisualPoints(layout));
		assertTrue(expected.get(0).style().isBold());
		assertEquals(inherited, expected.get(3).style(), "reset restores this segment's inherited base style");
		assertEquals(0xFF5555, expected.get(4).style().getColor().getValue());
		assertFalse(expected.get(4).style().isItalic(), "legacy color clears preceding format flags");
		assertTrue(expected.get(5).style().isBold(), "reset restores the explicit segment style");
		assertEquals(inherited, expected.get(6).style(), "legacy flags do not escape into sibling segments");
	}

	@Test
	void incompleteLegacySequenceNeverCrossesAComponentBoundary() {
		for (Component source : List.of(
			Component.empty().append(Component.literal("\u00A7")).append(Component.literal("lPig")),
			Component.empty().append(Component.literal("\u00A7lPig")).append(Component.literal("Q")))) {
			var layout = preview(source, style(200, 100, 1.5));
			assertEquals(visualPoints(source.getVisualOrderText()), visualPoints(layout.detail().text().getVisualOrderText()));
			assertEquals(visualPoints(source.getVisualOrderText()), paintedVisualPoints(layout));
			assertFalse(paintedVisualPoints(layout).getLast().style().isBold());
		}
	}

	@Test
	void legacyFormattingDoesNotConsumeTheVisibleCapAndEllipsisKeepsTheLastGlyphStyle() {
		Component boundary = Component.literal("\u00A7l" + "\uD83D\uDE00".repeat(32));
		var unchanged = preview(boundary, style(200, 100, 1.5));
		assertEquals(32, visualPoints(unchanged.detail().text().getVisualOrderText()).size());
		assertEquals(visualPoints(boundary.getVisualOrderText()), visualPoints(unchanged.detail().text().getVisualOrderText()));
		assertEquals(visualPoints(boundary.getVisualOrderText()), paintedVisualPoints(unchanged));
		Component source = Component.empty().append(Component.literal("a".repeat(29)))
			.append(Component.literal("\u00A7l\uD83D\uDE00\uD840\uDC00\u00A7rXYZ"));
		var truncated = preview(source, style(200, 100, 1.5));
		var expected = new ArrayList<>(visualPoints(source.getVisualOrderText()).subList(0, 31));
		expected.add(new StyledPoint(0x2026, expected.getLast().style()));
		assertEquals(expected, visualPoints(truncated.detail().text().getVisualOrderText()));
		assertEquals(expected, paintedVisualPoints(truncated));
		assertTrue(expected.getLast().style().isBold(), "ellipsis inherits the last retained visible glyph, not the later reset");
	}

	@Test
	void surrogatePairCrossingSegmentsKeepsItsResolvedLegacyStyleAndWholePaintedCodePoint() {
		var explicit = net.minecraft.network.chat.Style.EMPTY.withColor(0x12AB56);
		Component source = Component.empty().append(Component.literal("\u00A7l\uD83D").withStyle(explicit))
			.append(Component.literal("\uDE00Q").withStyle(explicit));
		var layout = preview(source, style(200, 100, 1.5));
		var points = paintedVisualPoints(layout);
		assertEquals("\uD83D\uDE00Q", layout.detail().text().getString());
		assertEquals(List.of(new StyledPoint(0x1F600, explicit.withBold(true)), new StyledPoint('Q', explicit)), points);
		assertEquals(points, visualPoints(layout.detail().text().getVisualOrderText()));
	}

	@Test
	void formattingOnlyDetailHasNoAreaAndCappingNeverVisitsTheOverflowTail() {
		var style = style(200, 100, 1.5);
		assertNull(preview(Component.literal("\u00A7l\u00A7r"), style).detail());
		var tailReads = new AtomicInteger();
		Component tail = MutableComponent.create(new ComponentContents() {
			@Override
			public <T> Optional<T> visit(FormattedText.StyledContentConsumer<T> consumer, net.minecraft.network.chat.Style style) {
				tailReads.incrementAndGet();
				throw new AssertionError("overflow traversal must not read the remaining component");
			}
			@Override
			public <T> Optional<T> visit(FormattedText.ContentConsumer<T> consumer) {
				tailReads.incrementAndGet();
				throw new AssertionError("a full getString peek must not read the remaining component");
			}
			@Override
			public ComponentContents.Type<?> type() { return null; }
		});
		Component source = Component.literal("\u00A7l" + "\uD83D\uDE00".repeat(33)).append(tail);
		var entity = entityNode(preciseController(230.0, true), "Pig", style);
		var node = new SpatialOverlayRenderer.NodePaint(entity.choice(), entity.label(), source,
			entity.selected(), entity.hoverProgress(), entity.maxExtent());
		assertTrue(node.hasDetail(), "availability checks also stay within the visible cap");
		var layout = layout(node, style);
		assertEquals(32, paintedVisualPoints(layout).size());
		assertEquals(0x2026, paintedVisualPoints(layout).getLast().codePoint());
		assertEquals(0, tailReads.get());
	}

	@Test
	void absentOrBlankPreviewAddsNoGeometry() {
		var style = style(100, 100, 1.5);
		var entity = entityNode(preciseController(230.0, true), "Pig", style);
		var missing = layout(new SpatialOverlayRenderer.NodePaint(entity.choice(), entity.label(), null,
			entity.selected(), entity.hoverProgress(), entity.maxExtent()), style);
		var blank = layout(new SpatialOverlayRenderer.NodePaint(entity.choice(), entity.label(), Component.literal("  "),
			entity.selected(), entity.hoverProgress(), entity.maxExtent()), style);
		assertEquals(missing, blank);
		assertNull(blank.detail());
	}

	@Test
	void defaultMixedSixSlotPlanKeepsExternalSiblingPreviewsSeparatedAtReadableScale() {
		var style = style(200, 100, 1.5);
		var controller = preciseController(230.0, true);
		for (int selected = 0; selected < 6; selected++) {
			focus(controller, selected);
			for (boolean chinese : new boolean[] {false, true}) {
				var nodes = nodes(controller.snapshot(), details(chinese), style, 400.0, 240.0, chinese);
				assertEquals(6, nodes.size());
				assertFramesSeparated(nodes, style, "default mixed six slots");
				for (int a = 0; a < nodes.size(); a++) {
					var first = layout((SpatialOverlayRenderer.NodePaint) nodes.get(a).data(), style);
					if (first.detail() == null) continue;
					assertPreviewOutside(first);
					assertTrue(first.detailScale() >= 0.5 && first.detailScale() <= style.inventoryTextScale());
					assertTrue(first.detail().width() <= ((SpatialOverlayRenderer.NodePaint) nodes.get(a).data()).maxExtent() + 1.0,
						"wrapping uses the full shared cell, not an unrelated viewport or title limit");
					for (int b = a + 1; b < nodes.size(); b++) {
						if (layout((SpatialOverlayRenderer.NodePaint) nodes.get(b).data(), style).detail() != null) {
							assertFalse(overlap(detailBounds(nodes.get(a), style), detailBounds(nodes.get(b), style)),
								"default external previews must not collide: " + a + "/" + b + ", selected " + selected);
						}
					}
				}
			}
		}
	}

	@Test
	void defaultPreviewFitReservesTheTitleAndGapBeforeRewrappingAtTheReportedCollision() {
		var style = style(200, 100, 1.5);
		var controller = preciseController(208.0, true);
		focus(controller, 5);
		String name = "Stone Stone Stone Stone (Egg)";
		var nodes = nodes(controller.snapshot(), choice -> choice.id().equals("precise:dropped_item")
			? Component.literal(name) : details(false).apply(choice), style, 400.0, 240.0);
		var node = (SpatialOverlayRenderer.NodePaint) nodes.getFirst().data();
		var layout = layout(node, style);
		assertEquals(29, codePoints(name));
		assertEquals(28, width(Component.literal("Stone")));
		assertEquals(26, width(Component.literal("(Egg)")));
		assertEquals(32.64, node.maxExtent(), 1.0e-9);
		assertEquals(0.5, layout.detailScale(), "five preferred-size rows exceed the remaining frame-plus-gap cell clearance");
		assertEquals(List.of("Stone Stone", "Stone Stone", "(Egg)"),
			layout.detail().lines().stream().map(line -> line.text().getString()).toList());
		assertEquals(name, layout.detail().text().getString(), "a short name remains complete, not geometry-truncated");
		assertEquals(visualPoints(Component.literal(name.replace(" ", "")).getVisualOrderText()),
			paintedVisualPoints(layout).stream().filter(point -> point.codePoint() != ' ').toList());
		assertPreviewOutside(layout);
		assertPreviewsClearSiblings(nodes, style, "208 degrees, Back focused");
	}

	@Test
	void default200PercentOptionFontPreviewsClearSiblingFramesAndPreviewsAcrossRotations() {
		var style = style(200, 100, 1.5);
		for (boolean mixed : new boolean[] {false, true}) {
			for (double entryBearing = 0.0; entryBearing < 360.0; entryBearing += 4.0) {
				var controller = preciseController(entryBearing, mixed);
				for (int selected = 0; selected < 6; selected++) {
					focus(controller, selected);
					for (boolean chinese : new boolean[] {false, true}) {
						var nodes = nodes(controller.snapshot(), choice -> {
							if (choice.id().equals("precise:dropped_item")) return Component.literal("Stone Stone Stone Stone (Egg)");
							return details(chinese).apply(choice);
						}, style, 400.0, 240.0, chinese);
						String scenario = "default option 200%, entry " + entryBearing + ", selected " + selected
							+ ", mixed " + mixed + ", Chinese " + chinese;
						assertFramesSeparated(nodes, style, scenario);
						assertPreviewsClearSiblings(nodes, style, scenario);
					}
				}
			}
		}
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
		assertPreviewOutside(title);
		assertPreviewOutside(detail);
	}

	@Test
	void growingEnglishAndChineseNamesStopAtTheCapWithoutShrinkingToZero() {
		var controller = preciseController(230.0, false);
		var style = style(100, 100, 1.5);
		var baseline = layout(entityNode(controller, "Pig", style), style);
		for (String prefix : new String[] {LONG_NAME, "自定义超长名字（钻石剑）"}) {
			for (int repetitions : new int[] {1, 4, 16, 64}) {
				var node = entityNode(controller, prefix.repeat(repetitions), style);
				var layout = layout(node, style);
				assertEquals(baseline.titleScale(), layout.titleScale(), 1.0e-9);
				assertTrue(layout.detailScale() >= Math.min(style.inventoryTextScale(), 0.5));
				assertTrue(codePoints(layout.detail().text().getString()) <= 32);
				assertPreviewOutside(layout);
			}
		}
	}

	@Test
	void titleHeightFitCannotConstrainTheExternalPreviewAndReadableFloorsRemain() {
		var controller = preciseController(230.0, false);
		double radialBase = style(100, 100, 1.0).optionTextScale();
		for (int[] preference : new int[][] {{1000, 100}, {100, 500}, {1000, 500}, {50, 25}}) {
			var style = style(preference[0], preference[1], 1.0);
			var node = entityNode(controller, LONG_NAME.repeat(16), style);
			var layout = layout(node, style);
			assertTrue(layout.titleScale() >= Math.min(radialBase, style.optionTextScale()) - 1.0e-9);
			assertTrue(layout.detailScale() >= Math.min(radialBase, style.inventoryTextScale()) - 1.0e-9,
				"a large title or target font cannot shrink the preview below the radial base size");
			assertTrue(layout.titleScale() <= style.optionTextScale());
			assertTrue(layout.detailScale() <= style.inventoryTextScale());
			assertTrue(layout.bottom() - layout.top() + 1 <= node.maxExtent());
			assertEquals(32, codePoints(layout.detail().text().getString()));
			assertPreviewOutside(layout);
		}
	}

	@Test
	void titlePreferenceOnlyChangesPreviewClearanceWithoutChangingItsVisibleTextOrFloor() {
		var controller = preciseController(230.0, false);
		var baselineStyle = style(100, 100, 1.0);
		var largeTitleStyle = style(1000, 100, 1.0);
		var baseline = layout(entityNode(controller, "W".repeat(32), baselineStyle), baselineStyle);
		var largeTitle = layout(entityNode(controller, "W".repeat(32), largeTitleStyle), largeTitleStyle);
		assertEquals(paintedDetail(baseline), paintedDetail(largeTitle));
		assertTrue(largeTitle.detailScale() >= Math.min(largeTitleStyle.inventoryTextScale(), 0.5));
		assertTrue(largeTitle.detailScale() <= baseline.detailScale());
		assertPreviewOutside(largeTitle);
	}

	@Test
	void backProgressRemainsOnTheTitleFrameEvenIfADetailIsSupplied() {
		var style = style(100, 100, 1.5);
		var controller = preciseController(230.0, true);
		focus(controller, 5);
		var back = (SpatialOverlayRenderer.NodePaint) nodes(controller.snapshot(), details(false), style, 400.0, 240.0).getLast().data();
		var plain = layout(back, style);
		var withDetail = layout(new SpatialOverlayRenderer.NodePaint(back.choice(), back.label(), Component.literal("Preview"),
			back.selected(), 1.0, back.maxExtent()), style);
		assertEquals(SpatialOverlayRenderer.backProgressSegments(plain.left(), plain.top(), plain.right(), plain.bottom(), 1.0),
			SpatialOverlayRenderer.backProgressSegments(withDetail.left(), withDetail.top(), withDetail.right(), withDetail.bottom(), 1.0));
		assertPreviewOutside(withDetail);
	}

	@Test
	void extremeGlyphWidthCannotAddASecondTruncationOrHideTheExternalLabel() {
		var style = style(1000, 500, 1.0);
		var entity = entityNode(preciseController(230.0, true), "Pig", style);
		var source = Component.literal("\uD83D\uDE00".repeat(32));
		var node = new SpatialOverlayRenderer.NodePaint(entity.choice(), entity.label(), source,
			entity.selected(), entity.hoverProgress(), entity.maxExtent());
		var wideGlyphs = new StringSplitter((codePoint, textStyle) -> 100.0f);
		var layout = SpatialOverlayRenderer.nodeLayout(node, text -> (int) Math.ceil(wideGlyphs.stringWidth(text)),
			LINE_HEIGHT, style, wideGlyphs);
		assertEquals(source.getString(), paintedDetail(layout), "overfull geometry does not authorize another text reduction");
		assertEquals(32, codePoints(paintedDetail(layout)));
		assertEquals(0.5, layout.detailScale(), "retain the readable floor when no glyph can fit the cell");
		assertTrue(layout.detail().width() > node.maxExtent());
		assertPreviewOutside(layout);
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
		assertClipped(node.label(), layout);
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
		var before = layout(paint, style);
		var after = layout(retained, style);
		assertEquals(before.width(), after.width());
		assertEquals(before.height(), after.height());
		assertEquals(before.titleScale(), after.titleScale());
		assertEquals(before.detailScale(), after.detailScale());
		assertEquals(before.detail().top(), after.detail().top());
		assertEquals(before.detail().height(), after.detail().height());
		assertEquals(paintedDetail(before), paintedDetail(after), "exit paint keeps its frame constraint with its name");
		assertFalse(exiting.getFirst().present());
	}

	/** Bitmap advances read from vanilla 1.21.1 ascii/nonlatin_european assets; non-Latin test glyphs are explicit. */
	private static float glyphAdvance(int codePoint) {
		if (codePoint >= 0x2E80) return 9.0f;
		return switch (codePoint) {
			case '!', '\'', ',', '.', ':', ';', 'i', '|' -> 2.0f;
			case '`', 'l' -> 3.0f;
			case ' ', '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4.0f;
			case '<', '>', 'f', 'k' -> 5.0f;
			case '@', '~' -> 7.0f;
			case '\u2026' -> 8.0f;
			default -> {
				if (codePoint >= 32 && codePoint <= 126) yield 6.0f;
				throw new AssertionError("missing deliberate glyph fixture for U+" + Integer.toHexString(codePoint));
			}
		};
	}

	private static int width(FormattedText label) {
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
		return SpatialOverlayRenderer.nodeLayout(node, SpatialOverlayRendererNodeLayoutTest::width, LINE_HEIGHT, style, FONT);
	}

	private static SpatialOverlayRenderer.NodeLayout preview(Component source, SpatialOverlayRenderer.Style style) {
		var entity = entityNode(preciseController(230.0, true), "Pig", style);
		return layout(new SpatialOverlayRenderer.NodePaint(entity.choice(), entity.label(), source,
			entity.selected(), entity.hoverProgress(), entity.maxExtent()), style);
	}

	private static String clipped(Component label, SpatialOverlayRenderer.NodeLayout layout) {
		return SpatialOverlayRenderer.clippedNodeText(label, layout.contentWidth(), layout.titleScale(), FONT).getString();
	}

	private static void assertClipped(Component label, SpatialOverlayRenderer.NodeLayout layout) {
		String clipped = clipped(label, layout);
		assertFalse(clipped.isEmpty(), "clipping must retain readable glyphs, not vanish the line");
		assertTrue(label.getString().startsWith(clipped));
		assertTrue(clipped.length() < label.getString().length());
		assertTrue(width(Component.literal(clipped)) * layout.titleScale() <= layout.contentWidth() + 1.0e-9);
	}

	private static void assertPreviewOutside(SpatialOverlayRenderer.NodeLayout layout) {
		assertTrue(layout.titleTop(LINE_HEIGHT) >= layout.top() + 1.0);
		assertTrue(layout.titleTop(LINE_HEIGHT) + LINE_HEIGHT * layout.titleScale() <= layout.bottom() - 1.0 + 1.0e-9);
		assertEquals(layout.bottom() + SpatialOverlayRenderer.NODE_DETAIL_GAP, layout.detail().top());
		assertTrue(layout.detail().top() > layout.bottom(), "candidate preview stays outside the frame with a visible gap");
		assertFalse(layout.detail().lines().isEmpty());
		assertTrue(layout.detail().height() > 0.0);
		assertEquals(0.0, layout.detail().left() + layout.detail().right(), 1.0e-9);
		assertTrue(codePoints(layout.detail().text().getString()) <= 32);
		for (var line : layout.detail().lines()) {
			assertEquals(-line.width() * layout.detailScale() / 2.0, line.left(layout.detailScale()));
		}
	}

	private static int codePoints(String text) { return text.codePointCount(0, text.length()); }

	private static String paintedDetail(SpatialOverlayRenderer.NodeLayout layout) {
		return layout.detail().lines().stream().map(line -> line.text().getString()).collect(java.util.stream.Collectors.joining());
	}

	private record StyledPoint(int codePoint, net.minecraft.network.chat.Style style) {}

	private static List<StyledPoint> visualPoints(FormattedCharSequence text) {
		var result = new ArrayList<StyledPoint>();
		text.accept((index, style, codePoint) -> {
			result.add(new StyledPoint(codePoint, style));
			return true;
		});
		return result;
	}

	private static List<StyledPoint> paintedVisualPoints(SpatialOverlayRenderer.NodeLayout layout) {
		return layout.detail().lines().stream().flatMap(line ->
			visualPoints(Language.getInstance().getVisualOrder(line.text())).stream()).toList();
	}

	private static List<StyledPoint> styledPoints(FormattedText text) {
		var result = new ArrayList<StyledPoint>();
		text.visit((style, segment) -> {
			segment.codePoints().forEach(codePoint -> result.add(new StyledPoint(codePoint, style)));
			return Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		return result;
	}

	private record Bounds(double left, double top, double right, double bottom) {}

	private static boolean overlap(Bounds first, Bounds second) {
		return first.left() < second.right() && second.left() < first.right()
			&& first.top() < second.bottom() && second.top() < first.bottom();
	}

	private static Bounds detailBounds(
		SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> target,
		SpatialOverlayRenderer.Style style) {
		var detail = layout((SpatialOverlayRenderer.NodePaint) target.data(), style).detail();
		double scale = (float) target.scale();
		return new Bounds(target.x() + detail.left() * scale, target.y() + detail.top() * scale,
			target.x() + detail.right() * scale, target.y() + detail.bottom() * scale);
	}

	private static List<Bounds> detailLineBounds(
		SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint> target,
		SpatialOverlayRenderer.Style style) {
		var detail = layout((SpatialOverlayRenderer.NodePaint) target.data(), style).detail();
		if (detail == null) return List.of();
		double scale = (float) target.scale();
		var result = new ArrayList<Bounds>();
		for (int i = 0; i < detail.lines().size(); i++) {
			var line = detail.lines().get(i);
			double top = detail.top() + LINE_HEIGHT * detail.scale() * i;
			result.add(new Bounds(target.x() + line.left(detail.scale()) * scale, target.y() + top * scale,
				target.x() - line.left(detail.scale()) * scale, target.y() + (top + LINE_HEIGHT * detail.scale()) * scale));
		}
		return result;
	}

	private static void assertPreviewsClearSiblings(
		List<SpatialOverlayTransitions.Target<SpatialOverlayRenderer.VisualKey, SpatialOverlayRenderer.Paint>> nodes,
		SpatialOverlayRenderer.Style style, String scenario) {
		for (int a = 0; a < nodes.size(); a++) {
			for (Bounds preview : detailLineBounds(nodes.get(a), style)) {
				for (int b = 0; b < nodes.size(); b++) {
					if (a == b) continue;
					assertFalse(overlap(preview, bounds(nodes.get(b), style)), scenario + ", preview/frame pair " + a + "/" + b);
					for (Bounds sibling : detailLineBounds(nodes.get(b), style)) {
						assertFalse(overlap(preview, sibling), scenario + ", preview/preview pair " + a + "/" + b);
					}
				}
			}
		}
	}

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
				assertFalse(overlap(first, second), scenario + ", overlapping pair " + nodes.get(a).key().entry()
					+ "/" + nodes.get(b).key().entry() + ": " + first + " / " + second);
			}
		}
	}
}
