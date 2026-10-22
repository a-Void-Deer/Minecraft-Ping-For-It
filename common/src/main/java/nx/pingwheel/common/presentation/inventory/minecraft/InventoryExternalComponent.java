package nx.pingwheel.common.presentation.inventory.minecraft;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/** Ephemeral external-trusted call; only the guarded owned tag enters identity. */
final class InventoryExternalComponent {
	private @Nullable Object resident;
	private @Nullable Codec<Object> codec;
	private final InventoryRegistryContext context;
	private final InventoryComponentBounds bounds;
	private final InventoryItemBudget budget;
	private final int depth;
	private @Nullable Tag owned;

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
			// Accept only full success; never evaluate native error suppliers or
			// adopt a partial. Diagnostics retain only the known failure stage.
			budget.work(64); budget.allocate(0, 0, 1, 1);
			Tag encoded = InventoryItemDiagnostics.complete(codec.encodeStart(context.externalOps(), resident), "external component");
			owned = bounds.ownedPersistentTag(encoded, depth);
			resident = null; codec = null;
		}
		return owned;
	}
}
