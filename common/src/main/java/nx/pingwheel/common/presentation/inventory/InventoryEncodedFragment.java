package nx.pingwheel.common.presentation.inventory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import nx.pingwheel.common.presentation.source.CaptureResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Immutable detached bytes for one identical fan-out projection.
 *
 * <p>The caller creates one instance inside one publication operation, after
 * that operation's admission, and reuses {@link #fragment()} for every
 * recipient whose value map is identical. It retains no resident item stack,
 * provider, world, callback or publication proof. Receiving recipients still
 * get independent {@link CaptureResult}s, authorization, evidence and ledger
 * admission; this type only removes duplicate UTF/detached encoding.
 */
@NotNullByDefault
public final class InventoryEncodedFragment {

	private final InventoryResourceKind resourceKind;
	private final CaptureResult.OpaqueKeyedFragment fragment;

	private InventoryEncodedFragment(InventoryResourceKind resourceKind, CaptureResult.OpaqueKeyedFragment fragment) {
		this.resourceKind = Objects.requireNonNull(resourceKind, "resourceKind");
		this.fragment = Objects.requireNonNull(fragment, "fragment");
	}

	/**
	 * Encodes one already-detached invocation-local value map. The returned
	 * fragment owns only bounded {@link CaptureResult.OpaqueValue} bytes and is
	 * safe to share among recipients of the same projection.
	 */
	public static InventoryEncodedFragment encode(InventoryResourceKind resourceKind,
		Map<String, ? extends InventoryResourceValue> items) {
		Objects.requireNonNull(resourceKind, "resourceKind");
		Objects.requireNonNull(items, "items");
		String codecId = InventoryResourceCodec.id(resourceKind);
		Map<String, CaptureResult.OpaqueValue> encoded = new LinkedHashMap<>();
		for (Map.Entry<String, ? extends InventoryResourceValue> entry : items.entrySet()) {
			String key = Objects.requireNonNull(entry.getKey(), "item key");
			InventoryResourceValue value = Objects.requireNonNull(entry.getValue(), "item value");
			if (value.resourceKind() != resourceKind)
				throw new IllegalArgumentException("inventory fragment lane mismatch");
			encoded.put(key, InventoryResourceCodec.encode(resourceKind, value));
		}
		return new InventoryEncodedFragment(resourceKind,
			new CaptureResult.OpaqueKeyedFragment(codecId, encoded));
	}

	public InventoryResourceKind resourceKind() {
		return resourceKind;
	}

	public String codecId() {
		return fragment.codecId();
	}

	/** The immutable fragment; the same instance is intentionally shared. */
	public CaptureResult.OpaqueKeyedFragment fragment() {
		return fragment;
	}

	/** Convenience for an immutable read-only entry view. */
	public @Unmodifiable Map<String, CaptureResult.OpaqueValue> entries() {
		return fragment.entries();
	}
}
