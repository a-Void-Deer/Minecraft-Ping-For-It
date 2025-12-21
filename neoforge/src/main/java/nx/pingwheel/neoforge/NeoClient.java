package nx.pingwheel.neoforge;

import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.command.ClientCommandBuilder;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.resource.ResourceReloadListener;
import nx.pingwheel.common.screen.SettingsScreen;

import static nx.pingwheel.common.Global.MOD_ID;

public class NeoClient {

	public static final Identifier RELOAD_LISTENER_ID = Identifier.fromNamespaceAndPath(MOD_ID, "reload-listener");

	public NeoClient(IEventBus modBus) {
		CommonClient.INSTANCE.onInit();

		NeoForge.EVENT_BUS.register(this);

		// packets are registered in Main class

		// resource reload
		modBus.addListener((AddClientReloadListenersEvent event) -> event.addListener(RELOAD_LISTENER_ID, new ResourceReloadListener()));

		// config screen
		ModLoadingContext
			.get()
			.registerExtensionPoint(
				IConfigScreenFactory.class,
				() -> (a, parent) -> new SettingsScreen(parent)
			);
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
