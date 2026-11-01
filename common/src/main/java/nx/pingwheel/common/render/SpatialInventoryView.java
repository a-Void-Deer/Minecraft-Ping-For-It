package nx.pingwheel.common.render;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Immutable, domain-neutral inventory paint input. Rows retain caller order,
 * selection and window; status never infers completion from counts or emptiness.
 * The native facade maps its DTO into this view without coupling rendering to
 * packets. List keys must distinguish preview sessions/targets; row keys are
 * opaque within one list. Positions are centre-relative GUI pixels.
 */
@NotNullByDefault
public record SpatialInventoryView(String listKey, boolean open, @Unmodifiable List<Row> rows,
	int selectedIndex, int windowFirst, int visibleRows, boolean backLeft, Status status,
	double axisX, double glideBaseY, double selectedRowCenterY, BackAffordance backAffordance,
	@Nullable String retainedRowKey, boolean forwardAvailable, boolean selectedRowCommittable,
	@Unmodifiable List<ResourceGroup> resourceGroups) {
	public record ResourceGroup(InventoryResourceKind resourceKind, String labelKey, Status status) {}
	public SpatialInventoryView(String listKey, boolean open, List<Row> rows,
		int selectedIndex, int windowFirst, int visibleRows, boolean backLeft, Status status,
		double axisX, double glideBaseY, double selectedRowCenterY, BackAffordance backAffordance,
		@Nullable String retainedRowKey, boolean forwardAvailable, boolean selectedRowCommittable) {
		this(listKey, open, List.copyOf(rows), selectedIndex, windowFirst, visibleRows, backLeft, status, axisX, glideBaseY,
			selectedRowCenterY, backAffordance, retainedRowKey, forwardAvailable, selectedRowCommittable, List.of());
	}

	/**
	 * Compatibility constructor for independent paint callers that predate the
	 * navigation availability fields; a valid selected row is assumed
	 * committable until the owner supplies explicit values.
	 */
	public SpatialInventoryView(String listKey, boolean open, List<Row> rows,
		int selectedIndex, int windowFirst, int visibleRows, boolean backLeft, Status status,
		double axisX, double glideBaseY, double selectedRowCenterY, BackAffordance backAffordance) {
		this(listKey, open, rows, selectedIndex, windowFirst, visibleRows, backLeft, status,
			axisX, glideBaseY, selectedRowCenterY, backAffordance, null, true,
			validSelectedIndex(selectedIndex, rows) && (backAffordance == null || !backAffordance.focused()));
	}

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
		resourceGroups = List.copyOf(resourceGroups);
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
			backLeft, status, axisX, glideBaseY, centerRelativeY, backAffordance,
			retainedRowKey, forwardAvailable, selectedRowCommittable, resourceGroups);
	}

	/** A non-input capacity/window relayout; every identity, permission and row anchor is retained. */
	public SpatialInventoryView withWindow(int first, int capacity) {
		return new SpatialInventoryView(listKey, open, rows, selectedIndex, first, capacity,
			backLeft, status, axisX, glideBaseY, selectedRowCenterY, backAffordance,
			retainedRowKey, forwardAvailable, selectedRowCommittable, resourceGroups);
	}

	/** Caller supplies timer output; rendering owns no hover timer or navigation. */
	public SpatialInventoryView withBackAffordance(boolean focused, double progress) {
		return new SpatialInventoryView(listKey, open, rows, selectedIndex, windowFirst, visibleRows,
			backLeft, status, axisX, glideBaseY, selectedRowCenterY, new BackAffordance(focused, progress),
			retainedRowKey, forwardAvailable, selectedRowCommittable, resourceGroups);
	}

	/**
	 * Explicit session-owned navigation availability. {@code retainedRowKey} is
	 * the frozen item key while that item's submenu is active, otherwise null;
	 * {@code forwardAvailable} and {@code selectedRowCommittable} are the
	 * session's own gates and are never reconstructed from paint rows.
	 */
	public SpatialInventoryView withNavigation(@Nullable String retainedRowKey, boolean forwardAvailable,
		boolean selectedRowCommittable) {
		return new SpatialInventoryView(listKey, open, rows, selectedIndex, windowFirst, visibleRows,
			backLeft, status, axisX, glideBaseY, selectedRowCenterY, backAffordance,
			retainedRowKey, forwardAvailable, selectedRowCommittable, resourceGroups);
	}
	public SpatialInventoryView withResourceGroups(List<ResourceGroup> groups) {
		return new SpatialInventoryView(listKey, open, rows, selectedIndex, windowFirst, visibleRows, backLeft, status,
			axisX, glideBaseY, selectedRowCenterY, backAffordance, retainedRowKey, forwardAvailable, selectedRowCommittable, List.copyOf(groups));
	}
	public Component groupSummary() {
		Component result = Component.empty();
		for (ResourceGroup group : resourceGroups) {
			if (!result.getString().isEmpty()) result = result.copy().append(" | ");
			result = result.copy().append(Component.translatable(group.labelKey()));
			if (group.status().translationKey() != null) result = result.copy().append(": ").append(Component.translatable(group.status().translationKey()));
		}
		return result;
	}
	/** A row inherits only its own lane lifecycle; an unrelated failing lane cannot grey healthy rows. */
	public boolean rowGrey(Row row) {
		Status lifecycle = resourceGroups.isEmpty() ? status : resourceGroups.stream()
			.filter(group -> group.resourceKind() == row.resourceKind()).findFirst().map(ResourceGroup::status).orElse(Status.UNKNOWN);
		return lifecycle.grey() || row.quality().grey();
	}

	private static boolean validSelectedIndex(int selectedIndex, @Nullable List<Row> rows) {
		return selectedIndex >= 0 && rows != null && selectedIndex < rows.size();
	}

	public record BackAffordance(boolean focused, double progress) {
		public static final BackAffordance NONE = new BackAffordance(false, 0.0);
		public BackAffordance {
			progress = !focused || !Double.isFinite(progress) ? 0.0 : Math.clamp(progress, 0.0, 1.0);
		}
	}

	/**
	 * Null count is missing, not zero. No display count is created by the
	 * renderer. {@code displayJson} is the immutable server-bounded encoded
	 * display value; null keeps the native registry-name fallback.
	 */
	public record Row(InventoryResourceKind resourceKind, String key, String label, @Nullable Long count, @Nullable String itemId, @Nullable String displayJson, Status quality,
		@Nullable Integer outlineColor, @Nullable RowDisplay display) {
		public Row(String key, String label, @Nullable Long count, @Nullable String itemId, @Nullable String displayJson, Status quality,
			@Nullable Integer outlineColor, @Nullable RowDisplay display) {
			this(InventoryResourceKind.ITEM, key, label, count, itemId, displayJson, quality, outlineColor, display);
		}

		public Row(String key, String label, @Nullable Long count, @Nullable String itemId, @Nullable String displayJson, Status quality,
			@Nullable Integer outlineColor) {
			this(key, label, count, itemId, displayJson, quality, outlineColor, null);
		}

		public Row {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(label, "label");
			Objects.requireNonNull(quality, "quality");
			if (outlineColor != null && (outlineColor & 0xFF000000) != 0)
				throw new IllegalArgumentException("outlineColor must be a 24-bit RGB value");
		}

		/** A null outline carries no direct-send capability; quality is independent of that capability. */
		public Row(String key, String label, @Nullable Long count, @Nullable String itemId, @Nullable String displayJson, Status quality) {
			this(key, label, count, itemId, displayJson, quality, null);
		}

		/** Compatibility form without encoded display data. */
		public Row(String key, String label, @Nullable Long count, @Nullable String itemId, Status quality) {
			this(key, label, count, itemId, null, quality);
		}
	}

	/**
	 * One detached name/icon projection shared by measurement and paint. Native
	 * icon rendering receives a copy, so mutation of a painted player-head stack
	 * cannot change the retained transmission or its measured display name.
	 */
	public static final class RowDisplay {
		private final Component name;
		private final @Nullable ItemStack icon;
		private final @Nullable Fluid fluid;

		public RowDisplay(Component name, ItemStack icon) {
			this.name = Objects.requireNonNull(name).copy();
			this.icon = Objects.requireNonNull(icon).copy();
			this.fluid = null;
		}
		/** Text-only detached observation; no item construction is needed to measure it. */
		public RowDisplay(Component name) { this.name = Objects.requireNonNull(name).copy(); this.icon = null; this.fluid = null; }
		public RowDisplay(Component name, @Nullable Fluid fluid) { this.name = Objects.requireNonNull(name).copy(); this.icon = null; this.fluid = fluid; }
		public Component name() { return name.copy(); }
		public boolean hasIcon() { return fluid != null || icon != null && !icon.isEmpty(); }
		public ItemStack icon() { return icon == null ? ItemStack.EMPTY : icon.copy(); }
		public @Nullable Fluid fluid() { return fluid; }
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
		COMPONENT_TOO_LONG("all_variants", true),
		EXPIRED("expired", true);

		private final @Nullable String suffix;
		private final boolean grey;

		Status(@Nullable String suffix, boolean grey) {
			this.suffix = suffix;
			this.grey = grey;
		}

		/** READY has no separate status label. */
		public @Nullable String translationKey() {
			return suffix == null ? null : "pingforit.spatial.inventory." + suffix;
		}

		public boolean grey() {
			return grey;
		}

		/** Name-only adapter for DTOs; an unrecognized/missing state is never READY. */
		public static Status fromName(@Nullable String name) {
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
