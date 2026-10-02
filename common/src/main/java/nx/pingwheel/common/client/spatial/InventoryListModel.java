package nx.pingwheel.common.client.spatial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure ordering and selection model for the streamed inventory list.
 *
 * <p>Entries are supplied by a server store as discovery batches. The model
 * owns only presentation ordering: the first batch is sorted by quantity
 * descending with a stable first-seen tie break; a pure quantity update never
 * moves an existing row; while the player has not scrolled vertically a later
 * batch that introduces new keys re-sorts the whole discovered list, and after
 * a vertical scroll existing positions stay fixed while new keys are sorted
 * internally and appended. Zero-count rows are retained and nothing is ever
 * deleted while the list is open. Closing and reopening starts a fresh
 * discovery order and re-sorts.
 *
 * <p>Vertical movement ({@link #glide(int)} and {@link #wheel(float)}) clamps
 * the selected index to the list and the window follows the selection with a
 * centered, clamped first row. Wheel input accepts fractional row deltas and
 * accumulates them; it is suppressed while a Back affordance is hovered. The
 * wheel-lock movement threshold intentionally belongs to the future
 * inventory-gesture controller, not to this row model. Nothing here calls a
 * clock, reads configuration, or touches a renderer; the returned snapshot is
 * immutable.
 */
public final class InventoryListModel {

	/** One discovered inventory row; {@code quality} is an opaque caller token. */
	public record Entry(String key, String label, long count, String quality) {

		public Entry {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(label, "label");
		}
	}

	/** Which list side owns Back and Forward. */
	public enum Side {
		LEFT,
		RIGHT
	}

	/** Frozen return/forward side pair for the current list entry. */
	public record Direction(Side back, Side forward) {

		public Direction {
			Objects.requireNonNull(back, "back");
			Objects.requireNonNull(forward, "forward");
		}
	}

	/** Separate discovery watermark; never derived from row counts. */
	public enum Status {
		UPDATING,
		COMPLETE
	}

	/** Immutable read model for the list renderer. */
	public record Snapshot(
		boolean open,
		List<Entry> entries,
		int selectedIndex,
		String selectedKey,
		int windowFirst,
		Direction direction,
		Status status,
		double axisX,
		double glideBaseY
	) {}

	private static final class Row {

		final String key;
		final long sequence;
		String label;
		long count;
		String quality;

		Row(Entry entry, long sequence) {
			this.key = entry.key();
			this.sequence = sequence;
			this.label = entry.label();
			this.count = entry.count();
			this.quality = entry.quality();
		}
	}

	private final int visibleRows;
	private final List<Row> rows = new ArrayList<>();
	private final Map<String, Row> byKey = new HashMap<>();

	private boolean open;
	private boolean userScrolled;
	private boolean backHoverFocused;
	private long nextSequence;
	private String selectedKey;
	private double wheelRemainder;
	private double axisX;
	private double glideBaseY;
	private Direction direction = new Direction(Side.LEFT, Side.RIGHT);
	private Status status = Status.UPDATING;

	public InventoryListModel(int visibleRows) {
		if (visibleRows <= 0) {
			throw new IllegalArgumentException("visibleRows must be positive");
		}

		this.visibleRows = visibleRows;
	}

	public int visibleRows() {
		return visibleRows;
	}

	public boolean isOpen() {
		return open;
	}

	/**
	 * Starts a fresh discovery order from the initial streamed batch, freezes
	 * the list geometry and return-side bearing, and selects the first row.
	 * A close/reopen always re-sorts the newly discovered entries.
	 */
	public void open(List<Entry> discovered, double axisX, double glideBaseY, double parentBearingDegrees) {
		Objects.requireNonNull(discovered, "discovered");

		open = true;
		userScrolled = false;
		backHoverFocused = false;
		nextSequence = 0L;
		wheelRemainder = 0.0;
		selectedKey = null;
		rows.clear();
		byKey.clear();
		this.axisX = axisX;
		this.glideBaseY = glideBaseY;
		this.direction = directionFor(parentBearingDegrees);
		this.status = Status.UPDATING;

		for (Entry entry : discovered) {
			Row row = new Row(Objects.requireNonNull(entry, "entry"), nextSequence++);
			rows.add(row);
			byKey.put(row.key, row);
		}

		sortByQuantity();
		selectFirstIfUnset();
	}

	/** Ends the session; a later {@link #open} starts a fresh discovery order. */
	public void close() {
		open = false;
		userScrolled = false;
		backHoverFocused = false;
		rows.clear();
		byKey.clear();
		selectedKey = null;
		wheelRemainder = 0.0;
	}

	/**
	 * Merges a streamed batch. Existing keys are updated in place and never
	 * reorder. New keys re-sort the whole list while the player has not
	 * scrolled, and are internally sorted then appended once the player has.
	 *
	 * @return whether the batch introduced at least one new key
	 */
	public boolean applyBatch(List<Entry> batch) {
		Objects.requireNonNull(batch, "batch");

		if (!open) {
			return false;
		}

		List<Row> appended = new ArrayList<>();

		for (Entry entry : batch) {
			Objects.requireNonNull(entry, "entry");
			Row existing = byKey.get(entry.key());

			if (existing != null) {
				// A pure quantity (or label/quality) update keeps its row.
				existing.label = entry.label();
				existing.count = entry.count();
				existing.quality = entry.quality();
			} else {
				Row row = new Row(entry, nextSequence++);
				// Index immediately so a duplicate key in this same batch, or a
				// later batch, updates this row instead of creating a twin.
				byKey.put(row.key, row);
				appended.add(row);
			}
		}

		if (appended.isEmpty()) {
			selectFirstIfUnset();
			return false;
		}

		if (userScrolled) {
			appended.sort(byQuantity());
			rows.addAll(appended);
		} else {
			rows.addAll(appended);
			sortByQuantity();
		}

		selectFirstIfUnset();
		return true;
	}

	/** Updates one existing row count in place; never reorders. */
	public boolean updateCount(String key, long count) {
		Row row = byKey.get(key);

		if (row == null) {
			return false;
		}

		row.count = count;
		return true;
	}

	/** Selects by index, clamped to the list bounds. */
	public void select(int index) {
		if (rows.isEmpty()) {
			selectedKey = null;
			return;
		}

		int target = Math.max(0, Math.min(index, rows.size() - 1));
		selectedKey = rows.get(target).key;
		userScrolled = true;
	}

	/** Selects an existing key; returns whether it was found. */
	public boolean select(String key) {
		Objects.requireNonNull(key, "key");

		if (!byKey.containsKey(key)) {
			return false;
		}

		if (!key.equals(selectedKey)) {
			selectedKey = key;
			userScrolled = true;
		}

		return true;
	}

	/** Moves the selection by whole rows; returns the rows actually moved. */
	public int glide(int steps) {
		return moveSelection(steps);
	}

	/**
	 * Adds a fractional wheel row delta, applies any whole rows, and returns
	 * the rows actually moved. Suppressed entirely while a Back hover is
	 * focused, so no stale remainder is kept.
	 */
	public int wheel(float rowDelta) {
		if (backHoverFocused) {
			return 0;
		}
		if (rowDelta != 0.0f) markScrolled();
		if (rows.isEmpty()) return 0;

		wheelRemainder += rowDelta;
		int steps = (int) wheelRemainder;
		wheelRemainder -= steps;

		if (steps == 0) {
			return 0;
		}

		return moveSelection(steps);
	}

	public void setBackHoverFocused(boolean focused) {
		this.backHoverFocused = focused;
	}

	/** A fractional or boundary-limited vertical scroll still freezes discovery positions. */
	public void markScrolled() {
		if (open) userScrolled = true;
	}

	public boolean isBackHoverFocused() {
		return backHoverFocused;
	}

	public void setStatus(Status status) {
		this.status = Objects.requireNonNull(status, "status");
	}

	public Status status() {
		return status;
	}

	public int size() {
		return rows.size();
	}

	public int selectedIndex() {
		if (selectedKey == null) {
			return -1;
		}

		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).key.equals(selectedKey)) {
				return i;
			}
		}

		return -1;
	}

	public String selectedKey() {
		return selectedKey;
	}

	/** First visible row: selection-centered and clamped to the list. */
	public int windowFirst() {
		if (rows.isEmpty() || rows.size() <= visibleRows) {
			return 0;
		}

		int index = Math.max(0, selectedIndex());
		int maxFirst = rows.size() - visibleRows;
		return Math.max(0, Math.min(index - visibleRows / 2, maxFirst));
	}

	public Direction direction() {
		return direction;
	}

	/** The frozen return-side rule: vertical parents keep Back left. */
	public static Direction directionFor(double parentBearingDegrees) {
		double bearing = normalize(parentBearingDegrees);
		boolean vertical = bearing < 3.0 || bearing > 357.0 || Math.abs(bearing - 180.0) < 3.0;

		if (vertical) {
			return new Direction(Side.LEFT, Side.RIGHT);
		}

		if (bearing > 0.0 && bearing < 180.0) {
			return new Direction(Side.RIGHT, Side.LEFT);
		}

		return new Direction(Side.LEFT, Side.RIGHT);
	}

	/** Immutable snapshot of the current rows and selection state. */
	public Snapshot snapshot() {
		List<Entry> entries = new ArrayList<>(rows.size());

		for (Row row : rows) {
			entries.add(new Entry(row.key, row.label, row.count, row.quality));
		}

		return new Snapshot(
			open,
			List.copyOf(entries),
			selectedIndex(),
			selectedKey,
			windowFirst(),
			direction,
			status,
			axisX,
			glideBaseY);
	}

	private void selectFirstIfUnset() {
		if (selectedKey == null && !rows.isEmpty()) {
			selectedKey = rows.get(0).key;
		}
	}

	private int moveSelection(int steps) {
		if (steps == 0) {
			return 0;
		}
		// An attempted vertical scroll freezes positions even at an end stop or
		// before the first partial batch arrives.
		userScrolled = true;
		if (rows.isEmpty()) return 0;

		int current = Math.max(0, selectedIndex());
		int target = Math.max(0, Math.min(current + steps, rows.size() - 1));
		int moved = target - current;

		if (moved != 0) {
			selectedKey = rows.get(target).key;
			userScrolled = true;
		}

		return moved;
	}

	private void sortByQuantity() {
		rows.sort(byQuantity());
	}

	private static Comparator<Row> byQuantity() {
		return Comparator.comparingLong((Row row) -> row.count)
			.reversed()
			.thenComparingLong(row -> row.sequence);
	}

	private static double normalize(double degrees) {
		return ((degrees % 360.0) + 360.0) % 360.0;
	}
}
