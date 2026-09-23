package nx.pingwheel.neoforge.mixin.presentation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import nx.pingwheel.neoforge.integration.create.presentation.CreateCachedNetworkView;

/** Reads Create's already-cached network totals; never recalculates a network. */
@Mixin(targets = "com.simibubi.create.content.kinetics.base.KineticBlockEntity", remap = false)
public abstract class KineticCachedNetworkMixin implements CreateCachedNetworkView {
	@Shadow(remap = false) protected float stress;
	@Shadow(remap = false) protected float capacity;

	@Override
	public float pingforit$cachedNetworkStress() {
		return stress;
	}

	@Override
	public float pingforit$cachedNetworkCapacity() {
		return capacity;
	}
}
