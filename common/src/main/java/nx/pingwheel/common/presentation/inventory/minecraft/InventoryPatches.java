package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.PatchedDataComponentMap;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Shared raw-patch traversal and reconstruction, never an effective component
 * view. No prototype, codec, value comparison or copying getter is consulted.
 * Values passed to a visitor are borrowed; owning mutable values and selecting
 * persistent or display components remain the caller's responsibilities.
 */
@NotNullByDefault
public final class InventoryPatches {

	private static final Class<?> EMPTY_MAP = Reference2ObjectMaps.emptyMap().getClass();

	private InventoryPatches() {}

	/**
	 * Visits only the actual patch of an exact native patched map. In particular,
	 * this does not call {@code asPatch()} or change its copy-on-write state.
	 * A simple, composite or foreign outer map is unsupported, even when empty.
	 *
	 * @return the number of visited additions and removals
	 * @throws UnsupportedPatchException if the outer map, backing or entries are unsupported
	 */
	public static int forEach(@Nullable DataComponentMap components, InventoryResidentAccess raw,
		Admission admission, BiConsumer<DataComponentType<?>, Optional<?>> visitor) {
		admission.inspect();
		if (components == null || components.getClass() != PatchedDataComponentMap.class) throw new UnsupportedPatchException();
		return visit(raw.patch((PatchedDataComponentMap) components), raw, admission, visitor);
	}

	/** Same traversal for a native standalone patch, preserving explicit removals. */
	public static int forEach(@Nullable DataComponentPatch patch, InventoryResidentAccess raw,
		Admission admission, BiConsumer<DataComponentType<?>, Optional<?>> visitor) {
		admission.inspect();
		if (patch == null || patch.getClass() != DataComponentPatch.class) throw new UnsupportedPatchException();
		return visit(raw.patch(patch), raw, admission, visitor);
	}

	private static int visit(@Nullable Map<?, ?> entries, InventoryResidentAccess raw,
		Admission admission, BiConsumer<DataComponentType<?>, Optional<?>> visitor) {
		if (entries == null) throw new UnsupportedPatchException();
		Class<?> type = entries.getClass();
		if (type != Reference2ObjectArrayMap.class && type != Reference2ObjectOpenHashMap.class && type != EMPTY_MAP)
			throw new UnsupportedPatchException();
		int size = entries.size();
		if (size < 0) throw new UnsupportedPatchException();
		int capacity = 0;
		if (size != 0 && type == Reference2ObjectOpenHashMap.class) {
			capacity = raw.referenceMapCapacity(entries);
			if (capacity < 1 || (capacity & (capacity - 1)) != 0 || size > capacity) throw new UnsupportedPatchException();
		}
		admission.traverse(size, capacity);
		// Empty output needs no native view/iterator or scan of retained buckets.
		if (size == 0) return 0;
		int visited = 0;
		for (var entry : entries.entrySet()) {
			if (visited == size) throw new UnsupportedPatchException();
			@Nullable Object key = entry.getKey();
			@Nullable Object value = entry.getValue();
			if (!(key instanceof DataComponentType<?> component) || !(value instanceof Optional<?> optional))
				throw new UnsupportedPatchException();
			visitor.accept(component, optional);
			visited++;
		}
		if (visited != size) throw new UnsupportedPatchException();
		return visited;
	}

	/**
	 * Writes one already admitted/owned entry without prototype normalization.
	 * An empty optional means removal, not omission. The caller admits native
	 * builder insertion/growth and must not mutate the builder after publishing
	 * its built patch: the native builder and result share their backing map.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static void put(DataComponentPatch.Builder builder, DataComponentType<?> type, Optional<?> value) {
		if (value.isEmpty()) builder.remove(type);
		else builder.set((DataComponentType) type, value.orElseThrow());
	}

	/**
	 * The two consumers keep their own accounting units and refusal exceptions.
	 * Every call admits its own pass; callbacks may throw to refuse before work.
	 */
	@NotNullByDefault
	public interface Admission {
		/**
		 * Before all outer/raw/backing inspection: admit fixed dispatch and field
		 * reads, including at most one reference-map capacity read. No iterator or
		 * value callback runs during inspection. Include the fixed refusal object
		 * if the caller's allocation ledger accounts for exceptional outcomes.
		 */
		void inspect();

		/**
		 * Before entrySet/iterator construction: admit the pass's native traversal
		 * and iterator/entry allocations, plus any caller output workspace. Capacity
		 * is the nonempty open hash map's bucket count, otherwise zero. Array-map
		 * iteration scans live entries only. The visitor must not mutate the source;
		 * any callbacks, owned value copies and builder growth need their own grant.
		 */
		void traverse(int entries, int capacity);
	}

	/**
	 * Unsupported native shape or malformed/changing patch, not a budget outcome.
	 * Server callers translate this to identity refusal; clients to unsafe display.
	 * Admission and visitor failures otherwise propagate unchanged.
	 */
	@NotNullByDefault
	public static final class UnsupportedPatchException extends RuntimeException {
		private UnsupportedPatchException() { super("unsupported inventory component patch", null, false, false); }
	}
}
