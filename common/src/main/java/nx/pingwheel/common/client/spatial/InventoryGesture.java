package nx.pingwheel.common.client.spatial;

import java.util.Objects;

/**
 * List-only gesture state. The controller remains the sole pointer owner: this
 * adapter retains only bounded exterior-side progress and the independent wheel
 * fraction/lock. All geometry is unanimated controller-space geometry.
 */
public final class InventoryGesture {

	public enum Action { NONE, BACK, FORWARD }

	private static final double WHEEL_UNLOCK = 14.0;
	private static final double CONE_TANGENT = Math.tan(Math.toRadians(25.0));
	private final InventoryListModel list;
	private final double deadzone;
	private final double stroke;
	private final boolean backHoverEnabled;
	private double backSign;
	private double horizontalProgress;
	private double wheelRemainder;
	private double wheelUnlockTravel;
	private boolean wheelLocked;

	public InventoryGesture(InventoryListModel list, double deadzone, double stroke, boolean backHoverEnabled) {
		this.list = Objects.requireNonNull(list, "list");
		if (!Double.isFinite(deadzone) || deadzone < 0 || !Double.isFinite(stroke) || stroke <= deadzone)
			throw new IllegalArgumentException("invalid side thresholds");
		this.deadzone = deadzone;
		this.stroke = stroke;
		this.backHoverEnabled = backHoverEnabled;
	}

	public void begin(InventoryListModel.Direction direction) {
		backSign = Objects.requireNonNull(direction).back() == InventoryListModel.Side.LEFT ? -1 : 1;
		rebase();
	}

	/** A surface handoff is not physical movement and never releases held Back. */
	public void rebase() {
		horizontalProgress = 0;
		wheelRemainder = 0;
		wheelUnlockTravel = 0;
		wheelLocked = false;
	}

	/** Ignore only this sample's near-vertical dx, not previously earned side progress. */
	public double horizontalDelta(double dx, double dy) {
		if (!Double.isFinite(dx) || !Double.isFinite(dy)) return 0;
		return dy != 0 && Math.abs(dx) <= Math.abs(dy) * CONE_TANGENT ? 0 : dx;
	}

	/**
	 * Count only physical travel outside the panel. At an unreachable panel edge,
	 * the corresponding viewport edge is the qualification boundary instead. Raw
	 * outward travel beyond confinement can continue a stroke, but never creates
	 * another pointer or banks overshoot. Alignment never calls this method.
	 */
	public Action moveHorizontal(double dx, double beforeX, double afterX, double panelLeft, double panelRight,
		SpatialController.Viewport viewport) {
		if (!Double.isFinite(dx) || dx == 0) return Action.NONE;
		double left = panelLeft, right = panelRight;
		if (viewport != null) {
			double screenLeft = -viewport.centerX() - viewport.viewX();
			double screenRight = viewport.width() - 1.0 - viewport.centerX() - viewport.viewX();
			left = Math.clamp(left, screenLeft, screenRight);
			right = Math.clamp(right, screenLeft, screenRight);
		}
		double previous = horizontalProgress;
		if (dx > 0) {
			if (afterX < left) horizontalProgress = Math.min(0, horizontalProgress + dx);
			else {
				double exterior = Math.max(0, dx - Math.max(0, right - beforeX));
				horizontalProgress = exterior > 0 ? Math.min(stroke, Math.max(0, horizontalProgress) + exterior) : 0;
			}
		} else {
			if (afterX > right) horizontalProgress = Math.max(0, horizontalProgress + dx);
			else {
				double exterior = Math.max(0, -dx - Math.max(0, beforeX - left));
				horizontalProgress = exterior > 0 ? -Math.min(stroke, Math.max(0, -horizontalProgress) + exterior) : 0;
			}
		}
		if (horizontalProgress == previous) return Action.NONE;
		if (horizontalProgress * backSign >= stroke) return backHoverEnabled ? Action.NONE : Action.BACK;
		if (horizontalProgress * -backSign >= stroke) return Action.FORWARD;
		return Action.NONE;
	}

	/** Signed-net raw GUI travel unlocks Y; only the excess over the lock is admitted. */
	public double verticalDelta(double dy) {
		if (!Double.isFinite(dy)) return 0;
		if (sideIntent()) {
			if (isBackHoverFocused()) wheelUnlockTravel = 0;
			return 0;
		}
		if (!wheelLocked) return dy;
		double net = wheelUnlockTravel + dy;
		if (Math.abs(net) < WHEEL_UNLOCK) {
			wheelUnlockTravel = net;
			return 0;
		}
		wheelLocked = false;
		wheelUnlockTravel = 0;
		return net - Math.copySign(WHEEL_UNLOCK, net);
	}

	/** Physical Y selection never snaps the pointer back to its row representative. */
	public void selectAt(double pointerY, SpatialController.Viewport viewport) {
		if (viewport == null || list.size() == 0 || viewport.height() <= 1) return;
		double screenY = pointerY + viewport.centerY() + viewport.viewY();
		double fraction = Math.clamp(screenY / (viewport.height() - 1.0), 0, 1);
		list.select((int) Math.round(fraction * (list.size() - 1.0)));
	}

	/** System alignment: first/last representatives are the inclusive screen endpoints. */
	public double selectedY(SpatialController.Viewport viewport) {
		double fraction = list.size() <= 1 ? 0 : Math.max(0, list.selectedIndex()) / (list.size() - 1.0);
		return fraction * (viewport.height() - 1.0) - viewport.centerY() - viewport.viewY();
	}

	/** Whole row steps synchronize Y at the caller; signed fractional wheel input survives. */
	public boolean wheelRows(double rows) {
		if (!Double.isFinite(rows) || isBackHoverFocused()) return false;
		if (rows != 0) list.markScrolled();
		double travel = wheelRemainder + rows;
		int steps = (int) travel;
		wheelRemainder = Double.isFinite(travel) ? travel % 1.0 : 0;
		if (steps == 0) return false;
		wheelLocked = true;
		wheelUnlockTravel = 0;
		list.glide(steps);
		return true;
	}

	public boolean sideIntent() { return Math.abs(horizontalProgress) > deadzone; }
	public boolean isBackSideFocused() { return horizontalProgress * backSign > deadzone; }
	public boolean isBackHoverFocused() { return backHoverEnabled && isBackSideFocused(); }
	public double horizontalTravel() { return horizontalProgress; }
}
