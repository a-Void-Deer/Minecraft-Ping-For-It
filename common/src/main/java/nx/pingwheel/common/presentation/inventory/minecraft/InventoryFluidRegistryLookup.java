package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.DefaultedMappedRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import nx.pingwheel.common.platform.IPlatformFluidRegistryService.Admission;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Audited raw fluid registration primitives shared by native loader adapters. No arbitrary registry port. */
@NotNullByDefault
public final class InventoryFluidRegistryLookup {

	private InventoryFluidRegistryLookup() {}

	public static @Nullable ResourceLocation mappedKey(Fluid fluid, Admission admission) {
		admission.admit(512, 0);
		Object registry = BuiltInRegistries.FLUID;
		if (fluid == Fluids.EMPTY || registry.getClass() != MappedRegistry.class && registry.getClass() != DefaultedMappedRegistry.class)
			return null;
		var mapped = (MappedRegistry<?>) registry;
		var raw = InventoryResidentAccess.load();
		if (raw.registryKey(mapped) != Registries.FLUID) return null;
		@Nullable Object candidate = identityHolder(raw.registryValues(mapped), fluid, admission);
		return holderKey(candidate, fluid, admission, raw);
	}

	/** IdentityHashMap#get uses reference comparison only. Pay retained capacity, even after clear(). */
	public static @Nullable Object identityHolder(@Nullable Map<?, ?> holders, Fluid fluid, Admission admission) {
		admission.admit(128, 0);
		if (holders == null || holders.getClass() != IdentityHashMap.class || !InventoryNativeTraversal.runtimeSupported()) return null;
		admission.admit(8192, 4096);
		if (holders.isEmpty()) return null; // no get on the retained empty table
		var shape = InventoryNativeTraversal.inspect(holders, 1L << 31);
		if (!shape.accepted()) return null;
		// H is the actual key/value-cell table length. At most H/2 occupied probes + one
		// terminating probe; 16(H+1) also pays dispatch, mask/hash and branch work.
		admission.admit(Math.multiplyExact(16, Math.addExact(shape.bucketWork(), 1)), 0);
		return holders.get(fluid);
	}

	public static @Nullable ResourceLocation holderKey(@Nullable Object candidate, Fluid fluid, Admission admission) {
		return holderKey(candidate, fluid, admission, InventoryResidentAccess.load());
	}
	private static @Nullable ResourceLocation holderKey(@Nullable Object candidate, Fluid fluid, Admission admission, InventoryResidentAccess raw) {
		admission.admit(512, 0);
		if (candidate == null || candidate.getClass() != Holder.Reference.class) return null;
		var holder = (Holder.Reference<?>) candidate;
		if (raw.holderValue(holder) != fluid) return null;
		@Nullable ResourceKey<?> key = raw.holderKey(holder);
		if (key == null || key.getClass() != ResourceKey.class) return null;
		if (key.registry() != Registries.FLUID.location()) return null;
		@Nullable ResourceLocation location = key.location();
		return boundedLocation(location, admission) ? location : null;
	}

	/** Final native ResourceLocation exposes immutable String lengths before any proportional operation. */
	public static boolean boundedLocation(@Nullable ResourceLocation location, Admission admission) {
		admission.admit(128, 0);
		if (location == null || location.getClass() != ResourceLocation.class) return false;
		int namespace = location.getNamespace().length(), path = location.getPath().length();
		return namespace > 0 && path > 0 && (long) namespace + path + 1 <= 256;
	}
}
