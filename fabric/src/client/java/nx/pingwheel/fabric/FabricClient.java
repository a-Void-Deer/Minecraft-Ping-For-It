package nx.pingwheel.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;

import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.command.ClientCommandBuilder;
import nx.pingwheel.common.network.MarkerCreatedS2CPacket;
import nx.pingwheel.common.network.MarkerRejectedS2CPacket;
import nx.pingwheel.common.network.MarkerRemovedS2CPacket;
import nx.pingwheel.common.network.MarkerWinnerChangedS2CPacket;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.ServerConfigSnapshotS2CPacket;
import nx.pingwheel.common.network.SyncDurationPolicyS2CPacket;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.resource.ResourceReloadListener;

import static nx.pingwheel.common.Global.MOD_ID;
import static nx.pingwheel.common.resource.ResourceConstants.PING_SOUND_EVENT;
import static nx.pingwheel.common.resource.ResourceConstants.PING_SOUND_ID;

@Environment(EnvType.CLIENT)
public class FabricClient implements ClientModInitializer {

	public static final Identifier RELOAD_LISTENER_ID = Identifier.fromNamespaceAndPath(MOD_ID, "reload-listener");

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(
			RateLimitPolicyS2CPacket.PACKET_TYPE,
			(packet, context) -> context.client().execute(() -> CommonClient.INSTANCE.onRateLimitPolicyPacket(packet)));
		ClientPlayNetworking.registerGlobalReceiver(
			SyncDurationPolicyS2CPacket.PACKET_TYPE,
			(packet, context) -> context.client().execute(() -> CommonClient.INSTANCE.onSyncDurationPolicyPacket(packet)));
		CommonClient.INSTANCE.onInit();

		Registry.register(BuiltInRegistries.SOUND_EVENT, PING_SOUND_ID, PING_SOUND_EVENT);

		ClientPlayNetworking.registerGlobalReceiver(
			PingLocationS2CPacket.PACKET_TYPE,
			(packet, context) -> {
				var client = context.client();
				client.execute(() -> CommonClient.INSTANCE.onPingLocationPacket(packet));
			}
		);
		ClientPlayNetworking.registerGlobalReceiver(
			ServerConfigSnapshotS2CPacket.PACKET_TYPE,
			(packet, context) -> context.client().execute(() -> CommonClient.INSTANCE.onServerConfigSnapshotPacket(packet)));
		ClientPlayNetworking.registerGlobalReceiver(
			MarkerCreatedS2CPacket.PACKET_TYPE,
			(packet, context) -> {
				var client = context.client();
				client.execute(() -> CommonClient.INSTANCE.onMarkerCreatedPacket(packet));
			}
		);
		ClientPlayNetworking.registerGlobalReceiver(
			MarkerRemovedS2CPacket.PACKET_TYPE,
			(packet, context) -> {
				var client = context.client();
				client.execute(() -> CommonClient.INSTANCE.onMarkerRemovedPacket(packet));
			}
		);
		ClientPlayNetworking.registerGlobalReceiver(
			MarkerRejectedS2CPacket.PACKET_TYPE,
			(packet, context) -> {
				var client = context.client();
				client.execute(() -> CommonClient.INSTANCE.onMarkerRejectedPacket(packet));
			}
		);
		ClientPlayNetworking.registerGlobalReceiver(
			MarkerWinnerChangedS2CPacket.PACKET_TYPE,
			(packet, context) -> {
				var client = context.client();
				client.execute(() -> CommonClient.INSTANCE.onMarkerWinnerChangedPacket(packet));
			}
		);

		ResourceLoader.get(PackType.CLIENT_RESOURCES)
			.registerReloadListener(RELOAD_LISTENER_ID, new ResourceReloadListener());

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandBuilder.build((context, success, response) -> {
				if (success) {
					context.getSource().sendFeedback(LanguageUtils.withModPrefix(response));
				} else {
					context.getSource().sendError(LanguageUtils.withModPrefix(response));
				}
			}));
		});
	}
}
