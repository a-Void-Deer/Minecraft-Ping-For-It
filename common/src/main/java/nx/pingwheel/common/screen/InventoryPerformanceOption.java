package nx.pingwheel.common.screen;

import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.network.chat.Component;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.resource.LanguageUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Native slider with synchronous, discrete index callbacks and one exact stop per arrow press. */
@NotNullByDefault
final class InventoryPerformanceOption {
	private InventoryPerformanceOption() {
	}

	static PerformanceSlider slider(LanguageUtils label, Field field, ServerSettingsModel model, Runnable onEdit) {
		var scale = model.inventoryScale(field);
		var slider = new PerformanceSlider(label, field, scale, model.inventoryPosition(field), model::canEdit, position -> {
			model.selectInventoryPosition(field, scale, position);
			onEdit.run();
		});
		slider.active = model.canEdit();
		return slider;
	}

	static Component valueLabel(Field field, InventoryPerformanceScale scale, int position) {
		if (scale.isUnlimited(position)) return LanguageUtils.settings("inventory.unlimited").get();
		var value = scale.finiteValueAt(position);
		if (value.signum() == 0) {
			if (field == Field.TRACKING_RESYNC_MIN_PERIODS) {
				return LanguageUtils.settings("inventory.resync_no_cooldown").get();
			}
			if (field == Field.TRACKING_HEARTBEAT_PERIODS) {
				return LanguageUtils.settings("inventory.heartbeat_disabled").get();
			}
		}
		return Component.literal(value.toPlainString());
	}

	@NotNullByDefault
	static final class PerformanceSlider extends AbstractSliderButton {
		private final LanguageUtils label;
		private final Field field;
		private final InventoryPerformanceScale scale;
		private final BooleanSupplier canEdit;
		private final IntConsumer setter;
		private int submittedPosition;
		private boolean adjusting;

		private PerformanceSlider(LanguageUtils label, Field field, InventoryPerformanceScale scale, int initial,
			BooleanSupplier canEdit, IntConsumer setter) {
			super(0, 0, SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsScreenLayout.ROW_HEIGHT,
				message(label, field, scale, initial), fractionOf(scale, initial));
			this.label = label;
			this.field = field;
			this.scale = scale;
			this.canEdit = canEdit;
			this.setter = setter;
			this.submittedPosition = initial;
			this.setTooltip(Tooltip.create(label.path("tooltip").get()));
		}

		int currentPosition() {
			return (int) Math.round(this.value * (this.scale.size() - 1));
		}

		@Override
		protected void updateMessage() {
			this.setMessage(message(this.label, this.field, this.scale, currentPosition()));
		}

		@Override
		protected void applyValue() {
			if (!this.active || !this.canEdit.getAsBoolean()) {
				this.value = fractionOf(this.scale, this.submittedPosition);
				return;
			}
			int position = currentPosition();
			if (position != this.submittedPosition) {
				this.submittedPosition = position;
				this.setter.accept(position);
			}
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			if (this.active && this.canEdit.getAsBoolean()) super.onClick(mouseX, mouseY);
		}

		@Override
		protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
			if (this.active && this.canEdit.getAsBoolean()) super.onDrag(mouseX, mouseY, dragX, dragY);
		}

		@Override
		public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
			if (!this.active || !this.canEdit.getAsBoolean()) return false;
			if (CommonInputs.selected(keyCode)) {
				super.keyPressed(keyCode, scanCode, modifiers);
				this.adjusting = !this.adjusting;
				return true;
			}
			if (this.adjusting && (keyCode == 263 || keyCode == 262)) {
				int next = Math.clamp(currentPosition() + (keyCode == 263 ? -1 : 1), 0, this.scale.size() - 1);
				this.setFraction(fractionOf(this.scale, next));
				return true;
			}
			return false;
		}

		@Override
		public void setFocused(boolean focused) {
			super.setFocused(focused);
			if (!focused) {
				this.adjusting = false;
			} else {
				InputType input = Minecraft.getInstance().getLastInputType();
				if (input.isMouse() || input == InputType.KEYBOARD_TAB) this.adjusting = true;
			}
		}

		/** Also used by keyboard adjustment; all paths use the same synchronous write gate. */
		private void setFraction(double fraction) {
			if (!this.active || !this.canEdit.getAsBoolean()) return;
			double previous = this.value;
			this.value = Math.clamp(fraction, 0.0, 1.0);
			if (previous != this.value) this.applyValue();
			this.updateMessage();
		}

		private static double fractionOf(InventoryPerformanceScale scale, int position) {
			return position / (double) Math.max(1, scale.size() - 1);
		}

		private static Component message(LanguageUtils label, Field field, InventoryPerformanceScale scale, int position) {
			return LanguageUtils.settings("inventory.value").get(label.get(), valueLabel(field, scale, position));
		}
	}
}
