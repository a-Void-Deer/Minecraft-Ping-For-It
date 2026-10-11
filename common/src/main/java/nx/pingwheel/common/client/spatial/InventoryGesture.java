package nx.pingwheel.common.client.spatial;

import java.util.Objects;

/**
 * Pure horizontal/vertical gesture adapter for the streamed inventory list.
 *
 * <p>It owns the frozen horizontal list axis and row-height threshold, the
 * virtual pointer, the glide and wheel fractions, the wheel-lock state and the
 * Back/Forward side pair supplied by {@link InventoryListModel#directionFor(double)}.
 * {@link #move(double, double)} emits a navigation {@link Action} when a side
 * gate is crossed and otherwise feeds vertical glide rows into
 * {@link InventoryListModel#glide(int)}; {@link #wheelPixels(double)} and
 * {@link #wheelRows(float)} convert scroll input (100 pixels per row) and feed
 * whole rows. Selection is only ever changed through the list model, and
 * nothing here paints or reads Minecraft state.
 *
 * <p>Rules mirrored from the latest prototype: a Back hover focus suppresses
 * glide and wheel entirely; with hover disabled a Back-side stroke past the
 * exit gate emits {@link Action#BACK}; a Forward-side stroke past the exit gate
 * emits {@link Action#FORWARD} (the caller then enters the selected row's
 * submenu); horizontal motion beyond the hint gate without a navigation
 * suppresses glide; after a wheel step, glide stays locked until vertical
 * movement passes its unlock threshold; relative vertical glide advances at
 * the frozen row-height threshold, discarding opposing sub-row travel on reversal.
 */
public final class InventoryGesture {

	/** What the caller should do after a movement sample. */
	public enum Action {
		NONE,
		BACK,
		FORWARD
	}

	private static final double SIDE_HINT = 19.0;
	private static final double SIDE_EXIT = 76.0;
	private static final double WHEEL_UNLOCK = 14.0;
	private static final double INTRINSIC_GLIDE = 0.5;
	private static final double PIXELS_PER_ROW = 9.0;
	private static final double WHEEL_PIXELS_PER_ROW = 100.0;

	private final InventoryListModel list;
	private final double glideSensitivity;
	private final boolean backHoverEnabled;
	private final double pixelsPerRow;

	private double axisX;
	private double pointerX;
	private double pointerY;
	private double glideRemainder;
	private double wheelRemainder;
	private double wheelUnlockTravel;
	private boolean wheelLocked;
	private boolean backHoverFocused;
	private InventoryListModel.Direction direction =
		new InventoryListModel.Direction(InventoryListModel.Side.LEFT, InventoryListModel.Side.RIGHT);
	private double backSign = -1.0;
	private double forwardSign = 1.0;

	public InventoryGesture(InventoryListModel list, double glideSensitivity, boolean backHoverEnabled) {
		this(list, glideSensitivity, backHoverEnabled, PIXELS_PER_ROW);
	}

	/** The native facade supplies its immutable layout's travel-per-row metric. */
	public InventoryGesture(InventoryListModel list, double glideSensitivity, boolean backHoverEnabled, double pixelsPerRow) {
		this.list = Objects.requireNonNull(list, "list");

		if (!Double.isFinite(glideSensitivity) || !(glideSensitivity > 0.0)
			|| !Double.isFinite(pixelsPerRow) || !(pixelsPerRow > 0.0)) {
			throw new IllegalArgumentException("glideSensitivity must be positive");
		}

		this.glideSensitivity = glideSensitivity;
		this.backHoverEnabled = backHoverEnabled;
		this.pixelsPerRow = pixelsPerRow;
	}

	/** Enters the list with its frozen axis, initial pointer and side pair. */
	public void begin(double axisX, double pointerY, InventoryListModel.Direction direction) {
		this.direction = Objects.requireNonNull(direction, "direction");
		this.backSign = direction.back() == InventoryListModel.Side.LEFT ? -1.0 : 1.0;
		this.forwardSign = -this.backSign;
		rebase(axisX, pointerY);
	}

	/**
	 * Re-bases after an item return: the axis, pointer and fractions reset
	 * while the model keeps its selected key and window.
	 */
	public void rebase(double x, double y) {
		axisX = x;
		pointerX = x;
		pointerY = y;
		glideRemainder = 0.0;
		wheelRemainder = 0.0;
		wheelUnlockTravel = 0.0;
		wheelLocked = false;
		backHoverFocused = false;
	}

	/** Applies one physical delta; returns the navigation the caller must run. */
	public Action move(double deltaX, double deltaY) {
		return move(deltaX, deltaY, deltaY);
	}

	/** Confined pointer travel and independent authorized vertical selection travel. */
	public Action move(double deltaX, double deltaY, double selectionDeltaY) {
		if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY) || !Double.isFinite(selectionDeltaY)) return Action.NONE;
		boolean pointerMoved = deltaX != 0.0 || deltaY != 0.0;
		pointerX += deltaX;
		pointerY += deltaY;
		double side = pointerX - axisX;

		if (backHoverFocused) {
			glideRemainder = 0.0;
			wheelUnlockTravel = 0.0;
			return Action.NONE;
		}

		if (pointerMoved && !backHoverEnabled && side * backSign >= SIDE_EXIT) {
			return Action.BACK;
		}

		if (pointerMoved && side * forwardSign >= SIDE_EXIT) {
			return Action.FORWARD;
		}

		if (Math.abs(side) > SIDE_HINT) {
			return Action.NONE;
		}

		if (wheelLocked) {
			// This is signed, unscaled travel, not the direction-cleared glide fraction.
			wheelUnlockTravel += selectionDeltaY;

			if (Math.abs(wheelUnlockTravel) < WHEEL_UNLOCK) {
				return Action.NONE;
			}

			wheelLocked = false;
			wheelUnlockTravel = 0.0;
			glideRemainder = 0.0;
		}

		if ((selectionDeltaY > 0.0 && glideRemainder < 0.0) || (selectionDeltaY < 0.0 && glideRemainder > 0.0)) {
			glideRemainder = 0.0;
		}
		double travel = glideRemainder + selectionDeltaY * (glideSensitivity * INTRINSIC_GLIDE);
		// Casting saturates attempted rows; modulo consumes all whole travel even
		// at an endpoint, without banking overshoot or overflowing an int count.
		int steps = (int) (travel / pixelsPerRow);
		glideRemainder = Double.isFinite(travel) ? travel % pixelsPerRow : 0.0;

		if (steps != 0) {
			list.glide(steps);
		}

		return Action.NONE;
	}

	/** Native movement computes current-sample Back focus, including deliberate leave. */
	public Action moveGui(double deltaX, double deltaY) {
		return moveGui(deltaX, deltaY, deltaY);
	}

	/** Current-sample Back focus follows only confined pointer travel, never selection travel. */
	public Action moveGui(double deltaX, double deltaY, double selectionDeltaY) {
		if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY) || !Double.isFinite(selectionDeltaY)) return Action.NONE;
		if (backHoverEnabled) backHoverFocused = (pointerX + deltaX - axisX) * backSign >= SIDE_HINT;
		return move(deltaX, deltaY, selectionDeltaY);
	}

	/** Platform-converted row deltas (already divided by its own scroll unit). */
	public int wheelRows(float rows) {
		return wheelRows((double) rows);
	}

	/** Double-precision native callback path; normalization remains external. */
	public int wheelRows(double rows) {
		return wheelPixels(rows * WHEEL_PIXELS_PER_ROW);
	}

	/** Raw wheel deltas: 100 pixels equals one row. Suppressed on Back hover. */
	public int wheelPixels(double pixels) {
		if (backHoverFocused) {
			return 0;
		}
		if (pixels != 0.0) list.markScrolled();

		wheelRemainder += pixels;
		int rows = (int) (wheelRemainder / WHEEL_PIXELS_PER_ROW);

		if (rows == 0) {
			return 0;
		}

		wheelRemainder -= rows * WHEEL_PIXELS_PER_ROW;
		wheelLocked = true;
		wheelUnlockTravel = 0.0;
		glideRemainder = 0.0;
		return list.glide(rows);
	}

	/** Set by the controller from its own Back-focus gate. */
	public void setBackHoverFocused(boolean focused) {
		this.backHoverFocused = focused;
	}

	public boolean isBackHoverFocused() {
		return backHoverFocused;
	}

	/** Same gate used by movement and release; no facade-owned tuning duplicate. */
	public boolean isBackSideFocused() {
		return (pointerX - axisX) * backSign >= SIDE_HINT;
	}

	/** Import the controller's sole logical pointer after a move or rebase. */
	public void alignPointer(double x, double y) {
		pointerX = x;
		pointerY = y;
		if (backHoverEnabled) backHoverFocused = isBackSideFocused();
	}

	/** Projection-only axis correction; keeps pointer, fractions and wheel lock untouched. */
	void constrainAxis(SpatialController.Viewport viewport) {
		axisX = viewport.confine(new SpatialController.Point(axisX, pointerY)).x();
	}

	public double axisX() {
		return axisX;
	}

	public double pointerX() {
		return pointerX;
	}

	public double pointerY() {
		return pointerY;
	}

	public InventoryListModel.Direction direction() {
		return direction;
	}
}
