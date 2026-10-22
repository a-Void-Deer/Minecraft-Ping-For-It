package nx.pingwheel.common.screen;

import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import nx.pingwheel.common.config.InventorySizeScale;
import nx.pingwheel.common.resource.LanguageUtils;

import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A native slider over one {@link InventorySizeScale}. A slider position is the
 * ordered index of the scale's legal values, not a quarter-multiplier quantum,
 * so the piecewise display grid is addressed exactly; the label shows the
 * physical KiB size. The maximum position is supplied by the caller because the
 * display maximum follows the current frame value.
 *
 * <p>Native {@code AbstractSliderButton} keeps its keyboard adjustment mode in
 * a private field and re-reads the last global input type on focus, so the
 * native option slider cannot carry that mode across the widget replacement a
 * size edit performs. This subclass owns the mode in {@link AdjustmentState},
 * which the screen carries through that replacement; it never simulates the
 * global input type or fabricates a key event.
 */
final class InventorySizeOption {
	private static final LanguageUtils UNIT_KIB = LanguageUtils.of("unit", "kib");
	private static final int LEFT_ARROW = 263;
	private static final int RIGHT_ARROW = 262;

	private InventorySizeOption() {
	}

	/** The physical KiB label of a legal size-scale value. */
	static MutableComponent kibLabel(InventorySizeScale scale, BigDecimal multiplier) {
		return UNIT_KIB.get(scale.bytes(multiplier) / 1024L);
	}

	/**
	 * The slider's keyboard adjustment mode, carried by the screen across the
	 * native widget replacement. An ordinary focus loss clears it like the
	 * native slider; a replacement performed through {@link #carry(Runnable)}
	 * preserves it so held arrow keys keep adjusting.
	 */
	static final class AdjustmentState {
		private boolean adjusting;
		private boolean carrying;

		boolean isAdjusting() {
			return this.adjusting;
		}

		boolean isCarrying() {
			return this.carrying;
		}

		void clear() {
			this.adjusting = false;
		}

		void clearUnlessCarried() {
			if (!this.carrying) {
				this.adjusting = false;
			}
		}

		void enableForInput() {
			this.adjusting = true;
		}

		void toggle() {
			this.adjusting = !this.adjusting;
		}

		/** Runs the replacement of the focused slider while preserving its mode. */
		void carry(Runnable replacement) {
			this.carrying = true;
			try {
				replacement.run();
			} finally {
				this.carrying = false;
			}
		}
	}

	static InventorySizeSlider slider(
		LanguageUtils label,
		InventorySizeScale scale,
		IntSupplier maximumPosition,
		Supplier<Component> tooltipSupplier,
		Supplier<BigDecimal> getter,
		Consumer<BigDecimal> setter,
		AdjustmentState state
	) {
		return new InventorySizeSlider(label, scale, maximumPosition, tooltipSupplier, getter, setter, state);
	}

	/** The supported replacement for the native option slider. */
	static final class InventorySizeSlider extends AbstractSliderButton {
		private final LanguageUtils label;
		private final InventorySizeScale scale;
		private final IntSupplier maximumPosition;
		private final Consumer<BigDecimal> setter;
		private final AdjustmentState state;
		private BigDecimal submittedValue;

		private InventorySizeSlider(
			LanguageUtils label,
			InventorySizeScale scale,
			IntSupplier maximumPosition,
			Supplier<Component> tooltipSupplier,
			Supplier<BigDecimal> getter,
			Consumer<BigDecimal> setter,
			AdjustmentState state
		) {
			super(
				0,
				0,
				SettingsScreenLayout.LARGE_WIDGET_WIDTH,
				SettingsScreenLayout.ROW_HEIGHT,
				label.get(kibLabel(scale, clampedValue(scale, maximumPosition.getAsInt(), getter.get()))),
				fractionOf(scale, maximumPosition.getAsInt(), getter.get()));
			this.label = label;
			this.scale = scale;
			this.maximumPosition = maximumPosition;
			this.setter = setter;
			this.state = state;
			this.submittedValue = clampedValue(scale, maximumPosition.getAsInt(), getter.get());
			if (tooltipSupplier != null) {
				this.setTooltip(Tooltip.create(tooltipSupplier.get()));
			}
			this.updateMessage();
		}

		/** The exact legal value the slider currently addresses. */
		BigDecimal currentValue() {
			return this.valueAt(this.value);
		}

		@Override
		protected void updateMessage() {
			this.setMessage(this.label.get(kibLabel(this.scale, this.valueAt(this.value))));
		}

		/**
		 * Calls the model only when the fraction now addresses a different legal
		 * value. A fraction-only move inside one discrete band keeps the
		 * accumulated fraction, refreshes the label, and triggers no page
		 * rebuild, so a real-width slider can accumulate arrow steps toward the
		 * next position instead of being reset to the band midpoint each press.
		 */
		@Override
		protected void applyValue() {
			BigDecimal value = this.valueAt(this.value);
			if (value.compareTo(this.submittedValue) == 0) {
				return;
			}
			this.submittedValue = value;
			this.setter.accept(value);
		}

		@Override
		public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
			if (!this.active) {
				return false;
			}
			if (CommonInputs.selected(keyCode)) {
				this.state.toggle();
				return true;
			}
			if (this.state.isAdjusting() && (keyCode == LEFT_ARROW || keyCode == RIGHT_ARROW)) {
				double step = 1.0 / (double) Math.max(1, this.width - 8);
				this.setFraction(this.value + (keyCode == LEFT_ARROW ? -step : step));
				return true;
			}
			return false;
		}

		@Override
		public void setFocused(boolean focused) {
			super.setFocused(focused);
			if (focused) {
				// Mirror the native slider's mouse and tab focus enablement; a
				// carried adjustment mode is already set and stays untouched.
				InputType input = Minecraft.getInstance().getLastInputType();
				if (input.isMouse() || input == InputType.KEYBOARD_TAB) {
					this.state.enableForInput();
				}
			} else {
				this.state.clearUnlessCarried();
			}
		}

		/** The native slider's exact fraction update, without the private mode field. */
		private void setFraction(double fraction) {
			double previous = this.value;
			this.value = Mth.clamp(fraction, 0.0, 1.0);
			if (previous != this.value) {
				this.applyValue();
			}
			this.updateMessage();
		}

		private BigDecimal valueAt(double fraction) {
			int maximum = Math.max(0, this.maximumPosition.getAsInt());
			double clamped = fraction >= 1.0 ? 0.99999 : Math.max(0.0, fraction);
			int position = Mth.floor(Mth.map(clamped, 0.0, 1.0, 0.0, maximum + 1.0));
			return this.scale.valueAt(position);
		}

		private static BigDecimal clampedValue(InventorySizeScale scale, int maximumPosition, BigDecimal value) {
			return scale.valueAt(clampedPosition(scale, maximumPosition, value));
		}

		private static int clampedPosition(InventorySizeScale scale, int maximumPosition, BigDecimal value) {
			return Math.min(Math.max(0, scale.indexOf(value)), Math.max(0, maximumPosition));
		}

		private static double fractionOf(InventorySizeScale scale, int maximumPosition, BigDecimal value) {
			int maximum = Math.max(0, maximumPosition);
			int position = clampedPosition(scale, maximumPosition, value);
			if (position == 0) {
				return 0.0;
			}
			if (position >= maximum) {
				return 1.0;
			}
			return Mth.map(position + 0.5, 0.0, maximum + 1.0, 0.0, 1.0);
		}
	}
}
