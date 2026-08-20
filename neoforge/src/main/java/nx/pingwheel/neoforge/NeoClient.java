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

import static nx.pingwheel.common.Global.LOGGER;

public class NeoClient {
	private static final String CREATE_FLYWHEEL_ADAPTER =
		"nx.pingwheel.neoforge.integration.create.CreateFlywheelGeometryAdapter";
	private static Boolean lastCreateDetected;
	private static Boolean lastFlywheelDetected;
	private static String lastAdapterState;

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
		boolean createDetected = ModList.get().isLoaded("create");
		boolean flywheelDetected = ModList.get().isLoaded("flywheel");
		if (!Boolean.valueOf(createDetected).equals(lastCreateDetected)
			|| !Boolean.valueOf(flywheelDetected).equals(lastFlywheelDetected)) {
			lastCreateDetected = createDetected;
			lastFlywheelDetected = flywheelDetected;
			LOGGER.info("create/flywheel detection changed: createDetected={} flywheelDetected={}",
				createDetected, flywheelDetected);
		}

		if (!createDetected || !flywheelDetected) {
			logAdapterState("not-detected");
			return;
		}

		try {
			LOGGER.debug("optional create/flywheel adapter reflection attempt: class={}",
				CREATE_FLYWHEEL_ADAPTER);
			Class<?> adapter = Class.forName(CREATE_FLYWHEEL_ADAPTER, true,
				NeoClient.class.getClassLoader());
			adapter.getMethod("register").invoke(null);
			String state = String.valueOf(adapter.getMethod("registrationState").invoke(null));
			logAdapterState("reflection-success; sourceHandleState=" + state);
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			logAdapterState("reflection-failure");
			LOGGER.warn(
				"optional create/flywheel adapter registration failed; createDetected={} flywheelDetected="
					+ flywheelDetected + "; sourceHandleState=failed",
				failure);
		}
	}

	private static void closeCreateFlywheelAdapter() {
		boolean createDetected = ModList.get().isLoaded("create");
		boolean flywheelDetected = ModList.get().isLoaded("flywheel");
		if (!createDetected || !flywheelDetected) {
			logAdapterState("not-detected");
			return;
		}

		try {
			Class<?> adapter = Class.forName(CREATE_FLYWHEEL_ADAPTER, false,
				NeoClient.class.getClassLoader());
			adapter.getMethod("close").invoke(null);
			logAdapterState("closed");
		} catch (ClassNotFoundException ignored) {
			// The optional adapter was never loaded.
			logAdapterState("not-loaded");
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			logAdapterState("close-failure");
			LOGGER.warn("optional create/flywheel adapter teardown failed; sourceHandleState=close-failure", failure);
		}
	}

	private static void logAdapterState(String state) {
		if (state.equals(lastAdapterState)) {
			return;
		}
		lastAdapterState = state;
		LOGGER.info("create/flywheel source handle state: {}", state);
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
