package nx.pingwheel.common.presentation.inventory.minecraft;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Pure owned metadata model. Resident worlds, containers and foreign capability internals are borrowed. */
@NotNullByDefault
final class InventorySourceRetention {
	private static final long O = 40, R = 8;
	private InventorySourceRetention() {}
	static long array(long cells, long width) { return O + cells * width; }
	static long list(long cells) { return O + array(cells, R); }
	private static long text(@Nullable String value) { return value == null ? 0 : 2 * O + 2L * value.length(); }
	static long layout(@Nullable InventorySnapshotLayout layout) {
		if (layout == null) return 0;
		long bytes = O + 4 * R + text(layout.layoutId()) + text(layout.layoutData()) + O + 12 + list(layout.members().size());
		for (var member : layout.members()) {
			bytes += O + 5 * R + O + 12 + text(member.blockId()) + text(member.blockEntityId()) + text(member.role()) + list(member.segments().size());
			for (var segment : member.segments()) {
				// Each boxed index is paid even when the VM happens to share a cached Integer.
				bytes += O + 3 * R + 4 + text(segment.fieldPath()) + text(segment.schemaId())
					+ list(segment.visibleSlots().size()) + segment.visibleSlots().size() * (O + 4);
			}
		}
		return bytes;
	}
	/** Scope closure, reusable audit scratch, fixed cache directories and <=32 compact composed ranges. */
	static long scope(int members) {
		return O + 12 * R + array(members, R) + list(members) + O + R + 2 * array(16, R) + 2 * array(1, 4)
			+ 2 * array(32, R) + 32 * (O + 4);
	}
	/** Known native Vault ownership beyond its shared snapshot DTO: Layout, positions/counts, offsets, wrappers. */
	static long provider(@Nullable InventorySnapshotLayout layout) {
		long members = layout == null ? 1 : layout.members().size();
		// Fixed bridge wrappers/closures are bounded independently of borrowed provider internals.
		long bytes = 16 * (O + 8 * R);
		if (layout != null && layout.members().stream().anyMatch(member -> member.blockId().equals("create:item_vault")))
			bytes += O + 5 * R + 3 * 4 + 2 * list(members) + members * (O + 12 + O + 4) + array(members + 1, 4);
		return bytes;
	}
	static long source(InventoryMinecraftSources.View view) {
		// Source, world/read closures, codec shell, validation profile, key/identity and fixed field state.
		long bytes = 16 * (O + 8 * R) + text(view.identity()) + layout(view.snapshotLayout())
			+ list(view.entities().size()) + list(view.slots().size()) + view.slots().size() * (O + R + 4);
		if (view.access() != null) {
			int members = view.snapshotLayout() == null ? 1 : view.snapshotLayout().members().size();
			// Reacquired accesses are operation-local and paid by validation scratch.
			// The access and source share this one layout DTO; provider() excludes it.
			bytes += scope(members) + provider(view.snapshotLayout());
		}
		return bytes;
	}
	static long external(InventoryMinecraftSources.View view, nx.pingwheel.common.integration.externalblock.BlockReadSource descriptor) {
		// ExternalSource retains detached evidence only, never View, slots, scope or a cross-tick binding.
		return 24 * (O + 8 * R) + text(view.identity()) + layout(view.snapshotLayout())
			+ O + 5 * R + text(descriptor.subLevelId()) + O + 4 * R + 3 * 4 + O + 3 * 8;
	}
}
