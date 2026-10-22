package nx.pingwheel.common.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;

/**
 * Finite, non-overlapping radial bands for a GUI sector underlay, not a bloom
 * pass. The caller owns the pose: the sector is centered at its local origin,
 * zero degrees points up, and positive angles run clockwise in GUI coordinates.
 * Angles use a start and a positive span, including a wrapped sector or a full
 * disc, rather than a start/end pair.
 *
 * <p>Coverage is preserved at the center and fades with inverse smoothstep to
 * zero at the outer radius. RGB is unchanged; the supplied color's alpha byte
 * is ignored. Coverage, the opacity percentage and falloff remain fractional
 * until each vertex's final alpha-byte rounding.
 */
final class RadialGradient {

	private static final int RADIAL_BANDS = 16;
	private static final int FULL_CIRCLE_SEGMENTS = 96;

	private RadialGradient() {}

	/**
	 * Queues the mesh in the native translucent POSITION_COLOR/QUADS GUI route,
	 * using the current pose matrix and scissor without changing global render
	 * state. No flush, pose push/pop or texture binding occurs here.
	 *
	 * <p>The caller must flush earlier GUI work before a consecutive underlay
	 * batch, then flush that batch before later-layer fills, text or icons (and
	 * before changing scissor/render state). For example: flush, push/translate/
	 * scale, draw the sectors, pop, flush, then paint foreground. Do not flush per
	 * quad or radial band. Each vertex is transformed while it is queued, so the
	 * caller may pop the pose before the final batch flush.
	 *
	 * @param coverage normalized background coverage before the wheel opacity
	 * @param opacityPercent wheel-underlay opacity percentage, not target opacity
	 */
	static void draw(GuiGraphics graphics, double radius, double startDegrees,
		double spanDegrees, int rgb, double coverage, double opacityPercent) {
		if (peakAlpha(radius, startDegrees, spanDegrees, coverage, opacityPercent) < 0.5) {
			return;
		}
		var matrix = graphics.pose().last().pose();
		VertexConsumer vertices = graphics.bufferSource().getBuffer(RenderType.gui());
		emit(radius, startDegrees, spanDegrees, rgb, coverage, opacityPercent,
			(x, y, color) -> vertices.addVertex(matrix, x, y, 0.0f).setColor(color));
	}

	/**
	 * Pure production mesh emitter. Every consecutive four vertices form one
	 * native GUI-wound quad: inner-start, inner-end, outer-end, outer-start. The
	 * first band's two inner vertices coincide at the center, yielding one
	 * degenerate triangle rather than overlapping filled discs.
	 *
	 * <p>The sink receives the actual local float positions and packed ARGB sent
	 * by {@link #draw}. Adjacent bands and angular segments share their edges;
	 * full-circle endpoints are normalized to the same bearing. Work is bounded
	 * independently of radius, with the angular budget proportional to span.
	 *
	 * <p>A non-positive or non-finite radius/span, a radius outside the finite
	 * float range, or any non-finite angle/coverage/opacity emits nothing. Spans
	 * over one turn cover one disc. Finite coverage and opacity are clamped to
	 * their normalized/percentage ranges. A quantized-zero peak emits nothing.
	 */
	static void emit(double radius, double startDegrees, double spanDegrees,
		int rgb, double coverage, double opacityPercent, VertexSink sink) {
		double peak = peakAlpha(radius, startDegrees, spanDegrees, coverage, opacityPercent);
		if (peak < 0.5) {
			return;
		}
		double start = normalizeDegrees(startDegrees);
		double span = Math.min(360.0, spanDegrees);
		int segments = Math.max(1, (int) Math.ceil(FULL_CIRCLE_SEGMENTS * span / 360.0));
		int color = rgb & 0x00FFFFFF;
		for (int segment = 0; segment < segments; segment++) {
			double first = Math.toRadians(normalizeDegrees(start + span * segment / segments));
			double end = span == 360.0 && segment + 1 == segments ? start
				: normalizeDegrees(start + span * (segment + 1) / segments);
			double last = Math.toRadians(end);
			double firstSin = Math.sin(first);
			double firstCos = Math.cos(first);
			double lastSin = Math.sin(last);
			double lastCos = Math.cos(last);
			float innerStartX = 0.0f;
			float innerStartY = 0.0f;
			float innerEndX = 0.0f;
			float innerEndY = 0.0f;
			int innerColor = color | ((int) Math.round(peak) << 24);
			for (int band = 1; band <= RADIAL_BANDS; band++) {
				double fraction = band / (double) RADIAL_BANDS;
				double outerRadius = radius * fraction;
				float outerStartX = (float) (outerRadius * firstSin);
				float outerStartY = (float) (-outerRadius * firstCos);
				float outerEndX = (float) (outerRadius * lastSin);
				float outerEndY = (float) (-outerRadius * lastCos);
				double falloff = 1.0 - fraction * fraction * (3.0 - 2.0 * fraction);
				int outerColor = color | ((int) Math.round(peak * falloff) << 24);
				sink.vertex(innerStartX, innerStartY, innerColor);
				sink.vertex(innerEndX, innerEndY, innerColor);
				sink.vertex(outerEndX, outerEndY, outerColor);
				sink.vertex(outerStartX, outerStartY, outerColor);
				innerStartX = outerStartX;
				innerStartY = outerStartY;
				innerEndX = outerEndX;
				innerEndY = outerEndY;
				innerColor = outerColor;
			}
		}
	}

	/** No pose, buffer, render state or per-vertex allocation is required by the emitter. */
	@FunctionalInterface
	interface VertexSink {
		void vertex(float x, float y, int argb);
	}

	private static double peakAlpha(double radius, double startDegrees, double spanDegrees,
		double coverage, double opacityPercent) {
		if (!Double.isFinite(radius) || radius <= 0.0 || radius > Float.MAX_VALUE
			|| !Double.isFinite(startDegrees) || !Double.isFinite(spanDegrees) || spanDegrees <= 0.0
			|| !Double.isFinite(coverage) || !Double.isFinite(opacityPercent)) {
			return 0.0;
		}
		return 255.0 * Math.max(0.0, Math.min(1.0, coverage))
			* Math.max(0.0, Math.min(100.0, opacityPercent)) / 100.0;
	}

	private static double normalizeDegrees(double degrees) {
		double normalized = degrees % 360.0;
		return normalized < 0.0 ? normalized + 360.0 : normalized;
	}
}
