package nx.pingwheel.common.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.client.ClientPingRuntime;
import nx.pingwheel.common.client.marker.ObservedServerClock;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes game time only after vanilla has processed a packet on the active client. */
@NotNullByDefault
@Mixin(ClientPacketListener.class)
public abstract class ClientGameTimeMixin implements ObservedServerClock.Source {

	@Unique
	private final ObservedServerClock pingforit$observedServerClock = new ObservedServerClock();

	@Override
	public ObservedServerClock pingforit$observedServerClock() {
		return pingforit$observedServerClock;
	}

	@Inject(method = "handleSetTime(Lnet/minecraft/network/protocol/game/ClientboundSetTimePacket;)V", at = @At("TAIL"))
	private void pingforit$onSetTime(ClientboundSetTimePacket packet, CallbackInfo callbackInfo) {
		Minecraft client = Minecraft.getInstance();
		ClientPacketListener listener = (ClientPacketListener) (Object) this;
		if (!client.isSameThread() || client.getConnection() != listener || !listener.isAcceptingMessages()) return;

		@Nullable ClientLevel level = listener.getLevel();
		if (level != null && client.level == level) {
			pingforit$observedServerClock.observeSample(level, packet.getGameTime());
			@Nullable ClientPingRuntime runtime = CommonClient.INSTANCE.getPingRuntime();
			if (runtime != null) runtime.onObservedServerTime(pingforit$observedServerClock);
		}
	}
}
