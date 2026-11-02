package nx.pingwheel.common.presentation.inventory;

import java.util.Objects;
import nx.pingwheel.common.presentation.source.CaptureResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Closed lane dispatch; the established ITEM observation bytes are unchanged. */
@NotNullByDefault
public final class InventoryResourceCodec {
	private InventoryResourceCodec() {}
	public static String id(InventoryResourceKind kind) {
		Objects.requireNonNull(kind);
		return kind == InventoryResourceKind.ITEM ? InventoryDomainCodec.ID : InventoryFluidCodec.CODEC.id();
	}
	public static InventoryResourceKind kind(String codecId) {
		if (InventoryDomainCodec.ID.equals(codecId)) return InventoryResourceKind.ITEM;
		if (InventoryFluidCodec.CODEC.id().equals(codecId)) return InventoryResourceKind.FLUID;
		throw new IllegalArgumentException("unknown inventory resource codec");
	}
	public static CaptureResult.OpaqueValue encode(InventoryResourceKind kind, @Nullable InventoryResourceValue value) {
		Objects.requireNonNull(kind);
		if (value != null && value.resourceKind() != kind) throw new IllegalArgumentException("inventory observation lane");
		return kind == InventoryResourceKind.ITEM ? InventoryDomainCodec.encode((InventoryDomainCodec.@Nullable Item) value)
			: InventoryFluidCodec.encode((@Nullable InventoryFluidValue) value);
	}
	public static @Nullable InventoryResourceValue decode(InventoryResourceKind kind, CaptureResult.OpaqueValue value) {
		Objects.requireNonNull(kind);
		return kind == InventoryResourceKind.ITEM ? InventoryDomainCodec.decode(value) : InventoryFluidCodec.decode(value);
	}
	public static InventoryResourceValue value(InventoryScanner.Key key, long amount, String label, @Nullable String json, boolean aggregate) {
		return key.kind() == InventoryResourceKind.ITEM ? new InventoryDomainCodec.Item(key, amount, label, json, aggregate)
			: new InventoryFluidValue(key, amount, new InventoryFluidValue.Display(key.resourceId(), label, json), aggregate);
	}
}
