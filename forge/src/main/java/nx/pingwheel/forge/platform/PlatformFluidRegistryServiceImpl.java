package nx.pingwheel.forge.platform;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegistryManager;
import nx.pingwheel.common.platform.IPlatformFluidRegistryService;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryFluidRegistryLookup;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Forge 52.0.2: identity holder plus a fresh forward active-name proof. Never uses owners.inverse(). */
@NotNullByDefault
public final class PlatformFluidRegistryServiceImpl implements IPlatformFluidRegistryService {

	private static @Nullable Metadata metadata;
	private static boolean resolved;

	@Override public @Nullable ResourceLocation registeredKey(Fluid fluid, Admission admission) {
		admission.admit(512, 0);
		if (fluid == Fluids.EMPTY) return null;
		@Nullable Metadata fields = metadata(admission);
		if (fields == null) return null;
		// Also pay lazy Field getter accessors on every attempt: discovery may have succeeded
		// in an earlier call whose current-read admission was refused before the first get.
		admission.admit(8192, 65536);
		Object wrapper = BuiltInRegistries.FLUID;
		if (wrapper.getClass() != fields.wrapper) return null;
		try {
			@Nullable Object delegate = fields.childDelegate.get(wrapper);
			if (delegate == null || delegate.getClass() != ForgeRegistry.class || fields.parentDelegate.get(wrapper) != delegate
				|| fields.wrapperStage.get(wrapper) != RegistryManager.ACTIVE || fields.delegateStage.get(delegate) != RegistryManager.ACTIVE
				|| fields.registryKey.get(delegate) != Registries.FLUID) return null;
			@Nullable Object holders = fields.holders.get(wrapper);
			if (!(holders instanceof Map<?, ?> map)) return null;
			@Nullable Object holder = InventoryFluidRegistryLookup.identityHolder(map, fluid, admission);
			@Nullable ResourceLocation location = InventoryFluidRegistryLookup.holderKey(holder, fluid, admission);
			if (location == null) return null;
			admission.admit(256, 0);
			@Nullable Object currentNames = fields.names.get(delegate);
			if (currentNames == null || currentNames.getClass() != HashBiMap.class) return null;
			var names = (Map<?, ?>) currentNames;
			// Guava 32.1.2 HashBiMap forward seek visits at most N entries. Each comparison
			// calls the bounded final query ResourceLocation's equals (never Fluid.equals).
			// Hashing pays both full String hash scans; each collision pays <=L+1 character
			// comparisons. 32(N+1)(L+1)+512 dominates hash/smear/seek/dispatch. No inverse,
			// iterator, copy, callback, aliases or default-value route is involved.
			int count = names.size();
			long length = (long) location.getNamespace().length() + location.getPath().length() + 1;
			admission.admit(Math.addExact(512, Math.multiplyExact(32, Math.multiplyExact(count + 1L, length + 1))), 0);
			return names.get(location) == fluid ? location : null;
		} catch (IllegalAccessException unavailable) {
			return null;
		}
	}

	private static synchronized @Nullable Metadata metadata(Admission admission) {
		if (!resolved) {
			// Seven fixed Field discoveries/accessibility descriptors + two fixed class lookups.
			// Includes lazy first Field getter accessors, descriptor checks, failure scratch and
			// cache object (<=8 KiB/site, <=4096 events/site, with two-site headroom).
			admission.admit(65536, 98304);
			try {
				Class<?> wrapper = Class.forName("net.minecraftforge.registries.NamespacedDefaultedWrapper", false,
					PlatformFluidRegistryServiceImpl.class.getClassLoader());
				Class<?> parent = Class.forName("net.minecraftforge.registries.NamespacedWrapper", false, wrapper.getClassLoader());
				if (wrapper.getSuperclass() != parent || parent.getSuperclass() != net.minecraft.core.MappedRegistry.class) {
					resolved = true; return null;
				}
				metadata = new Metadata(wrapper, field(wrapper, "delegate", ForgeRegistry.class), field(parent, "delegate", ForgeRegistry.class),
					field(parent, "holders", Map.class), field(parent, "stage", RegistryManager.class),
					field(ForgeRegistry.class, "stage", RegistryManager.class), field(ForgeRegistry.class, "key", ResourceKey.class),
					field(ForgeRegistry.class, "names", BiMap.class));
			} catch (ReflectiveOperationException | RuntimeException unavailable) {
				metadata = null;
			}
			resolved = true; // fatal discovery failure propagates without installing a failed cache
		} else admission.admit(128, 0);
		return metadata;
	}
	private static Field field(Class<?> owner, String name, Class<?> type) throws ReflectiveOperationException {
		Field result = owner.getDeclaredField(name);
		if (result.getType() != type || Modifier.isStatic(result.getModifiers())) throw new NoSuchFieldException(name);
		result.setAccessible(true); return result;
	}
	@NotNullByDefault
	private record Metadata(Class<?> wrapper, Field childDelegate, Field parentDelegate, Field holders,
		Field wrapperStage, Field delegateStage, Field registryKey, Field names) {}
}
