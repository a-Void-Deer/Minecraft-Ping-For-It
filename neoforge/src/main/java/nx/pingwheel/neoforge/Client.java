package nx.pingwheel.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import nx.pingwheel.common.commands.ClientCommandBuilder;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.ConfigHandler;
import nx.pingwheel.common.core.ClientCore;
import nx.pingwheel.common.helper.LanguageUtils;
import nx.pingwheel.common.networking.UpdateChannelC2SPacket;
import nx.pingwheel.common.resource.ResourceReloadListener;
import nx.pingwheel.common.screen.SettingsScreen;

import static nx.pingwheel.common.ClientGlobal.*;
import static nx.pingwheel.common.Global.MOD_ID;
import static nx.pingwheel.common.Global.NetHandler;

@OnlyIn(Dist.CLIENT)
public class Client {

	public Client(IEventBus modBus) {
		ConfigHandler = new ConfigHandler<>(ClientConfig.class, FMLPaths.CONFIGDIR.get().resolve(MOD_ID + ".json"));
		ConfigHandler.load();

		NeoForge.EVENT_BUS.register(this);
		modBus.addListener(this::onRegisterReloadListeners);
		modBus.addListener(this::onRegisterKeyBindings);

		ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class, () -> (a, parent) -> new SettingsScreen(parent));
	}

	private void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
		event.registerReloadListener(new ResourceReloadListener());
	}

	private void onRegisterKeyBindings(RegisterKeyMappingsEvent event) {
		event.register(KEY_BINDING_PING);
		event.register(KEY_BINDING_SETTINGS);
		event.register(KEY_BINDING_NAME_LABELS);
	}

	@SubscribeEvent
	public void onClientTick(ClientTickEvent.Pre event) {
		ClientCore.onTick();
	}

	@SubscribeEvent
	public void onClientConnectedToServer(ClientPlayerNetworkEvent.LoggingIn event) {
		NetHandler.sendToServer(new UpdateChannelC2SPacket(ConfigHandler.getConfig().getChannel()));
	}

	@SubscribeEvent
	public void onClientDisconnectedFromServer(ClientPlayerNetworkEvent.LoggingOut event) {
		ClientCore.onDisconnect();
	}

	@SubscribeEvent
	public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
		event.getDispatcher().register(ClientCommandBuilder.build((context, success, response) -> {
			if (success) {
				context.getSource().sendSuccess(() -> LanguageUtils.withModPrefix(response), false);
			} else {
				context.getSource().sendFailure(LanguageUtils.withModPrefix(response));
			}
		}));
	}
}
