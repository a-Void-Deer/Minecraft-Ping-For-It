package nx.pingwheel.common.mixin;

import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RegistryAccess.ImmutableRegistryAccess.class)
public interface InventoryRegistryAccessAccessor {
	@Accessor("registries") Map<? extends ResourceKey<?>, ? extends Registry<?>> pingforit$registries();
}
