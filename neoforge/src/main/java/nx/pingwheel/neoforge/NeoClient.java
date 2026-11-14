package nx.pingwheel.neoforge;

import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
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
import nx.pingwheel.common.platform.IPlatformClientEventService;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.resource.ResourceReloadListener;
import nx.pingwheel.common.screen.SettingsScreen;
import nx.pingwheel.neoforge.platform.PlatformClientContextServiceImpl;
import nx.pingwheel.neoforge.platform.PlatformClientEventServiceImpl;

import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.Global.MOD_ID;

public class NeoClient {
	private static final String CREATE_DOOR_RESOLVER =
		"nx.pingwheel.neoforge.integration.create.CreateDoorPresentationResolver";
	private static final String SIMULATED_DOCKING_CONNECTOR_RESOLVER =
		"nx.pingwheel.neoforge.integration.simulated.SimulatedDockingConnectorPresentationResolver";

	public NeoClient(IEventBus modBus) {
		PlatformClientEventServiceImpl.setModBus(modBus);
		PlatformClientContextServiceImpl.setModBus(modBus);

		CommonClient.INSTANCE.onInit();

		NeoForge.EVENT_BUS.register(this);
		modBus.addListener(this::onRegisterClientPayloads);
		modBus.addListener(this::onAddClientReloadListeners);

		ModLoadingContext.get().registerExtensionPoint(
			IConfigScreenFactory.class,
			() -> (container, parent) -> new SettingsScreen(parent)
		);

		loadCreateDoorResolver();
		loadSimulatedResolver();
		IPlatformClientEventService.INSTANCE.registerJoinServerEvent(() -> {
			loadCreateDoorResolver();
			loadSimulatedResolver();
		});
	}

	private void onRegisterClientPayloads(RegisterClientPayloadHandlersEvent event) {
		event.register(PingLocationS2CPacket.PACKET_TYPE,
			(payload, context) -> CommonClient.INSTANCE.onPingLocationPacket(payload));
		event.register(ServerConfigSnapshotS2CPacket.PACKET_TYPE,
			(payload, context) -> CommonClient.INSTANCE.onServerConfigSnapshotPacket(payload));
		event.register(MarkerCreatedS2CPacket.PACKET_TYPE,
			(payload, context) -> CommonClient.INSTANCE.onMarkerCreatedPacket(payload));
		event.register(MarkerRemovedS2CPacket.PACKET_TYPE,
			(payload, context) -> CommonClient.INSTANCE.onMarkerRemovedPacket(payload));
		event.register(MarkerRejectedS2CPacket.PACKET_TYPE,
			(payload, context) -> CommonClient.INSTANCE.onMarkerRejectedPacket(payload));
		event.register(MarkerWinnerChangedS2CPacket.PACKET_TYPE,
			(payload, context) -> CommonClient.INSTANCE.onMarkerWinnerChangedPacket(payload));
		event.register(RateLimitPolicyS2CPacket.PACKET_TYPE,
			(payload, context) -> CommonClient.INSTANCE.onRateLimitPolicyPacket(payload));
		event.register(SyncDurationPolicyS2CPacket.PACKET_TYPE,
			(payload, context) -> CommonClient.INSTANCE.onSyncDurationPolicyPacket(payload));
	}

	private void onAddClientReloadListeners(AddClientReloadListenersEvent event) {
		event.addListener(
			Identifier.fromNamespaceAndPath(MOD_ID, "reload-listener"),
			new ResourceReloadListener()
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

	private static void loadCreateDoorResolver() {
		if (ModList.get().isLoaded("create")) {
			registerOptionalResolver(CREATE_DOOR_RESOLVER, "create-door-presentation");
		}
	}

	private static void loadSimulatedResolver() {
		if (ModList.get().isLoaded("simulated")) {
			registerOptionalResolver(SIMULATED_DOCKING_CONNECTOR_RESOLVER, "simulated-docking-connector-presentation");
		}
	}

	private static void registerOptionalResolver(String className, String resolverName) {
		try {
			Class<?> resolver = Class.forName(className, true, NeoClient.class.getClassLoader());
			resolver.getMethod("register").invoke(null);
			String state = String.valueOf(resolver.getMethod("registrationState").invoke(null));
			LOGGER.info("optional presentation resolver registration: resolver={} state={}", resolverName, state);
		} catch (ReflectiveOperationException | LinkageError | AssertionError failure) {
			LOGGER.warn("optional presentation resolver registration failed: resolver={} class={}", resolverName, className, failure);
		}
	}
}
