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
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import org.jetbrains.annotations.Nullable;
import nx.pingwheel.common.command.ClientCommandBuilder;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.resource.ResourceReloadListener;
import nx.pingwheel.common.screen.SettingsScreen;
import nx.pingwheel.common.platform.IPlatformClientEventService;
import nx.pingwheel.neoforge.integration.NeoForgeWorldAwareBlockModelOutlineAdapter;
import java.util.function.Supplier;


public class NeoClient {
	private static final DiagnosticLogger LOG = Diagnostics.client();
	private static final DiagnosticChannel ADAPTER_REGISTRATION = DiagnosticChannel.interval("neoforge-adapter-registration", java.time.Duration.ofSeconds(10));
	private static final DiagnosticChannel RESOLVER_REGISTRATION = DiagnosticChannel.interval("neoforge-resolver-registration", java.time.Duration.ofSeconds(10));
	private static final DiagnosticChannel ADAPTER_CLOSE = DiagnosticChannel.interval("neoforge-adapter-close", java.time.Duration.ofSeconds(10));
	private static final String CREATE_ENTITY_ADAPTER =
		"nx.pingwheel.neoforge.integration.create.CreateEntityOutlineAdapter";
	private static final String CREATE_CONTRAPTION_RAYCAST_ADAPTER =
		"nx.pingwheel.neoforge.integration.create.CreateContraptionRaycastAdapter";
	private static final String AERONAUTICS_PROPELLER_RAYCAST_ADAPTER =
		"nx.pingwheel.neoforge.integration.create.AeronauticsPropellerRaycastAdapter";
	private static final String CREATE_FLYWHEEL_ADAPTER =
		"nx.pingwheel.neoforge.integration.create.CreateFlywheelGeometryAdapter";
	private static final String CREATE_WATER_WHEEL_RESOLVER =
		"nx.pingwheel.neoforge.integration.create.CreateLargeWaterWheelPresentationResolver";
	private static final String CREATE_DOOR_RESOLVER =
		"nx.pingwheel.neoforge.integration.create.CreateDoorPresentationResolver";
	private static final String CREATE_VAULT_RESOLVER =
		"nx.pingwheel.neoforge.integration.create.NeoForgeCreateVaultBlockPresentationResolver";
	private static final String CREATE_TANK_RESOLVER =
		"nx.pingwheel.neoforge.integration.create.NeoForgeCreateFluidTankBlockPresentationResolver";
	private static final String SIMULATED_DOCKING_CONNECTOR_RESOLVER =
		"nx.pingwheel.neoforge.integration.simulated.SimulatedDockingConnectorPresentationResolver";
	private static @Nullable Boolean lastCreateDetected;
	private static @Nullable Boolean lastFlywheelDetected;
	private static @Nullable String lastEntityAdapterState;
	private static @Nullable String lastContraptionRaycastAdapterState;
	private static @Nullable String lastAeronauticsPropellerRaycastAdapterState;
	private static @Nullable String lastFlywheelAdapterState;
	private static @Nullable String lastWaterWheelResolverState;
	private static @Nullable String lastCreateDoorResolverState;
	private static @Nullable String lastCreateVaultResolverState;
	private static @Nullable String lastCreateTankResolverState;
	private static @Nullable String lastSimulatedResolverState;
	private static boolean entityAdapterResolved;
	private static boolean contraptionRaycastAdapterResolved;
	private static boolean aeronauticsPropellerRaycastAdapterResolved;
	private static boolean flywheelAdapterResolved;

	public NeoClient(IEventBus modBus) {
		CommonClient.INSTANCE.onInit();
		NeoForgeWorldAwareBlockModelOutlineAdapter.register();
		loadCreateAdapters();
		loadSimulatedResolver();
		loadAeronauticsPropellerRaycastAdapter();
		IPlatformClientEventService.INSTANCE.registerJoinServerEvent(() -> {
			loadCreateAdapters();
			loadSimulatedResolver();
			loadAeronauticsPropellerRaycastAdapter();
		});
		IPlatformClientEventService.INSTANCE.registerLeaveServerEvent(NeoClient::closeOptionalAdapters);

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
			LOG.atNormal().info("create/flywheel detection changed: createDetected={} flywheelDetected={}",
				nx.pingwheel.common.diagnostics.SafeDiagnosticValue.bool(createDetected),
				nx.pingwheel.common.diagnostics.SafeDiagnosticValue.bool(flywheelDetected));
		}

		if (createDetected) {
			registerOptionalAdapter(CREATE_ENTITY_ADAPTER, "create-entity", true);
			registerOptionalContraptionRaycastAdapter();
			registerOptionalResolver(CREATE_WATER_WHEEL_RESOLVER, "create-water-wheel-presentation");
			registerOptionalResolver(CREATE_DOOR_RESOLVER, "create-door-presentation");
			if (nx.pingwheel.neoforge.integration.create.presentation.CreatePresentationAvailability.available(LOG)) {
				registerOptionalResolver(CREATE_VAULT_RESOLVER, "create-vault-presentation");
				registerOptionalResolver(CREATE_TANK_RESOLVER, "create-tank-presentation");
			} else {
				logResolverState("create-vault-presentation", "unsupported-version");
				logResolverState("create-tank-presentation", "unsupported-version");
			}
		} else {
			logAdapterState("create-entity", "not-detected");
			logAdapterState("create-contraption-raycast", "not-detected");
			logResolverState("create-water-wheel-presentation", "not-detected");
			logResolverState("create-door-presentation", "not-detected");
			logResolverState("create-vault-presentation", "not-detected");
			logResolverState("create-tank-presentation", "not-detected");
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
		Class<?> adapter;
		try {
			LOG.atDebug().debug(() -> "optional adapter reflection attempt: adapter=create-contraption-raycast class="
				+ CREATE_CONTRAPTION_RAYCAST_ADAPTER);
			adapter = Class.forName(
				CREATE_CONTRAPTION_RAYCAST_ADAPTER, true, NeoClient.class.getClassLoader());
			contraptionRaycastAdapterResolved = true;
			adapter.getMethod("register").invoke(null);
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			logAdapterState("create-contraption-raycast", "reflection-failure; sourceHandleState=failed");
			LOG.atNormal().rateLimited(ADAPTER_REGISTRATION).warn("optional-adapter-register", "adapter-registration-failed",
				failure, () -> "adapter=create-contraption-raycast; class=" + CREATE_CONTRAPTION_RAYCAST_ADAPTER);
			return;
		}
		logAdapterState("create-contraption-raycast", "reflection-success",
			() -> "reflection-success; sourceHandleState=" + registrationState(adapter));
	}

	/**
	 * The Aero owner is resolved only after the Aeronautics mod-id check. It is
	 * independent of the Create contraption route and does not require Flywheel.
	 */
	private static void loadAeronauticsPropellerRaycastAdapter() {
		if (ModList.get().isLoaded("aeronautics")) {
			registerOptionalAeronauticsPropellerRaycastAdapter();
		} else {
			logAeronauticsPropellerRaycastAdapterState("not-detected");
		}
	}

	private static void registerOptionalAeronauticsPropellerRaycastAdapter() {
		Class<?> adapter;
		try {
			LOG.atDebug().debug(() -> "optional adapter reflection attempt: adapter=aeronautics-propeller-raycast class="
				+ AERONAUTICS_PROPELLER_RAYCAST_ADAPTER);
			adapter = Class.forName(
				AERONAUTICS_PROPELLER_RAYCAST_ADAPTER, true, NeoClient.class.getClassLoader());
			aeronauticsPropellerRaycastAdapterResolved = true;
			adapter.getMethod("register").invoke(null);
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			logAeronauticsPropellerRaycastAdapterState("reflection-failure; sourceHandleState=failed");
			LOG.atNormal().rateLimited(ADAPTER_REGISTRATION).warn("optional-adapter-register", "adapter-registration-failed",
				failure, () -> "adapter=aeronautics-propeller-raycast; class=" + AERONAUTICS_PROPELLER_RAYCAST_ADAPTER);
			return;
		}
		logAeronauticsPropellerRaycastAdapterState("reflection-success",
			() -> "reflection-success; sourceHandleState=" + registrationState(adapter));
	}

	private static void registerOptionalAdapter(String className, String adapterName, boolean entityAdapter) {
		Class<?> adapter;
		try {
			LOG.atDebug().debug(() -> "optional adapter reflection attempt: adapter=" + adapterName + " class=" + className);
			adapter = Class.forName(className, true, NeoClient.class.getClassLoader());
			if (entityAdapter) {
				entityAdapterResolved = true;
			} else {
				flywheelAdapterResolved = true;
			}
			adapter.getMethod("register").invoke(null);
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			logAdapterState(adapterName, "reflection-failure; sourceHandleState=failed");
			LOG.atNormal().rateLimited(ADAPTER_REGISTRATION).warn("optional-adapter-register", "adapter-registration-failed",
				failure, () -> "adapter=" + adapterName + "; class=" + className);
			return;
		}
		logAdapterState(adapterName, "reflection-success",
			() -> "reflection-success; sourceHandleState=" + registrationState(adapter));
	}

	private static void loadSimulatedResolver() {
		if (ModList.get().isLoaded("simulated")) {
			registerOptionalResolver(
				SIMULATED_DOCKING_CONNECTOR_RESOLVER, "simulated-docking-connector-presentation");
		}
	}

	private static void registerOptionalResolver(String className, String resolverName) {
		Class<?> resolver;
		try {
			LOG.atDebug().debug(() -> "optional resolver reflection attempt: resolver=" + resolverName + " class=" + className);
			resolver = Class.forName(className, true, NeoClient.class.getClassLoader());
			resolver.getMethod("register").invoke(null);
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			logResolverState(resolverName, "reflection-failure; registrationState=failed");
			LOG.atNormal().rateLimited(RESOLVER_REGISTRATION).warn("optional-resolver-register", "resolver-registration-failed",
				failure, () -> "resolver=" + resolverName + "; class=" + className);
			return;
		}
		logResolverState(resolverName, "reflection-success",
			() -> "reflection-success; registrationState=" + registrationState(resolver));
	}

	/** Called only by admitted detail suppliers, never by registration or its failure boundary. */
	private static String registrationState(Class<?> owner) {
		try {
			return String.valueOf(owner.getMethod("registrationState").invoke(null));
		} catch (java.lang.reflect.InvocationTargetException failure) {
			@Nullable Throwable cause = failure.getCause();
			if (cause instanceof RuntimeException runtime) throw runtime;
			if (cause instanceof LinkageError linkage) throw linkage;
			if (cause instanceof Error error && !(cause instanceof AssertionError)) throw error;
			throw new IllegalStateException("registrationState probe failed", failure);
		} catch (ReflectiveOperationException | AssertionError failure) {
			throw new IllegalStateException("registrationState probe unavailable", failure);
		}
	}

	private static void closeOptionalAdapters() {
		closeCreateAdapters();
		closeAeronauticsPropellerRaycastAdapter();
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

	private static void closeAeronauticsPropellerRaycastAdapter() {
		if (ModList.get().isLoaded("aeronautics") || aeronauticsPropellerRaycastAdapterResolved) {
			closeOptionalAeronauticsPropellerRaycastAdapter();
		} else {
			logAeronauticsPropellerRaycastAdapterState("not-detected");
		}
	}

	private static void closeOptionalAeronauticsPropellerRaycastAdapter() {
		try {
			Class<?> adapter = Class.forName(
				AERONAUTICS_PROPELLER_RAYCAST_ADAPTER, false, NeoClient.class.getClassLoader());
			adapter.getMethod("close").invoke(null);
			logAeronauticsPropellerRaycastAdapterState("closed; sourceHandleState=closed");
		} catch (ClassNotFoundException ignored) {
			logAeronauticsPropellerRaycastAdapterState("not-loaded");
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			logAeronauticsPropellerRaycastAdapterState("close-failure; sourceHandleState=close-failure");
			LOG.atNormal().rateLimited(ADAPTER_CLOSE).warn("optional-adapter-close", "adapter-teardown-failed",
				failure, () -> "adapter=aeronautics-propeller-raycast; class=" + AERONAUTICS_PROPELLER_RAYCAST_ADAPTER);
		} finally {
			aeronauticsPropellerRaycastAdapterResolved = false;
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
			LOG.atNormal().rateLimited(ADAPTER_CLOSE).warn("optional-adapter-close", "adapter-teardown-failed",
				failure, () -> "adapter=create-contraption-raycast; class=" + CREATE_CONTRAPTION_RAYCAST_ADAPTER);
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
			LOG.atNormal().rateLimited(ADAPTER_CLOSE).warn("optional-adapter-close", "adapter-teardown-failed",
				failure, () -> "adapter=" + adapterName + "; class=" + className);
		} finally {
			if (entityAdapter) {
				entityAdapterResolved = false;
			} else {
				flywheelAdapterResolved = false;
			}
		}
	}

	private static void logAeronauticsPropellerRaycastAdapterState(String state) {
		logAeronauticsPropellerRaycastAdapterState(state, () -> state);
	}

	private static void logAeronauticsPropellerRaycastAdapterState(String state, Supplier<String> description) {
		if (state.equals(lastAeronauticsPropellerRaycastAdapterState)) {
			return;
		}
		lastAeronauticsPropellerRaycastAdapterState = state;
		LOG.atDetailed().site("optional-adapter-state").info(() ->
			"optional source handle state transition: adapter=aeronautics-propeller-raycast state=" + description.get()
				+ " aeronauticsDetected=" + ModList.get().isLoaded("aeronautics"));
	}

	private static void logAdapterState(String adapterName, String state) {
		logAdapterState(adapterName, state, () -> state);
	}

	private static void logAdapterState(String adapterName, String state, Supplier<String> description) {
		@Nullable String previous = switch (adapterName) {
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
		LOG.atDetailed().site("optional-adapter-state").info(() -> "optional source handle state transition: adapter=" + adapterName + " state=" + description.get()
			+ " createDetected=" + Boolean.TRUE.equals(lastCreateDetected) + " flywheelDetected=" + Boolean.TRUE.equals(lastFlywheelDetected));
	}

	private static void logResolverState(String resolverName, String state) {
		logResolverState(resolverName, state, () -> state);
	}

	private static void logResolverState(String resolverName, String state, Supplier<String> description) {
		@Nullable String previous;
		switch (resolverName) {
			case "simulated-docking-connector-presentation" -> previous = lastSimulatedResolverState;
			case "create-door-presentation" -> previous = lastCreateDoorResolverState;
			case "create-vault-presentation" -> previous = lastCreateVaultResolverState;
			case "create-tank-presentation" -> previous = lastCreateTankResolverState;
			default -> previous = lastWaterWheelResolverState;
		}
		if (state.equals(previous)) {
			return;
		}
		switch (resolverName) {
			case "simulated-docking-connector-presentation" -> lastSimulatedResolverState = state;
			case "create-door-presentation" -> lastCreateDoorResolverState = state;
			case "create-vault-presentation" -> lastCreateVaultResolverState = state;
			case "create-tank-presentation" -> lastCreateTankResolverState = state;
			default -> lastWaterWheelResolverState = state;
		}
		LOG.atDetailed().site("optional-resolver-state").info(() -> "optional presentation resolver state transition: resolver=" + resolverName + " state=" + description.get()
			+ " createDetected=" + Boolean.TRUE.equals(lastCreateDetected));
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
