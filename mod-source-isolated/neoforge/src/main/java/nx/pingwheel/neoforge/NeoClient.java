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
import nx.pingwheel.neoforge.integration.NeoForgeWorldAwareBlockModelOutlineAdapter;

import static nx.pingwheel.common.Global.LOGGER;

public class NeoClient {
	private static final String CREATE_ENTITY_ADAPTER =
		"nx.pingwheel.neoforge.integration.create.CreateEntityOutlineAdapter";
	private static final String CREATE_CONTRAPTION_RAYCAST_ADAPTER =
		"nx.pingwheel.neoforge.integration.create.CreateContraptionRaycastAdapter";
	private static final String CREATE_FLYWHEEL_ADAPTER =
		"nx.pingwheel.neoforge.integration.create.CreateFlywheelGeometryAdapter";
	private static final String CREATE_WATER_WHEEL_RESOLVER =
		"nx.pingwheel.neoforge.integration.create.CreateLargeWaterWheelPresentationResolver";
	private static final String CREATE_DOOR_RESOLVER =
		"nx.pingwheel.neoforge.integration.create.CreateDoorPresentationResolver";
	private static final String SIMULATED_DOCKING_CONNECTOR_RESOLVER =
		"nx.pingwheel.neoforge.integration.simulated.SimulatedDockingConnectorPresentationResolver";
	private static Boolean lastCreateDetected;
	private static Boolean lastFlywheelDetected;
	private static String lastEntityAdapterState;
	private static String lastContraptionRaycastAdapterState;
	private static String lastFlywheelAdapterState;
	private static String lastWaterWheelResolverState;
	private static String lastCreateDoorResolverState;
	private static String lastSimulatedResolverState;
	private static boolean entityAdapterResolved;
	private static boolean contraptionRaycastAdapterResolved;
	private static boolean flywheelAdapterResolved;

	public NeoClient(IEventBus modBus) {
		CommonClient.INSTANCE.onInit();
		NeoForgeWorldAwareBlockModelOutlineAdapter.register();
		loadCreateAdapters();
		loadSimulatedResolver();
		IPlatformClientEventService.INSTANCE.registerJoinServerEvent(() -> {
			loadCreateAdapters();
			loadSimulatedResolver();
		});
		IPlatformClientEventService.INSTANCE.registerLeaveServerEvent(NeoClient::closeCreateAdapters);

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
	 * Optional adapter classes are resolved only after their respective mod-id
	 * checks. Create entity rendering is independent of the Flywheel geometry
	 * adapter, so it is attempted whenever Create is present while the existing
	 * block geometry adapter still requires both Create and Flywheel.
	 */
	private static void loadCreateAdapters() {
		boolean createDetected = ModList.get().isLoaded("create");
		boolean flywheelDetected = ModList.get().isLoaded("flywheel");
		if (!Boolean.valueOf(createDetected).equals(lastCreateDetected)
			|| !Boolean.valueOf(flywheelDetected).equals(lastFlywheelDetected)) {
			lastCreateDetected = createDetected;
			lastFlywheelDetected = flywheelDetected;
			LOGGER.info("create/flywheel detection changed: createDetected={} flywheelDetected={}",
				createDetected, flywheelDetected);
		}

		if (createDetected) {
			registerOptionalAdapter(CREATE_ENTITY_ADAPTER, "create-entity", true);
			registerOptionalContraptionRaycastAdapter();
			registerOptionalResolver(CREATE_WATER_WHEEL_RESOLVER, "create-water-wheel-presentation");
			registerOptionalResolver(CREATE_DOOR_RESOLVER, "create-door-presentation");
		} else {
			logAdapterState("create-entity", "not-detected");
			logAdapterState("create-contraption-raycast", "not-detected");
			logResolverState("create-water-wheel-presentation", "not-detected");
			logResolverState("create-door-presentation", "not-detected");
		}

		if (createDetected && flywheelDetected) {
			registerOptionalAdapter(CREATE_FLYWHEEL_ADAPTER, "create-flywheel", false);
		} else {
			logAdapterState("create-flywheel", "not-detected");
		}
	}

	/**
	 * The contraption raycast owner intentionally has no Create symbols in its
	 * shell class, but remains reflectively loaded under the Create check so an
	 * absent optional mod never alters the ordinary client class-loading path.
	 */
	private static void registerOptionalContraptionRaycastAdapter() {
		try {
			LOGGER.debug("optional adapter reflection attempt: adapter=create-contraption-raycast class={}",
				CREATE_CONTRAPTION_RAYCAST_ADAPTER);
			Class<?> adapter = Class.forName(
				CREATE_CONTRAPTION_RAYCAST_ADAPTER, true, NeoClient.class.getClassLoader());
			contraptionRaycastAdapterResolved = true;
			adapter.getMethod("register").invoke(null);
			String state = String.valueOf(adapter.getMethod("registrationState").invoke(null));
			logAdapterState("create-contraption-raycast", "reflection-success; sourceHandleState=" + state);
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			logAdapterState("create-contraption-raycast", "reflection-failure; sourceHandleState=failed");
			LOGGER.warn(
				"optional adapter registration failed; adapter=create-contraption-raycast"
					+ "; class=" + CREATE_CONTRAPTION_RAYCAST_ADAPTER + "; sourceHandleState=failed",
				failure);
		}
	}

	private static void registerOptionalAdapter(String className, String adapterName, boolean entityAdapter) {
		try {
			LOGGER.debug("optional adapter reflection attempt: adapter={} class={}", adapterName, className);
			Class<?> adapter = Class.forName(className, true, NeoClient.class.getClassLoader());
			if (entityAdapter) {
				entityAdapterResolved = true;
			} else {
				flywheelAdapterResolved = true;
			}
			adapter.getMethod("register").invoke(null);
			String state = String.valueOf(adapter.getMethod("registrationState").invoke(null));
			logAdapterState(adapterName, "reflection-success; sourceHandleState=" + state);
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			logAdapterState(adapterName, "reflection-failure; sourceHandleState=failed");
			LOGGER.warn(
				"optional adapter registration failed; adapter=" + adapterName
					+ "; class=" + className + "; sourceHandleState=failed",
				failure);
		}
	}

	private static void loadSimulatedResolver() {
		if (ModList.get().isLoaded("simulated")) {
			registerOptionalResolver(
				SIMULATED_DOCKING_CONNECTOR_RESOLVER, "simulated-docking-connector-presentation");
		}
	}

	private static void registerOptionalResolver(String className, String resolverName) {
		try {
			LOGGER.debug("optional resolver reflection attempt: resolver={} class={}", resolverName, className);
			Class<?> resolver = Class.forName(className, true, NeoClient.class.getClassLoader());
			resolver.getMethod("register").invoke(null);
			String state = String.valueOf(resolver.getMethod("registrationState").invoke(null));
			logResolverState(resolverName, "reflection-success; registrationState=" + state);
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			logResolverState(resolverName, "reflection-failure; registrationState=failed");
			LOGGER.warn(
				"optional resolver registration failed; resolver=" + resolverName
					+ "; class=" + className + "; registrationState=failed",
				failure);
		}
	}

	private static void closeCreateAdapters() {
		boolean createDetected = ModList.get().isLoaded("create");
		boolean flywheelDetected = ModList.get().isLoaded("flywheel");

		if (createDetected || entityAdapterResolved) {
			closeOptionalAdapter(CREATE_ENTITY_ADAPTER, "create-entity", true);
		} else {
			logAdapterState("create-entity", "not-detected");
		}

		if (createDetected || contraptionRaycastAdapterResolved) {
			closeOptionalContraptionRaycastAdapter();
		} else {
			logAdapterState("create-contraption-raycast", "not-detected");
		}

		if ((createDetected && flywheelDetected) || flywheelAdapterResolved) {
			closeOptionalAdapter(CREATE_FLYWHEEL_ADAPTER, "create-flywheel", false);
		} else {
			logAdapterState("create-flywheel", "not-detected");
		}
	}

	private static void closeOptionalContraptionRaycastAdapter() {
		try {
			Class<?> adapter = Class.forName(
				CREATE_CONTRAPTION_RAYCAST_ADAPTER, false, NeoClient.class.getClassLoader());
			adapter.getMethod("close").invoke(null);
			logAdapterState("create-contraption-raycast", "closed; sourceHandleState=closed");
		} catch (ClassNotFoundException ignored) {
			logAdapterState("create-contraption-raycast", "not-loaded");
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			logAdapterState("create-contraption-raycast", "close-failure; sourceHandleState=close-failure");
			LOGGER.warn(
				"optional adapter teardown failed; adapter=create-contraption-raycast"
					+ "; class=" + CREATE_CONTRAPTION_RAYCAST_ADAPTER
					+ "; sourceHandleState=close-failure",
				failure);
		} finally {
			contraptionRaycastAdapterResolved = false;
		}
	}

	private static void closeOptionalAdapter(String className, String adapterName, boolean entityAdapter) {
		try {
			Class<?> adapter = Class.forName(className, false, NeoClient.class.getClassLoader());
			adapter.getMethod("close").invoke(null);
			logAdapterState(adapterName, "closed; sourceHandleState=closed");
		} catch (ClassNotFoundException ignored) {
			logAdapterState(adapterName, "not-loaded");
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			logAdapterState(adapterName, "close-failure; sourceHandleState=close-failure");
			LOGGER.warn(
				"optional adapter teardown failed; adapter=" + adapterName
					+ "; class=" + className + "; sourceHandleState=close-failure",
				failure);
		} finally {
			if (entityAdapter) {
				entityAdapterResolved = false;
			} else {
				flywheelAdapterResolved = false;
			}
		}
	}

	private static void logAdapterState(String adapterName, String state) {
		String previous = switch (adapterName) {
			case "create-entity" -> lastEntityAdapterState;
			case "create-contraption-raycast" -> lastContraptionRaycastAdapterState;
			default -> lastFlywheelAdapterState;
		};
		if (state.equals(previous)) {
			return;
		}
		switch (adapterName) {
			case "create-entity" -> lastEntityAdapterState = state;
			case "create-contraption-raycast" -> lastContraptionRaycastAdapterState = state;
			default -> lastFlywheelAdapterState = state;
		}
		LOGGER.info("optional source handle state transition: adapter={} state={} createDetected={} flywheelDetected={}",
			adapterName, state,
			lastCreateDetected == null ? false : lastCreateDetected,
			lastFlywheelDetected == null ? false : lastFlywheelDetected);
	}

	private static void logResolverState(String resolverName, String state) {
		String previous;
		switch (resolverName) {
			case "simulated-docking-connector-presentation" -> previous = lastSimulatedResolverState;
			case "create-door-presentation" -> previous = lastCreateDoorResolverState;
			default -> previous = lastWaterWheelResolverState;
		}
		if (state.equals(previous)) {
			return;
		}
		switch (resolverName) {
			case "simulated-docking-connector-presentation" -> lastSimulatedResolverState = state;
			case "create-door-presentation" -> lastCreateDoorResolverState = state;
			default -> lastWaterWheelResolverState = state;
		}
		LOGGER.info("optional presentation resolver state transition: resolver={} state={} createDetected={}",
			resolverName, state, lastCreateDetected == null ? false : lastCreateDetected);
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
