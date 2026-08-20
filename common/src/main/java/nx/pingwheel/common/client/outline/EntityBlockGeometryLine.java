package nx.pingwheel.common.client.outline;

/**
 * One camera-relative line segment emitted by a deferred entity-block
 * geometry source.
 *
 * <p>The coordinates are deliberately plain doubles. Optional integrations
 * can collect geometry without touching a Minecraft buffer, and the late
 * common render pass is the only place that turns the immutable batch into
 * vertices.</p>
 */
public record EntityBlockGeometryLine(
	 double x0,
	 double y0,
	 double z0,
	 double x1,
	 double y1,
	 double z1
) {
	public boolean isFiniteNonZero() {
		float floatX0 = (float) x0;
		float floatY0 = (float) y0;
		float floatZ0 = (float) z0;
		float floatX1 = (float) x1;
		float floatY1 = (float) y1;
		float floatZ1 = (float) z1;
		return Double.isFinite(x0) && Double.isFinite(y0) && Double.isFinite(z0)
			&& Double.isFinite(x1) && Double.isFinite(y1) && Double.isFinite(z1)
			&& Float.isFinite(floatX0) && Float.isFinite(floatY0)
			&& Float.isFinite(floatZ0) && Float.isFinite(floatX1)
			&& Float.isFinite(floatY1) && Float.isFinite(floatZ1)
			&& (x0 != x1 || y0 != y1 || z0 != z1)
			&& (floatX0 != floatX1 || floatY0 != floatY1 || floatZ0 != floatZ1);
	}
}
