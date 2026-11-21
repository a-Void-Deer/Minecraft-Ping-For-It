package nx.pingwheel.common.integration.aeronautics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Both normal and gyro rotors use this one exact entity registry identity. */
@NotNullByDefault
public final class AeroRotorScope {
	public static final ResourceLocation ENTITY_TYPE_ID =
		ResourceLocation.fromNamespaceAndPath("aeronautics", "propeller_bearing_contraption");
	private AeroRotorScope() {}
	public static boolean matches(Entity entity) {
		return matchesId(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
	}
	static boolean matchesId(@Nullable ResourceLocation id) { return ENTITY_TYPE_ID.equals(id); }
}
