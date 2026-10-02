package nx.pingwheel.common.render;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable, domain-neutral inventory paint input. Rows retain caller order,
 * selection and window; status never infers completion from counts or emptiness.
 * The native facade maps its DTO into this view without coupling rendering to
 * packets. List keys must distinguish preview sessions/targets; row keys are
 * opaque within one list. Positions are centre-relative GUI pixels.
 */
public record SpatialInventoryView(String listKey, boolean open, List<Row> rows,
	int selectedIndex, int windowFirst, int visibleRows, boolean backLeft, Status status,
	double axisX, double glideBaseY, double selectedRowCenterY, BackAffordance backAffordance) {

	/** Compatibility constructor without list-Back focus/progress. */
	public SpatialInventoryView(String listKey, boolean open, List<Row> rows,
		int selectedIndex, int windowFirst, int visibleRows, boolean backLeft, Status status,
		double axisX, double glideBaseY, double selectedRowCenterY) {
		this(listKey, open, rows, selectedIndex, windowFirst, visibleRows, backLeft, status,
			axisX, glideBaseY, selectedRowCenterY, BackAffordance.NONE);
	}

	/** No explicit row anchor: centre the panel on glideBaseY. */
	public SpatialInventoryView(String listKey, boolean open, List<Row> rows,
		int selectedIndex, int windowFirst, int visibleRows, boolean backLeft, Status status,
		double axisX, double glideBaseY) {
		this(listKey, open, rows, selectedIndex, windowFirst, visibleRows, backLeft, status,
			axisX, glideBaseY, Double.NaN);
	}

	public SpatialInventoryView {
		Objects.requireNonNull(listKey, "listKey");
		rows = List.copyOf(rows);
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(backAffordance, "backAffordance");
		if (visibleRows <= 0 || !Double.isFinite(axisX) || !Double.isFinite(glideBaseY)
			|| (!Double.isNaN(selectedRowCenterY) && !Double.isFinite(selectedRowCenterY))) {
			throw new IllegalArgumentException("invalid list geometry");
		}
	}

	/**
	 * Optional caller-owned logical selected-row anchor in CENTRE-RELATIVE GUI Y.
	 * This lets the native selector and painting share one logical row origin.
	 * Animated RowLayout remains absolute; no implicit controller conversion occurs.
	 */
	public SpatialInventoryView withSelectedRowCenterY(double centerRelativeY) {
		return new SpatialInventoryView(listKey, open, rows, selectedIndex, windowFirst, visibleRows,
			backLeft, status, axisX, glideBaseY, centerRelativeY, backAffordance);
	}

	/** Caller supplies timer output; rendering owns no hover timer or navigation. */
	public SpatialInventoryView withBackAffordance(boolean focused, double progress) {
		return new SpatialInventoryView(listKey, open, rows, selectedIndex, windowFirst, visibleRows,
			backLeft, status, axisX, glideBaseY, selectedRowCenterY, new BackAffordance(focused, progress));
	}

	public record BackAffordance(boolean focused, double progress) {
		public static final BackAffordance NONE = new BackAffordance(false, 0.0);
		public BackAffordance {
			progress = !focused || !Double.isFinite(progress) ? 0.0 : Math.clamp(progress, 0.0, 1.0);
		}
	}

	/** Null count is missing, not zero. No display count is created by the renderer. */
	public record Row(String key, String label, Long count, String itemId, Status quality) {

		public Row {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(label, "label");
			Objects.requireNonNull(quality, "quality");
		}
	}

	/** Separate view status and row quality use the same presentation vocabulary. */
	public enum Status {
		READY(null, false),
		UPDATING("updating", false),
		UNKNOWN("unknown", true),
		UNCERTAIN("uncertain", true),
		INCOMPLETE("incomplete", true),
		UNAVAILABLE("unavailable", true),
		INVALID("invalid", true),
		COMPONENT_TOO_LONG("component_too_long", true),
		EXPIRED("expired", true);

		private final String suffix;
		private final boolean grey;

		Status(String suffix, boolean grey) {
			this.suffix = suffix;
			this.grey = grey;
		}

		/** READY has no separate status label. */
		public String translationKey() {
			return suffix == null ? null : "pingforit.spatial.inventory." + suffix;
		}

		public boolean grey() {
			return grey;
		}

		/** Name-only adapter for DTOs; an unrecognized/missing state is never READY. */
		public static Status fromName(String name) {
			if (name == null) {
				return UNKNOWN;
			}
			String normalized = name.toUpperCase(Locale.ROOT);
			if (normalized.equals("COMPLETE") || normalized.equals("VALID")) {
				return READY;
			}
			try {
				return valueOf(normalized);
			} catch (IllegalArgumentException ignored) {
				return UNKNOWN;
			}
		}
	}
}
