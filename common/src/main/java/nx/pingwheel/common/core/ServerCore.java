package nx.pingwheel.common.core;

import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;
import nx.pingwheel.common.config.ServerConfigUpdateService;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.ExternalBlockServerProviders;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.integration.externalblock.ExternalBlockRefreshTriage;
import nx.pingwheel.common.integration.TeamContextHandler;
import nx.pingwheel.common.marker.MarkerCreationLogger;
import nx.pingwheel.common.marker.MarkerCreationService;
import nx.pingwheel.common.marker.MarkerIdSource;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.MarkerRemoval;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerRequestKind;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.MarkerWinnerChange;
import nx.pingwheel.common.marker.MinecraftAuthoritativeTargetValidator;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.name.MinecraftTargetNameResolver;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.network.MarkerCreateC2SPacket;
import nx.pingwheel.common.network.MarkerCreatedS2CPacket;
import nx.pingwheel.common.network.MarkerRejectedS2CPacket;
import nx.pingwheel.common.network.MarkerRemoveC2SPacket;
import nx.pingwheel.common.network.MarkerRemovedS2CPacket;
import nx.pingwheel.common.network.MarkerWinnerChangedS2CPacket;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.network.ServerConfigSnapshotS2CPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import nx.pingwheel.common.network.SyncDurationPolicyS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;
import nx.pingwheel.common.util.RateLimiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static nx.pingwheel.common.Global.CLIENT_COMMAND_ROOT;
import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.Global.MOD_PREFIX;
import static nx.pingwheel.common.Global.MOD_VERSION;

public class ServerCore {
	private ServerCore() {}

	private static final int TICKS_PER_SECOND = 20;
	private static final int EXTERNAL_REFRESH_INTERVAL_TICKS = 5;

	private static final ServerConfig SERVER_CONFIG = ServerConfig.HANDLER.getConfig();
	private static final HashMap<UUID, String> PLAYER_CHANNELS = new HashMap<>();
	private static final HashMap<UUID, RateLimiter> PLAYER_RATES = new HashMap<>();

	/**
	 * The shared, server-authoritative marker store. It is replaced on common
	 * server initialization ({@link #initMarkers()}) and again whenever the
	 * first server tick observes a different {@link MinecraftServer} instance
	 * (e.g. an integrated world restart), so stale markers, expiries, and
	 * winners can never carry across server instances. {@link #init()} never
	 * touches it, so a live config reload keeps active markers.
	 */
	private static ServerMarkerStore MARKER_STORE;

	/**
	 * Identity of the {@link MinecraftServer} instance the current
	 * {@link #MARKER_STORE} belongs to, or {@code null} after a reset.
	 * Written under the synchronized transition in {@link #ensureMarkerStore}
	 * and {@link #initMarkers()}, read via the volatile fast path in
	 * {@link #onServerTick(MinecraftServer)}.
	 */
	private static volatile MinecraftServer ACTIVE_SERVER;

	/**
	 * Applies the live rate-limit configuration. Also invoked on every config
	 * update, so it must never touch the marker store: markers survive a
	 * config reload within the same server.
	 */
	public static void init() {
		RateLimiter.setRates(SERVER_CONFIG.getMsToRegenerate(), SERVER_CONFIG.getRateLimit());
	}

	/**
	 * Resets the marker lifecycle store and clears the active-server identity,
	 * so the next observed server tick re-initializes the store for that
	 * server. Called from common server initialization only; no winners can
	 * remain to synchronize after a reset, so this is a plain drop.
	 */
	public static synchronized void initMarkers() {
		if (ACTIVE_SERVER != null && MARKER_STORE != null) {
			releaseExternalMarkers(ACTIVE_SERVER, MARKER_STORE.allMarkers());
			MARKER_STORE.clear();
			ExternalBlockServerProviders.close(ACTIVE_SERVER);
		}

		MARKER_STORE = new ServerMarkerStore(new MarkerIdSource());
		ACTIVE_SERVER = null;
	}

	/**
	 * Replaces the marker store and id source when {@code server} differs from
	 * the server instance the current store belongs to, then records the new
	 * active-server identity. Called from the server thread at the start of
	 * every server tick, before any marker request handler touches the store,
	 * and before disconnect cleanup; the volatile identity check in
	 * {@link #onServerTick(MinecraftServer)} short-circuits the common case,
	 * and the transition itself is synchronized so it can never interleave
	 * with {@link #initMarkers()} or a concurrent marker handler. Emits
	 * exactly one safe debug log per server instance.
	 */
	private static synchronized void ensureMarkerStore(MinecraftServer server) {
		if (ACTIVE_SERVER == server) {
			return;
		}

		if (ACTIVE_SERVER != null && MARKER_STORE != null) {
			releaseExternalMarkers(ACTIVE_SERVER, MARKER_STORE.allMarkers());
			MARKER_STORE.clear();
			ExternalBlockServerProviders.close(ACTIVE_SERVER);
		}

		MARKER_STORE = new ServerMarkerStore(new MarkerIdSource());
		ACTIVE_SERVER = server;

		LOGGER.debug(() -> "marker store initialized for server instance 0x%s".formatted(
			Integer.toHexString(System.identityHashCode(server))));
	}

	/**
	 * The shared marker store, created lazily as a defensive fallback so the
	 * marker handlers can never observe a null store even if a loader wired
	 * them before common server initialization.
	 */
	private static synchronized ServerMarkerStore markerStore() {
		if (MARKER_STORE == null) {
			MARKER_STORE = new ServerMarkerStore(new MarkerIdSource());
		}

		return MARKER_STORE;
	}

	/**
	 * Builds a {@link MarkerCreationService} for one request, sharing the
	 * static store. The authoritative validator and the authoritative target
	 * name resolver both depend on the current server instance and live
	 * config, so they (and with them the service) are instantiated per
	 * request while the store, id source, and built-in catalogs stay shared.
	 */
	private static MarkerCreationService markerService(MinecraftServer server) {
		final var externalProviders = ExternalBlockServerProviders.registry();
		final var nameResolver = new MinecraftTargetNameResolver(server);

		return new MarkerCreationService(
			markerStore(),
			DefaultTargetResolver.builtIn(TargetResolutionLogger.global()),
			PingTypeCatalog.builtIn(),
			new MinecraftAuthoritativeTargetValidator(
				server,
				SERVER_CONFIG.getPingDistance(),
				SERVER_CONFIG.isPlayerTrackingEnabled(),
				nameResolver,
				externalProviders),
			MarkerCreationLogger.global(),
			externalProviders,
			nameResolver);
	}

	public static void onChannelUpdate(ServerPlayer player, UpdateChannelC2SPacket packet) {
		if (packet.isCorrupt()) {
			LOGGER.warn(() -> "invalid channel update from %s (%s)".formatted(player.getGameProfile().getName(), player.getUUID()));
			player.displayClientMessage(Component.literal("§8" + MOD_PREFIX + "§cChannel couldn't be updated\n§fMake sure your version matches the server's version: §d" + MOD_VERSION), false);
			return;
		}

		updatePlayerChannel(player, packet.channel());
		IPlatformNetworkService.INSTANCE.sendToClient(
			new RateLimitPolicyS2CPacket(SERVER_CONFIG.getRateLimit(), SERVER_CONFIG.getMsToRegenerate()),
			player);
		IPlatformNetworkService.INSTANCE.sendToClient(
			new SyncDurationPolicyS2CPacket(SERVER_CONFIG.getSyncDuration()),
			player);
		LOGGER.debug("sent rate limit policy");
	}

	/**
	 * Broadcasts the current policy to every online player on the active server.
	 * A config reload that occurs before a server instance is active has no
	 * recipients and is intentionally a no-op.
	 */
	public static void broadcastRateLimitPolicy() {
		MinecraftServer server = ACTIVE_SERVER;

		if (server == null) {
			return;
		}

		var players = server.getPlayerList().getPlayers();
		var packet = new RateLimitPolicyS2CPacket(
			SERVER_CONFIG.getRateLimit(), SERVER_CONFIG.getMsToRegenerate());

		for (ServerPlayer player : players) {
			IPlatformNetworkService.INSTANCE.sendToClient(packet, player);
		}

		LOGGER.debug("broadcast rate limit policy");
	}

	/**
	 * Broadcasts the current marker-duration policy after an effective server
	 * configuration update.  Marker expiry timestamps are still frozen when a
	 * marker is created; this only updates the policy used by future markers and
	 * the client-side policy mirror.
	 */
	public static void broadcastSyncDurationPolicy() {
		MinecraftServer server = ACTIVE_SERVER;

		if (server == null) {
			return;
		}

		var packet = new SyncDurationPolicyS2CPacket(SERVER_CONFIG.getSyncDuration());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			IPlatformNetworkService.INSTANCE.sendToClient(packet, player);
		}

		LOGGER.debug("broadcast sync duration policy");
	}

	/**
	 * Sends a fresh snapshot for the authenticated sender. Permission is read
	 * here, on the server thread, for every request rather than being cached from
	 * the client.
	 */
	public static void onServerConfigRequest(
		MinecraftServer server,
		ServerPlayer player,
		ServerConfigRequestC2SPacket packet) {
		if (packet.isCorrupt()) {
			LOGGER.debug("server settings request rejected: invalid request id");
			return;
		}

		sendServerConfigSnapshot(player, player.hasPermissions(3), packet.requestId());
	}

	/**
	 * Applies only the fields selected by a valid dirty mask. The permission
	 * check is deliberately repeated for every update and no client-provided
	 * permission or display data is accepted.
	 */
	public static void onServerConfigUpdate(
		MinecraftServer server,
		ServerPlayer player,
		ServerConfigUpdateC2SPacket packet) {
		final boolean canEdit = player.hasPermissions(3);

		if (packet.isCorrupt()) {
			LOGGER.debug("server settings update rejected: invalid packet");
			return;
		}

		if (!canEdit) {
			LOGGER.debug("server settings update rejected: insufficient permission");
			return;
		}

		final var config = ServerConfig.HANDLER.getConfig();
		final var update = packet.update();
		final var plan = ServerConfigUpdateService.apply(
			true,
			ServerConfigSnapshot.from(config, true),
			update);
		if (!plan.applied()) {
			LOGGER.debug("server settings update rejected: invalid update");
			return;
		}

		if ((update.changedFields() & ServerConfigUpdate.DEFAULT_CHANNEL_MODE) != 0) {
			config.setDefaultChannelMode(update.defaultChannelMode());
		}
		if ((update.changedFields() & ServerConfigUpdate.PLAYER_TRACKING_ENABLED) != 0) {
			config.setPlayerTrackingEnabled(update.playerTrackingEnabled());
		}
		if ((update.changedFields() & ServerConfigUpdate.MS_TO_REGENERATE) != 0) {
			config.setMsToRegenerate(update.msToRegenerate());
		}
		if ((update.changedFields() & ServerConfigUpdate.RATE_LIMIT) != 0) {
			config.setRateLimit(update.rateLimit());
		}
		if ((update.changedFields() & ServerConfigUpdate.SYNC_DURATION) != 0) {
			config.setSyncDuration(update.syncDuration());
		}

		config.validate();
		ServerConfig.HANDLER.save();
	}

	private static void sendServerConfigSnapshot(ServerPlayer player, boolean canEdit, long requestId) {
		IPlatformNetworkService.INSTANCE.sendToClient(
			new ServerConfigSnapshotS2CPacket(
				requestId,
				ServerConfigSnapshot.from(ServerConfig.HANDLER.getConfig(), canEdit)),
			player);
	}

	public static void onPingLocation(MinecraftServer server, ServerPlayer player, PingLocationC2SPacket packet) {
		if (packet.isCorrupt()) {
			LOGGER.warn(() -> "invalid ping location from %s (%s)".formatted(player.getGameProfile().getName(), player.getUUID()));
			player.displayClientMessage(Component.literal("§8" + MOD_PREFIX + "§cUnable to send ping\n§fMake sure your version matches the server's version: §d" + MOD_VERSION), false);
			return;
		}

		PLAYER_RATES.putIfAbsent(player.getUUID(), new RateLimiter());
		final var rateLimiter = PLAYER_RATES.get(player.getUUID());

		if (SERVER_CONFIG.getRateLimit() > 0 && rateLimiter.checkExceeded()) {
			return;
		}
		
		final var channel = packet.channel();
		final var defaultChannelMode = SERVER_CONFIG.getDefaultChannelMode();

		if (channel.isEmpty() && defaultChannelMode == ChannelMode.DISABLED) {
			player.displayClientMessage(Component.literal("§8" + MOD_PREFIX + "§eMust be in a channel to ping location\n§fUse §a/" + CLIENT_COMMAND_ROOT + " channel§f to switch"), false);
			return;
		}

		if (channel.isEmpty() && defaultChannelMode == ChannelMode.TEAM_ONLY && !TeamContextHandler.hasTeam(player)) {
			player.displayClientMessage(Component.literal("§8" + MOD_PREFIX + "§eMust be in a team or channel to ping location\n§fUse §a/" + CLIENT_COMMAND_ROOT + " channel§f to switch"), false);
			return;
		}

		if (!channel.equals(PLAYER_CHANNELS.getOrDefault(player.getUUID(), ""))) {
			updatePlayerChannel(player, channel);
		}

		PingLocationS2CPacket packetOut;
		final var playerList = server.getPlayerList();

		if (!SERVER_CONFIG.isPlayerTrackingEnabled() && targetEntityIsPlayer(packet, playerList)) {
			packetOut = new PingLocationS2CPacket(packet.channel(), packet.pos(), null, packet.sequence(), packet.dimension(), player.getUUID());
		} else {
			packetOut = PingLocationS2CPacket.fromClientPacket(packet, player.getUUID());
		}

		for (ServerPlayer p : playerList.getPlayers()) {
			if (!channel.equals(PLAYER_CHANNELS.getOrDefault(p.getUUID(), ""))) {
				continue;
			}

			if (channel.isEmpty() && defaultChannelMode != ChannelMode.GLOBAL && !TeamContextHandler.inSameContext(player, p)) {
				continue;
			}

			IPlatformNetworkService.INSTANCE.sendToClient(packetOut, p);
		}
	}

	private static boolean targetEntityIsPlayer(PingLocationC2SPacket packet, PlayerList playerList) {
		final var playerUUID = packet.entity();

		if (playerUUID == null) {
			return false;
		}

		return playerList.getPlayer(playerUUID) != null;
	}

	/**
	 * Handles an authoritative marker creation request.
	 *
	 * <p>The packet carries no channel: the server-stored channel of the
	 * requester is authoritative. Corrupt requests, rate-limited requests, and
	 * requests blocked by the DISABLED/TEAM_ONLY channel modes are rejected
	 * with the corresponding reason; the recipient list is derived with the
	 * same channel/team filtering as the legacy location ping and snapshotted
	 * before creation, so later channel switches cannot change the audience.
	 * The marker lifetime uses the server tick arrival time and the configured
	 * server-authoritative duration.
	 */
	public static void onMarkerCreate(MinecraftServer server, ServerPlayer player, MarkerCreateC2SPacket packet) {
		ensureMarkerStore(server);

		final long requestId = packet.requestId() >= 0L ? packet.requestId() : 0L;

		if (packet.isCorrupt()) {
			LOGGER.debug(() -> "marker create rejected: requestId=%d reason=%s".formatted(requestId, MarkerRejectReason.INVALID_REQUEST));
			sendReject(player, requestId, MarkerRequestKind.CREATE, MarkerRejectReason.INVALID_REQUEST);
			return;
		}

		PLAYER_RATES.putIfAbsent(player.getUUID(), new RateLimiter());
		final var rateLimiter = PLAYER_RATES.get(player.getUUID());

		if (SERVER_CONFIG.getRateLimit() > 0 && rateLimiter.checkExceeded()) {
			LOGGER.debug(() -> "marker create rejected: requestId=%d reason=%s".formatted(requestId, MarkerRejectReason.RATE_LIMITED));
			sendReject(player, requestId, MarkerRequestKind.CREATE, MarkerRejectReason.RATE_LIMITED);
			return;
		}

		final var channel = PLAYER_CHANNELS.getOrDefault(player.getUUID(), "");
		final var defaultChannelMode = SERVER_CONFIG.getDefaultChannelMode();

		if (channel.isEmpty() && defaultChannelMode == ChannelMode.DISABLED) {
			LOGGER.debug(() -> "marker create rejected: requestId=%d reason=%s".formatted(requestId, MarkerRejectReason.CHANNEL_DISABLED));
			sendReject(player, requestId, MarkerRequestKind.CREATE, MarkerRejectReason.CHANNEL_DISABLED);
			return;
		}

		if (channel.isEmpty() && defaultChannelMode == ChannelMode.TEAM_ONLY && !TeamContextHandler.hasTeam(player)) {
			LOGGER.debug(() -> "marker create rejected: requestId=%d reason=%s".formatted(requestId, MarkerRejectReason.CHANNEL_DISABLED));
			sendReject(player, requestId, MarkerRequestKind.CREATE, MarkerRejectReason.CHANNEL_DISABLED);
			return;
		}

		final var playerList = server.getPlayerList();
		final var recipients = snapshotRecipients(playerList, player, channel, defaultChannelMode);

		final long arrivalTick = server.getTickCount();
		final long expiresAtTick = arrivalTick + SERVER_CONFIG.getSyncDuration() * (long) TICKS_PER_SECOND;

		final var outcome = markerService(server).create(
			player.serverLevel(),
			player.getUUID(), packet.target(), packet.pingTypeId(), arrivalTick, expiresAtTick, recipients);

		if (!outcome.isAccepted()) {
			final var reason = outcome.rejectReason().orElseThrow();

			LOGGER.debug(() -> "marker create rejected: requestId=%d reason=%s".formatted(requestId, reason));
			sendReject(player, requestId, MarkerRequestKind.CREATE, reason);
			return;
		}

		final var creation = outcome.creation().orElseThrow();
		final var marker = creation.marker();
		final var targetName = outcome.targetName().orElseThrow();

		LOGGER.debug(() -> "marker create accepted: requestId=%d markerId=%d kind=%s targetType=%s pingType=%s recipients=%d".formatted(
			requestId,
			marker.id().value(),
			marker.target().kind(),
			marker.targetType().id(),
			marker.pingType().id(),
			marker.recipients().size()));

		// Fixed ordering for every accepted marker: first the created packet
		// (marker, authoritative name, and authoritative owner profile name),
		// then the winner changes. Chat is emitted client-side from the packet,
		// so each recipient uses its selected language exactly once.
		sendMarkerCreated(playerList, marker, targetName, player.getGameProfile().getName());
		sendWinnerChanges(playerList, creation.winnerChanges(), null);
	}

	/**
	 * Handles a marker removal request. Ownership is never trusted: the store
	 * performs the ownership check, and only an owned, active marker is
	 * removed (reason {@link MarkerRemovalReason#CANCELLED}). Removal is not
	 * rate-limited, matching the established legacy convention.
	 */
	public static void onMarkerRemove(MinecraftServer server, ServerPlayer player, MarkerRemoveC2SPacket packet) {
		ensureMarkerStore(server);

		if (packet.isCorrupt()) {
			final long requestId = packet.markerId() != null ? packet.markerId().value() : 0L;

			LOGGER.debug(() -> "marker remove rejected: requestId=%d reason=%s".formatted(requestId, MarkerRejectReason.INVALID_REQUEST));
			sendReject(player, requestId, MarkerRequestKind.REMOVE, MarkerRejectReason.INVALID_REQUEST);
			return;
		}

		final var markerId = packet.markerId();
		final var result = markerStore().removeOwned(player.getUUID(), markerId);

		switch (result.status()) {
			case REMOVED -> {
				final var removal = result.removal().orElseThrow();
				releaseExternal(server, removal.marker());

				LOGGER.debug(() -> "marker remove accepted: markerId=%d reason=%s".formatted(markerId.value(), removal.reason()));
				sendMarkerRemoved(server.getPlayerList(), removal, null);
				sendWinnerChanges(server.getPlayerList(), result.winnerChanges(), null);
			}
			case NOT_FOUND -> {
				LOGGER.debug(() -> "marker remove rejected: requestId=%d reason=%s".formatted(markerId.value(), MarkerRejectReason.NOT_FOUND));
				sendReject(player, markerId.value(), MarkerRequestKind.REMOVE, MarkerRejectReason.NOT_FOUND);
			}
			case NOT_OWNER -> {
				LOGGER.debug(() -> "marker remove rejected: requestId=%d reason=%s".formatted(markerId.value(), MarkerRejectReason.NOT_OWNER));
				sendReject(player, markerId.value(), MarkerRequestKind.REMOVE, MarkerRejectReason.NOT_OWNER);
			}
		}
	}

	/**
	 * Expires every marker whose lifetime elapsed at the current server tick
	 * and synchronizes the removals and resulting winner changes to the still
	 * online recipients. Replaces the marker store first when a new server
	 * instance is observed, so no state carries across world restarts.
	 */
	public static void onServerTick(MinecraftServer server) {
		if (ACTIVE_SERVER != server) {
			ensureMarkerStore(server);
		}

		final var batch = markerStore().expire(server.getTickCount());

		final var playerList = server.getPlayerList();

		if (!batch.removals().isEmpty()) {
			LOGGER.debug(() -> "marker expiry: removed=%d winnerChanges=%d".formatted(
				batch.removals().size(), batch.winnerChanges().size()));

			for (final var removal : batch.removals()) {
				releaseExternal(server, removal.marker());
				sendMarkerRemoved(playerList, removal, null);
			}

			sendWinnerChanges(playerList, batch.winnerChanges(), null);
		}

		if (server.getTickCount() % EXTERNAL_REFRESH_INTERVAL_TICKS == 0) {
			refreshExternalMarkers(server);
		}
	}

	/**
	 * Cleans up the disconnecting player's markers before the legacy channel
	 * and rate-limit cleanup: owned markers are removed with reason
	 * {@link MarkerRemovalReason#OWNER_DISCONNECTED} and synchronized to the
	 * still online recipients, then the disconnected player is forgotten as a
	 * recipient of other players' markers. Audience-empty drops are debug
	 * logged. No packets are ever sent to the disconnected session.
	 */
	public static void onPlayerDisconnect(ServerPlayer player) {
		final var server = player.serverLevel().getServer();
		ensureMarkerStore(server);

		final var store = markerStore();
		final var removed = store.removeOwnedBy(player.getUUID(), MarkerRemovalReason.OWNER_DISCONNECTED);
		final var playerList = server.getPlayerList();

		for (final var removal : removed.removals()) {
			releaseExternal(server, removal.marker());
			sendMarkerRemoved(playerList, removal, player.getUUID());
		}

		sendWinnerChanges(playerList, removed.winnerChanges(), player.getUUID());

		final var dropped = store.forgetRecipient(player.getUUID());

		for (final var droppedMarker : dropped) {
			releaseExternal(server, droppedMarker);
		}

		LOGGER.debug(() -> "marker disconnect cleanup: removed=%d audienceEmptyDrops=%d".formatted(
			removed.removals().size(), dropped.size()));

		PLAYER_CHANNELS.remove(player.getUUID());
		PLAYER_RATES.remove(player.getUUID());
	}

	/**
	 * Refreshes committed external markers at a fixed, modest cadence. Provider
	 * temporary-unavailable results retain the marker; an invalid live block is
	 * removed through the ordinary authoritative removal path.
	 */
	private static void refreshExternalMarkers(MinecraftServer server) {
		final var store = markerStore();
		final var registry = ExternalBlockServerProviders.registry();
		final var playerList = server.getPlayerList();
		final var nameResolver = new MinecraftTargetNameResolver(server);

		for (final ServerMarker marker : store.allMarkers()) {
			if (!(marker.target() instanceof Target.ExternalBlockTarget external)) {
				continue;
			}

			ServerLevel level = levelFor(server, external.dimensionId());
			if (level == null) {
				continue;
			}

			ExternalBlockServerProvider.RefreshResult result = registry.refresh(level, external);

			boolean sameTargetKey = false;
			if (result instanceof ExternalBlockServerProvider.RefreshResult.Available available) {
				try {
					sameTargetKey = marker.targetKey().equals(nx.pingwheel.common.marker.TargetKey.from(available.target()));
				} catch (RuntimeException ignored) {
					sameTargetKey = false;
				}
			}

			ExternalBlockRefreshTriage.Action action = ExternalBlockRefreshTriage.action(result, sameTargetKey);

			if (action == ExternalBlockRefreshTriage.Action.RETAIN) {
				continue;
			}

			if (action == ExternalBlockRefreshTriage.Action.UPDATE
				&& result instanceof ExternalBlockServerProvider.RefreshResult.Available available) {
				Target.ExternalBlockTarget refreshedTarget = available.target();
				Target.ExternalBlockTarget previousTarget = (Target.ExternalBlockTarget) marker.target();
				boolean changed = !previousTarget.providerLocator().equals(refreshedTarget.providerLocator())
					|| !marker.anchor().equals(available.anchor());

				if (!changed) {
					continue;
				}

				final ServerMarker updated = store.updateExternalTarget(
					marker.id(), refreshedTarget, available.anchor()).orElse(null);

				if (updated == null) {
					continue;
				}

				ServerPlayer owner = playerList.getPlayer(updated.owner());
				if (owner != null) {
					TargetNameJson targetName = nameResolver.resolveName(updated.owner(), updated.target());
					sendMarkerCreated(playerList, updated, targetName, owner.getGameProfile().getName());
				}

				continue;
			}

			final var removalResult = store.removeByServer(marker.id(), MarkerRemovalReason.TARGET_INVALID);
			if (removalResult.status() == nx.pingwheel.common.marker.MarkerRemovalResult.Status.REMOVED) {
				final var removal = removalResult.removal().orElseThrow();
				releaseExternal(server, removal.marker());
				sendMarkerRemoved(playerList, removal, null);
				sendWinnerChanges(playerList, removalResult.winnerChanges(), null);
			}
		}
	}

	private static ServerLevel levelFor(MinecraftServer server, String dimensionId) {
		ResourceLocation location = ResourceLocation.tryParse(dimensionId);
		if (location == null) {
			return null;
		}

		return server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
	}

	private static void releaseExternal(MinecraftServer server, ServerMarker marker) {
		if (marker.target() instanceof Target.ExternalBlockTarget external) {
			ExternalBlockServerProviders.registry().release(
				server, external, Long.toString(marker.id().value()));
		}
	}

	private static void releaseExternalMarkers(MinecraftServer server, List<ServerMarker> markers) {
		for (ServerMarker marker : markers) {
			releaseExternal(server, marker);
		}
	}

	/**
	 * Snapshots the recipient list for a marker creation using the exact
	 * channel/team filtering of the legacy location ping, plus the creator
	 * (who always satisfies the filtering they just passed). The returned list
	 * is immutable, so channel switches after creation cannot change the
	 * marker's audience.
	 */
	private static List<UUID> snapshotRecipients(
		PlayerList playerList, ServerPlayer creator, String channel, ChannelMode defaultChannelMode
	) {
		final var recipients = new ArrayList<UUID>();
		final var creatorId = creator.getUUID();

		recipients.add(creatorId);

		for (ServerPlayer p : playerList.getPlayers()) {
			if (!channel.equals(PLAYER_CHANNELS.getOrDefault(p.getUUID(), ""))) {
				continue;
			}

			if (channel.isEmpty() && defaultChannelMode != ChannelMode.GLOBAL && !TeamContextHandler.inSameContext(creator, p)) {
				continue;
			}

			recipients.add(p.getUUID());
		}

		return recipients.stream().distinct().toList();
	}

	/**
	 * Sends a creation packet carrying {@code marker}'s snapshot, the
	 * validator's authoritative target name, and the creator's authoritative
	 * profile name to every recipient of {@code marker} that is currently
	 * online. Recipients that logged out since the marker was created are
	 * skipped; the stored snapshot remains authoritative.
	 */
	private static void sendMarkerCreated(
		PlayerList playerList, ServerMarker marker, TargetNameJson targetName, String ownerName
	) {
		final var packet = new MarkerCreatedS2CPacket(MarkerSnapshot.from(marker), targetName, ownerName);

		if (packet.isCorrupt()) {
			LOGGER.debug(() -> "marker created packet skipped: markerId=%d reason=invalid_authoritative_fields"
				.formatted(marker.id().value()));
			return;
		}

		for (final var recipientId : marker.recipients()) {
			final var recipient = playerList.getPlayer(recipientId);

			if (recipient != null) {
				IPlatformNetworkService.INSTANCE.sendToClient(packet, recipient);
			}
		}
	}

	/**
	 * Sends a removal packet for {@code removal.marker()} to every online
	 * recipient, skipping the optional {@code excluded} session (used during
	 * disconnect cleanup).
	 */
	private static void sendMarkerRemoved(PlayerList playerList, MarkerRemoval removal, UUID excluded) {
		final var packet = new MarkerRemovedS2CPacket(removal.marker().id(), removal.reason());

		for (final var recipientId : removal.marker().recipients()) {
			if (excluded != null && excluded.equals(recipientId)) {
				continue;
			}

			final var recipient = playerList.getPlayer(recipientId);

			if (recipient != null) {
				IPlatformNetworkService.INSTANCE.sendToClient(packet, recipient);
			}
		}
	}

	/**
	 * Sends one winner-change packet per change to the affected online
	 * recipient, skipping the optional {@code excluded} session.
	 */
	private static void sendWinnerChanges(PlayerList playerList, List<MarkerWinnerChange> changes, UUID excluded) {
		for (final var change : changes) {
			if (excluded != null && excluded.equals(change.recipientId())) {
				continue;
			}

			final var recipient = playerList.getPlayer(change.recipientId());

			if (recipient != null) {
				IPlatformNetworkService.INSTANCE.sendToClient(
					new MarkerWinnerChangedS2CPacket(change.targetKey(), change.currentWinner()), recipient);
			}
		}
	}

	/**
	 * Sends a rejection for one request to its creator. The creator just sent
	 * the request, so they are online by definition.
	 */
	private static void sendReject(
		ServerPlayer player, long requestId, MarkerRequestKind requestKind, MarkerRejectReason reason
	) {
		IPlatformNetworkService.INSTANCE.sendToClient(
			new MarkerRejectedS2CPacket(requestId, requestKind, reason), player);
	}

	private static void updatePlayerChannel(ServerPlayer player, String channel) {
		if (channel.isEmpty()) {
			PLAYER_CHANNELS.remove(player.getUUID());
			LOGGER.info(() -> "Channel update: %s -> default".formatted(player.getGameProfile().getName()));
		} else {
			PLAYER_CHANNELS.put(player.getUUID(), channel);
			LOGGER.info(() -> "Channel update: %s -> \"%s\"".formatted(player.getGameProfile().getName(), channel));
		}
	}
}
