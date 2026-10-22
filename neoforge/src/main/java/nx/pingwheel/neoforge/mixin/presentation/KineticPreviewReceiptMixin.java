package nx.pingwheel.neoforge.mixin.presentation;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import nx.pingwheel.neoforge.integration.create.presentation.CreateClientPreviewReceipt;
import nx.pingwheel.neoforge.integration.create.presentation.CreatePreviewReceiptState;

/** Version/shape-gated client receipt only; constructor/default/virtual state is not evidence. */
@Mixin(targets = "com.simibubi.create.content.kinetics.base.KineticBlockEntity", remap = false)
public abstract class KineticPreviewReceiptMixin implements CreateClientPreviewReceipt {
	@Shadow(remap = false) protected boolean wasMoved;
	@Unique private final CreatePreviewReceiptState pingforit$receipt = new CreatePreviewReceiptState();
	@Inject(method = "read", at = @At("HEAD"), remap = false, require = 0)
	private void pingforit$beforeRead(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
		pingforit$receipt.clear();
	}
	@Inject(method = "read", at = @At("TAIL"), remap = false, require = 0)
	private void pingforit$afterRead(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
		KineticBlockEntity entity = (KineticBlockEntity) (Object) this;
		pingforit$receipt.accept(tag, clientPacket, entity.getLevel() != null && entity.getLevel().isClientSide,
			wasMoved, entity.isVirtual(), entity.getLevel() == null ? -1 : entity.getLevel().getGameTime());
	}
	@Inject(method = {"clearKineticInformation", "warnOfMovement"}, at = @At("HEAD"), remap = false, require = 0)
	private void pingforit$invalidate(CallbackInfo ci) { pingforit$receipt.clear(); }
	@Override public boolean pingforit$receivedKinetics() { return pingforit$receipt.received() && !wasMoved; }
	@Override public long pingforit$kineticReceiptTick() { return pingforit$receipt.tick(); }
}
