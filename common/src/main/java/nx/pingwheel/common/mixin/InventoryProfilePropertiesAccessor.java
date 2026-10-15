package nx.pingwheel.common.mixin;

import com.google.common.collect.Multimap;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = PropertyMap.class, remap = false)
public interface InventoryProfilePropertiesAccessor {
	@Accessor("properties") Multimap<String, Property> pingforit$properties();
}
