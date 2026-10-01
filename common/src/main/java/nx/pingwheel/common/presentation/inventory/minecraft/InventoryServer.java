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
import nx.pingwheel.common.presentation.inventory.InventoryPreviewServer;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

/** Minecraft-facing inventory ingress. All world handles stay on the active server thread. */
public final class InventoryServer {

	private static MinecraftServer active;
	private static InventoryPreviewServer previews;
	private static long epochSeed = 1;

	private InventoryServer() {}

	public static void activate(MinecraftServer server) {
		if (active == server) return;
		reset();
		active = server;
		epochSeed = Math.addExact(epochSeed, 2048);
		previews = new InventoryPreviewServer(new Host(server), epochSeed);
	}

	public static void reset() {
		if (previews != null) previews.reset();
		previews = null;
		active = null;
	}

	public static void handle(MinecraftServer server, ServerPlayer player, InventoryC2SPacket packet) {
		if (!server.isSameThread() || player.serverLevel().getServer() != server) return;
		activate(server);
		previews.handle(player.getUUID(), packet, server.getTickCount(), ServerConfig.HANDLER.getConfig().getInventory());
	}

	public static void tick(MinecraftServer server) {
		if (!server.isSameThread()) return;
		activate(server);
		previews.tick(server.getTickCount(), ServerConfig.HANDLER.getConfig().getInventory());
	}

	public static void disconnect(UUID player) {
		if (previews != null) previews.disconnect(player);
	}

	private record Host(MinecraftServer server) implements InventoryPreviewServer.Host {
		@Override
		public Optional<InventoryPreviewServer.Resolved> resolve(UUID playerId, Target requested) {
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
			return VanillaInventorySource.resolve(server, player, validated.normalizedTarget()).map(source -> {
				// Lock authorization depends on the player's held key. Until a provider exposes a public-view
				// descriptor, only the same player's compatible requests share this observation handle.
				SourceKey key = source.key();
				SourceKey scoped = new SourceKey(key.providerId(), key.kind(), key.stableId(), key.readScope() + "/" + playerId);
				return new InventoryPreviewServer.Resolved(scoped, source.source(), item -> {
					InventoryItemCodec.Display display = source.catalog().get(item);
					return display == null ? null : new InventoryPreviewServer.Display(display.itemId(), display.label(),
						display.displayJson(), display.componentsStripped());
				});
			});
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
