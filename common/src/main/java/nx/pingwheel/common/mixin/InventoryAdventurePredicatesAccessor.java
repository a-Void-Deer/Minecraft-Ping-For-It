package nx.pingwheel.common.mixin;

import java.util.List;
import net.minecraft.advancements.critereon.BlockPredicate;
import net.minecraft.world.item.AdventureModePredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AdventureModePredicate.class)
public interface InventoryAdventurePredicatesAccessor {
	@Accessor("predicates") List<BlockPredicate> pingforit$predicates();
}
