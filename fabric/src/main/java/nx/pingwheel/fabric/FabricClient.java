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
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
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
import nx.pingwheel.fabric.integration.FabricWorldAwareBlockModelOutlineAdapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

import static nx.pingwheel.common.Global.MOD_ID;
import static nx.pingwheel.common.resource.ResourceConstants.PING_SOUND_EVENT;
import static nx.pingwheel.common.resource.ResourceConstants.PING_SOUND_ID;

@Environment(EnvType.CLIENT)
public class FabricClient implements ClientModInitializer {

	public static final ResourceLocation RELOAD_LISTENER_ID = new ResourceLocation(MOD_ID, "reload-listener");

	/**
	 * Decodes a server packet inside the networking callback and runs the
	 * client handler on the game thread.
	 *
	 * <p>The legacy {@code Identifier}/{@code FriendlyByteBuf} receiver runs on
	 * a Netty event loop. Reading the buffer there is correct (and the buffer is
	 * never touched again afterwards), but the decoded packet must not mutate
	 * the marker store or other client state from that thread.</p>
	 */
	private static <T> void receive(
		ResourceLocation packetId,
		Function<FriendlyByteBuf, T> reader,
		Consumer<T> handler
	) {
		ClientPlayNetworking.registerGlobalReceiver(packetId, (client, networking, buf, responseSender) -> {
			T packet = reader.apply(buf);
			client.execute(() -> handler.accept(packet));
		});
	}

	@Override
	public void onInitializeClient() {
		FabricWorldAwareBlockModelOutlineAdapter.register();
		receive(RateLimitPolicyS2CPacket.PACKET_ID,
			RateLimitPolicyS2CPacket::readSafe, CommonClient.INSTANCE::onRateLimitPolicyPacket);
		receive(SyncDurationPolicyS2CPacket.PACKET_ID,
			SyncDurationPolicyS2CPacket::readSafe, CommonClient.INSTANCE::onSyncDurationPolicyPacket);
		CommonClient.INSTANCE.onInit();

		Registry.register(BuiltInRegistries.SOUND_EVENT, PING_SOUND_ID, PING_SOUND_EVENT);

		// packets
		receive(PingLocationS2CPacket.PACKET_ID,
			PingLocationS2CPacket::readSafe, CommonClient.INSTANCE::onPingLocationPacket);
		receive(ServerConfigSnapshotS2CPacket.PACKET_ID,
			ServerConfigSnapshotS2CPacket::readSafe, CommonClient.INSTANCE::onServerConfigSnapshotPacket);
		receive(MarkerCreatedS2CPacket.PACKET_ID,
			MarkerCreatedS2CPacket::readSafe, CommonClient.INSTANCE::onMarkerCreatedPacket);
		receive(MarkerRemovedS2CPacket.PACKET_ID,
			MarkerRemovedS2CPacket::readSafe, CommonClient.INSTANCE::onMarkerRemovedPacket);
		receive(MarkerRejectedS2CPacket.PACKET_ID,
			MarkerRejectedS2CPacket::readSafe, CommonClient.INSTANCE::onMarkerRejectedPacket);
		receive(MarkerWinnerChangedS2CPacket.PACKET_ID,
			MarkerWinnerChangedS2CPacket::readSafe, CommonClient.INSTANCE::onMarkerWinnerChangedPacket);

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
}
