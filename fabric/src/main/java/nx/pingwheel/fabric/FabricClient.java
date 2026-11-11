package nx.pingwheel.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.Connection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.monitor.integration.MonitorPacketDispatch;
import nx.pingwheel.common.command.ClientCommandBuilder;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.ServerConfigSnapshotS2CPacket;
import nx.pingwheel.common.network.ServerPresentationPolicyS2CPacket;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.resource.ResourceReloadListener;
import nx.pingwheel.fabric.integration.FabricWorldAwareBlockModelOutlineAdapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import org.jetbrains.annotations.Nullable;

import static nx.pingwheel.common.Global.MOD_ID;
import static nx.pingwheel.common.resource.ResourceConstants.PING_SOUND_EVENT;
import static nx.pingwheel.common.resource.ResourceConstants.PING_SOUND_ID;

@Environment(EnvType.CLIENT)
public class FabricClient implements ClientModInitializer {

	public static final ResourceLocation RELOAD_LISTENER_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "reload-listener");

	@Override
	public void onInitializeClient() {
		FabricWorldAwareBlockModelOutlineAdapter.register();
		ClientPlayNetworking.registerGlobalReceiver(
			RateLimitPolicyS2CPacket.PACKET_TYPE,
			(packet, context) -> context.client().execute(() -> CommonClient.INSTANCE.onRateLimitPolicyPacket(packet)));
		CommonClient.INSTANCE.onInit();
		registerMonitorReceivers(CommonClient.INSTANCE::onMonitorStatus, CommonClient.INSTANCE::onMonitorSample);

		Registry.register(BuiltInRegistries.SOUND_EVENT, PING_SOUND_ID, PING_SOUND_EVENT);

		// packets
		ClientPlayNetworking.registerGlobalReceiver(
			ServerConfigSnapshotS2CPacket.PACKET_TYPE,
			(packet, context) -> context.client().execute(() -> CommonClient.INSTANCE.onServerConfigSnapshotPacket(packet)));
		ClientPlayNetworking.registerGlobalReceiver(
			ServerPresentationPolicyS2CPacket.PACKET_TYPE,
			(packet, context) -> context.client().execute(() -> CommonClient.INSTANCE.onServerPresentationPolicyPacket(packet)));
		ClientPlayNetworking.registerGlobalReceiver(
			PresentationS2CPacket.PACKET_TYPE,
			(packet, context) -> context.client().execute(() -> CommonClient.INSTANCE.onPresentationPacket(packet)));
		ClientPlayNetworking.registerGlobalReceiver(
			InventoryS2CPacket.PACKET_TYPE,
			(packet, context) -> {
				Minecraft client = context.client();
				@Nullable ClientPacketListener origin = client.getConnection();
				if (origin == null || context.responseSender() != ClientPlayNetworking.getSender()) return;
				client.execute(() -> {
					if (client.getConnection() == origin && origin.isAcceptingMessages()) CommonClient.INSTANCE.onInventoryPacket(packet);
				});
			});
		ClientPlayNetworking.registerGlobalReceiver(PresentationPreviewS2CPacket.PACKET_TYPE,
			(packet, context) -> context.client().execute(() -> CommonClient.INSTANCE.onPresentationPreview(packet)));

		// resource reload
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
			.registerReloadListener(new IdentifiableResourceReloadListener() {
				@Override
				public ResourceLocation getFabricId() {
					return RELOAD_LISTENER_ID;
				}

				@Override
				public CompletableFuture<Void> reload(PreparationBarrier helper, ResourceManager resourceManager, ProfilerFiller loadProfiler, ProfilerFiller applyProfiler, Executor loadExecutor, Executor applyExecutor) {
					return ResourceReloadListener.reloadTextures(helper, resourceManager, loadExecutor, applyExecutor);
				}
			});

		// commands
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

	static void registerMonitorReceivers(BiConsumer<Long, InventoryMonitorStatusS2CPacket> status,
		BiConsumer<Long, InventoryMonitorSampleS2CPacket> sample) {
		ClientPlayNetworking.registerGlobalReceiver(InventoryMonitorStatusS2CPacket.PACKET_TYPE,
			(packet, context) -> dispatchMonitor(packet, context, token -> status.accept(token, packet)));
		ClientPlayNetworking.registerGlobalReceiver(InventoryMonitorSampleS2CPacket.PACKET_TYPE,
			(packet, context) -> dispatchMonitor(packet, context, token -> sample.accept(token, packet)));
	}

	private static void dispatchMonitor(nx.pingwheel.common.network.IPacket packet,
		ClientPlayNetworking.Context context, LongConsumer receive) {
		Minecraft client = context.client();
		@Nullable ClientPacketListener listener = client.getConnection();
		// Context.player() is dynamic in Fabric; the response sender retains the originating addon.
		if (listener == null || !listener.isAcceptingMessages() || context.responseSender() != ClientPlayNetworking.getSender()) return;
		Connection connection = listener.getConnection();
		long token = CommonClient.INSTANCE.monitorConnectionToken();
		MonitorPacketDispatch.client(client, connection, () -> {
			@Nullable ClientPacketListener current = client.getConnection();
			return current == null ? null : current.getConnection();
		}, packet, () -> receive.accept(token));
	}
}
