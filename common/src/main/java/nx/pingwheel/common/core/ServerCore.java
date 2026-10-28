package nx.pingwheel.common.core;

import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.ServerConfigUpdate;
import nx.pingwheel.common.config.ServerConfigUpdateService;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.MarkerId;
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
import nx.pingwheel.common.marker.MarkerWinnerChange;
import nx.pingwheel.common.marker.MinecraftAuthoritativeTargetValidator;
import nx.pingwheel.common.marker.MinecraftTargetGroupingResolver;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.name.MinecraftTargetNameResolver;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.ServerPresentationPolicyC2SPacket;
import nx.pingwheel.common.network.ServerPresentationPolicyS2CPacket;
import nx.pingwheel.common.presentation.PresentationPropertyIntent;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService;
import nx.pingwheel.common.presentation.minecraft.BudgetDelayMonitoring;
import nx.pingwheel.common.presentation.minecraft.PresentationServer;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryServer;
import nx.pingwheel.common.presentation.inventory.InventoryBackend;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.network.ServerConfigSnapshotS2CPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;
import nx.pingwheel.common.util.RateLimiter;
import nx.pingwheel.common.util.RateLimitedDiagnostics;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
	 * Monotonic in-memory revision of the presentation policy rule view. It
	 * advances once per applied mutation and resets with the server instance, so
	 * a client can order rule-view snapshots within one connection.
	 */
	private static final AtomicLong PRESENTATION_POLICY_REVISION = new AtomicLong();

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
		BudgetDelayMonitoring.reset();
		PresentationServer.reset();
		InventoryServer.reset();
		if (ACTIVE_SERVER != null && MARKER_STORE != null) {
			releaseExternalMarkers(ACTIVE_SERVER, MARKER_STORE.allMarkers());
			MARKER_STORE.clear();
			ExternalBlockServerProviders.close(ACTIVE_SERVER);
		}

		MARKER_STORE = new ServerMarkerStore(new MarkerIdSource());
		ACTIVE_SERVER = null;
		PRESENTATION_POLICY_REVISION.set(0L);
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
			PRESENTATION_POLICY_REVISION.set(0L);
		}

		MARKER_STORE = new ServerMarkerStore(new MarkerIdSource());
		ACTIVE_SERVER = server;
		BudgetDelayMonitoring.sink(server);
		PresentationServer.activate(server);
		InventoryServer.activate(server);

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
			nameResolver,
			new MinecraftTargetGroupingResolver(server));
	}

	public static void onChannelUpdate(ServerPlayer player, UpdateChannelC2SPacket packet) {
		if (packet.isCorrupt()) {
			LOGGER.warn(() -> "invalid channel update from %s (%s)".formatted(player.getGameProfile().getName(), player.getUUID()));
			player.displayClientMessage(Component.literal("§8" + MOD_PREFIX + "§cChannel couldn't be updated\n§fMake sure your version matches the server's version: §d" + MOD_VERSION), false);
			return;
		}

		ensureMarkerStore(player.serverLevel().getServer());
		updatePlayerChannel(player, packet.channel());
		IPlatformNetworkService.INSTANCE.sendToClient(
			new RateLimitPolicyS2CPacket(SERVER_CONFIG.getRateLimit(), SERVER_CONFIG.getMsToRegenerate()),
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
		final var inventoryCandidate = (update.changedFields() & ServerConfigUpdate.ALL_INVENTORY_FIELDS) != 0
			? plan.snapshot().inventory().toSettings() : null;

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

		if (inventoryCandidate != null) config.setInventory(inventoryCandidate);
		if ((update.changedFields() & ServerConfigUpdate.BUDGET_DELAY_NOTIFICATIONS_ENABLED) != 0) {
			config.setBudgetDelayNotificationsEnabled(update.budgetDelayNotificationsEnabled());
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

	/**
	 * Handles one presentation policy rule-view read or mutation. Reads are
	 * allowed for every authenticated player and disclose only the three
	 * presentation selector values. Every mutation rechecks inherent permission
	 * level 3 on the server thread immediately before the atomic service
	 * mutation.
	 *
	 * <p>The mutation is transactional: the service runs against a detached
	 * exact-field copy, the live config object is only replaced after the
	 * candidate was validated and persisted, and a failed persistence rolls the
	 * previous settings object back before answering. An applied change
	 * advances the in-memory revision exactly once and is sent to the requester
	 * and to every other connected route-capable peer; a no-op or rejected
	 * request never rewrites the config, advances the revision, or broadcasts.
	 * Peers without the versioned route receive nothing, because every platform
	 * send service checks route presence before sending.
	 */
	public static void onServerPresentationPolicy(
		MinecraftServer server,
		ServerPlayer player,
		ServerPresentationPolicyC2SPacket packet) {
		if (packet == null || packet.isCorrupt()) {
			LOGGER.debug("presentation policy request rejected: invalid packet");
			return;
		}

		final ServerConfig config = ServerConfig.HANDLER.getConfig();
		final PresentationSettings settings = config == null ? null : config.getPresentation();
		final boolean canEdit = player.hasPermissions(3);

		if (packet.operation() == ServerPresentationPolicyService.Operation.READ) {
			sendPresentationPolicySnapshot(
				player,
				packet.requestId(),
				ServerPresentationPolicyService.Status.OK,
				canEdit,
				ServerPresentationPolicyService.readAll(settings),
				PRESENTATION_POLICY_REVISION.get());
			return;
		}

		if (settings == null) {
			sendPresentationPolicySnapshot(
				player,
				packet.requestId(),
				ServerPresentationPolicyService.Status.INVALID,
				canEdit,
				ServerPresentationPolicyService.readAll(null),
				PRESENTATION_POLICY_REVISION.get());
			return;
		}

		final PresentationSettings candidate = ServerPresentationPolicyService.detachedCopy(settings);
		final var result = ServerPresentationPolicyService.mutateSelectedRules(
			canEdit, candidate, packet.targetTypeId(), packet.operation(), packet.selector(), packet.whitelistOnly());

		if (!result.applied()) {
			// Denied, invalid, duplicate, missing, full, and no-op requests never
			// touch the persisted config, the revision, or other clients.
			sendPresentationPolicySnapshot(
				player,
				packet.requestId(),
				result.status(),
				canEdit,
				ServerPresentationPolicyService.readAll(settings),
				PRESENTATION_POLICY_REVISION.get());
			return;
		}

		// Transactional swap: the original settings object is only replaced after
		// the validated candidate is persisted, so a failed save restores the
		// exact previous rule view instead of leaving a half-applied state.
		config.setPresentation(candidate);
		boolean saved;

		try {
			config.validate();
			saved = ServerConfig.HANDLER.saveSafely();
		} catch (RuntimeException ex) {
			RateLimitedDiagnostics.warn("presentation-policy-save", "server-config",
				() -> "presentation policy validation/save failed: path=%s requestId=%d operation=%s targetType=%s selector=%s whitelistOnly=%s player=%s"
					.formatted(ServerConfig.HANDLER.getConfigPath(), packet.requestId(), packet.operation(),
						packet.targetTypeId(), packet.selector(), packet.whitelistOnly(), player.getUUID()), ex);
			saved = false;
		}

		if (!saved) {
			config.setPresentation(settings);
			sendPresentationPolicySnapshot(
				player,
				packet.requestId(),
				ServerPresentationPolicyService.Status.FAILED,
				canEdit,
				ServerPresentationPolicyService.readAll(settings),
				PRESENTATION_POLICY_REVISION.get());
			return;
		}

		final long revision = PRESENTATION_POLICY_REVISION.incrementAndGet();
		final Map<String, ServerPresentationPolicyService.RulesView> appliedRules =
			ServerPresentationPolicyService.readAll(candidate);

		sendPresentationPolicySnapshot(
			player, packet.requestId(), result.status(), canEdit, appliedRules, revision);

		for (ServerPlayer recipient : server.getPlayerList().getPlayers()) {
			if (recipient == player) {
				continue;
			}

			sendPresentationPolicySnapshot(
				recipient,
				0L,
				ServerPresentationPolicyService.Status.OK,
				recipient.hasPermissions(3),
				appliedRules,
				revision);
		}
	}

	private static void sendPresentationPolicySnapshot(
		ServerPlayer player,
		long requestId,
		ServerPresentationPolicyService.Status status,
		boolean canEdit,
		Map<String, ServerPresentationPolicyService.RulesView> rules,
		long revision) {
		IPlatformNetworkService.INSTANCE.sendToClient(
			new ServerPresentationPolicyS2CPacket(
				requestId,
				revision,
				status,
				canEdit,
				rules),
			player);
	}

	public static void onInventoryPacket(MinecraftServer server, ServerPlayer player, InventoryC2SPacket packet) {
		if (server == null || player == null || !server.isSameThread() || player.serverLevel().getServer() != server) return;
		ensureMarkerStore(server);
		InventoryServer.handle(server, player, packet);
	}

	public static void onPresentationPacket(MinecraftServer server, ServerPlayer player, PresentationC2SPacket packet) {
		PresentationServer.activate(server);
		if (packet == null || packet.isCorrupt()) return;
		if (packet.kind() == PresentationC2SPacket.Kind.HELLO) {
			ensureMarkerStore(server);
			PresentationServer.negotiate(server, player, packet, markerStore());
			return;
		}
		if (!PresentationServer.accepts(player, packet)) return;
		switch (packet.kind()) {
			case CREATE -> onMarkerCreate(server, player, packet.requestId(), packet.target(), packet.pingType(), packet.properties());
			case REMOVE -> onMarkerRemove(server, player, packet.markerId());
			default -> { }
		}
	}

	/** Read-only route: thread/session admission and queueing, never marker creation or source reads. */
	public static void onPresentationPreview(MinecraftServer server, ServerPlayer player, PresentationPreviewC2SPacket packet) {
		PresentationServer.preview(server, player, packet);
	}

	/**
	 * Adjudicates an authoritative create admitted by the presentation session.
	 *
	 * <p>The intent carries no channel: the requester's server-stored channel is
	 * authoritative. Invalid arguments, rate-limited requests, and requests
	 * blocked by DISABLED/TEAM_ONLY channel modes receive the corresponding
	 * rejection. After the channel gate, recipients are snapshotted using the
	 * stored channel and current team context before target and property
	 * validation, so later channel switches cannot change the audience.
	 * The marker lifetime uses the server tick arrival time and the configured
	 * server-authoritative duration.
	 */
	private static void onMarkerCreate(MinecraftServer server, ServerPlayer player, long requestId,
		@Nullable Target requestedTarget, @Nullable String requestedPingType, List<PresentationPropertyIntent> properties) {
		ensureMarkerStore(server);

		if (requestedTarget == null || requestedPingType == null || requestedPingType.isBlank() || requestId < 0L) {
			LOGGER.debug(() -> "marker create rejected: requestId=%d reason=%s".formatted(requestId, MarkerRejectReason.INVALID_REQUEST));
			PresentationServer.rejected(player, requestId, MarkerRequestKind.CREATE, MarkerRejectReason.INVALID_REQUEST);
			return;
		}

		PLAYER_RATES.putIfAbsent(player.getUUID(), new RateLimiter());
		final var rateLimiter = PLAYER_RATES.get(player.getUUID());

		if (SERVER_CONFIG.getRateLimit() > 0 && rateLimiter.checkExceeded()) {
			LOGGER.debug(() -> "marker create rejected: requestId=%d reason=%s".formatted(requestId, MarkerRejectReason.RATE_LIMITED));
			PresentationServer.rejected(player, requestId, MarkerRequestKind.CREATE, MarkerRejectReason.RATE_LIMITED);
			return;
		}

		final var channel = PLAYER_CHANNELS.getOrDefault(player.getUUID(), "");
		final var defaultChannelMode = SERVER_CONFIG.getDefaultChannelMode();

		if (channel.isEmpty() && defaultChannelMode == ChannelMode.DISABLED) {
			LOGGER.debug(() -> "marker create rejected: requestId=%d reason=%s".formatted(requestId, MarkerRejectReason.CHANNEL_DISABLED));
			PresentationServer.rejected(player, requestId, MarkerRequestKind.CREATE, MarkerRejectReason.CHANNEL_DISABLED);
			return;
		}

		if (channel.isEmpty() && defaultChannelMode == ChannelMode.TEAM_ONLY && !TeamContextHandler.hasTeam(player)) {
			LOGGER.debug(() -> "marker create rejected: requestId=%d reason=%s".formatted(requestId, MarkerRejectReason.CHANNEL_DISABLED));
			PresentationServer.rejected(player, requestId, MarkerRequestKind.CREATE, MarkerRejectReason.CHANNEL_DISABLED);
			return;
		}

		final var playerList = server.getPlayerList();
		final var recipients = snapshotRecipients(playerList, player, channel, defaultChannelMode);

		final long arrivalTick = server.getTickCount();
		final long expiresAtTick = arrivalTick + SERVER_CONFIG.getSyncDuration() * (long) TICKS_PER_SECOND;

		final var outcome = markerService(server).create(
			player.serverLevel(),
			player.getUUID(), requestedTarget, requestedPingType, arrivalTick, expiresAtTick, recipients,
			properties, (committed, finalType, audienceOwner, audience, intents) ->
				PresentationServer.admit(server, player, committed, finalType, audience, intents));

		if (!outcome.isAccepted()) {
			final var reason = outcome.rejectReason().orElseThrow();

			LOGGER.debug(() -> "marker create rejected: requestId=%d reason=%s".formatted(requestId, reason));
			PresentationServer.rejected(player, requestId, MarkerRequestKind.CREATE, reason);
			return;
		}

		final var creation = outcome.creation().orElseThrow();
		final var marker = creation.marker();

		LOGGER.debug(() -> "marker create accepted: requestId=%d markerId=%d kind=%s targetType=%s pingType=%s recipients=%d".formatted(
			requestId,
			marker.id().value(),
			marker.target().kind(),
			marker.targetType().id(),
			marker.pingType().id(),
			marker.recipients().size()));

		// Basic name and details are projected independently for every negotiated recipient.
		PresentationServer.created(server, marker, outcome.targetName().orElseThrow(),
			player.getGameProfile().getName(), outcome.sourceSeeds());
		sendPresentationWinnerChanges(playerList, creation.winnerChanges(), null);
	}

	/** Immediate dedicated create; no queued retry, SECTION intent or client-supplied audience. */
	public static InventoryBackend.Created createInventory(MinecraftServer server, ServerPlayer player,
		InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
		if (player == null || !server.isSameThread()) return new InventoryBackend.Created(null, MarkerRejectReason.INVALID_REQUEST);
		ensureMarkerStore(server);
		PLAYER_RATES.putIfAbsent(player.getUUID(), new RateLimiter());
		if (SERVER_CONFIG.getRateLimit() > 0 && PLAYER_RATES.get(player.getUUID()).checkExceeded())
			return new InventoryBackend.Created(null, MarkerRejectReason.RATE_LIMITED);
		String channel = PLAYER_CHANNELS.getOrDefault(player.getUUID(), "");
		ChannelMode mode = SERVER_CONFIG.getDefaultChannelMode();
		if (channel.isEmpty() && (mode == ChannelMode.DISABLED || mode == ChannelMode.TEAM_ONLY && !TeamContextHandler.hasTeam(player)))
			return new InventoryBackend.Created(null, MarkerRejectReason.CHANNEL_DISABLED);
		List<UUID> audience = snapshotRecipients(server.getPlayerList(), player, channel, mode);
		long now = server.getTickCount();
		var outcome = markerService(server).createDedicated(player.serverLevel(), player.getUUID(), frozen.target(), frozen.defaultPingType(),
			now, now + SERVER_CONFIG.getSyncDuration() * (long) TICKS_PER_SECOND, audience, (target, targetKey, type, owner, recipients) -> {
				@Nullable var policy = PresentationServer.inventoryPolicy(owner).orElse(null);
				@Nullable MarkerRejectReason rejection = policy == null || !policy.types().contains(type) ? MarkerRejectReason.INVALID_REQUEST : admission.prepare(target, targetKey, type, recipients);
				return rejection == null ? MarkerCreationService.AdmissionResult.accepted(List.of(), Map.of()) : MarkerCreationService.AdmissionResult.rejected(rejection);
			});
		if (!outcome.isAccepted()) return new InventoryBackend.Created(null, outcome.rejectReason().orElseThrow());
		var creation = outcome.creation().orElseThrow();
		// Committed storage only: the backend installs the tracking sidecar and
		// publishes the CREATED/winner changes after it exists.
		return new InventoryBackend.Created(creation.marker(), null, outcome.targetName().orElseThrow(), creation.winnerChanges());
	}

	/**
	 * Committed dedicated publication. The tracking sidecar already exists, so
	 * the atomic initial projects the inventory receipt kind. Storage is
	 * committed: a publication failure is logged and never becomes a retryable
	 * rejection, and the existing Basic-known-recipient gate still precedes
	 * every inventory data frame.
	 */
	public static void publishInventoryCreated(MinecraftServer server, InventoryBackend.Created created) {
		if (created == null || created.marker() == null) return;
		ServerPlayer owner = server.getPlayerList().getPlayer(created.marker().owner());
		if (owner == null) return;
		try {
			PresentationServer.created(server, created.marker(), created.authoritativeName(), owner.getGameProfile().getName());
			sendPresentationWinnerChanges(server.getPlayerList(), created.winnerChanges(), null);
		} catch (RuntimeException | LinkageError deliveryFailure) {
			RateLimitedDiagnostics.error("inventory-create-publish", "server-marker",
				() -> "inventory create publication failed after commit: marker=" + created.marker()
					+ " winnerChanges=" + created.winnerChanges(), deliveryFailure);
		}
	}

	/**
	 * Adjudicates a marker removal admitted by the presentation session. The
	 * store checks ownership and removes only an owned, active marker with
	 * reason {@link MarkerRemovalReason#CANCELLED}. Removal is not rate-limited.
	 */
	private static void onMarkerRemove(MinecraftServer server, ServerPlayer player, @Nullable MarkerId requestedMarkerId) {
		ensureMarkerStore(server);
		if (requestedMarkerId == null) {
			final long requestId = 0L;

			LOGGER.debug(() -> "marker remove rejected: requestId=%d reason=%s".formatted(requestId, MarkerRejectReason.INVALID_REQUEST));
			PresentationServer.rejected(player, requestId, MarkerRequestKind.REMOVE, MarkerRejectReason.INVALID_REQUEST);
			return;
		}

		final var markerId = requestedMarkerId;
		final var result = markerStore().removeOwned(player.getUUID(), markerId);

		switch (result.status()) {
			case REMOVED -> {
				final var removal = result.removal().orElseThrow();
				releaseExternal(server, removal.marker());

				LOGGER.debug(() -> "marker remove accepted: markerId=%d reason=%s".formatted(markerId.value(), removal.reason()));
				PresentationServer.forget(removal.marker().id());
				sendPresentationRemoved(server.getPlayerList(), removal, null);
				sendPresentationWinnerChanges(server.getPlayerList(), result.winnerChanges(), null);
			}
			case NOT_FOUND -> {
				LOGGER.debug(() -> "marker remove rejected: requestId=%d reason=%s".formatted(markerId.value(), MarkerRejectReason.NOT_FOUND));
				PresentationServer.rejected(player, markerId.value(), MarkerRequestKind.REMOVE, MarkerRejectReason.NOT_FOUND);
			}
			case NOT_OWNER -> {
				LOGGER.debug(() -> "marker remove rejected: requestId=%d reason=%s".formatted(markerId.value(), MarkerRejectReason.NOT_OWNER));
				PresentationServer.rejected(player, markerId.value(), MarkerRequestKind.REMOVE, MarkerRejectReason.NOT_OWNER);
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

		final var store = markerStore();
		PresentationServer.tick(server, store);
		final var batch = store.expire(server.getTickCount());

		final var playerList = server.getPlayerList();

		if (!batch.removals().isEmpty()) {
			LOGGER.debug(() -> "marker expiry: removed=%d winnerChanges=%d".formatted(
				batch.removals().size(), batch.winnerChanges().size()));

			for (final var removal : batch.removals()) {
				releaseExternal(server, removal.marker());
				PresentationServer.forget(removal.marker().id());
				for (UUID recipientId : removal.marker().recipients()) {
					ServerPlayer recipient = playerList.getPlayer(recipientId);
					if (recipient != null) PresentationServer.removed(recipient, removal.marker().id(), removal.reason());
				}
			}
			for (MarkerWinnerChange change : batch.winnerChanges()) {
				ServerPlayer recipient = playerList.getPlayer(change.recipientId());
				if (recipient != null) PresentationServer.winner(recipient, change.targetKey(), change.currentWinner());
			}
		}

		if (server.getTickCount() % EXTERNAL_REFRESH_INTERVAL_TICKS == 0) {
			refreshExternalMarkers(server);
		}
		InventoryServer.tick(server);
		BudgetDelayMonitoring.finishTick(server);
	}

	/**
	 * Cleans up the disconnecting player's markers before the channel
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
			PresentationServer.forget(removal.marker().id());
			for (UUID recipientId : removal.marker().recipients()) {
				if (recipientId.equals(player.getUUID())) continue;
				ServerPlayer recipient = playerList.getPlayer(recipientId);
				if (recipient != null) PresentationServer.removed(recipient, removal.marker().id(), removal.reason());
			}
		}
		for (MarkerWinnerChange change : removed.winnerChanges()) {
			if (change.recipientId().equals(player.getUUID())) continue;
			ServerPlayer recipient = playerList.getPlayer(change.recipientId());
			if (recipient != null) PresentationServer.winner(recipient, change.targetKey(), change.currentWinner());
		}

		final var dropped = store.forgetRecipient(player.getUUID());

		for (final var droppedMarker : dropped) {
			releaseExternal(server, droppedMarker);
		}

		LOGGER.debug(() -> "marker disconnect cleanup: removed=%d audienceEmptyDrops=%d".formatted(
			removed.removals().size(), dropped.size()));

		PLAYER_CHANNELS.remove(player.getUUID());
		PLAYER_RATES.remove(player.getUUID());
		PresentationServer.disconnect(player.getUUID());
		InventoryServer.disconnect(player.getUUID());
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
				} catch (RuntimeException invalidRefresh) {
					RateLimitedDiagnostics.warn("external-marker-refresh-key", external.providerId(),
						() -> "markerId=" + marker.id() + " committedTarget=" + external
							+ " refreshedTarget=" + available.target(), invalidRefresh);
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

				PresentationServer.updated(server, updated);

				continue;
			}

			final var removalResult = store.removeByServer(marker.id(), MarkerRemovalReason.TARGET_INVALID);
			if (removalResult.status() == nx.pingwheel.common.marker.MarkerRemovalResult.Status.REMOVED) {
				final var removal = removalResult.removal().orElseThrow();
				releaseExternal(server, removal.marker());
				PresentationServer.forget(removal.marker().id());
				sendPresentationRemoved(playerList, removal, null);
				sendPresentationWinnerChanges(playerList, removalResult.winnerChanges(), null);
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
		InventoryServer.remove(marker.id());
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
	 * Snapshots the recipient list for a marker creation: always the creator,
	 * plus players with the exact same stored channel. An empty, non-GLOBAL
	 * channel also requires the same team context. The returned list is
	 * immutable, so channel switches after creation cannot change the audience.
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

	private static void sendPresentationRemoved(PlayerList playerList, MarkerRemoval removal, UUID excluded) {
		for (UUID recipientId : removal.marker().recipients()) {
			if (excluded != null && excluded.equals(recipientId)) continue;
			ServerPlayer recipient = playerList.getPlayer(recipientId);
			if (recipient != null) PresentationServer.removed(recipient, removal.marker().id(), removal.reason());
		}
	}

	private static void sendPresentationWinnerChanges(PlayerList playerList, List<MarkerWinnerChange> changes, UUID excluded) {
		for (MarkerWinnerChange change : changes) {
			if (excluded != null && excluded.equals(change.recipientId())) continue;
			ServerPlayer recipient = playerList.getPlayer(change.recipientId());
			if (recipient != null) PresentationServer.winner(recipient, change.targetKey(), change.currentWinner());
		}
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
