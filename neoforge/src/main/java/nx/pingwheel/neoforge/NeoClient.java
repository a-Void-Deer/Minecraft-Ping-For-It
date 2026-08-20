package nx.pingwheel.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.command.ClientCommandBuilder;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.resource.ResourceReloadListener;
import nx.pingwheel.common.screen.SettingsScreen;
import nx.pingwheel.common.platform.IPlatformClientEventService;

import static nx.pingwheel.common.Global.warnException;

public class NeoClient {
	private static final String CREATE_FLYWHEEL_ADAPTER =
		"nx.pingwheel.neoforge.integration.create.CreateFlywheelGeometryAdapter";

	public NeoClient(IEventBus modBus) {
		CommonClient.INSTANCE.onInit();
		loadCreateFlywheelAdapter();
		IPlatformClientEventService.INSTANCE.registerJoinServerEvent(NeoClient::loadCreateFlywheelAdapter);
		IPlatformClientEventService.INSTANCE.registerLeaveServerEvent(NeoClient::closeCreateFlywheelAdapter);

		NeoForge.EVENT_BUS.register(this);

		// packets are registered in Main class

		// resource reload
		modBus.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(new ResourceReloadListener()));

		// config screen
		ModLoadingContext
			.get()
			.registerExtensionPoint(
				IConfigScreenFactory.class,
				() -> (a, parent) -> new SettingsScreen(parent)
			);
	}

	/**
	 * The optional adapter class is not resolved until both optional mod ids are
	 * present. This keeps absent Create/Flywheel classes out of NeoForge's
	 * normal client class-loading path.
	 */
	private static void loadCreateFlywheelAdapter() {
		if (!ModList.get().isLoaded("create") || !ModList.get().isLoaded("flywheel")) {
			return;
		}

		try {
			Class<?> adapter = Class.forName(CREATE_FLYWHEEL_ADAPTER, true,
				NeoClient.class.getClassLoader());
			adapter.getMethod("register").invoke(null);
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			warnException("optional create flywheel adapter unavailable; category=load", failure);
		}
	}

	private static void closeCreateFlywheelAdapter() {
		if (!ModList.get().isLoaded("create") || !ModList.get().isLoaded("flywheel")) {
			return;
		}

		try {
			Class<?> adapter = Class.forName(CREATE_FLYWHEEL_ADAPTER, false,
				NeoClient.class.getClassLoader());
			adapter.getMethod("close").invoke(null);
		} catch (ClassNotFoundException ignored) {
			// The optional adapter was never loaded.
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			warnException("optional create flywheel adapter teardown failed; category=close", failure);
		}
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
