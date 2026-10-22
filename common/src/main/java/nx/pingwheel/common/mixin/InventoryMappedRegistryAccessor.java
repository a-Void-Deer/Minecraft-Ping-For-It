package nx.pingwheel.common.mixin;

import java.util.Map;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MappedRegistry.class)
public interface InventoryMappedRegistryAccessor {
	@Accessor("key") ResourceKey<?> pingforit$key();
	@Accessor("lookup") HolderLookup.RegistryLookup<?> pingforit$lookup();
	@Accessor("registryLifecycle") Lifecycle pingforit$lifecycle();
	@Accessor("byKey") Map<ResourceKey<?>, Holder.Reference<?>> pingforit$byKey();
	@Accessor("byValue") Map<?, ?> pingforit$byValue();
	@Accessor("registrationInfos") Map<?, ?> pingforit$registrationInfos();
}
