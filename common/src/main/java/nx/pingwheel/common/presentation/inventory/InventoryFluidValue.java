package nx.pingwheel.common.presentation.inventory;

import java.util.Objects;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Detached fluid identity and presentation. No native stack, component, tag or capacity escapes here. */
@NotNullByDefault
public record InventoryFluidValue(InventoryScanner.Key key, long amount, Display display, boolean aggregate) implements InventoryResourceValue {
	public InventoryFluidValue {
		Objects.requireNonNull(key); Objects.requireNonNull(display);
		InventoryQuantities.require(amount);
		if (key.kind() != InventoryResourceKind.FLUID || aggregate != key.componentsKey().isEmpty())
			throw new IllegalArgumentException("fluid identity mode");
		if (!key.resourceId().equals(display.fluidId())) throw new IllegalArgumentException("fluid display identity");
	}
	@Override public String label() { return display.label(); }
	@Override public @Nullable String displayJson() { return display.displayJson(); }
	@Override public boolean aggregateRequired() { return aggregate; }
	@Override public InventoryFluidValue withAmount(long amount) { return new InventoryFluidValue(key, amount, display, aggregate); }

	/**
	 * Bounded transport display only. {@code label} is the registry translation fallback, not a custom
	 * name or JSON document. New display JSON uses {@link InventoryFluidDisplayCodec}; this transport
	 * envelope also retains unavailable or unrecognized display for independent client-side refusal.
	 * Neither absence nor display refusal is native identity refusal.
	 */
	@NotNullByDefault
	public record Display(String fluidId, String label, @Nullable String displayJson) {
		public Display {
			InventoryFluidCodec.requireId(fluidId);
			InventoryFluidCodec.requireText(label, InventoryFluidCodec.MAX_LABEL_BYTES);
			if (displayJson != null) InventoryFluidCodec.requireText(displayJson, InventoryFluidCodec.MAX_DISPLAY_BYTES);
		}
	}
}
