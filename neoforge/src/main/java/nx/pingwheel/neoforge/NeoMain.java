package nx.pingwheel.neoforge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.CommonServer;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.network.ServerConfigSnapshotS2CPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import nx.pingwheel.common.network.ServerPresentationPolicyC2SPacket;
import nx.pingwheel.common.network.ServerPresentationPolicyS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.neoforge.platform.PlatformContextServiceImpl;

import static nx.pingwheel.common.Global.MOD_ID;

@Mod(MOD_ID)
public class NeoMain {

	private static final StreamCodec<FriendlyByteBuf, UpdateChannelC2SPacket> UPDATE_CHANNEL_C2S_CODEC = StreamCodec.ofMember(UpdateChannelC2SPacket::write, UpdateChannelC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, RateLimitPolicyS2CPacket> RATE_LIMIT_POLICY_S2C_CODEC = StreamCodec.ofMember(RateLimitPolicyS2CPacket::write, RateLimitPolicyS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, ServerConfigRequestC2SPacket> SERVER_CONFIG_REQUEST_C2S_CODEC = StreamCodec.ofMember(ServerConfigRequestC2SPacket::write, ServerConfigRequestC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, ServerConfigUpdateC2SPacket> SERVER_CONFIG_UPDATE_C2S_CODEC = StreamCodec.ofMember(ServerConfigUpdateC2SPacket::write, ServerConfigUpdateC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, ServerConfigSnapshotS2CPacket> SERVER_CONFIG_SNAPSHOT_S2C_CODEC = StreamCodec.ofMember(ServerConfigSnapshotS2CPacket::write, ServerConfigSnapshotS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, ServerPresentationPolicyC2SPacket> SERVER_PRESENTATION_POLICY_C2S_CODEC = StreamCodec.ofMember(ServerPresentationPolicyC2SPacket::write, ServerPresentationPolicyC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, ServerPresentationPolicyS2CPacket> SERVER_PRESENTATION_POLICY_S2C_CODEC = StreamCodec.ofMember(ServerPresentationPolicyS2CPacket::write, ServerPresentationPolicyS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, PresentationC2SPacket> PRESENTATION_C2S_CODEC = StreamCodec.ofMember(PresentationC2SPacket::write, PresentationC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, PresentationS2CPacket> PRESENTATION_S2C_CODEC = StreamCodec.ofMember(PresentationS2CPacket::write, PresentationS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, InventoryC2SPacket> INVENTORY_C2S_CODEC = StreamCodec.ofMember(InventoryC2SPacket::write, InventoryC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, InventoryS2CPacket> INVENTORY_S2C_CODEC = StreamCodec.ofMember(InventoryS2CPacket::write, InventoryS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, PresentationPreviewC2SPacket> PRESENTATION_PREVIEW_C2S_CODEC = StreamCodec.ofMember(PresentationPreviewC2SPacket::write, PresentationPreviewC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, PresentationPreviewS2CPacket> PRESENTATION_PREVIEW_S2C_CODEC = StreamCodec.ofMember(PresentationPreviewS2CPacket::write, PresentationPreviewS2CPacket::readSafe);

	public NeoMain(IEventBus modBus) {
		CommonServer.INSTANCE.onInit();
		NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> CommonServer.INSTANCE.onServerStopped(event.getServer()));

		PlatformContextServiceImpl.modBus = modBus;

		modBus.addListener(this::onRegisterPackets);

		if (FMLEnvironment.dist.isClient()) {
			new NeoClient(modBus);
		}
	}

	public void onRegisterPackets(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar(MOD_ID).optional();

		registrar.playToServer(UpdateChannelC2SPacket.PACKET_TYPE, UPDATE_CHANNEL_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onChannelUpdatePacket(context.player().getServer(), (ServerPlayer)context.player(), payload));
		});

		registrar.playToClient(RateLimitPolicyS2CPacket.PACKET_TYPE, RATE_LIMIT_POLICY_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onRateLimitPolicyPacket(payload));
		});

		registrar.playToServer(ServerConfigRequestC2SPacket.PACKET_TYPE, SERVER_CONFIG_REQUEST_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onServerConfigRequestPacket(context.player().getServer(), (ServerPlayer) context.player(), payload));
		});

		registrar.playToServer(ServerConfigUpdateC2SPacket.PACKET_TYPE, SERVER_CONFIG_UPDATE_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onServerConfigUpdatePacket(context.player().getServer(), (ServerPlayer) context.player(), payload));
		});

		registrar.playToClient(ServerConfigSnapshotS2CPacket.PACKET_TYPE, SERVER_CONFIG_SNAPSHOT_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onServerConfigSnapshotPacket(payload));
		});

		registrar.playToServer(ServerPresentationPolicyC2SPacket.PACKET_TYPE, SERVER_PRESENTATION_POLICY_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onServerPresentationPolicyPacket(context.player().getServer(), (ServerPlayer) context.player(), payload));
		});

		registrar.playToClient(ServerPresentationPolicyS2CPacket.PACKET_TYPE, SERVER_PRESENTATION_POLICY_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onServerPresentationPolicyPacket(payload));
		});

		registrar.playToServer(PresentationC2SPacket.PACKET_TYPE, PRESENTATION_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onPresentationPacket(context.player().getServer(), (ServerPlayer) context.player(), payload));
		});

		registrar.playToClient(PresentationS2CPacket.PACKET_TYPE, PRESENTATION_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onPresentationPacket(payload));
		});

		registrar.playToServer(InventoryC2SPacket.PACKET_TYPE, INVENTORY_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onInventoryPacket(context.player().getServer(), (ServerPlayer) context.player(), payload));
		});

		registrar.playToClient(InventoryS2CPacket.PACKET_TYPE, INVENTORY_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onInventoryPacket(payload));
		});
		registrar.playToServer(PresentationPreviewC2SPacket.PACKET_TYPE, PRESENTATION_PREVIEW_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onPresentationPreview(context.player().getServer(), (ServerPlayer) context.player(), payload));
		});
		registrar.playToClient(PresentationPreviewS2CPacket.PACKET_TYPE, PRESENTATION_PREVIEW_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onPresentationPreview(payload));
		});
	}
}
