package nx.pingwheel.common.client.spatial;

import java.util.Objects;

/**
 * Pure horizontal/vertical gesture adapter for the streamed inventory list.
 *
 * <p>It owns the frozen list geometry ({@code axisX}/{@code glideBaseY}), the
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
 * movement passes its unlock threshold; glide gains speed away from the
 * baseline and divides by the fixed row size.
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
	private static final double GAIN_DISTANCE = 18.0;
	private static final double MAX_GAIN = 8.0;
	private static final double PIXELS_PER_ROW = 9.0;
	private static final double WHEEL_PIXELS_PER_ROW = 100.0;

	private final InventoryListModel list;
	private final double glideSensitivity;
	private final boolean backHoverEnabled;
	private final double pixelsPerRow;

	private double axisX;
	private double glideBaseY;
	private double pointerX;
	private double pointerY;
	private double glideRemainder;
	private double wheelRemainder;
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

	/** Enters the list with its frozen axis, vertical baseline and side pair. */
	public void begin(double axisX, double glideBaseY, InventoryListModel.Direction direction) {
		this.direction = Objects.requireNonNull(direction, "direction");
		this.backSign = direction.back() == InventoryListModel.Side.LEFT ? -1.0 : 1.0;
		this.forwardSign = -this.backSign;
		rebase(axisX, glideBaseY);
	}

	/**
	 * Re-bases after an item return: the axis, baseline and fractions reset
	 * while the model keeps its selected key and window.
	 */
	public void rebase(double x, double y) {
		axisX = x;
		glideBaseY = y;
		pointerX = x;
		pointerY = y;
		glideRemainder = 0.0;
		wheelRemainder = 0.0;
		wheelLocked = false;
		backHoverFocused = false;
	}

	/** Applies one physical delta; returns the navigation the caller must run. */
	public Action move(double deltaX, double deltaY) {
		pointerX += deltaX;
		pointerY += deltaY;
		double side = pointerX - axisX;

		if (backHoverFocused) {
			glideRemainder = 0.0;
			return Action.NONE;
		}

		if (!backHoverEnabled && side * backSign >= SIDE_EXIT) {
			return Action.BACK;
		}

		if (side * forwardSign >= SIDE_EXIT) {
			return Action.FORWARD;
		}

		if (Math.abs(side) > SIDE_HINT) {
			return Action.NONE;
		}

		if (wheelLocked) {
			glideRemainder += deltaY;

			if (Math.abs(glideRemainder) < WHEEL_UNLOCK) {
				return Action.NONE;
			}

			wheelLocked = false;
			glideRemainder = 0.0;
		}

		double gain = 1.0 + Math.min(MAX_GAIN, Math.abs(pointerY - glideBaseY) / GAIN_DISTANCE);
		glideRemainder += deltaY * gain * glideSensitivity;
		int steps = (int) (glideRemainder / pixelsPerRow);

		if (steps != 0) {
			glideRemainder -= steps * pixelsPerRow;
			list.glide(steps);
		}

		return Action.NONE;
	}

	/** Native movement computes current-sample Back focus, including deliberate leave. */
	public Action moveGui(double deltaX, double deltaY) {
		if (backHoverEnabled) backHoverFocused = (pointerX + deltaX - axisX) * backSign >= SIDE_HINT;
		return move(deltaX, deltaY);
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
	}

	public double axisX() {
		return axisX;
	}

	public double glideBaseY() {
		return glideBaseY;
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
