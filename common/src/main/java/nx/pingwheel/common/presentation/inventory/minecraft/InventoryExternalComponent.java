package nx.pingwheel.common.presentation.inventory.minecraft;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.Tag;

/** Ephemeral external-trusted call; only the guarded owned tag enters identity. */
final class InventoryExternalComponent {
	private Object resident;
	private Codec<Object> codec;
	private final InventoryRegistryContext context;
	private final InventoryComponentBounds bounds;
	private final InventoryItemBudget budget;
	private final int depth;
	private Tag owned;

	@SuppressWarnings("unchecked")
	InventoryExternalComponent(Object resident, Codec<?> codec, InventoryRegistryContext context,
		InventoryComponentBounds bounds, InventoryItemBudget budget, int depth) {
		this.resident = resident; this.codec = (Codec<Object>) codec; this.context = context;
		this.bounds = bounds; this.budget = budget; this.depth = depth;
	}

	Tag encode() {
		if (owned == null) {
			// Count one actual native codec invocation, including any native cache,
			// resident getters/hash and registry callbacks it selects. Those call
			// internals are explicitly external-trusted, not a work/time certificate.
			// result() accepts only full success; never evaluate a lazy error message
			// or adopt a partial. The Optional wrapper is Ping-owned admission.
			budget.work(64); budget.allocate(0, 0, 1, 1);
			var encoded = codec.encodeStart(context.externalOps(), resident).result();
			if (encoded.isEmpty()) throw new InventoryItemBudget.RootLimitException("external component does not encode");
			owned = bounds.ownedPersistentTag(encoded.orElseThrow(), depth);
			resident = null; codec = null;
		}
		return owned;
	}
}
