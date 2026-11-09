package nx.pingwheel.common.monitor.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Client-thread layout lifetime, independent of sample and prepared-output lifetime. */
@NotNullByDefault
public final class MonitorLayoutState {

	private static final MethodHandle LINE_HEIGHT = lineHeightGetter();
	private @Nullable ClientMonitorState.View preparedView;
	private @Nullable Font font;
	private @Nullable Language language;
	private @Unmodifiable List<Metric> selection = List.of();
	private @Unmodifiable Set<Metric> normalizedSelection = Set.of();
	private long connectionGeneration;
	private int width;
	private int lineHeight;
	private boolean unicode;
	private boolean japanese;
	private boolean initialized;
	private MonitorDisplayModel.Reservations reservations = MonitorDisplayModel.Reservations.EMPTY;

	/** Unchanged frames neither allocate nor format or measure text. Disabled views still check identity. */
	public boolean update(ClientMonitorState.View view, long connectionGeneration, int maximumWidth, Font font,
		Language language, boolean unicode, boolean japanese) {
		Objects.requireNonNull(view); Objects.requireNonNull(font); Objects.requireNonNull(language);
		int nextLineHeight = lineHeight(font);
		boolean changed = !initialized || this.connectionGeneration != connectionGeneration || width != maximumWidth
			|| this.font != font || this.language != language || lineHeight != nextLineHeight
			|| this.unicode != unicode || this.japanese != japanese;
		if (selection != view.selected()) {
			@Unmodifiable Set<Metric> normalized = Set.copyOf(view.selected());
			changed |= !normalizedSelection.equals(normalized);
			normalizedSelection = normalized;
			selection = view.selected();
		}
		if (!changed && preparedView == view) return false;
		if (changed) reservations = MonitorDisplayModel.Reservations.EMPTY;
		this.connectionGeneration = connectionGeneration; width = maximumWidth;
		this.font = font; this.language = language; lineHeight = nextLineHeight;
		this.unicode = unicode; this.japanese = japanese; initialized = true;
		preparedView = view;
		return true;
	}

	/**
	 * Font's native public instance int is lineHeight. A direct Java read inlines
	 * its ConstantValue, hiding same-instance mutations by font integrations.
	 * Resolve by the supported native shape, not a name that differs by loader.
	 */
	private static MethodHandle lineHeightGetter() {
		try {
			@Nullable MethodHandle getter = null;
			for (var field : Font.class.getFields()) {
				if (field.getType() != int.class || Modifier.isStatic(field.getModifiers())) continue;
				if (getter != null) throw new IllegalStateException("ambiguous native Font line height");
				getter = MethodHandles.publicLookup().unreflectGetter(field);
			}
			return Objects.requireNonNull(getter, "native Font line height");
		} catch (IllegalAccessException failure) { throw new ExceptionInInitializerError(failure); }
	}

	private static int lineHeight(Font font) {
		try { return (int) LINE_HEIGHT.invokeExact(font); }
		catch (Throwable failure) { throw new IllegalStateException("native Font line height access", failure); }
	}

	public MonitorDisplayModel.Reservations reservations() { return reservations; }
	public void commit(MonitorDisplayModel.Reservations reservations) { this.reservations = Objects.requireNonNull(reservations); }
	/** Temporary output loss, such as a missing font, must not forget layout high-water marks. */
	public void invalidatePrepared() { preparedView = null; }
	/** Disconnect/runtime end drops both layout identity and every reserved height. */
	public void reset() {
		preparedView = null; font = null; language = null;
		selection = List.of(); normalizedSelection = Set.of(); initialized = false;
		reservations = MonitorDisplayModel.Reservations.EMPTY;
	}
}
