package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Optional;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.integration.ExternalBlockServerProviders;
import nx.pingwheel.common.marker.MinecraftAuthoritativeTargetValidator;
import nx.pingwheel.common.name.MinecraftTargetNameResolver;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.platform.TransportHandoff;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.server.ServerInventoryMonitor;
import nx.pingwheel.common.monitor.server.ServerMonitorCoordinator.CollectionState;
import nx.pingwheel.common.monitor.integration.ServerMonitorService;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.presentation.inventory.InventoryBackend;
import nx.pingwheel.common.presentation.inventory.InventoryDecision;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import nx.pingwheel.common.presentation.inventory.InventoryImpact;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec;
import nx.pingwheel.common.presentation.InventoryItemPingTypes;
import nx.pingwheel.common.presentation.minecraft.MinecraftBlockReadSources;
import nx.pingwheel.common.presentation.minecraft.PresentationServer;
import nx.pingwheel.common.presentation.minecraft.BudgetDelayMonitoring;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import nx.pingwheel.common.core.ServerCore;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;

/** Minecraft-facing inventory ingress. All world handles stay on the active server thread. */
public final class InventoryServer {

	private static @Nullable MinecraftServer active;
	private static @Nullable InventoryBackend backend;
	private static long epochSeed = 1;
	private static long monitorGeneration;
	private static @Nullable ServerMonitorService monitorService;

	private InventoryServer() {}

	public static void activate(MinecraftServer server) {
		if (active == server) return;
		reset();
		active = server;
		epochSeed = Math.addExact(epochSeed, 2048);
		var monitor = new ServerInventoryMonitor();
		monitor.resetGeneration(Math.incrementExact(monitorGeneration));
		monitorGeneration = monitor.serverGeneration();
		configureMonitor(monitor);
		var sink = BudgetDelayMonitoring.sink(server);
		InventoryRuntime.DisplayReprojector reprojector = (display, displayBytes) ->
			InventoryItemCodec.reproject(display, server.registryAccess(), displayBytes);
		var runtime = new InventoryRuntime(
			new InventoryRuntime.SourceResolver() {
				@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return InventoryMinecraftSources.resolutionValidationCost(input); }
				@Override public InventoryValidationBudget.Cost resolutionProbeCost(InventorySourceInput input) { return InventoryMinecraftSources.resolutionProbeCost(input); }
				@Override public Optional<nx.pingwheel.common.presentation.inventory.InventorySourceAccess.Source> resolve(InventorySourceInput input, int displayBytes) {
					throw new IllegalStateException("production inventory resolution requires admission");
				}
				@Override public Optional<nx.pingwheel.common.presentation.inventory.InventorySourceAccess.Source> resolve(InventorySourceInput input, int displayBytes, InventoryValidationBudget.Allowance allowance) {
					return InventoryMinecraftSources.resolve(server, input, displayBytes, allowance);
				}
			},
			ServerConfig.HANDLER.getConfig().getInventory().pendingMemoryBytes(),
			reprojector, sink, true, monitor);
		backend = new InventoryBackend(new Host(server, runtime, reprojector), runtime, epochSeed, sink);
		monitorService = new ServerMonitorService(server, IPlatformNetworkService.INSTANCE, monitor::latest,
			() -> new CollectionState(monitor.serverGeneration(), monitor.collectionGeneration(),
				monitor.enabled(Lane.PREVIEW), monitor.enabled(Lane.TRACKING)));
	}

	public static void reset() {
		InventoryFluidRouteDiagnostics.clear();
		if (monitorService != null) monitorService.close();
		monitorService = null;
		if (backend != null) backend.close();
		backend = null;
		active = null;
	}

	public static void stopped(MinecraftServer server) {
		if (active == server) reset();
	}

	public static void handle(MinecraftServer server, ServerPlayer player, InventoryC2SPacket packet) {
		if (!server.isSameThread() || player.serverLevel().getServer() != server) return;
		activate(server);
		configureMonitor(backend.monitor());
		backend.handle(player.getUUID(), packet, server.getTickCount(), ServerConfig.HANDLER.getConfig().getInventory());
	}

	public static void tick(MinecraftServer server) {
		if (!server.isSameThread()) return;
		activate(server);
		configureMonitor(backend.monitor());
		backend.tick(server.getTickCount(), ServerConfig.HANDLER.getConfig().getInventory());
		backend.completeMonitorTick(server.getTickCount());
		if (monitorService != null) monitorService.tick(server.getTickCount());
	}

	private static void configureMonitor(ServerInventoryMonitor monitor) {
		ServerConfig config = ServerConfig.HANDLER.getConfig();
		boolean debug = config.getLoggingProfile() == LoggingProfile.DEBUG;
		monitor.configure(debug && config.isPreviewMonitorEnabled(), debug && config.isTrackingMonitorEnabled());
	}

	public static void subscribeMonitor(MinecraftServer server, ServerPlayer player, InventoryMonitorSubscribeC2SPacket packet) {
		if (!server.isSameThread() || packet.isCorrupt() || server.getPlayerList().getPlayer(player.getUUID()) != player) return;
		activate(server);
		configureMonitor(backend.monitor());
		if (monitorService != null) monitorService.subscribe(player, packet);
	}

	public static void disconnect(ServerPlayer player) {
		if (monitorService != null) monitorService.disconnect(player);
		disconnect(player.getUUID());
	}

	public static void disconnect(UUID player) {
		if (backend != null) backend.disconnect(player);
	}
	public static void remove(MarkerId marker) { if (backend != null) backend.remove(marker); }
	/**
	 * Pure sidecar query for the presentation receipt projection: whether the
	 * marker owns an active committed inventory selection. It never samples,
	 * publishes, or mutates state.
	 */
	public static boolean tracksInventory(MarkerId marker) {
		return backend != null && backend.tracksInventory(marker);
	}
	public static Optional<nx.pingwheel.common.presentation.inventory.InventoryResourceKind> trackedKind(MarkerId marker) {
		return backend == null ? Optional.empty() : backend.trackedKind(marker);
	}
	public static Optional<nx.pingwheel.common.presentation.inventory.InventoryCreationSnapshot> creationSnapshot(MarkerId marker) {
		return backend == null ? Optional.empty() : backend.creationSnapshot(marker);
	}
	public static InventoryBackend.InitialDecision creationDecision(MarkerId marker, UUID recipient, long epoch, long view) {
		return backend == null ? InventoryBackend.InitialDecision.SUPPRESSED : backend.creationDecision(marker, recipient, epoch, view);
	}
	public static Optional<InventoryBackend.InitialPublication> reserveInitial(MarkerId marker, UUID recipient,
		long epoch, long view, long wireBytes, long encodingWorkspace) {
		prepareInitialAdmission();
		return backend == null ? Optional.empty() : backend.reserveInitial(marker, recipient, epoch, view, wireBytes, encodingWorkspace);
	}
	public static Optional<InventoryBackend.InitialEnvelope> reserveInitialEnvelope(long wireBytes, long workspace) {
		prepareInitialAdmission();
		return backend == null ? Optional.empty() : backend.reserveInitialEnvelope(wireBytes, workspace);
	}
	private static void prepareInitialAdmission() {
		if (backend != null && active != null) backend.prepareInitialAdmission(active.getTickCount());
	}
	public static boolean initialAudienceActive(MarkerId marker, UUID recipient) { return backend != null && backend.initialAudienceActive(marker, recipient); }
	public static boolean initialCanonicalAudienceActive(MarkerId marker, UUID recipient, long now) {
		return backend != null && backend.initialCanonicalAudienceActive(marker, recipient, now);
	}
	public static void suppressCreation(MarkerId marker, UUID recipient) { if (backend != null) backend.suppressCreation(marker, recipient); }

	private record Host(MinecraftServer server, InventoryRuntime runtime, InventoryRuntime.DisplayReprojector reprojector) implements InventoryBackend.Host {
		@Override public Optional<InventoryRuntime.DisplayReprojector> displayReprojector() { return Optional.of(reprojector); }
		@Override public Optional<InventoryBackend.Policy> policy(UUID player) {
			return PresentationServer.inventoryPolicy(player).map(p -> new InventoryBackend.Policy(
				p.epoch(), p.view(), p.types(), p.fluidTypes(), p.revision()));
		}
		@Override public void policyDemand(UUID player) {
			PresentationServer.refreshInventoryPolicy(player);
		}
		@Override public InventoryBackend.PolicyToken policyToken(UUID player) {
			var token = PresentationServer.inventoryHintToken(player);
			return new InventoryBackend.PolicyToken(token.owner(), token.sessionEpoch(), token.sessionView(),
				token.hintGeneration(), token.revision(), token.providerRevision());
		}
		@Override public InventoryBackend.PreviewLaneGrant freezeInventoryPreviewGrant(UUID owner, String targetTypeId) {
			var grant = PresentationServer.freezeInventoryPreviewGrant(owner, targetTypeId);
			return new InventoryBackend.PreviewLaneGrant(grant.lanes(), new InventoryBackend.PolicyToken(
				grant.token().owner(), grant.token().sessionEpoch(), grant.token().sessionView(),
				grant.token().hintGeneration(), grant.token().revision(), grant.token().providerRevision()));
		}
		@Override public void adoptPreviewPolicy(UUID player, String targetTypeId,
			java.util.Set<nx.pingwheel.common.presentation.inventory.InventoryResourceKind> lanes) {
			PresentationServer.adoptInventoryPolicyLanes(player, targetTypeId, lanes);
		}
		@Override public void adoptPreviewPolicy(UUID player, String targetTypeId, InventoryBackend.PreviewLaneGrant grant) {
			PresentationServer.adoptInventoryPolicyLanes(player, targetTypeId, grant.lanes(),
				new PresentationServer.InventoryHintToken(grant.token().owner(), grant.token().sessionEpoch(),
					grant.token().sessionView(), grant.token().hintGeneration(),
					grant.token().hintRevision(), grant.token().providerRevision()));
		}
		@Override public void adoptCreatePolicy(UUID player, String targetTypeId,
			nx.pingwheel.common.presentation.inventory.InventoryResourceKind kind, boolean allowed) {
			PresentationServer.adoptInventoryPolicyLane(player, targetTypeId, kind, allowed);
		}
		@Override public void adoptCreatePolicy(UUID player, String targetTypeId,
			nx.pingwheel.common.presentation.inventory.InventoryResourceKind kind, boolean allowed,
			InventoryBackend.PolicyToken token) {
			PresentationServer.adoptInventoryPolicyLane(player, targetTypeId, kind, allowed,
				new PresentationServer.InventoryHintToken(token.owner(), token.sessionEpoch(), token.sessionView(),
					token.hintGeneration(), token.hintRevision(), token.providerRevision()));
		}
		@Override public Optional<InventoryBackend.SessionAccess> session(UUID player) {
			return PresentationServer.sessionAccess(player).map(s -> new InventoryBackend.SessionAccess(
				s.epoch(), s.view(), s.compatibleInventoryKinds()));
		}
		@Override public Optional<nx.pingwheel.common.marker.ServerMarker> activeMarker(MarkerId marker) {
			return ServerCore.activeMarker(marker);
		}
		@Override public java.util.Set<nx.pingwheel.common.presentation.inventory.InventoryResourceKind> freezeInventoryPreviewLanes(
			UUID owner, String targetTypeId) {
			return PresentationServer.freezeInventoryPreviewLanes(owner, targetTypeId);
		}
		@Override public boolean authorized(SyncPublisher.Context context) {
			return session(context.recipient()).map(s -> s.stamp().equals(context.sessionView())).orElse(false);
		}
		@Override public boolean knows(UUID player, MarkerId marker) { return PresentationServer.inventoryKnows(player, marker); }
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen, InventoryBackend.Admission admission) {
			return ServerCore.createInventory(server, server.getPlayerList().getPlayer(player), frozen, admission);
		}
		@Override public InventoryBackend.Created create(UUID player, InventoryBackend.Opened frozen,
			nx.pingwheel.common.presentation.inventory.InventoryResourceKind kind, InventoryBackend.Admission admission) {
			return ServerCore.createInventory(server, server.getPlayerList().getPlayer(player), frozen, kind, admission);
		}
		/**
		 * Production committed publication: the backend installed the tracking
		 * sidecar before this call, so the atomic initial can project the
		 * inventory receipt kind. Publication failures stay fail-soft.
		 */
		@Override public void publishCreated(InventoryBackend.Created created) {
			ServerCore.publishInventoryCreated(server, created);
		}
		/**
		 * Annotation policy runs inside the runtime's provider-work preflight.
		 * It queries the actual provider-confirmed physical block's live tags,
		 * never a client-supplied expected registry or a placeholder position:
		 * the inventory item policy grants {@code take} only when those actual
		 * tags carry {@code #c:chests}.
		 */
		@Override public boolean annotationAllowed(InventorySourceInput input, String pingType) {
			ServerLevel level = levelFor(input.target().dimensionId());
			if (level == null) return false;
			var resolved = input.target() instanceof Target.ExternalBlockTarget external
				? (external.isCandidate() ? MinecraftBlockReadSources.resolvePreviewReadSource(level, external)
					: MinecraftBlockReadSources.resolveCommittedReadSource(level, external))
				: input.ordinaryTarget().flatMap(block -> MinecraftBlockReadSources.resolveOrdinary(level, block));
			if (resolved.isEmpty() || resolved.orElseThrow().level() != level) return false;
			var physical = resolved.orElseThrow().descriptor().blockTarget();
			BlockPos pos = new BlockPos(physical.x(), physical.y(), physical.z());
			if (!level.isLoaded(pos)) return false;
			var state = level.getBlockState(pos);
			if (state == null || state.isAir()) return false;
			String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			return id.equals(physical.blockRegistryId()) && InventoryItemPingTypes.builtIn().allows(input.resourceKind(), pingType, id,
				BuiltInRegistries.BLOCK.wrapAsHolder(state.getBlock()).tags().map(tag -> tag.location().toString()).collect(java.util.stream.Collectors.toSet()));
		}

		/**
		 * Preview acceptance keeps the existing authenticated-session, field,
		 * range, nonallocating provider-validation and classification gates, and
		 * now also accepts a normalized external provider candidate. Nothing is
		 * materialized and no inventory is read here.
		 */
		@Override
		public Optional<InventoryBackend.Opened> open(UUID playerId, Target requested) {
			return Optional.ofNullable(openDecision(playerId, requested).value());
		}
		@Override
		public InventoryDecision<InventoryBackend.Opened> openDecision(UUID playerId, Target requested) {
			@Nullable ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				debugOpen("player", playerId, requested);
				return InventoryDecision.reject(InventoryFailureReason.TARGET_GONE, InventoryImpact.PING_UNAVAILABLE);
			}
			// Entity/private inventory and committed external contexts require their separate owning contracts.
			boolean openable = requested instanceof Target.BlockTarget
				|| requested instanceof Target.ExternalBlockTarget external && external.isCandidate();
			if (!openable) {
				debugOpen("context", playerId, requested);
				return InventoryDecision.unsupported(InventoryFailureReason.UNSUPPORTED_CONTEXT, InventoryImpact.PING_UNAVAILABLE);
			}
			ServerConfig config = ServerConfig.HANDLER.getConfig();
			var validation = new MinecraftAuthoritativeTargetValidator(server, config.getPingDistance(),
				config.isPlayerTrackingEnabled(), new MinecraftTargetNameResolver(server), ExternalBlockServerProviders.registry())
				.validate(playerId, requested);
			if (!validation.isAccepted()) {
				debugOpen("validation", playerId, requested, null, null,
					() -> "rejection=" + validation.rejectReason().map(reason -> reason.name()).orElse("unknown"));
				return InventoryDecision.reject(InventoryFailureReason.from(validation.rejectReason().orElseThrow()), InventoryImpact.PING_UNAVAILABLE);
			}
			var validated = validation.validatedTarget().orElseThrow();
			// The validator may correct a candidate's classification, but a
			// provider, dimension, expected registry or opaque locator drift is
			// rejected before any resolver or source access. Ordinary targets
			// keep their existing normalization behavior.
			if (requested instanceof Target.ExternalBlockTarget external && external.isCandidate()
				&& !InventoryBackend.sameCandidateNormalization(requested, validated.normalizedTarget())) {
				debugOpen("normalize", playerId, requested, validated.normalizedTarget(), null, null);
				return InventoryDecision.reject(InventoryFailureReason.SOURCE_CHANGED, InventoryImpact.PING_UNAVAILABLE);
			}
			var resolved = DefaultTargetResolver.builtIn(TargetResolutionLogger.global(nx.pingwheel.common.diagnostics.Diagnostics.server()))
				.resolve(validated.normalizedTarget(), validated.matchContext());
			debugOpen("opened", playerId, requested, resolved.target(), resolved.targetType().id(),
				() -> "defaultPingType=" + resolved.targetType().defaultPingType().id());
			return InventoryDecision.accept(new InventoryBackend.Opened(resolved.target(),
				resolved.targetType().id(), resolved.targetType().defaultPingType().id()));
		}

		private static void debugOpen(String reason, UUID playerId, Target requested) {
			debugOpen(reason, playerId, requested, null, null, null);
		}

		private static void debugOpen(String reason, UUID playerId, Target requested, @Nullable Target normalized,
			@Nullable String targetType, @Nullable java.util.function.Supplier<String> detail) {
			Diagnostics.server().atDebug().event(Level.DEBUG, "inventory/preview/host", reason,
				() -> "owner=" + playerId + " requested=" + requested
					+ (normalized == null ? "" : " normalized=" + normalized)
					+ (targetType == null ? "" : " targetType=" + targetType)
					+ (detail == null ? "" : " " + detail.get()));
		}

		/**
		 * Binds a preview input to the materialized committed target through both
		 * current provider-confirmed physical read bindings. Ordinary identity is
		 * exact. An external candidate must resolve to the same current provider,
		 * sub-level, physical root block and registry as the committed target;
		 * neither the target equality nor the opaque locator alone is trusted, so
		 * same-registry candidates and different hit roots cannot cross-bind.
		 * Runs inside the runtime's provider-work preflight, never uncharged.
		 */
		@Override
		public Optional<InventorySourceInput> bindCommitted(InventorySourceInput previewInput, Target committed) {
			Target requested = previewInput == null ? null : previewInput.target();
			if (requested == null || committed == null) return Optional.empty();
			if (!(committed instanceof Target.ExternalBlockTarget committedExternal) || !committedExternal.isCommitted()) {
				return previewInput.ordinaryTarget().filter(block -> block.equals(committed))
					.map(block -> new InventorySourceInput(block, previewInput.readOwner(), previewInput.face(), previewInput.resourceKind()));
			}
			if (!(requested instanceof Target.ExternalBlockTarget candidate) || !candidate.isCandidate()
				|| !candidate.dimensionId().equals(committedExternal.dimensionId())
				|| !candidate.providerId().equals(committedExternal.providerId())
				|| !candidate.expectedBlockRegistryId().equals(committedExternal.expectedBlockRegistryId()))
				return Optional.empty();
			return runtime.preflight(() -> {
				ServerLevel level = levelFor(candidate.dimensionId());
				if (level == null) return Optional.empty();
				var preview = MinecraftBlockReadSources.resolvePreviewReadSource(level, candidate);
				var current = MinecraftBlockReadSources.resolveCommittedReadSource(level, committedExternal);
				if (preview.isEmpty() || current.isEmpty()) return Optional.empty();
				var previewBinding = preview.orElseThrow().descriptor();
				var committedBinding = current.orElseThrow().descriptor();
				if (!InventoryBackend.correspondingPhysicalBinding(previewBinding, committedBinding)) return Optional.empty();
				return Optional.of(new InventorySourceInput(committedExternal, previewInput.readOwner(), previewInput.face(), previewInput.resourceKind()));
			});
		}

		private ServerLevel levelFor(String dimensionId) {
			ResourceLocation dimension = ResourceLocation.tryParse(dimensionId);
			return dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
		}

		@Override
		public int encodedBytes(InventoryS2CPacket packet) {
			FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(256, InventoryS2CPacket.MAX_FRAME_BYTES));
			try { packet.write(buffer); return buffer.readableBytes(); }
			finally { buffer.release(); }
		}

		@Override
		public void send(UUID playerId, InventoryS2CPacket packet) {
			@Nullable ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) throw new IllegalStateException("inventory recipient disconnected");
			IPlatformNetworkService.INSTANCE.sendToClient(packet, player);
		}
		@Override
		public TransportHandoff sendObserved(UUID playerId, InventoryS2CPacket packet) {
			@Nullable ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) throw new IllegalStateException("inventory recipient disconnected");
			return IPlatformNetworkService.INSTANCE.sendToClientObserved(packet, player);
		}
	}
}
