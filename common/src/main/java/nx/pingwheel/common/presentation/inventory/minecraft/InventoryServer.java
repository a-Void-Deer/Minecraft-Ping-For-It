package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Optional;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.ExternalBlockServerProviders;
import nx.pingwheel.common.marker.MinecraftAuthoritativeTargetValidator;
import nx.pingwheel.common.name.MinecraftTargetNameResolver;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.presentation.inventory.InventoryPresentation;
import nx.pingwheel.common.presentation.inventory.InventoryBackend;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.PresentationPropertyPingTypes;
import nx.pingwheel.common.presentation.minecraft.PresentationServer;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import nx.pingwheel.common.core.ServerCore;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

/** Minecraft-facing inventory ingress. All world handles stay on the active server thread. */
public final class InventoryServer {

	private static MinecraftServer active;
	private static InventoryBackend backend;
	private static long epochSeed = 1;

	private InventoryServer() {}

	public static void activate(MinecraftServer server) {
		if (active == server) return;
		reset();
		active = server;
		epochSeed = Math.addExact(epochSeed, 2048);
		var runtime = new InventoryRuntime(input -> InventoryMinecraftSources.resolve(server, input), ServerConfig.HANDLER.getConfig().getInventory().pendingMemoryBytes());
		backend = new InventoryBackend(new Host(server), runtime, epochSeed);
	}

	public static void reset() {
		if (backend != null) backend.close();
		backend = null;
		active = null;
	}

	public static void handle(MinecraftServer server, ServerPlayer player, InventoryC2SPacket packet) {
		if (!server.isSameThread() || player.serverLevel().getServer() != server) return;
		activate(server);
		backend.handle(player.getUUID(), packet, server.getTickCount(), ServerConfig.HANDLER.getConfig().getInventory());
	}

	public static void tick(MinecraftServer server) {
		if (!server.isSameThread()) return;
		activate(server);
		backend.tick(server.getTickCount(), ServerConfig.HANDLER.getConfig().getInventory());
	}

	public static void disconnect(UUID player) {
		if (backend != null) backend.disconnect(player);
	}
	public static void remove(MarkerId marker) { if (backend != null) backend.remove(marker); }

	private record Host(MinecraftServer server) implements InventoryBackend.Host {
		@Override public Optional<InventoryBackend.Policy> policy(UUID player) {
			return PresentationServer.inventoryPolicy(player).map(p -> new InventoryBackend.Policy(p.epoch(), p.view(), p.types()));
		}
		@Override public boolean authorized(SyncPublisher.Context context) { return policy(context.recipient()).map(p -> (p.epoch() + "/" + p.view()).equals(context.sessionView())).orElse(false); }
		@Override public boolean knows(UUID player, MarkerId marker) { return PresentationServer.inventoryKnows(player, marker); }
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			return ServerCore.createInventory(server, server.getPlayerList().getPlayer(player), frozen, admission);
		}
		@Override public boolean annotationAllowed(InventorySourceInput input, String pingType) {
			var dimension = net.minecraft.resources.ResourceLocation.tryParse(input.target().dimensionId());
			var level = dimension == null ? null : server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension));
			var pos = new net.minecraft.core.BlockPos(input.target().x(), input.target().y(), input.target().z());
			if (level == null || !level.isLoaded(pos)) return false;
			var state = level.getBlockState(pos);
			String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			return id.equals(input.target().blockRegistryId()) && PresentationPropertyPingTypes.builtIn().allows(pingType, id,
				net.minecraft.core.registries.BuiltInRegistries.BLOCK.wrapAsHolder(state.getBlock()).tags().map(tag -> tag.location().toString()).collect(java.util.stream.Collectors.toSet()));
		}
		@Override
		public Optional<InventoryBackend.Opened> open(UUID playerId, Target requested) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			// Entity/private inventory and external-provider contexts require their separate owning contracts.
			if (player == null || !(requested instanceof Target.BlockTarget)) return Optional.empty();
			ServerConfig config = ServerConfig.HANDLER.getConfig();
			var validation = new MinecraftAuthoritativeTargetValidator(server, config.getPingDistance(),
				config.isPlayerTrackingEnabled(), new MinecraftTargetNameResolver(server), ExternalBlockServerProviders.registry())
				.validate(playerId, requested);
			if (!validation.isAccepted()) return Optional.empty();
			var validated = validation.validatedTarget().orElseThrow();
			var resolved = DefaultTargetResolver.builtIn(TargetResolutionLogger.global())
				.resolve(validated.normalizedTarget(), validated.matchContext());
			int level = 0;
			for (int candidate = 1; candidate <= 4; candidate++) if (player.hasPermissions(candidate)) level = candidate;
			if (!InventoryPresentation.allowed(config.getPresentation(), playerId, level, resolved.targetType().id())
				.contains(InventoryPresentation.ITEMS)) return Optional.empty();
			return resolved.target() instanceof Target.BlockTarget block ? Optional.of(new InventoryBackend.Opened(block,
				resolved.targetType().id(), resolved.targetType().defaultPingType().id())) : Optional.empty();
		}

		@Override
		public int encodedBytes(InventoryS2CPacket packet) {
			FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(256, InventoryS2CPacket.MAX_FRAME_BYTES));
			try { packet.write(buffer); return buffer.readableBytes(); }
			finally { buffer.release(); }
		}

		@Override
		public void send(UUID playerId, InventoryS2CPacket packet) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) throw new IllegalStateException("inventory recipient disconnected");
			IPlatformNetworkService.INSTANCE.sendToClient(packet, player);
		}
	}
}
